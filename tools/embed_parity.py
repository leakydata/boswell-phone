"""Can the phone make the same voiceprints as desktop Boswell?

Desktop Boswell embeds speech with pyannote's wrapper around WeSpeaker
ResNet34-LM (web/pipeline.py `_embed_audio`). The phone would run sherpa-onnx's
ONNX export of the same network. This embeds the same speaker audio both ways
and reports how close the vectors are, then checks the question that actually
matters: does the phone's vector pick the same person out of the desktop's
speakers.db as the desktop's vector does?

Three embedders per span:
  desk   pyannote PretrainedSpeakerEmbedding, called exactly as Boswell does
  sherpa sherpa-onnx SpeakerEmbeddingExtractor (what the Android app would run)
  onnx   the same ONNX file fed pyannote's own fbank recipe (isolates the
         network from the feature extraction if sherpa disagrees)
  phone  models/voiceprint.onnx (export_voiceprint.py): fbank inside the
         graph, raw audio in. The naming decisions below use this one.

Read-only against the desktop archive. Touches no device.

    uv run python embed_parity.py [--clips 40]
"""

import argparse
import glob
import json
import os
import random
import sqlite3
import sys

import numpy as np
import soundfile as sf

BOSWELL = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "nRF52840"))
DATA = os.path.join(BOSWELL, "data")
ONNX = os.path.join(os.path.dirname(__file__), "models", "wespeaker_en_voxceleb_resnet34_LM.onnx")
PHONE = os.path.join(os.path.dirname(__file__), "models", "voiceprint.onnx")
SR = 16000
MIN_SECONDS = 0.8          # web/embedder.py
MATCH_HIGH, MARGIN_MIN = 0.75, 0.15   # web/speaker_store.py


def unit(v):
    v = np.asarray(v, dtype=np.float64).ravel()
    n = np.linalg.norm(v)
    return v / n if n else v


def pick_clips(n, seed):
    rng = random.Random(seed)
    files = sorted(glob.glob(os.path.join(DATA, "transcripts", "omi_*.json")))
    rng.shuffle(files)
    out = []
    for f in files:
        t = json.load(open(f))
        wav = os.path.join(DATA, t.get("clip", ""))
        segs = t.get("segments") or []
        spans = {}
        for s in segs:
            if s.get("speaker") is None:
                continue
            a, b = float(s["start"]), float(s["end"])
            if b > a:
                spans.setdefault(s["speaker"], []).append((a, b))
        if len(spans) >= 2 and os.path.exists(wav):
            out.append((wav, spans))
        if len(out) >= n:
            break
    return out


def speaker_audio(audio, spans):
    """Concatenate one speaker's stretches, as web/embedder.voiceprints does."""
    pieces = [audio[int(a * SR):int(b * SR)] for a, b in spans]
    pieces = [p for p in pieces if len(p)]
    return np.concatenate(pieces) if pieces else np.zeros(0, np.float32)


def load_desk():
    from pyannote.audio.pipelines.speaker_verification import PretrainedSpeakerEmbedding
    import torch
    m = PretrainedSpeakerEmbedding("pyannote/wespeaker-voxceleb-resnet34-LM",
                                   device=torch.device("cpu"))

    def embed(x):
        w = torch.from_numpy(x.astype(np.float32)).reshape(1, 1, -1)
        return unit(np.asarray(m(w)))
    return embed


def load_sherpa():
    import sherpa_onnx
    cfg = sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=ONNX, num_threads=4)
    ex = sherpa_onnx.SpeakerEmbeddingExtractor(cfg)

    def embed(x):
        s = ex.create_stream()
        s.accept_waveform(SR, x.astype(np.float32))
        s.input_finished()
        return unit(ex.compute(s))
    return embed


def load_phone():
    """models/voiceprint.onnx -- what the Android app runs: raw audio in."""
    import onnxruntime as ort
    sess = ort.InferenceSession(PHONE)

    def embed(x):
        return unit(sess.run(None, {"audio": x.astype(np.float32)[None, :]})[0])
    return embed


def load_onnx_pyfbank():
    import onnxruntime as ort
    import torch
    import torchaudio.compliance.kaldi as kaldi
    sess = ort.InferenceSession(ONNX)

    def embed(x):
        w = torch.from_numpy(x.astype(np.float32)).reshape(1, -1) * (1 << 15)
        f = kaldi.fbank(w, num_mel_bins=80, frame_length=25.0, frame_shift=10.0,
                        dither=0.0, sample_frequency=SR, window_type="hamming")
        f = f - f.mean(dim=0, keepdim=True)
        return unit(sess.run(None, {"feats": f.unsqueeze(0).numpy()})[0])
    return embed


