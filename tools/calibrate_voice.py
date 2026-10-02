"""Thresholds for a new voiceprint model, matched to the current model's caution.

For each of Boswell's thresholds (tuned for WeSpeaker ResNet34-LM), find the
score under the new model that wrongly accepts different people exactly as
often, measured over every different-person pair in desktop Boswell's archive
(embed_bench.load_samples). Same caution, and the better model should accept
more true pairs: both are reported. Margins scale with the score spread.

Uses the exact files and limits the phone uses: models/voiceprint.onnx and the
exported speaker-ID ONNX, at most VoiceModel.MAX_ID_SECONDS (8 s) of speech.

    uv run python calibrate_voice.py [--model models/redimnet2_b6.onnx]
"""
import argparse
import numpy as np
import onnxruntime as ort

from embed_bench import load_samples, wespeaker_resnet34, unit, evaluate

OLD = {"matchHigh": 0.75, "matchLow": 0.55, "clusterMin": 0.75, "sameVoice": 0.60, "likely": 0.65}
MARGINS = {"marginMin": 0.15, "marginStrong": 0.25}
import os
CAP = int(float(os.environ.get("CAP_S", "8")) * 16000)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="models/redimnet2_b6.onnx")
    ap.add_argument("--limit-auto", type=int, default=400)
    a = ap.parse_args()
    sets = load_samples(a.limit_auto, 3)
    samples = sets["hand"] + sets["auto"]
    persons = np.array([s["person"] for s in samples])
    old = wespeaker_resnet34()
    s = ort.InferenceSession(a.model, providers=["CPUExecutionProvider"])
    new = lambda x: unit(s.run(None, {"waveform": x[None, :CAP].astype(np.float32)})[0])
    E = {}
    for name, f in (("old", old), ("new", new)):
        E[name] = np.stack([f(x["full"][:CAP]) for x in samples])
        for setname, part in (("hand", slice(0, len(sets["hand"]))), ("all", slice(None))):
            r = evaluate(list(E[name][part]), list(persons[part]))
            print(f"{name} {setname:4s} (8 s cap): EER {r['eer'] * 100:.2f}%  ident {r['ident'] * 100:.1f}% (n={r['n']})")
    iu = np.triu_indices(len(samples), 1)
    same = (persons[:, None] == persons[None, :])[iu]
    So, Sn = (E["old"] @ E["old"].T)[iu], (E["new"] @ E["new"].T)[iu]
    print(f"\n{same.sum()} same-person pairs, {(~same).sum()} different-person pairs")
    out = {}
    for k, t in OLD.items():
        far = (So[~same] >= t).mean()
        tn = float(np.quantile(Sn[~same], 1 - far)) if far > 0 else float(Sn[~same].max() + 1e-3)
        out[k] = tn
        print(f"{k:12s} old {t:.2f}: wrongly accepts {far * 100:6.3f}%, accepts {(So[same] >= t).mean() * 100:5.1f}% of true pairs"
              f"  ->  new {tn:.3f}: accepts {(Sn[same] >= tn).mean() * 100:5.1f}% of true pairs")
    scale = (out["matchHigh"] - out["matchLow"]) / (OLD["matchHigh"] - OLD["matchLow"])
    for k, m in MARGINS.items():
        print(f"{k:12s} old {m:.2f}  ->  new {m * scale:.3f}  (score spread x{scale:.2f})")


if __name__ == "__main__":
    main()
