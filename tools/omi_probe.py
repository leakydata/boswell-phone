"""Does the Omi stream live audio right now? Counts notifications, writes nothing.

Connects from this machine, subscribes to the audio characteristic, and
reports how many packets arrive per second and whether the counter advances.
Touches no archive and sends no storage commands. The Omi takes one
connection at a time: stop omid and the phone app first.

    uv run python omi_probe.py [--address C4:B3:FD:7F:1E:91] [--seconds 15]
"""

import argparse
import asyncio
import time

from bleak import BleakClient

AUDIO = "19b10001-e8f2-537e-4f6c-d104768a1214"


async def main(address, seconds):
    got = []
    async with BleakClient(address, timeout=25.0) as c:
        print(f"connected, mtu {c.mtu_size}")
        t0 = time.monotonic()
        await c.start_notify(AUDIO, lambda _h, d: got.append((time.monotonic() - t0, d[0] | (d[1] << 8), len(d))))
        await asyncio.sleep(seconds)
        await c.stop_notify(AUDIO)
    if not got:
        print(f"0 notifications in {seconds}s")
        return
    buckets = [0] * int(seconds // 5 + 1)
    for t, _, _ in got:
        buckets[int(t // 5)] += 1
    print("per 5 s:", " ".join(f"{b / 5:.0f}" for b in buckets[:-1]), "/s")
    span = got[-1][1] - got[0][1] + 1
    print(f"{len(got)} notifications in {seconds}s = {len(got) / seconds:.1f}/s (50/s is realtime); "
          f"counter {got[0][1]}..{got[-1][1]} span {span}; first after {got[0][0]:.2f}s")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--address", default="C4:B3:FD:7F:1E:91")
    ap.add_argument("--seconds", type=float, default=15)
    a = ap.parse_args()
    asyncio.run(main(a.address, a.seconds))
