"""Which voiceprint model tells the owner's people apart best -- on their own recordings?

Ground truth is desktop Boswell's archive: every voiceprint of a named person
points at a clip and a diarizer label, so the exact speech behind it can be cut
out again and given to each model. Two sets are reported separately:

  hand   voiceprints named or confirmed by a person (unbiased, small)
  auto   voiceprints the current model matched by itself (large, but chosen by
         the incumbent, so biased in its favor)

For each model and set: EER over all same/different-person pairs, and
leave-one-out identification (best row per person, as Boswell matches), on the
full speech (up to 15 s) and on a 2-second slice (short replies are the hard
case on a phone).

    uv run python embed_bench.py [--limit-auto 600]
"""
import argparse
import collections
import json
import os
import random
import sqlite3
import sys
import time

import numpy as np
import soundfile as sf

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(os.path.dirname(os.path.dirname(HERE)), "nRF52840", "data")
SR = 16000


def unit(v):
    v = np.asarray(v, dtype=np.float32).reshape(-1)
    return v / (np.linalg.norm(v) + 1e-12)


def load_samples(limit_auto, seed):
    db = sqlite3.connect(os.path.join(DATA, "speakers.db"))
    rows = db.execute("""SELECT v.id, p.id, p.name, v.clip, v.speaker, v.origin FROM voiceprints v JOIN people p ON p.id = v.person_id
                         WHERE p.name IS NOT NULL AND v.clip IS NOT NULL AND v.speaker IS NOT NULL""").fetchall()
    hand = [r for r in rows if r[5] in ("manual", "confirmed")]
    auto = [r for r in rows if r[5] == "auto"]
    random.Random(seed).shuffle(auto)
    auto = auto[:limit_auto]
    out = {"hand": [], "auto": []}
    cache = {}
    for name, rs in (("hand", hand), ("auto", auto)):
        for vid, pid, pname, clip, spk, origin in rs:
            base = os.path.splitext(clip)[0]
            wav, tr = os.path.join(DATA, base + ".wav"), os.path.join(DATA, "transcripts", base + ".json")
            if not (os.path.exists(wav) and os.path.exists(tr)):
                continue
            t = json.load(open(tr))
            spans = [(s["start"], s["end"]) for s in t.get("segments", []) if s.get("speaker") == spk]
            if not spans:
                continue
            if wav not in cache:
                a, sr = sf.read(wav, dtype="float32")
                if a.ndim > 1:
                    a = a.mean(axis=1)
                if sr != SR:
                    continue
                cache = {wav: a}
            a = cache[wav]
            speech = np.concatenate([a[int(s * SR):int(e * SR)] for s, e in spans])
            if len(speech) < SR:          # under a second: too short to be a fair test of anything
                continue
            out[name].append(dict(person=pid, name=pname, full=speech[:15 * SR], short=speech[:2 * SR]))
    return out


# ------------------------------------------------------------------ models

def wespeaker_resnet34():
    """The model Boswell uses now: models/voiceprint.onnx, fbank inside, raw 16 kHz in."""
    import onnxruntime as ort
    s = ort.InferenceSession(os.path.join(HERE, "models", "voiceprint.onnx"), providers=["CPUExecutionProvider"])
    name = s.get_inputs()[0].name
    return lambda x: unit(s.run(None, {name: x[None, :].astype(np.float32)})[0])


def redimnet2(model_name, dataset):
    import torch
    m = torch.hub.load("PalabraAI/redimnet2", "redimnet2", model_name=model_name, train_type="lm",
                       dataset=dataset, pretrained=True, trust_repo=True).eval()
    def embed(x):
        with torch.no_grad():
            return unit(m(torch.from_numpy(x)[None, :]).numpy())
    return embed


# ------------------------------------------------------------------ metrics

def eer(scores, labels):
    order = np.argsort(-scores)
    s, l = scores[order], labels[order]
    pos, neg = l.sum(), (1 - l).sum()
    tp = np.cumsum(l); fp = np.cumsum(1 - l)
    frr = 1 - tp / pos; far = fp / neg
    i = np.argmin(np.abs(frr - far))
    return (frr[i] + far[i]) / 2, s[i]


def evaluate(embs, persons):
    E = np.stack(embs); P = np.array(persons)
    S = E @ E.T
    iu = np.triu_indices(len(E), 1)
    same = (P[:, None] == P[None, :])[iu].astype(int)
    e, thr = eer(S[iu], same)
    # Leave-one-out, best row per person (people with another sample only).
    hit = n = 0
    for i in range(len(E)):
        if (P == P[i]).sum() < 2:
            continue
        best = {}
        for j in range(len(E)):
            if j != i:
                best[P[j]] = max(best.get(P[j], -2), S[i, j])
        n += 1; hit += max(best, key=best.get) == P[i]
    same_s, diff_s = S[iu][same == 1], S[iu][same == 0]
    return dict(eer=e, thr=thr, ident=hit / max(n, 1), n=n, same_med=float(np.median(same_s)),
                diff_p99=float(np.quantile(diff_s, 0.99)), pairs=len(same_s))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit-auto", type=int, default=600)
    ap.add_argument("--seed", type=int, default=3)
    ap.add_argument("--models", default="current,b6,b3")
    args = ap.parse_args()
    t0 = time.time()
    sets = load_samples(args.limit_auto, args.seed)
    for k, v in sets.items():
        print(f"{k}: {len(v)} samples, {len(set(s['person'] for s in v))} people")
    makers = {
        "current": ("WeSpeaker ResNet34-LM (now)", wespeaker_resnet34),
        "b6": ("ReDimNet2-B6 vb2+vox2 lm", lambda: redimnet2("b6", "vb2+vox2_v0")),
        "b3": ("ReDimNet2-B3 vb2+vox2+cnc2 lm", lambda: redimnet2("b3", "vb2+vox2+cnc2_v0")),
    }
    for key in args.models.split(","):
        label, make = makers[key]
        embed = make()
        for setname, samples in sets.items():
            for cut in ("full", "short"):
                t = time.time()
                embs = [embed(s[cut]) for s in samples]
                r = evaluate(embs, [s["person"] for s in samples])
                print(f"{label:32s} {setname:4s} {cut:5s}  EER {r['eer'] * 100:5.2f}%  ident {r['ident'] * 100:5.1f}% (n={r['n']})  "
                      f"same median {r['same_med']:.3f}  diff 99th pct {r['diff_p99']:.3f}  thr@EER {r['thr']:.3f}  "
                      f"{(time.time() - t) / len(samples) * 1000:.0f} ms/sample", flush=True)
    print(f"done in {time.time() - t0:.0f} s")


if __name__ == "__main__":
    main()
