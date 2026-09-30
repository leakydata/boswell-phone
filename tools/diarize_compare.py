"""The phone's diarizer against desktop Boswell's, on the same Omi clips.

Runs the Kotlin Diarizer (through the JVM unit test DiarizerTest, with the
real ONNX models) and pyannote/speaker-diarization-3.1 on each clip, then
reports:

  * speakers found by each
  * timeline agreement: the share of speech time where the phone's speaker,
    mapped one-to-one onto pyannote's, is the same (1.0 = identical)
  * naming: each phone speaker's voiceprint matched against the desktop's
    speakers.db with the desktop's own rules, next to the name the desktop
    archive gave the voice that talked the most in the same time

Read-only against the desktop archive. Writes nothing there.

    uv run python diarize_compare.py [--clips 15]
"""

import argparse
import glob
import json
import os
import random
import sqlite3
import subprocess
import sys

import numpy as np
import soundfile as sf

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DATA = os.path.join(os.path.dirname(ROOT), "nRF52840", "data")
MODELS = os.path.join(HERE, "models", "release-models-v1")
SCRATCH = os.environ.get("SCRATCH", "/tmp")
STEP = 0.01
EXTRA = []

MATCH_HIGH, MATCH_LOW, MARGIN_MIN, MARGIN_STRONG = 0.75, 0.55, 0.15, 0.25


def pick(n, seed):
    rng = random.Random(seed)
    fs = sorted(glob.glob(os.path.join(DATA, "transcripts", "omi_*.json")))
    rng.shuffle(fs)
    out = []
    for f in fs:
        t = json.load(open(f))
        segs = t.get("segments") or []
        spk = {s.get("speaker") for s in segs if s.get("speaker")}
        words = sum(len((s.get("text") or "").split()) for s in segs)
        wav = os.path.join(DATA, t.get("clip", ""))
        if len(spk) >= 2 and words >= 40 and os.path.exists(wav):
            out.append((wav, t))
        if len(out) >= n:
            break
    return out


