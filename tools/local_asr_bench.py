"""Nemotron 3.5 ASR run locally through sherpa-onnx -- the build a phone would run.

sherpa-onnx publishes int8 exports of nvidia/nemotron-3.5-asr-streaming-0.6b
(one per streaming chunk size) that run on its Android library unchanged. This
runs the same 12 Omi clips as asr_bench.py through them on CPU, with the thread
count capped to something phone-shaped, and compares the text against:

  * desktop WhisperX (the same imperfect reference asr_bench.py uses)
  * OpenRouter's Nemotron and Nova-3 outputs saved by asr_bench.py

Speed is reported as RTF (processing seconds per audio second; < 1 is faster
than realtime). A desktop Xeon core is not a phone core, so treat RTF here as
an order of magnitude, not a promise -- a flagship phone's big cores are
roughly comparable, a budget phone's are several times slower.

    uv run python local_asr_bench.py [--chunk 1120ms] [--threads 4]
"""

import argparse
import glob
import json
import os
import time

import numpy as np
import sherpa_onnx
import soundfile as sf

import asr_bench

HERE = os.path.dirname(os.path.abspath(__file__))
MODELS = os.path.join(HERE, "models")
RESULTS = os.path.join(HERE, "results", "asr")


def load(chunk, threads):
    d = glob.glob(os.path.join(MODELS, f"sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-{chunk}-int8-*"))
    if not d:
        raise SystemExit(f"no model for chunk {chunk} under {MODELS}")
    d = d[0]
    return sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=os.path.join(d, "tokens.txt"),
        encoder=os.path.join(d, "encoder.int8.onnx"),
        decoder=os.path.join(d, "decoder.int8.onnx"),
        joiner=os.path.join(d, "joiner.int8.onnx"),
        num_threads=threads,
        sample_rate=16000,
        feature_dim=128,
        decoding_method="greedy_search",
        model_type="nemo_transducer",
    )


def run(rec, audio, language):
    s = rec.create_stream()
    if language and s.has_option("language") or language:
        try:
            s.set_option("language", language)
        except Exception:
            pass
    # Feed in 100 ms pieces, as a live BLE stream would arrive, then flush with
    # trailing silence so the last chunk is decoded.
    step = 1600
    for i in range(0, len(audio), step):
        s.accept_waveform(16000, audio[i:i + step])
        while rec.is_ready(s):
            rec.decode_stream(s)
    s.accept_waveform(16000, np.zeros(int(16000 * 1.5), np.float32))
    s.input_finished()
    while rec.is_ready(s):
        rec.decode_stream(s)
    return rec.get_result(s).strip()


def saved_text(name, model):
    p = os.path.join(RESULTS, f"{name}.{model}.json")
    if not os.path.exists(p):
        return None
    return json.load(open(p)).get("text")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--chunk", default="1120ms")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--clips", type=int, default=12)
    ap.add_argument("--seed", type=int, default=11)
    ap.add_argument("--language", default="en")
    args = ap.parse_args()

    t0 = time.monotonic()
    rec = load(args.chunk, args.threads)
    print(f"model {args.chunk} loaded in {time.monotonic() - t0:.1f}s, {args.threads} threads\n")

    rows = []
    for wav, ref, voices in asr_bench.pick_clips(args.clips, args.seed):
        name = os.path.basename(wav)
        audio, sr = sf.read(wav, dtype="float32")
        t = time.monotonic()
        text = run(rec, audio, args.language)
        dt = time.monotonic() - t
        cloud_nem, cloud_nova = saved_text(name, "nemotron"), saved_text(name, "nova")
        r = dict(name=name, rtf=dt / (len(audio) / sr), text=text,
                 vs_whisperx=asr_bench.wer(ref, text) if text else 1.0,
                 vs_cloud_nemotron=asr_bench.wer(cloud_nem, text) if cloud_nem else None,
                 nova_vs_whisperx=asr_bench.wer(ref, cloud_nova) if cloud_nova else None,
                 cloud_nem_vs_whisperx=asr_bench.wer(ref, cloud_nem) if cloud_nem else None,
                 all_have_text=bool(text and cloud_nem and cloud_nova))
        rows.append(r)
        json.dump(r, open(os.path.join(RESULTS, f"{name}.local-{args.chunk}.json"), "w"), indent=1)
        f = lambda x: "  n/a" if x is None else f"{x:5.1%}"
        print(f"{name}  rtf {r['rtf']:.2f}  vsWhisperX {f(r['vs_whisperx'])}  "
              f"vsCloudNemotron {f(r['vs_cloud_nemotron'])}  | {text[:70]!r}")

    both = [r for r in rows if r["all_have_text"]]
    med = lambda k, rs: float(np.median([r[k] for r in rs])) if rs else float("nan")
    print(f"\nRTF median {med('rtf', rows):.2f}  max {max(r['rtf'] for r in rows):.2f} "
          f"(30 s clip takes ~{med('rtf', rows) * 30:.0f} s)")
    print(f"empty results: {sum(1 for r in rows if not r['text'])}/{len(rows)}")
    print(f"on the {len(both)} clips where every system produced text, median disagreement with WhisperX:")
    print(f"  local Nemotron {args.chunk:7s} {med('vs_whisperx', both):.1%}")
    print(f"  cloud Nemotron          {med('cloud_nem_vs_whisperx', both):.1%}")
    print(f"  cloud Nova-3            {med('nova_vs_whisperx', both):.1%}")
    print(f"local vs cloud Nemotron text, median difference: {med('vs_cloud_nemotron', both):.1%}")


if __name__ == "__main__":
    main()
