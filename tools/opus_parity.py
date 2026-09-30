"""Does the phone's Opus decoder (Concentus) produce what the desktop's (libopus) does?

Decodes the same stored Omi packets with libopus -- the library desktop
Boswell uses -- and compares against the PCM the Android unit test
OpusParityTest wrote from the same file. Reports sample agreement, SNR, and
the cosine between voiceprints computed from each, which is the number that
matters: identical voiceprints mean the decoder choice cannot move a name.

    uv run python opus_parity.py RAW PHONE_PCM [--packets 500]
"""

import argparse
import ctypes
import ctypes.util
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
PACKET = 444


def libopus():
    name = ctypes.util.find_library("opus")
    if not name:
        sys.exit("libopus not found (apt install libopus0)")
    lib = ctypes.CDLL(name)
    lib.opus_decoder_create.restype = ctypes.c_void_p
    lib.opus_decode.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int32,
                                ctypes.POINTER(ctypes.c_int16), ctypes.c_int, ctypes.c_int]
    return lib


def frames(raw, packets):
    for i in range(min(packets, len(raw) // PACKET)):
        p = raw[i * PACKET:(i + 1) * PACKET]
        off = 4
        while off < PACKET:
            n = p[off]
            if n == 0 or off + 1 + n > PACKET:
                break
            yield p[off + 1:off + 1 + n]
            off += 1 + n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("raw")
    ap.add_argument("phone_pcm")
    ap.add_argument("--packets", type=int, default=500)
    a = ap.parse_args()

    lib = libopus()
    err = ctypes.c_int()
    dec = lib.opus_decoder_create(16000, 1, ctypes.byref(err))
    buf = (ctypes.c_int16 * 1920)()
    # Frames the phone decoder refused (OpusParityTest writes them alongside),
    # skipped here too so both decoders see the same stream.
    skip_path = a.phone_pcm + ".skipped"
    skip = {int(x) for x in open(skip_path).read().split()} if os.path.exists(skip_path) else set()
    ref, lib_failed, total = [], 0, 0
    for i, f in enumerate(frames(open(a.raw, "rb").read(), a.packets)):
        total += 1
        if i in skip:
            continue
        n = lib.opus_decode(dec, bytes(f), len(f), buf, 1920, 0)
        if n < 0:
            lib_failed += 1
            continue
        ref.append(np.frombuffer(buf, dtype=np.int16, count=n).copy())
    print(f"frames: {total}  refused by phone decoder: {len(skip)}  refused by libopus: {lib_failed}")
    ref = np.concatenate(ref).astype(np.float64)
    phone = np.fromfile(a.phone_pcm, dtype="<i2").astype(np.float64)

    n = min(len(ref), len(phone))
    print(f"samples: libopus {len(ref)}  phone {len(phone)}  ({n / 16000:.1f} s compared)")
    ref, phone = ref[:n], phone[:n]
    diff = ref - phone
    snr = 10 * np.log10((ref ** 2).sum() / max((diff ** 2).sum(), 1e-9))
    print(f"identical samples: {np.mean(diff == 0):.1%}   max |diff| {np.abs(diff).max():.0f}   SNR {snr:.1f} dB")

    import onnxruntime as ort
    sess = ort.InferenceSession(os.path.join(HERE, "models", "voiceprint.onnx"))
    emb = lambda x: (lambda v: v / np.linalg.norm(v))(
        sess.run(None, {"audio": (x / 32768.0).astype(np.float32)[None, :]})[0].ravel())
    cos = float(emb(ref) @ emb(phone))
    print(f"voiceprint cosine libopus vs phone decode: {cos:.6f}")


if __name__ == "__main__":
    main()
