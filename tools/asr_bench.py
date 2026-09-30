"""Nemotron 3.5 ASR vs Deepgram Nova-3 on real Omi clips, through OpenRouter.

Answers the questions the phone design hangs on:
  * does each model return word timestamps?   (needed to attach words to speakers)
  * does each model return speaker labels with diarize=true?
  * what does a clip actually cost, and how long does it take?
  * how close is each to the desktop's own transcript?

There is no hand-made ground truth. The reference is desktop Boswell's
WhisperX transcript of the same clip -- itself a machine transcript -- so the
"WER" here is disagreement with WhisperX, not error against the truth. It is
good for ranking two models on the same audio, not for quoting as accuracy.
Every raw response is saved under results/asr/ so the text can be read.

Key: OPENROUTER_API_KEY from the environment, or from tools/.env
(a line OPENROUTER_API_KEY=...). It is only ever sent to openrouter.ai.

    uv run python asr_bench.py [--clips 12] [--models nemotron,nova]
"""

import argparse
import base64
import glob
import json
import os
import random
import re
import sys
import time

import httpx

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.abspath(os.path.join(HERE, "..", "..", "nRF52840", "data"))
OUT = os.path.join(HERE, "results", "asr")
URL = "https://openrouter.ai/api/v1/audio/transcriptions"
MODELS = {
    "nemotron": "nvidia/nemotron-3.5-asr-streaming-multilingual-0.6b",
    "nova": "deepgram/nova-3",
}


def api_key():
    k = os.environ.get("OPENROUTER_API_KEY")
    env = os.path.join(HERE, ".env")
    if not k and os.path.exists(env):
        for line in open(env):
            if line.strip().startswith("OPENROUTER_API_KEY="):
                k = line.split("=", 1)[1].strip().strip('"').strip("'")
    if not k:
        sys.exit("OPENROUTER_API_KEY not set (environment or tools/.env)")
    return k


def pick_clips(n, seed):
    """Omi clips with real conversation: two or more voices, 40+ words."""
    rng = random.Random(seed)
    files = sorted(glob.glob(os.path.join(DATA, "transcripts", "omi_*.json")))
    rng.shuffle(files)
    out = []
    for f in files:
        t = json.load(open(f))
        segs = t.get("segments") or []
        text = " ".join((s.get("text") or "") for s in segs)
        voices = {s.get("speaker") for s in segs if s.get("speaker")}
        wav = os.path.join(DATA, t.get("clip", ""))
        if len(voices) >= 2 and len(text.split()) >= 40 and os.path.exists(wav):
            out.append((wav, text, len(voices)))
        if len(out) >= n:
            break
    return out


def norm_words(s):
    s = s.lower().replace("’", "'")
    s = re.sub(r"[^a-z0-9' ]+", " ", s)
    return [w.strip("'") for w in s.split() if w.strip("'")]


def wer(ref, hyp):
    r, h = norm_words(ref), norm_words(hyp)
    if not r:
        return float("nan")
    prev = list(range(len(h) + 1))
    for i, rw in enumerate(r, 1):
        cur = [i] + [0] * len(h)
        for j, hw in enumerate(h, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (rw != hw))
        prev = cur
    return prev[-1] / len(r)


def transcribe(client, key, model, wav, diarize=False, timeout=300):
    body = {
        "model": model,
        "input_audio": {"data": base64.b64encode(open(wav, "rb").read()).decode(), "format": "wav"},
        "language": "en",
        "response_format": "verbose_json",
        "timestamp_granularities": ["word", "segment"],
    }
    # Measured 2026-09-30: both models answer 400 "does not support diarize"
    # through OpenRouter. Kept as a flag so it can be re-checked later.
    if diarize:
        body["diarize"] = True
    t0 = time.monotonic()
    try:
        r = client.post(URL, json=body, headers={"Authorization": f"Bearer {key}"},
                        timeout=timeout)
    except httpx.TransportError as e:
        # A hung request is a result worth counting, not a reason to stop.
        return 0, time.monotonic() - t0, {"error": {"message": f"{type(e).__name__} after {timeout}s"}}
    dt = time.monotonic() - t0
    try:
        j = r.json()
    except ValueError:
        j = {"_raw": r.text[:2000]}
    return r.status_code, dt, j


