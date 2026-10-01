"""The phone's own transcription against cloud engines, on the same clips.

Two measures, because everyday clips have no answer key:

  * reference clips -- someone reading the setup passage aloud, whose words
    are known exactly: a true word error rate for each engine (scored
    against the stretch of the passage actually read);
  * everyday clips with speech -- how far each engine is from each other one,
    and the words where they part, to judge by ear.

Phone transcripts come from the app (transcripts/*.json); the cloud engines
are called through OpenRouter's /audio/transcriptions.

    uv run python phone_vs_cloud.py <clips dir> <transcripts dir> [--n 20] [--ref a.wav,b.wav]

Results (personal text) go to tools/results/cloud/, which is not committed.
"""
import argparse, glob, json, os, random, re, sys, time
from concurrent.futures import ThreadPoolExecutor

import httpx

import asr_bench
from asr_bench import api_key, norm_words, wer

# Engines that only answer plain "json" (no word timings).
PLAIN_JSON = {"openai/gpt-transcribe"}


def transcribe(client, key, model, wav, timeout=300):
    if model not in PLAIN_JSON:
        return asr_bench.transcribe(client, key, model, wav, timeout=timeout)
    import base64
    body = {"model": model, "language": "en", "response_format": "json",
            "input_audio": {"data": base64.b64encode(open(wav, "rb").read()).decode(), "format": "wav"}}
    t0 = time.monotonic()
    try:
        r = client.post(asr_bench.URL, json=body, headers={"Authorization": f"Bearer {key}"}, timeout=timeout)
    except httpx.TransportError as e:
        return 0, time.monotonic() - t0, {"error": {"message": f"{type(e).__name__} after {timeout}s"}}
    dt = time.monotonic() - t0
    try:
        return r.status_code, dt, r.json()
    except ValueError:
        return r.status_code, dt, {"_raw": r.text[:2000]}

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "results", "cloud")
ENGINES = {
    "whisper-turbo": "openai/whisper-large-v3-turbo",
    "nemotron-cloud": "nvidia/nemotron-3.5-asr-streaming-multilingual-0.6b",
    "nova-3": "deepgram/nova-3",
    "parakeet-v3": "nvidia/parakeet-tdt-0.6b-v3",
    "gpt-transcribe": "openai/gpt-transcribe",
}


def passage():
    src = open(os.path.join(HERE, "..", "app/src/main/java/net/boswell/phone/setup/SetupScreen.kt")).read()
    m = re.search(r'private const val PASSAGE = ((?:"[^"]*"\s*\+?\s*)+)', src)
    return "".join(re.findall(r'"([^"]*)"', m.group(1)))


def best_window_wer(ref_words, hyp):
    """WER against the contiguous stretch of the reference that best explains the hypothesis
    (a reading may start late or stop early)."""
    h = norm_words(hyp)
    if not h:
        return 1.0
    best = 9.0
    n = len(ref_words)
    for a in range(n):
        for b in range(a + max(1, len(h) // 2), min(n, a + 2 * len(h)) + 1):
            best = min(best, wer(" ".join(ref_words[a:b]), hyp))
    return best


def phone_text(tr_dir, wav):
    t = json.load(open(os.path.join(tr_dir, os.path.basename(wav)[:-4] + ".json")))
    return " ".join(s["text"] for s in t.get("segments", []))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("clips"); ap.add_argument("transcripts")
    ap.add_argument("--n", type=int, default=20)
    ap.add_argument("--ref", default="", help="clips of the setup passage read aloud (need phone transcripts too)")
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()
    key = api_key()
    os.makedirs(OUT, exist_ok=True)

    # Everyday clips: the phone heard 15+ words.
    pool = []
    for tf in sorted(glob.glob(os.path.join(args.transcripts, "omi_*.json"))):
        t = json.load(open(tf))
        words = sum(len(s["text"].split()) for s in t.get("segments", []))
        wav = os.path.join(args.clips, os.path.basename(tf)[:-5] + ".wav")
        if words >= 15 and os.path.exists(wav) and os.path.getsize(wav) > 44:
            pool.append(wav)
    refs = [r for r in args.ref.split(",") if r]
    random.Random(args.seed).shuffle(pool)
    everyday = [w for w in pool if w not in refs][:args.n]
    clips = refs + everyday
    print(f"{len(refs)} reference clips, {len(everyday)} everyday clips; engines: phone, {', '.join(ENGINES)}")

    results = {c: {"phone": {"text": phone_text(args.transcripts, c)}} for c in clips}
    jobs = [(c, e) for c in clips for e in ENGINES]
    with httpx.Client() as client, ThreadPoolExecutor(8) as pool_ex:
        def run(job):
            c, e = job
            st, dt, j = transcribe(client, key, ENGINES[e], c, timeout=300)
            ok = st == 200 and isinstance(j, dict) and "text" in j
            return c, e, dict(text=j.get("text", "") if ok else "", ok=ok, seconds=dt,
                              cost=((j.get("usage") or {}).get("cost") if ok else None) or 0.0,
                              audio=((j.get("usage") or {}).get("seconds") if ok else None) or 0.0,
                              error=None if ok else str(j)[:200])
        for c, e, r in pool_ex.map(run, jobs):
            results[c][e] = r
            print(f"  {e:15s} {os.path.basename(c)} {'ok' if r['ok'] else 'FAILED ' + r['error']} {r['seconds']:.1f}s")

    names = ["phone"] + list(ENGINES)
    print("\n== Reading the setup passage (true word error rate, lower is better)")
    ref_words = norm_words(passage())
    for c in refs:
        print(f"  {os.path.basename(c)}: " + "  ".join(
            f"{e} {best_window_wer(ref_words, results[c][e]['text']) * 100:.1f}%" for e in names if results[c].get(e, {}).get('ok', True)))

    print("\n== Everyday clips: disagreement between engines (word error of the row against the column)")
    print(" " * 16 + "".join(f"{n:>16s}" for n in names))
    for a in names:
        row = []
        for b in names:
            vals = [wer(results[c][b]["text"], results[c][a]["text"]) for c in everyday
                    if results[c].get(a, {}).get("ok", True) and results[c].get(b, {}).get("ok", True) and norm_words(results[c][b]["text"])]
            row.append(f"{sum(vals) / len(vals) * 100:15.1f}%" if vals and a != b else f"{'-':>16s}")
        print(f"{a:16s}" + "".join(row))

    print("\n== Speed and cost (cloud: wall time per request including queueing)")
    for e in ENGINES:
        rs = [results[c][e] for c in clips if results[c][e]["ok"]]
        fails = sum(1 for c in clips if not results[c][e]["ok"])
        if not rs:
            print(f"  {e}: all {fails} failed"); continue
        secs = sorted(r["seconds"] for r in rs)
        audio = sum(r["audio"] for r in rs); cost = sum(r["cost"] for r in rs)
        print(f"  {e:15s} median {secs[len(secs) // 2]:.1f}s, worst {secs[-1]:.1f}s, failed {fails}; "
              f"${cost:.4f} for {audio / 60:.1f} min = ${cost / audio * 3600 if audio else 0:.3f}/hour")

    stamp = time.strftime("%Y%m%d-%H%M%S")
    path = os.path.join(OUT, f"phone_vs_cloud_{stamp}.json")
    json.dump(results, open(path, "w"), indent=1)
    print(f"\nall texts: {path}")


if __name__ == "__main__":
    main()
