# Using the desktop codebase as a reference

**Location:** `/home/scholyx/Documents/electronics/nRF52840`
**GitHub:** `https://github.com/leakydata/boswell` (public, branch `main`)

It is a sibling of this directory, so `../nRF52840/` from here.

## How to read it

The codebase is unusually heavily commented, and **the comments are the point**.
They are not descriptions of what the code does — they are records of what was
tried, what it cost, and what the measurement said. A typical one names the
bug, the number that settled it, and the date.

So: **grep the comments, not just the code.** When you need to know why a
threshold is what it is, the answer is almost always written directly above it.

Two other sources in the same repo:

- **`README.md`** (~1,600 lines) is a genuine engineering document, not a
  getting-started page. Sections worth reading before any design work:
  *Using an Omi as a recorder*, *What Omi does differently*, *Naming voices*,
  *What kind of voice it is*, *Reading it back as sentences*, *Searching by
  meaning*, and *Who does the thinking*.
- **`git log`** messages are long and explain reasoning. `git log -S<symbol>`
  to find when something changed and why is often faster than reading the file.

## File by file — what each is good for

### The parts that transfer directly

| file | why you care |
|---|---|
| **`host/omi_sync.py`** | **The most valuable file here.** The complete offload protocol: ring info, batched reads, advancing the read pointer, spooling raw bytes before decode, salvaging a partial batch. Its module docstring is a protocol spec. Read this before designing phone-side storage. |
| **`host/omi_capture.py`** | Live BLE: service/characteristic UUIDs, codec negotiation, frame decoding, device settings (gain, LED), reading the device clock. |
| **`host/omid.py`** | The orchestration that is easy to underestimate — how live capture and offload share one exclusive radio, backoff, reconnection, liveness, and what to do when the device is merely absent. The phone equivalent of this file is most of the work. |

### The parts to read for judgement, not for code

| file | why you care |
|---|---|
| **`web/pipeline.py`** | Transcription and the per-clip processing chain. Note `_process`'s `cloud_diarized` branch — segments arrive with labels from elsewhere, voiceprints get built locally. That is the exact seam a hosted diarizer plugs into. |
| **`web/speaker_store.py`** | Speaker identity: thresholds, margins, voice kinds, why nothing is averaged. The design reasoning matters even if the phone defers identity to a server. |
| **`web/embedder.py`** | Turning diarized spans into voiceprints, independent of who did the diarizing. `spans_from_segments()` + `voiceprints()` is the bring-your-own-diarizer path. |
| **`web/threads.py`** | Stitching clip fragments into utterances and finding subject boundaries. Relevant to any UI that shows conversations rather than clips. |
| **`web/index_db.py`** | Conversation grouping, the single `CONVERSATION_GAP`, and the clip index. |
| **`web/semantic.py`**, **`web/units.py`** | Meaning search: embeddings in sqlite-vec, reciprocal rank fusion, and why a sentence is a better search unit than a transcript line. |
| **`host/boswell_mcp.py`** | The tool surface a model uses to read and write the archive. If the phone exposes anything to an assistant, this is the vocabulary that already works. |
| **`host/tools_impl.py`** | The attribution gate — what is refused and why. |

### Tests are documentation

`tests/` has ~870 passing tests and many are written as narratives about a
specific bug. Names like `test_the_overnight_failure_now_heals`,
`test_a_batch_that_stops_early_keeps_what_already_arrived`,
`test_the_cure_must_not_cost_more_than_the_disease` point straight at the
incident that caused them. Reading the test file for a subsystem is often the
fastest way to learn its constraints.

Useful ones: `tests/test_omi_sync.py` (the offload protocol and its failure
paths), `tests/test_omid_heal.py` and `tests/test_omid_watchdog.py` (liveness
and recovery), `tests/test_speaker_store.py` (identity).

## Claude's memory for this project

If the new session has access to the auto-memory at
`~/.claude/projects/-home-scholyx-Documents-electronics-nRF52840/memory/`,
several entries are directly relevant:

- `omi-as-a-recorder.md` — the two protocol facts, condensed
- `when-the-ring-query-fails.md` — diagnosing offload failures
- `never-pair-the-omi-in-os-settings.md` — a BlueZ bond makes the device
  invisible; the platform-specific lesson generalises to "find out what your
  OS does with a bonded device before assuming the device is at fault"
- `boswell-phone-is-its-own-project.md` — the decision this directory exists for
- `talks-at-the-screen.md`, `labelling-voices-not-clips.md`,
  `extraction-needs-an-attributable-speaker.md` — why the attribution rules
  are shaped as they are

Those are notes written for a different project; treat them as leads.

## How to use it well

**Do:**
- Read `host/omi_sync.py` and the README's Omi sections before designing
  anything that touches the device.
- Copy the *constants* and the *reasoning*. They were expensive.
- Check `git log` when something looks arbitrary. It usually is not.

**Don't:**
- Port the architecture. It assumes a GPU, mains power, and a process that
  lives for days — a phone has none of those.
- Assume a number is right because it is in the code. Several were wrong for
  months. The ones that were measured say so in a comment; the ones that do not
  are worth re-checking.
- Run anything in the desktop repo that touches the radio. It is a live
  always-on recorder holding a real archive, and the Omi allows **one
  connection at a time** — a phone-side experiment and the desktop daemon
  cannot both have it. Stop `omid` deliberately, or work when the desktop is
  not collecting.