def load_store():
    """Named, non-redundant, pure references from the desktop speakers.db."""
    c = sqlite3.connect(f"file:{os.path.join(DATA, 'speakers.db')}?mode=ro", uri=True)
    rows = c.execute(
        "SELECT v.person_id, p.name, v.vec FROM voiceprints v JOIN people p ON p.id=v.person_id "
        "WHERE p.name IS NOT NULL AND v.redundant=0 AND v.impure=0").fetchall()
    pids = np.array([r[0] for r in rows])
    names = {r[0]: r[1] for r in rows}
    M = np.stack([unit(np.frombuffer(r[2], dtype=np.float32)) for r in rows])
    return pids, names, M


def best_person(v, store):
    """Top person and margin over the next person, as speaker_store.match does."""
    pids, names, M = store
    s = M @ v
    best = {}
    for pid, sc in zip(pids, s):
        if sc > best.get(pid, -2):
            best[pid] = sc
    ranked = sorted(best.items(), key=lambda kv: -kv[1])
    (p1, s1), (_, s2) = ranked[0], ranked[1]
    return p1, float(s1), float(s1 - s2), names[p1]


def decision(score, margin):
    if (score >= MATCH_HIGH and margin >= MARGIN_MIN) or margin >= 0.25:
        return "matched"
    return "none"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--clips", type=int, default=40)
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()

    os.environ.setdefault("HF_HUB_OFFLINE", "1")
    desk, sherpa, onnxpy, phone = load_desk(), load_sherpa(), load_onnx_pyfbank(), load_phone()
    store = load_store()
    print(f"store: {len(store[0])} named references, {len(store[1])} people", file=sys.stderr)

    rows = []
    for wav, spans in pick_clips(args.clips, args.seed):
        audio, sr = sf.read(wav, dtype="float32")
        assert sr == SR
        vecs = {}
        for spk, sp in spans.items():
            x = speaker_audio(audio, sp)
            if len(x) < MIN_SECONDS * SR:
                continue
            d, s, o, ph = desk(x), sherpa(x), onnxpy(x), phone(x)
            vecs[spk] = d
            pd, sd, md, nd = best_person(d, store)
            ps, ss, ms, ns = best_person(ph, store)
            rows.append(dict(clip=os.path.basename(wav), spk=spk, sec=len(x) / SR,
                             cos_sherpa=float(d @ s), cos_onnx=float(d @ o), cos_phone=float(d @ ph),
                             desk=(nd, sd, md, decision(sd, md)),
                             phone=(ns, ss, ms, decision(ss, ms))))
        ks = list(vecs)
        for i in range(len(ks)):
            for j in range(i + 1, len(ks)):
                rows.append(dict(pair=float(vecs[ks[i]] @ vecs[ks[j]])))

    spk_rows = [r for r in rows if "spk" in r]
    cs = np.array([r["cos_sherpa"] for r in spk_rows])
    co = np.array([r["cos_onnx"] for r in spk_rows])
    cp = np.array([r["cos_phone"] for r in spk_rows])
    pairs = np.array([r["pair"] for r in rows if "pair" in r])
    same_top = sum(r["desk"][0] == r["phone"][0] for r in spk_rows)
    same_dec = sum(r["desk"][3] == r["phone"][3] and
                   (r["desk"][3] == "none" or r["desk"][0] == r["phone"][0]) for r in spk_rows)
    dscore = np.array([abs(r["desk"][1] - r["phone"][1]) for r in spk_rows])

    def q(a):
        return (f"min {a.min():.4f}  p5 {np.percentile(a, 5):.4f}  "
                f"median {np.median(a):.4f}  mean {a.mean():.4f}")
    print(f"\nspeaker spans embedded: {len(spk_rows)}  (median {np.median([r['sec'] for r in spk_rows]):.1f}s)")
    print(f"cosine desk vs sherpa-onnx      : {q(cs)}")
    print(f"cosine desk vs onnx+pyannote fbank: {q(co)}")
    print(f"cosine desk vs voiceprint.onnx  : {q(cp)}")
    print(f"different speakers, same clip (desk): median {np.median(pairs):.3f}  (reference: gap scale)")
    print(f"same top person in speakers.db  : {same_top}/{len(spk_rows)}")
    print(f"same naming decision            : {same_dec}/{len(spk_rows)}")
    print(f"|score difference| vs store     : median {np.median(dscore):.4f}  max {dscore.max():.4f}")
    worst = sorted(spk_rows, key=lambda r: r["cos_phone"])[:5]
    print("\nlowest agreement:")
    for r in worst:
        print(f"  {r['clip']} {r['spk']} {r['sec']:.1f}s cos={r['cos_phone']:.5f} desk={r['desk']} phone={r['phone']}")
    disagree = [r for r in spk_rows if r["desk"][3] != r["phone"][3] or
                (r["desk"][3] == "matched" and r["desk"][0] != r["phone"][0])]
    if disagree:
        print("\ndecision disagreements:")
        for r in disagree:
            print(f"  {r['clip']} {r['spk']} desk={r['desk']} phone={r['phone']}")


if __name__ == "__main__":
    main()
