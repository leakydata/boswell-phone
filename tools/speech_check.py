"""Would a segmentation-only speech check agree with full transcription?

For each clip with a transcript, run pyannote segmentation-3.0 over 10 s
windows (as the phone does) and count frames where anyone speaks. Compare
"has speech" by that measure with "transcript has lines", for a range of
thresholds, and time it.

    uv run python speech_check.py <dir with omi_*.wav> <dir with omi_*.json transcripts>
"""
import glob, json, os, sys, time
import numpy as np, onnxruntime as ort, soundfile as sf

MODEL = os.path.join(os.path.dirname(__file__), "models/release-models-v1/pyannote-segmentation-3.0.onnx")
WINDOW, FRAME_S = 160_000, 270 / 16_000

def speech_seconds(sess, audio):
    total = 0.0
    for st in range(0, max(len(audio), 1), WINDOW):
        w = np.zeros(WINDOW, np.float32)
        chunk = audio[st:st + WINDOW]
        w[:len(chunk)] = chunk
        logits = sess.run(None, {sess.get_inputs()[0].name: w[None, None, :]})[0][0]
        frames = min(logits.shape[0], int(len(chunk) / 270) + 1)
        total += (logits[:frames].argmax(1) != 0).sum() * FRAME_S
    return total

def main(wavs, trs):
    sess = ort.InferenceSession(MODEL, providers=["CPUExecutionProvider"])
    rows, t = [], 0.0
    for tf in sorted(glob.glob(os.path.join(trs, "*.json"))):
        tr = json.load(open(tf))
        if "error" in tr: continue
        wf = os.path.join(wavs, os.path.basename(tf)[:-5] + ".wav")
        if not os.path.exists(wf) or os.path.getsize(wf) == 0: continue
        a, _ = sf.read(wf, dtype="float32")
        t0 = time.time(); s = speech_seconds(sess, a); t += time.time() - t0
        words = sum(len(x["text"].split()) for x in tr.get("segments", []))
        rows.append((os.path.basename(wf), s, words, len(a) / 16000))
    print(f"{len(rows)} clips, segmentation {t / max(len(rows), 1) * 1000:.0f} ms per clip on this CPU")
    for thr in (0.0, 0.3, 0.5, 1.0, 2.0):
        miss = [r for r in rows if r[2] > 0 and r[1] <= thr]       # transcript has words, check says silent
        extra = [r for r in rows if r[2] == 0 and r[1] > thr]      # would still be transcribed, nothing found
        skipped = sum(1 for r in rows if r[1] <= thr)
        print(f"threshold {thr:.1f}s: skip {skipped:3d} | would miss speech in {len(miss):2d} "
              f"(words lost: {sum(r[2] for r in miss)}) | still transcribe {len(extra):2d} silent ones")
        for r in miss[:6]: print(f"    missed {r[0]}: {r[2]} words, {r[1]:.2f}s speech by segmentation")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
