# The Omi CV 1 over BLE

Everything here was read off a working implementation and verified against a
real device. It is the most valuable thing in this directory: the intelligence
layer can be bought from a vendor, this cannot.

Device used throughout: **Omi CV 1**, address `C4:B3:FD:7F:1E:91`, firmware
**3.0.21**, hardware 5.0, maker "Based Hardware". It connects with **no pairing
and no authentication**. Its GATT table matches the open-source firmware at
`github.com/BasedHardware/omi` (`omi/firmware/omi/`), which is the reference
when something here is ambiguous.

---

## Services and characteristics

**Live audio** — service `19b10000-e8f2-537e-4f6c-d104768a1214`

| char | purpose |
|---|---|
| `19b10001-…` | audio stream (notify) |
| `19b10002-…` | codec id (read) |
| `19b10011-…` | LED brightness, read/write |
| `19b10012-…` | microphone gain, read/write |
| `19b10013-…` | 1 while charging |
| `19b10021-…` | capability bits |
| `19b10032-…` | the device's own clock, epoch seconds |

Standard characteristics are also present: battery `0x2A19`, model `0x2A24`,
firmware `0x2A26`, hardware `0x2A27`, manufacturer `0x2A29`.

**Storage / offload** — service `30295780-4301-eabd-2904-2849adfeae43`,
single characteristic `30295781-4301-eabd-2904-2849adfeae43` carrying **both**
commands and data.

## The offload protocol

Commands are written to the control characteristic; everything comes back as
notifications on the same characteristic. **All multi-byte fields are
big-endian.**

```
-> 0x10                              ring info
<- 0x02 read:u64 write:u64 cap:u32 dropped:u64 pktbytes:u16

-> 0x11 start:u64 [count:u32]        read
<- 0x05                              read begins
<- 0x03 <payload>                    ... repeated
<- 0x04 status:u8 next:u64           done

-> 0x12 seq:u64                      mark read up to here
<- 0x01 status:u8                    acknowledged

-> 0x13                              clear (not used by the desktop)
```

Notification kinds: `0x01` ack, `0x02` info, `0x03` data, `0x04` done,
`0x05` begin.

Status codes: `0` ok, `6` invalid command, `9` storage not ready,
`10` sequence out of range.

## The stored packet

**444 bytes**, fixed:

```
[timestamp: u32 big-endian][len: u8][opus frame][len: u8][opus frame]… padding
```

Padding after the last frame is **not** a frame — a length byte of zero ends
the packet. Audio is 16 kHz. Codec id **21 = Opus at 20 ms frames** (CV 1,
320 samples); **20 = Opus at 10 ms** (devkit, 160 samples). The numbering is by
frame length, not by codec.

The desktop reads in batches of 400 packets. That number is a trade: large
enough that the round trip is not the cost, small enough that an interruption
loses little and the read pointer advances often.

---

## The two properties that shape every design

### 1. Reading consumes

The device advances its own read pointer as it confirms bytes sent
(`STORAGE_ADVANCE_CHECKPOINT_MS` in their firmware). **A packet handed over is
a packet gone.** Asking for it again returns "sequence out of range". There is
no re-read, and no option that avoids this.

Everything follows from that:

- Raw bytes must reach durable storage **before** anything tries to decode
  them. The desktop spools to a file and `fsync`s, then makes clips from the
  spool afterwards. A crash then costs the seconds in flight rather than
  everything unprocessed.
- **An error on the transfer path is a deletion, not a failure.** The desktop
  originally raised on a mid-batch timeout and discarded every whole packet
  already received — a link dropping after 400 packets destroyed 400 packets
  permanently and reported itself as "the sync achieved nothing". A partial
  batch must hand back what arrived, be persisted, and have the pointer
  advanced only over what was kept.
- Do not delete the raw spool until its audio is demonstrably elsewhere. A
  decoder failure that deletes the original turns one bug into data loss.

**On a phone this is sharper, not softer.** The OS can kill your process
between the device handing over bytes and your writing them. Design for that
explicitly.

### 2. One connection at a time

While anything holds the device, nothing else can. The phone app and the
desktop daemon are mutually exclusive, and so is the vendor's own Omi app.

## Timestamps: two paths, two meanings

- **Offloaded audio carries real timestamps** — the device stamps what it
  stores.
- **The live stream does not.** It carries only a packet counter, so those
  clips are placed by arrival time.

This distinction leaks everywhere and must be modelled explicitly, not
inferred. The desktop marks it `time_known` per clip. Two consequences that
cost real time to learn:

- Recovered clips are written with the time they were **captured**, so they do
  not appear as new files, do not show up in a "modified recently" search, and
  sort into the past. **A successful recovery can look like it did nothing.**
- Any UI that orders by arrival will scramble a docked dump that lands this
  evening carrying this morning.

## Throughput, measured

| condition | rate |
|---|---|
| healthy link (about −61 dBm) | **89 kB/s** |
| degraded stack | 26 kB/s |
| weak link (−84 dBm) | **3.0 kB/s** |

The ring holds roughly **27 hours** (1,115,064 packets ≈ 472 MB). It is
external flash, not RAM — **it survives a power cycle**. An nRF52840 has 256 KB
of RAM, so a buffer that size cannot be anything else.

A full drain flattens the battery; one was observed starting at 24% and the
device died during the next.

**Break-even matters more than peak speed.** If your collection duty cycle
gives the radio N minutes out of every M, you must pull M minutes of audio in
N minutes of transfer or the backlog grows forever. On the desktop's 12-minute
sync inside a 32-minute cycle, that break-even is about **13 kB/s** — healthy
links have 5–7× headroom, a weak one does not clear it at all.

## Reading signal strength correctly

This cost hours. **The RSSI in `bluetoothctl info` is a cached advertisement
value, not the live link.** It read −87 dBm while the actual connection was
−61 dBm. On Linux the true figure comes from the controller
(`hcitool cmd 0x05 0x0005 <handle-lo> <handle-hi>`, last byte signed dBm).

Whatever the phone platform offers, find out whether its RSSI is the live link
or the last advertisement before building any logic on it.
