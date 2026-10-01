"""Why do some frames in Omi storage downloads refuse to decode?

Reads kept spool files (444-byte stored packets: BE u32 timestamp, then
[len][opus frame]...), decodes every frame with libopus in stream order, and
describes each failure: where it sits (frame within packet, packet within the
400-packet read batch, timestamp continuity), and what it looks like (length,
TOC byte, zero/0xFF padding). Patterns there point at the cause.

    uv run python spool_doctor.py <dir of .raw spool files>
"""
import collections, ctypes, glob, os, sys
from opus_parity import libopus, PACKET

BATCH = 400
GHOSTS_SKIPPED = "--raw" not in sys.argv


def packets(raw):
    for i in range(len(raw) // PACKET):
        p = raw[i * PACKET:(i + 1) * PACKET]
        ts = int.from_bytes(p[0:4], "big")
        frames, off = [], 4
        while off < PACKET:
            n = p[off]
            if n == 0 or off + 1 + n > PACKET:
                break
            if GHOSTS_SKIPPED and off + 1 + n == PACKET:   # the firmware's exact-fit ghost (see Offload.kt)
                break
            frames.append((off, p[off + 1:off + 1 + n]))
            off += 1 + n
        yield i, ts, p, frames, off


def main(d):
    lib = libopus()
    buf = (ctypes.c_int16 * 5760)()
    stats = collections.Counter()
    fails = []
    for path in sorted(glob.glob(os.path.join(d, "*.raw"))):
        raw = open(path, "rb").read()
        err = ctypes.c_int()
        dec = lib.opus_decoder_create(16000, 1, ctypes.byref(err))
        prev_ts = None
        for i, ts, p, frames, end in packets(raw):
            stats["packets"] += 1
            gap = None if prev_ts is None else ts - prev_ts
            prev_ts = ts
            for k, (off, f) in enumerate(frames):
                stats["frames"] += 1
                n = lib.opus_decode(dec, bytes(f), len(f), buf, 5760, 0)
                if n < 0:
                    fails.append(dict(file=os.path.basename(path), packet=i, frame=k, of=len(frames), len=len(f), toc=f[0],
                                      batch_pos=i % BATCH, ts=ts, gap=gap, tail=p[end:], body=f, prev_len=len(frames[k - 1][1]) if k else None,
                                      err=n))
        if len(raw) % PACKET:
            stats["trailing bytes"] += len(raw) % PACKET
    print(f"{stats['frames']} frames in {stats['packets']} packets; {len(fails)} would not decode "
          f"({100 * len(fails) / max(stats['frames'], 1):.3f}%); trailing partial-packet bytes: {stats['trailing bytes']}")
    if not fails:
        return
    c = collections.Counter
    print("errors:", dict(c(f["err"] for f in fails)))
    print("frame position in its packet:", dict(c("first" if f["frame"] == 0 else "last" if f["frame"] == f["of"] - 1 else "middle" for f in fails)))
    print("frames per packet when failing:", dict(c(f["of"] for f in fails)))
    print("frame lengths (top):", c(f["len"] for f in fails).most_common(8))
    print("TOC bytes (top):", [(hex(t), n) for t, n in c(f["toc"] for f in fails).most_common(6)])
    print("packet position in the 400-packet batch (top):", c(f["batch_pos"] for f in fails).most_common(8))
    print("timestamp gap before the failing packet (top):", c(f["gap"] for f in fails).most_common(8))
    print("failing packets that end in zero padding:", sum(1 for f in fails if f["tail"] and set(f["tail"]) <= {0}), "of", len(fails))
    runs = c((f["file"], f["packet"]) for f in fails)
    print("failures per affected packet:", dict(c(runs.values())), "| affected packets:", len(runs))
    allzero = sum(1 for f in fails if set(f["body"]) <= {0})
    allff = sum(1 for f in fails if set(f["body"]) <= {0xff})
    print(f"failing frames that are all 0x00: {allzero}, all 0xFF: {allff}")
    # Neighbours: are failures clustered (a damaged stretch) or isolated?
    by_file = collections.defaultdict(list)
    for f in fails:
        by_file[f["file"]].append(f["packet"])
    clusters = []
    for fname, ps in by_file.items():
        ps = sorted(set(ps)); start = prev = ps[0]
        for x in ps[1:] + [None]:
            if x is not None and x - prev <= 2:
                prev = x; continue
            clusters.append((fname, start, prev));
            if x is not None: start = prev = x
    print("clusters of affected packets (file, first, last):", len(clusters))
    for cl in clusters[:12]:
        print("   ", cl)
    print("examples:")
    for f in fails[:6]:
        print(f"    {f['file']} packet {f['packet']} frame {f['frame']}/{f['of']} len {f['len']} toc {f['toc']:#04x} "
              f"err {f['err']} gap {f['gap']} first bytes {f['body'][:12].hex()}")


if __name__ == "__main__":
    main([a for a in sys.argv[1:] if not a.startswith("--")][0])