def run_phone(wavs):
    out = os.path.join(SCRATCH, "phone_diar.json")
    cmd = ["./gradlew", ":app:testDebugUnitTest", "--tests", "*DiarizerTest.diarizeForComparison",
           f"-Ddiar.models={MODELS}", f"-Ddiar.clips={','.join(wavs)}", f"-Ddiar.out={out}",
           "--console=plain", "-q", "--rerun"] + EXTRA
    env = {**os.environ, "JAVA_HOME": "/usr/lib/jvm/java-21-openjdk-amd64"}
    r = subprocess.run(cmd, cwd=ROOT, env=env, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(r.stdout[-3000:] + r.stderr[-3000:])
    return {d["clip"]: d for d in json.load(open(out))}


def timeline(turns_by_speaker, seconds):
    n = int(seconds / STEP) + 1
    m = np.zeros((len(turns_by_speaker), n), bool)
    for i, turns in enumerate(turns_by_speaker):
        for a, b in turns:
            m[i, int(a / STEP):int(b / STEP)] = True
    return m


def agreement(phone, ref):
    """Best one-to-one mapping of phone speakers onto reference speakers."""
    from scipy.optimize import linear_sum_assignment
    if not len(phone) or not len(ref):
        return 0.0, {}
    overlap = np.array([[np.sum(p & r) for r in ref] for p in phone])
    rows, cols = linear_sum_assignment(-overlap)
    # Speaker-time, so overlapped speech counts once per speaker on both sides.
    agree = overlap[rows, cols].sum() / max(ref.sum(), 1)
    return float(agree), dict(zip(rows.tolist(), cols.tolist()))


def load_store():
    c = sqlite3.connect(f"file:{os.path.join(DATA, 'speakers.db')}?mode=ro", uri=True)
    rows = c.execute("SELECT v.person_id, p.name, v.vec FROM voiceprints v JOIN people p ON p.id=v.person_id "
                     "WHERE p.name IS NOT NULL AND v.redundant=0 AND v.impure=0").fetchall()
    M = np.stack([np.frombuffer(r[2], np.float32) for r in rows])
    M = M / np.linalg.norm(M, axis=1, keepdims=True)
    return np.array([r[0] for r in rows]), {r[0]: r[1] for r in rows}, M


def name_for(v, store):
    pids, names, M = store
    v = np.asarray(v, np.float64)
    v = v / np.linalg.norm(v)
    s = M @ v
    best = {}
    for p, x in zip(pids, s):
        best[p] = max(best.get(p, -2), x)
    ranked = sorted(best.items(), key=lambda kv: -kv[1])
    score = ranked[0][1]
    margin = score - ranked[1][1] if len(ranked) > 1 else None
    if margin is None:
        ok = score >= MATCH_HIGH
    else:
        ok = (score >= MATCH_HIGH and margin >= MARGIN_MIN) or (score >= MATCH_LOW and margin >= MARGIN_STRONG)
    return (names[ranked[0][0]] if ok else None), float(score)


def desktop_names(t, turns):
    """Name the desktop archive gave to whichever of its speakers overlaps these turns most."""
    spk_names = {k: (v or {}).get("name") for k, v in (t.get("speakers") or {}).items()}
    tally = {}
    for s in t.get("segments") or []:
        a, b, k = s.get("start"), s.get("end"), s.get("speaker")
        if k is None:
            continue
        ov = sum(max(0.0, min(b, y) - max(a, x)) for x, y in turns)
        tally[k] = tally.get(k, 0.0) + ov
    if not tally:
        return None
    k = max(tally, key=tally.get)
    return spk_names.get(k) if tally[k] > 0 else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--clips", type=int, default=15)
    ap.add_argument("--seed", type=int, default=5)
    ap.add_argument("--merge", type=float, default=None, help="Diarizer mergeAt override")
    ap.add_argument("--step", type=float, default=None, help="Diarizer stepSeconds override")
    a = ap.parse_args()

    # The archive keeps growing, so the sample is pinned on first use: runs
    # with different settings must be scored on the same clips.
    pinned = os.path.join(SCRATCH, f"diar_clips_{a.clips}_{a.seed}.json")
    if os.path.exists(pinned):
        names = json.load(open(pinned))
        clips = [(os.path.join(DATA, n), json.load(open(os.path.join(DATA, "transcripts", n[:-4] + ".json")))) for n in names]
    else:
        clips = pick(a.clips, a.seed)
        json.dump([os.path.basename(w) for w, _ in clips], open(pinned, "w"))
    global EXTRA
    EXTRA = ([f"-Ddiar.merge={a.merge}"] if a.merge else []) + ([f"-Ddiar.step={a.step}"] if a.step else [])
    wavs = [w for w, _ in clips]
    phone = run_phone(wavs)

    os.environ.setdefault("HF_HUB_OFFLINE", "1")
    import torch
    from pyannote.audio import Pipeline
    pipe = Pipeline.from_pretrained("pyannote/speaker-diarization-3.1")
    store = load_store()

    agrees, counts, name_rows, ms = [], [], [], []
    for wav, t in clips:
        audio, sr = sf.read(wav, dtype="float32")
        secs = len(audio) / sr
        ann = pipe({"waveform": torch.from_numpy(audio)[None], "sample_rate": sr})
        ann = getattr(ann, "speaker_diarization", ann)
        ref_turns = {}
        for seg, _, lab in ann.itertracks(yield_label=True):
            ref_turns.setdefault(lab, []).append((seg.start, seg.end))
        p = phone[wav]
        ms.append(p["ms"])
        ph_turns = [s["turns"] for s in p["speakers"]]
        agree, _ = agreement(timeline(ph_turns, secs), timeline(list(ref_turns.values()), secs))
        agrees.append(agree)
        counts.append((len(ph_turns), len(ref_turns)))
        for s in p["speakers"]:
            if not s["voiceprint"]:
                continue
            mine, score = name_for(s["voiceprint"], store)
            theirs = desktop_names(t, s["turns"])
            name_rows.append((os.path.basename(wav), s["seconds"], mine, theirs, score))
        print(f"{os.path.basename(wav)}  speakers phone {len(ph_turns)} / pyannote {len(ref_turns)}  "
              f"timeline agreement {agree:.0%}  phone {p['ms']:.0f} ms")

    print(f"\ntimeline agreement: median {np.median(agrees):.0%}  mean {np.mean(agrees):.0%}")
    print(f"same speaker count: {sum(a == b for a, b in counts)}/{len(counts)}")
    print(f"phone diarizer time per 30 s clip (desktop JVM, 4 threads): median {np.median(ms):.0f} ms")
    both = [r for r in name_rows if r[3] is not None]
    same = sum(1 for r in both if r[2] == r[3])
    print(f"\nnaming, where the desktop had named the voice: phone gave the same name {same}/{len(both)}")
    print(f"phone named a voice the desktop left unnamed: {sum(1 for r in name_rows if r[3] is None and r[2])}")
    for r in name_rows:
        flag = "" if r[2] == r[3] else "   <-- differs"
        print(f"  {r[0]}  {r[1]:5.1f}s  phone={r[2]!s:22s} desktop={r[3]!s:22s} score {r[4]:.2f}{flag}")


if __name__ == "__main__":
    main()
