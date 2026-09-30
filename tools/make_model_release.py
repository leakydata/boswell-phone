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
        "id": "voiceprint",
        "name": "Voiceprint (WeSpeaker ResNet34-LM, desktop-Boswell compatible)",
        "purpose": "who they are",
        "license": "CC-BY-4.0",
        "source": "WeSpeaker voxceleb_resnet34_LM with pyannote's fbank in-graph (tools/export_voiceprint.py)",
        "files": ["voiceprint.onnx", "NOTICE-voiceprint.txt"],
    },
]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


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
            files.append({"name": name, "size": os.path.getsize(p), "sha256": sha256(p)})
        catalog["models"].append({**{k: v for k, v in m.items() if k != "files"}, "files": files})
    out = json.dumps(catalog, indent=1)
    open(os.path.join(d, "models.json"), "w").write(out + "\n")
    asset = os.path.join(HERE, "..", "app", "src", "main", "assets", "models.json")
    os.makedirs(os.path.dirname(asset), exist_ok=True)
    open(asset, "w").write(out + "\n")
    for m in catalog["models"]:
        print(f"{m['id']:20s} {sum(f['size'] for f in m['files']) / 1e6:7.1f} MB  {len(m['files'])} files")


if __name__ == "__main__":
    main()
