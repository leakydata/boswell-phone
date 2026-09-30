"""On-device sound tagging (sherpa-onnx CED) against desktop Boswell's AST tags.

Tags recent Omi clips the desktop has already tagged, with the desktop's own
windowing (10 s windows every 5 s plus one whole-clip look) and thresholds,
and reports how often the two agree on each clip's sounds and on the
keep/empty verdict that decides whether a no-speech clip's audio may go.

    uv run python sound_compare.py [--clips 60]
"""

import argparse
import glob
import json
import os

import numpy as np
import sherpa_onnx
import soundfile as sf

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.abspath(os.path.join(HERE, "..", "..", "nRF52840", "data"))

# host/audio_tags.py
VOICE = {"Speech", "Male speech, man speaking", "Female speech, woman speaking",
         "Child speech, kid speaking", "Conversation", "Narration, monologue",
         "Whispering", "Shout", "Yell", "Screaming", "Laughter", "Crying, sobbing",
         "Singing", "Speech synthesizer"}
AMBIENT = {"Silence", "White noise", "Pink noise", "Wind noise (microphone)",
           "Wind", "Mechanical fan", "Air conditioning", "Hum", "Mains hum",
           "Static", "Noise", "Environmental noise", "Inside, small room",
           "Inside, large room or hall", "Rustling leaves", "Vehicle",
           "Tick", "Tick-tock", "Clock"}
VOICE_FLOOR, EVENT_FLOOR = 0.10, 0.35
# web/pipeline.py
WINDOW, HOP, WIN_MIN, WIN_KEEP, WHOLE_KEEP = 10.0, 5.0, 0.25, 0.35, 0.20


def tagger(kind):
    d = os.path.join(HERE, "models", f"sherpa-onnx-ced-{kind}-audio-tagging-2024-04-19")
    cfg = sherpa_onnx.AudioTaggingConfig(
        model=sherpa_onnx.AudioTaggingModelConfig(ced=os.path.join(d, "model.int8.onnx"), num_threads=4),
        labels=os.path.join(d, "class_labels_indices.csv"), top_k=20)
    return sherpa_onnx.AudioTagging(cfg)


def look(t, a):
    # CED's position table covers 10 s; AST on the desktop truncates at 10.24 s
    # the same way, so its "whole clip" look is the first ten seconds too.
    a = a[:160000]
    s = t.create_stream()
    s.accept_waveform(16000, a)
    return {e.name: e.prob for e in t.compute(s)}


def tag(t, a):
    """Desktop rule: a label counts if seen >= WIN_KEEP in two windows, or >= WHOLE_KEEP whole."""
    whole = look(t, a)
    n, step = int(WINDOW * 16000), int(HOP * 16000)
    wins = [look(t, a[i:i + n]) for i in range(0, max(1, len(a) - n + 1), step) if len(a[i:i + n]) >= 16000]
    out = {}
    for lab in set(whole) | {k for w in wins for k in w}:
        hits = [w.get(lab, 0) for w in wins if w.get(lab, 0) >= WIN_MIN]
        best = max([whole.get(lab, 0)] + hits)
        if sum(1 for h in hits if h >= WIN_KEEP) >= 2 or whole.get(lab, 0) >= WHOLE_KEEP:
            out[lab] = best
    return out


def verdict(tags):
    if any(n in VOICE and v >= VOICE_FLOOR for n, v in tags.items()):
        return "keep"
    if any(n not in AMBIENT and n not in VOICE and v >= EVENT_FLOOR for n, v in tags.items()):
        return "keep"
    return "empty"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--clips", type=int, default=60)
    a = ap.parse_args()
    fs = sorted(glob.glob(os.path.join(DATA, "transcripts", "omi_*.json")))[-3000:]
    rng = np.random.default_rng(3)
    rng.shuffle(fs)
    clips = []
    for f in fs:
        t = json.load(open(f))
        wav = os.path.join(DATA, t.get("clip", ""))
        if t.get("sounds") is not None and os.path.exists(wav):
            clips.append((wav, {s[0]: s[1] for s in t["sounds"]}))
        if len(clips) >= a.clips:
            break
    import time
    for kind in ("mini", "small"):
        tg = tagger(kind)
        jac, vagree, ms = [], 0, []
        for wav, ref in clips:
            audio, _ = sf.read(wav, dtype="float32")
            t0 = time.time()
            mine = tag(tg, audio)
            ms.append((time.time() - t0) * 1000)
            A, B = set(mine), set(ref)
            jac.append(len(A & B) / len(A | B) if A | B else 1.0)
            vref = verdict(ref)
            vagree += verdict(mine) == vref
        print(f"CED-{kind}: label-set overlap with AST median {np.median(jac):.0%} mean {np.mean(jac):.0%}; "
              f"keep/empty verdict agrees {vagree}/{len(clips)}; {np.median(ms):.0f} ms per 30 s clip (4 threads, desktop)")
        for wav, ref in clips[:6]:
            audio, _ = sf.read(wav, dtype="float32")
            mine = tag(tg, audio)
            print(f"   {os.path.basename(wav)}  AST: {', '.join(f'{k} {v:.2f}' for k, v in sorted(ref.items(), key=lambda x: -x[1])[:3])}")
            print(f"   {' ' * len(os.path.basename(wav))}  CED: {', '.join(f'{k} {v:.2f}' for k, v in sorted(mine.items(), key=lambda x: -x[1])[:3])}")


if __name__ == "__main__":
    main()
