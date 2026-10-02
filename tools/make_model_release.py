"""Build the model catalog the app downloads from, for a GitHub release.

Hashes every file in tools/models/release-<tag>/ and writes:
  * app/src/main/assets/models.json -- the catalog the app ships with
  * tools/models/release-<tag>/models.json -- the same, uploaded with the files

The app ships no models; it downloads these files on request and checks each
one against the SHA-256 recorded here before using it.

    uv run python make_model_release.py [--tag models-v1]
"""

import argparse
import hashlib
import subprocess
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = "leakydata/boswell-phone"

MODELS = [
    {
        "id": "asr-nemotron-1120",
        "name": "Nemotron 3.5 ASR (on-device, 1120 ms, int8)",
        "purpose": "transcription",
        "license": "OpenMDW-1.1",
        "source": "https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b via sherpa-onnx int8 export",
        "files": ["nemotron-asr-1120ms-encoder.int8.onnx", "nemotron-asr-1120ms-decoder.int8.onnx",
                  "nemotron-asr-1120ms-joiner.int8.onnx", "nemotron-asr-1120ms-tokens.txt",
                  "LICENSE-nemotron-OpenMDW-1.1.txt"],
    },
    {
        "id": "segmentation",
        "name": "Speaker segmentation (pyannote 3.0)",
        "purpose": "who spoke when",
        "license": "MIT",
        "source": "https://huggingface.co/pyannote/segmentation-3.0 via sherpa-onnx export",
        "files": ["pyannote-segmentation-3.0.onnx", "LICENSE-pyannote-segmentation-3.0.txt"],
    },
    {
        "id": "sound-tags",
        "name": "Sound tagging (CED-Mini, AudioSet)",
        "purpose": "what else was audible: typing, TV, music, dogs…",
        "license": "Apache-2.0",
        "source": "https://huggingface.co/mispeech/ced-mini via sherpa-onnx int8 export",
        "files": ["ced-mini-audio-tagging.int8.onnx", "ced-mini-audio-tagging-labels.csv",
                  "LICENSE-ced-mini-Apache-2.0.txt"],
    },
    {
        "id": "voiceprint",
        "name": "Voiceprint (WeSpeaker ResNet34-LM, desktop-Boswell compatible)",
        "purpose": "who they are",
        "license": "CC-BY-4.0",
        "source": "WeSpeaker voxceleb_resnet34_LM with pyannote's fbank in-graph (tools/export_voiceprint.py)",
        "files": ["voiceprint.onnx", "NOTICE-voiceprint.txt"],
    },
    {
        "id": "speaker-id",
        "name": "Speaker ID (ReDimNet2-B6)",
        "purpose": "telling voices apart: about twice as many confident matches",
        "license": "MIT",
        "source": "https://github.com/PalabraAI/redimnet2 b6 vb2+vox2 lm, variable-length ONNX (tools/export_redimnet.py)",
        "files": ["speaker-id-redimnet2-b6.onnx", "LICENSE-redimnet2-MIT.txt"],
    },
]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def pack(path, min_size=5_000_000, min_saving=0.10):
    """A gzip copy beside a big file when it saves enough to be worth unpacking
    on the phone (the int8 ASR encoder: 627 -> 429 MB). Deterministic (-n), so
    rebuilding a release leaves existing assets byte-identical."""
    if os.path.getsize(path) < min_size:
        return None
    gz = path + ".gz"
    if not os.path.exists(gz) or os.path.getmtime(gz) < os.path.getmtime(path):
        subprocess.run(["gzip", "-9", "-n", "-k", "-f", path], check=True)
    if os.path.getsize(gz) > os.path.getsize(path) * (1 - min_saving):
        os.remove(gz)
        return None
    return gz


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="models-v1")
    a = ap.parse_args()
    d = os.path.join(HERE, "models", f"release-{a.tag}")
    catalog = {
        "version": 1,
        "release": a.tag,
        "base_url": f"https://github.com/{REPO}/releases/download/{a.tag}/",
        "models": [],
    }
    for m in MODELS:
        files = []
        for name in m["files"]:
            p = os.path.join(d, name)
            entry = {"name": name, "size": os.path.getsize(p), "sha256": sha256(p)}
            gz = pack(p)
            if gz:
                entry["gz"] = {"name": os.path.basename(gz), "size": os.path.getsize(gz), "sha256": sha256(gz)}
            files.append(entry)
        catalog["models"].append({**{k: v for k, v in m.items() if k != "files"}, "files": files})
    out = json.dumps(catalog, indent=1)
    open(os.path.join(d, "models.json"), "w").write(out + "\n")
    asset = os.path.join(HERE, "..", "app", "src", "main", "assets", "models.json")
    os.makedirs(os.path.dirname(asset), exist_ok=True)
    open(asset, "w").write(out + "\n")
    for m in catalog["models"]:
        print(f"{m['id']:20s} {sum(f['size'] for f in m['files']) / 1e6:7.1f} MB, download "
              f"{sum(f.get('gz', f)['size'] for f in m['files']) / 1e6:7.1f} MB  {len(m['files'])} files")


if __name__ == "__main__":
    main()
