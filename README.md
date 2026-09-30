# boswell-phone — start here

The phone version of Boswell: an Android app that records from an Omi, then
transcribes, diarizes and names voices, independently of the desktop.

**Nathan's instruction, 2026-09-26:** the phone version is *its own project*,
not a branch, port, or rewrite of the desktop one. Work started 2026-09-30.
Nothing here is a commitment to an architecture; it is what was learned paying
for it once.

## Decided and measured (2026-09-30)

Scripts are in `tools/`, a uv project (`uv run python <script>`). They read the
desktop archive read-only and never touch a device.

- **Voiceprints match the desktop exactly.** `tools/export_voiceprint.py` builds
  `voiceprint.onnx`: raw 16 kHz audio in, a 256-d WeSpeaker ResNet34-LM vector
  out, with pyannote's fbank built into the graph. `tools/embed_parity.py`: cosine 1.0000
  against desktop Boswell on 217 speakers, with the same naming decision in 217/217.
  sherpa-onnx's own speaker extractor does *not* match (cosine ~0, because it
  skips pyannote's mean centring), so do not use it for voiceprints.
- **Hosted ASR cannot diarize.** Through OpenRouter, Nemotron 3.5 ASR and
  Nova-3 both reject `diarize`. Both return word timestamps. Nova is more
  accurate and fast (~$0.26/hr) but returns nothing for TV/background speech;
  cloud Nemotron is ~$0.012/hr but queues ~150 s per request
  (`tools/asr_bench.py`).
- **Nemotron runs on-device.** sherpa-onnx's int8 build of
  `nemotron-3.5-asr-streaming-0.6b` (1120 ms chunk, ~475 MB) runs at 0.22×
  realtime on 4 desktop threads, with accuracy about equal to the cloud version
  (`tools/local_asr_bench.py`). Real phone speed not yet measured.
- **The app ships with no models.** ASR, segmentation and `voiceprint.onnx`
  are optional in-app downloads. The app must work with none installed.

Read these four files in order:

| file | what it is |
|---|---|
| `README.md` | this — the problem, the constraint, what is decided |
| `OMI-PROTOCOL.md` | the BLE protocol, exact and hard-won. The part worth carrying |
| `LESSONS.md` | numbers that were measured, and failures that looked like health |
| `REFERENCE-CODEBASE.md` | where to look in the desktop repo, file by file |

---

## What Boswell is

An always-on personal audio archive. A wearable recorder captures continuously;
a host collects the audio, transcribes it, works out who was speaking, groups
it into conversations, and makes the result searchable — by word, by meaning,
and by a model through an MCP tool surface.

The desktop version has been running for months and holds roughly **29,000
clips, 26,000 transcripts and 78,000 transcript segments**. It is not a
prototype. Its assumptions have been tested by a real archive, which is why the
numbers in `LESSONS.md` are worth more than the code that uses them.

Two recorders feed it: a handmade XIAO nRF52840 board running Zephyr, and a
commercial **Omi CV 1**. The phone project is about the Omi. The handmade board
is not in scope unless Nathan says otherwise.

## The constraint that shapes everything

**A phone has no RTX 4090.**

The desktop does all its thinking locally: WhisperX for transcription, pyannote
for diarization, wespeaker for voiceprints, an AST model for sound tagging.
Every one of those assumes a card with several gigabytes of VRAM. On a phone,
that work has to be hosted.

This is the whole reason the phone version is a separate project rather than a
port. It is not "the same program on a smaller screen" — the division of labour
is different. What stays local is the radio and the storage; what leaves is the
intelligence.

## What is already solved, and what is genuinely new

**Mostly solved (the desktop already has hosted paths):**

- **Transcription** already selects between `local`, `openai` and `deepgram`.
  Two cloud implementations exist and work.
- **Diarization from a hosted service** already has a path: when segments
  arrive already carrying speaker labels, the desktop builds the voiceprints
  itself. Its own comment says *"the one thing they cannot send back is a
  voiceprint"*. Any hosted diarizer that returns labels but no embeddings —
  NVIDIA's Nemotron-3-Diarization is one Nathan has looked at — fits that shape.
- **The reviewing model** is already remote by default (OpenRouter, DeepSeek).

**Genuinely new work, and it is the hard part:**

- **Being the BLE host on a phone.** Background BLE on iOS and Android is a
  different discipline from a Linux daemon with bleak. This is where the
  protocol knowledge in `OMI-PROTOCOL.md` is worth real money.
- **Storage and offload on a device with limited disk and aggressive process
  lifecycle management.** The desktop spools raw bytes to disk and fsyncs
  before decoding, because *reading the device consumes it*. A phone OS that
  kills your process mid-transfer makes that property much sharper.
- **Voiceprints without a local embedding model.** Speaker identity is the
  thing the desktop does best and the thing hosted services least provide.
  Decide early whether the phone does identity at all, defers it to a server,
  or ships audio somewhere that can.

## One fact that constrains the product, not just the code

**The Omi allows one connection at a time.** A phone holding it means the
laptop cannot, and vice versa. They are alternatives, not a pair.

This matters for how the two projects coexist. Nathan's Neo 1 and Looki L1
record independently and can run alongside anything; the Omi cannot.

## What NOT to assume

- Do not assume the desktop's architecture is right for a phone. It is right
  for a machine with a GPU, a stable power supply, and a process that lives for
  days. Question the clip-at-a-time pipeline in particular — see `LESSONS.md`.
- Do not assume the desktop's code can be lifted. Read it for *what was
  learned*, not for what to copy.
- Do not start building until Nathan says so.