def summarize(j):
    words = j.get("words") or []
    segs = j.get("segments") or []
    speakers = {w.get("speaker") for w in words if w.get("speaker") is not None} | \
               {s.get("speaker") for s in segs if s.get("speaker") is not None}
    return dict(
        text=j.get("text") or "",
        word_ts=bool(words) and all("start" in w for w in words),
        seg_ts=bool(segs) and all("start" in s for s in segs),
        n_speakers=len(speakers),
        cost=(j.get("usage") or {}).get("cost"),
        seconds=(j.get("usage") or {}).get("seconds") or j.get("duration"),
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--clips", type=int, default=12)
    ap.add_argument("--seed", type=int, default=11)
    ap.add_argument("--models", default="nemotron,nova")
    ap.add_argument("--diarize", action="store_true", help="also request diarize=true")
    # Nemotron measured at ~170 s per request on 2026-09-30 regardless of clip
    # length -- queueing, not compute -- so requests go out in parallel.
    ap.add_argument("--timeout", type=int, default=300)
    ap.add_argument("--parallel", type=int, default=12)
    args = ap.parse_args()

    key = api_key()
    models = [m.strip() for m in args.models.split(",")]
    os.makedirs(OUT, exist_ok=True)
    clips = pick_clips(args.clips, args.seed)
    print(f"{len(clips)} clips; reference = desktop WhisperX transcript\n")

    agg = {m: dict(wer=[], lat=[], cost=0.0, sec=0.0, word_ts=0, seg_ts=0, spk=[], fail=0) for m in models}
    from concurrent.futures import ThreadPoolExecutor
    jobs = [(wav, m) for wav, _, _ in clips for m in models]
    with httpx.Client(limits=httpx.Limits(max_connections=args.parallel)) as client, \
            ThreadPoolExecutor(args.parallel) as pool:
        done = dict(zip(jobs, pool.map(
            lambda job: transcribe(client, key, MODELS[job[1]], job[0], args.diarize, args.timeout),
            jobs)))
    if True:
        for wav, ref, voices in clips:
            name = os.path.basename(wav)
            line = [f"{name} ({voices} voices on desktop)"]
            for m in models:
                code, dt, j = done[(wav, m)]
                json.dump(j, open(os.path.join(OUT, f"{name}.{m}.json"), "w"), indent=1)
                a = agg[m]
                if code != 200 or "text" not in j:
                    a["fail"] += 1
                    err = (j.get("error") or {}).get("message") if isinstance(j.get("error"), dict) else j
                    line.append(f"  {m:9s} HTTP {code}: {str(err)[:160]}")
                    continue
                s = summarize(j)
                w = wer(ref, s["text"])
                a["wer"].append(w)
                a["lat"].append(dt)
                a["cost"] += s["cost"] or 0.0
                a["sec"] += s["seconds"] or 30.0
                a["word_ts"] += s["word_ts"]
                a["seg_ts"] += s["seg_ts"]
                a["spk"].append(s["n_speakers"])
                line.append(f"  {m:9s} vsWhisperX={w:5.1%}  {dt:4.1f}s  words_ts={s['word_ts']} "
                            f"seg_ts={s['seg_ts']} speakers={s['n_speakers']}  cost={s['cost']}")
            print("\n".join(line))

    print("\nsummary")
    for m, a in agg.items():
        n = len(a["wer"])
        if not n:
            print(f"  {m}: all {a['fail']} requests failed")
            continue
        ws = sorted(a["wer"])
        per_hour = a["cost"] / a["sec"] * 3600 if a["sec"] else float("nan")
        print(f"  {m:9s} n={n} fail={a['fail']}  disagreement w/ WhisperX median {ws[n // 2]:.1%} "
              f"mean {sum(ws) / n:.1%}  latency median {sorted(a['lat'])[n // 2]:.1f}s  "
              f"word_ts {a['word_ts']}/{n}  seg_ts {a['seg_ts']}/{n}  "
              f"diarized {sum(1 for x in a['spk'] if x >= 1)}/{n}  "
              f"cost ${a['cost']:.5f} (~${per_hour:.3f}/hr)")
    print(f"\nraw responses: {OUT}")


if __name__ == "__main__":
    main()
