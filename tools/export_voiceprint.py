"""Build voiceprint.onnx: raw 16 kHz audio in, a desktop-Boswell voiceprint out.

sherpa-onnx ships the right network (WeSpeaker ResNet34-LM) but feeds it
different features from pyannote's: no per-utterance mean subtraction and a
povey window instead of hamming. Its vectors land at cosine ~0 against the
desktop's -- useless for matching speakers.db. The network itself is fine:
fed pyannote's exact fbank it agrees with the desktop to cosine 1.0000.

So the feature extraction goes inside the graph. pyannote's recipe
(kaldi.fbank, 80 mel, 25/10 ms, hamming, dither 0, samples scaled by 2^15,
then the mean over time subtracted) is written here with plain matmuls --
framing by gather, the DFT as a cos/sin matrix -- so it exports to ops every
ONNX Runtime build has, including Android's. The phone then needs no DSP code
of its own, and there is nothing to drift.

Output: models/voiceprint.onnx
  input  "audio"  float32 [1, N]  mono 16 kHz, range -1..1, N >= 400
  output "embs"   float32 [1, 256] (not normalized; unit-normalize before cosine)

    uv run python export_voiceprint.py
"""

import os

import numpy as np
import onnx
import torch
import torchaudio.compliance.kaldi as kaldi
from onnx import compose

HERE = os.path.dirname(os.path.abspath(__file__))
NET = os.path.join(HERE, "models", "wespeaker_en_voxceleb_resnet34_LM.onnx")
FRONT = os.path.join(HERE, "models", "_frontend.onnx")
OUT = os.path.join(HERE, "models", "voiceprint.onnx")

SR, WIN, HOP, NFFT, MELS = 16000, 400, 160, 512, 80
PREEMPH = 0.97


class KaldiFbank(torch.nn.Module):
    """kaldi.fbank as pyannote calls it, plus pyannote's global mean centering."""

    def __init__(self):
        super().__init__()
        n = torch.arange(WIN, dtype=torch.float64)
        window = 0.54 - 0.46 * torch.cos(2 * torch.pi * n / (WIN - 1))     # hamming, kaldi form
        k = torch.arange(NFFT // 2 + 1, dtype=torch.float64)
        ang = 2 * torch.pi * n[:, None] * k[None, :] / NFFT               # only the first WIN rows matter
        self.register_buffer("cos", (window[:, None] * torch.cos(ang)).float())
        self.register_buffer("sin", (window[:, None] * torch.sin(ang)).float())
        mel, _ = kaldi.get_mel_banks(MELS, NFFT, float(SR), 20.0, 0.0, 100.0, -500.0, 1.0)
        mel = torch.nn.functional.pad(mel, (0, 1))                        # 256 -> 257 bins
        self.register_buffer("mel", mel.T.float())                        # [257, 80]
        self.register_buffer("offs", torch.arange(WIN).unsqueeze(0))
        self.eps = float(torch.finfo(torch.float32).eps)

    def forward(self, audio):                       # [1, N]
        x = audio[0] * 32768.0
        frames = 1 + (x.shape[0] - WIN) // HOP       # snip_edges=True
        idx = torch.arange(frames).unsqueeze(1) * HOP + self.offs
        f = x[idx]                                                # [T, 400]
        f = f - f.mean(dim=1, keepdim=True)                       # remove_dc_offset
        prev = torch.cat([f[:, :1], f[:, :-1]], dim=1)
        f = f - PREEMPH * prev                                    # preemphasis, kaldi edge
        re, im = f @ self.cos, f @ self.sin
        power = re * re + im * im                                 # [T, 257]
        logmel = torch.log(torch.clamp(power @ self.mel, min=self.eps))
        logmel = logmel - logmel.mean(dim=0, keepdim=True)        # pyannote centering
        return logmel.unsqueeze(0)                                # [1, T, 80]


def reference_fbank(audio):
    w = torch.from_numpy(audio).reshape(1, -1) * 32768.0
    f = kaldi.fbank(w, num_mel_bins=MELS, frame_length=25.0, frame_shift=10.0,
                    dither=0.0, sample_frequency=SR, window_type="hamming")
    return (f - f.mean(0, keepdim=True)).unsqueeze(0).numpy()


def main():
    fb = KaldiFbank().eval()
    rng = np.random.default_rng(0)
    probe = (rng.standard_normal(SR * 3) * 0.05).astype(np.float32)
    ours = fb(torch.from_numpy(probe).unsqueeze(0)).detach().numpy()
    ref = reference_fbank(probe)
    print(f"torch frontend vs kaldi.fbank: max abs diff {np.abs(ours - ref).max():.2e}")

    torch.onnx.export(fb, torch.from_numpy(probe).unsqueeze(0), FRONT,
                      input_names=["audio"], output_names=["feats"],
                      dynamic_axes={"audio": {1: "N"}, "feats": {1: "T"}},
                      opset_version=14, dynamo=False)   # the net is opset 14

    front, net = onnx.load(FRONT), onnx.load(NET)
    front.ir_version = net.ir_version     # compose needs matching IR versions
    net = compose.add_prefix(net, "net/", rename_inputs=False, rename_outputs=False)
    merged = compose.merge_models(front, net, io_map=[("feats", "feats")])
    merged.metadata_props.clear()
    for k, v in {"what": "desktop-Boswell voiceprint: raw 16k audio -> 256-d WeSpeaker ResNet34-LM",
                 "frontend": "pyannote kaldi fbank 80 mel 25/10ms hamming dither0 x2^15, global mean centered",
                 "sample_rate": "16000", "output_dim": "256"}.items():
        onnx.helper.set_model_props(merged, {**{p.key: p.value for p in merged.metadata_props}, k: v})
    onnx.checker.check_model(merged)
    onnx.save(merged, OUT)
    os.remove(FRONT)

    import onnxruntime as ort
    sess = ort.InferenceSession(OUT)
    net_sess = ort.InferenceSession(NET)
    a = sess.run(None, {"audio": probe[None, :]})[0].ravel()
    b = net_sess.run(None, {"feats": ref})[0].ravel()
    cos = float(a @ b / np.linalg.norm(a) / np.linalg.norm(b))
    print(f"voiceprint.onnx vs net(kaldi.fbank): cosine {cos:.6f}")
    print(f"wrote {OUT} ({os.path.getsize(OUT) / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
