"""On-device Parakeet v3 (sherpa-onnx int8) against the cloud results of
phone_vs_cloud.py, on the same clips: does the phone-sized model keep the
cloud model's accuracy, and how fast is it?

    uv run python parakeet_local.py <phone_vs_cloud results json> [--threads 4]
"""
import argparse, json, os, sys, time
import numpy as np, sherpa_onnx, soundfile as sf
from asr_bench import norm_words, wer
from phone_vs_cloud import best_window_wer, passage

HERE = os.path.dirname(os.path.abspath(__file__))
M = os.path.join(HERE, "models", "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8")

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("results"); ap.add_argument("--threads", type=int, default=4)
    a = ap.parse_args()
    r = json.load(open(a.results))
    rec = sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=f"{M}/encoder.int8.onnx", decoder=f"{M}/decoder.int8.onnx", joiner=f"{M}/joiner.int8.onnx",
        tokens=f"{M}/tokens.txt", num_threads=a.threads, model_type="nemo_transducer", decoding_method="greedy_search")
    audio_s = proc_s = 0.0
    for clip in r:
        x, sr = sf.read(clip, dtype="float32")
        s = rec.create_stream(); s.accept_waveform(sr, x)
        t0 = time.perf_counter(); rec.decode_stream(s); proc_s += time.perf_counter() - t0
        audio_s += len(x) / sr
        r[clip]["parakeet-local"] = {"text": s.result.text, "ok": True}
    refs = [c for c in r if c.endswith("omi_1790805093.wav")]
    every = [c for c in r if c not in refs]
    ref_words = norm_words(passage())
    for c in refs:
        print("reading the passage:", "  ".join(f"{e} {best_window_wer(ref_words, r[c][e]['text']) * 100:.1f}%"
              for e in ("phone", "parakeet-local", "parakeet-v3", "nova-3")))
    print("\neveryday clips, word difference from each cloud engine (lower = closer):")
    for mine in ("phone", "parakeet-local"):
        cells = []
        for other in ("parakeet-v3", "nova-3", "gpt-transcribe", "nemotron-cloud"):
            v = [wer(r[c][other]["text"], r[c][mine]["text"]) for c in every if r[c].get(other, {}).get("ok") and norm_words(r[c][other]["text"])]
            cells.append(f"{other} {sum(v) / len(v) * 100:.1f}%")
        print(f"  {mine:15s} " + "  ".join(cells))
    print(f"\nspeed here ({a.threads} threads): {proc_s:.1f}s for {audio_s:.0f}s of audio = {proc_s / audio_s:.3f}x real time")
    for c in every[:3]:
        print(f"\n--- {os.path.basename(c)}")
        for e in ("phone", "parakeet-local", "parakeet-v3"): print(f"  {e:15s} {r[c][e]['text'][:200]}")

if __name__ == "__main__":
    main()
