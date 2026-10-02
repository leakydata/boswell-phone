"""Export ReDimNet2 (PalabraAI/redimnet2, MIT) to ONNX with a variable-length
waveform input: raw 16 kHz float32 [1, samples] in, a unit 192-d voiceprint out.
The phone uses it only for each speaker's voiceprint (who they are); splitting
speakers apart stays on the faster WeSpeaker model.

    uv run python export_redimnet.py [--model b6] [--dataset vb2+vox2_v0]
"""
import argparse, os, time
import numpy as np, torch

HERE = os.path.dirname(os.path.abspath(__file__))

class Unit(torch.nn.Module):
    def __init__(self, m): super().__init__(); self.m = m
    def forward(self, x):
        e = self.m(x)
        return e / torch.linalg.vector_norm(e, dim=-1, keepdim=True).clamp_min(1e-12)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="b6"); ap.add_argument("--dataset", default="vb2+vox2_v0")
    a = ap.parse_args()
    m = torch.hub.load("PalabraAI/redimnet2", "redimnet2", model_name=a.model, train_type="lm", dataset=a.dataset,
                       pretrained=True, trust_repo=True).eval()
    net = Unit(m).eval()
    out = os.path.join(HERE, "models", f"redimnet2_{a.model}.onnx")
    x = torch.randn(1, 16000 * 4)
    torch.onnx.export(net, (x,), out, input_names=["waveform"], output_names=["embedding"],
                      dynamic_axes={"waveform": {1: "samples"}}, opset_version=17, dynamo=False)
    import onnxruntime as ort
    s = ort.InferenceSession(out, providers=["CPUExecutionProvider"])
    for secs in (1.0, 2.0, 6.0, 15.0):
        w = np.random.RandomState(0).randn(1, int(16000 * secs)).astype(np.float32) * 0.1
        with torch.no_grad(): ref = net(torch.from_numpy(w)).numpy()
        t = time.time(); got = s.run(None, {"waveform": w})[0]; dt = time.time() - t
        print(f"{secs:4.1f} s  cosine vs PyTorch {float((ref * got).sum()):.6f}  onnxruntime {dt * 1000:.0f} ms")
    print(out, os.path.getsize(out) // 1024, "KB")

if __name__ == "__main__":
    main()
