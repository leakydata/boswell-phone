# Development log

How Boswell Phone was built, milestone by milestone, with what was measured along the way. The project overview is the [README](../README.md).

## Origins

The phone version of Boswell: an Android app that records from an Omi, then
transcribes, diarizes and names voices, independently of the desktop.

**The owner's instruction, 2026-09-26:** the phone version is *its own project*,
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
  (`tools/local_asr_bench.py`). **On a Pixel 10 Pro XL (Tensor G5):** RTF 0.12
  on 4 threads (~8× faster than realtime), 0.16 on 1 thread, 0.26 on 8 (the
  little cores slow it down, so use 4). Decoding several streams in one batch is
  faster still. Measured with sherpa-onnx's Android CLI binary over adb.
- **The app ships with no models.** ASR, segmentation and `voiceprint.onnx`
  are optional in-app downloads. The app must work with none installed.

## The app (milestone 1, 2026-09-30)

Kotlin + Compose, `app/`, package `net.boswell.phone` (debug build installs as
`net.boswell.phone.debug`). Build with `./gradlew :app:assembleDebug`; unit
tests with `./gradlew :app:testDebugUnitTest`. JDK 21, AGP 9.4, compileSdk 37.1,
minSdk 33, arm64 only.

What works, verified on a Pixel 10 Pro XL with the Omi CV 1 (fw 3.0.21):
scan by service UUID, connect without bonding, read device info, battery,
charging, live link RSSI and device clock (each shown with its age), subscribe
to live audio, decode Opus, and write 30 s WAV clips with desktop-compatible
sidecar JSON (`time_known: false`) in app-private storage, from a foreground
service that reconnects with backoff.

Things learned on the device:

- **The CV 1 sends nothing while it is quiet.** Its mic has hardware acoustic
  activity detection (`CONFIG_OMI_ENABLE_T5838_AAD`, ~3 s hold): in silence
  the stream pauses, for minutes, on a healthy link. Liveness is therefore a
  GATT read succeeding, never audio arriving, and a pause longer than 4 s
  closes the current clip so a clip never spans a silence.
- **Concentus (pure-Java Opus) is close enough to libopus.** Same refused
  frames, 99.85% of samples within ±1, voiceprint cosine 0.9999
  (`tools/opus_parity.py`, `OpusParityTest`).
- The live packet counter is 16-bit and wraps every ~22 min. `RunTracker`
  treats a wrap as a wrap. The desktop's `Run.note` treats it as a reboot.
- **Offload is deliberately absent.** Only the read-only ring-info query
  exists (the "Check backlog" button). Reading the ring consumes it: audio
  drained to the phone is gone from the device, and so never reaches desktop
  Boswell.

`tools/omi_probe.py` counts live notifications from this machine without
writing anything, to tell device behavior from phone behavior.

## Milestone 2: on-phone transcripts with speakers (2026-09-30, local only)

Everything runs on the phone; nothing is sent anywhere.

- **Models** download on request from the `models-v1` GitHub release
  (`tools/make_model_release.py` builds the catalog `app/src/main/assets/models.json`),
  resume after interruption, and are checked against SHA-256. The APK ships none.
- **Transcription:** Nemotron 3.5 ASR via the sherpa-onnx static AAR, fetched
  and hash-checked by the `fetchSherpa` Gradle task (not committed).
- **Diarization:** `diarize/Diarizer.kt`, pyannote's recipe in miniature --
  segmentation-3.0 over 10 s windows every 2 s, local speakers embedded with
  `voiceprint.onnx`, average-linkage clustering at cosine 0.60 (the desktop's
  `SAME_VOICE`), and a per-frame vote to rebuild the timeline. The segmentation
  ONNX matches pyannote's torch model frame for frame. Against desktop
  pyannote 3.1 on 30 Omi clips (`tools/diarize_compare.py`): median 88%
  speaker-time agreement, and the phone names the same person the desktop did
  for 72% of voices the desktop had named. Most misses are voices under ~3 s.
- **Identity:** `speakers/` ports the desktop's rules unchanged (best row per
  person, margin between people, MATCH_HIGH 0.75, MARGIN_MIN 0.15,
  MARGIN_STRONG 0.25, unnamed clusters at 0.75). The store starts empty.
- **Transcripts** are saved per clip in desktop Boswell's own JSON format.
  Organizing them into conversations is still to be decided.
- **Speed on the Pixel 10 Pro XL:** median 3.6 s to transcribe, diarize and
  identify a 30 s clip.

Read these four files in order:

| file | what it is |
|---|---|
| `DEVLOG.md` | this — the problem, the constraint, what is decided |
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
is not in scope unless the owner says otherwise.

## The constraint that shapes everything

**A phone has no RTX 4090.**

The desktop does all its thinking locally: WhisperX for transcription, pyannote
for diarization, wespeaker for voiceprints, an AST model for sound tagging.
Every one of those assumes a card with several gigabytes of VRAM. On a phone,
that work has to be hosted.

This is the whole reason the phone version is a separate project rather than a
port. It is not "the same program on a smaller screen" — the division of labor
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
  NVIDIA's Nemotron-3-Diarization is one the owner has looked at — fits that shape.
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

This matters for how the two projects coexist. the owner's Neo 1 and Looki L1
record independently and can run alongside anything; the Omi cannot.

## What NOT to assume

- Do not assume the desktop's architecture is right for a phone. It is right
  for a machine with a GPU, a stable power supply, and a process that lives for
  days. Question the clip-at-a-time pipeline in particular — see `LESSONS.md`.
- Do not assume the desktop's code can be lifted. Read it for *what was
  learned*, not for what to copy.
- Do not start building until the owner says so.

## Milestone 3: a phone-first app (2026-09-30)

Deliberately unlike the desktop in layout: it is for looking back at your
day, not for operating a pipeline.

- **Today:** swipe between days; a 24-hour ribbon (talk solid, typing/TV/other
  sounds tinted, background faint); conversation cards with faces, first
  lines, names and sound chips.
- **Conversation:** chat-style bubbles per voice (one stable color per
  person), tap a line to hear it, and a player across all its clips (Media3).
  Tap a voice: "Sounds like X?" to confirm, pick someone, name them, or mark
  it TV/media.
- **People:** a "Who's this?" queue of recurring unnamed voices (hear, name,
  it's TV, skip), known people, and a person page with their conversations
  and "Not them" to take a wrongly named group back off (nothing is deleted).
- **Search:** full-text across everything said (SQLite FTS4), by day.
- **Device:** the Omi, battery-optimization exemption, models, storage and
  diagnostics. Recording restarts after a reboot or an app update if it was on.
- **Archive index** (`archive/Archive.kt`): rebuildable from the files.
  Conversations are speech clips within 60 s of each other; voices keep one
  identity across a conversation's clips (person id, else SAME_VOICE 0.60).
- **Sound tagging:** CED-Mini (10 MB, Apache-2.0) with the desktop's windowing
  and keep/empty rules. It agrees with the desktop's AST verdict on 92% of 150
  clips (`tools/sound_compare.py`), but is less sensitive, so cleanup is
  **off by default**. When switched on it removes only the audio of clips at
  least a week old that have no speech and only background sound. The
  timeline entry and tags are kept.

## Milestone 4: modes and the assistant (2026-09-30)

**Modes** (Device page): Off, Sync, Live.
- **Sync:** the Omi records on its own; the phone visits every 15 min–4 h and
  whenever the Omi comes into range (companion-device presence), downloads
  the backlog and lets go. Reading consumes, so every batch is fsynced to a
  spool before the read pointer moves; partial batches are salvaged; visits
  are capped at 10 min; the device clock is set on each visit. Clips carry the
  device's own timestamps. Storage commands are never sent during a live stream.
- **Live:** streaming, the Omi button, and the assistant listening along.

**Assistant** (Ask tab; settings under Device → Assistant):
- OpenRouter (default `z-ai/glm-5.3-flash`, configurable), key in the Android
  Keystore, text only, every call logged with its cost.
- Tools over the phone's archive: search, recent lines, a day's
  conversations, read a conversation, people, set a reminder. Verified live:
  the model calls `search_transcripts` and answers from the result
  (`LlmToolTest`, about $0.00007 per two-round exchange).
- **Omi button:** tap asks (listen until a pause, transcribe on the phone,
  answer as a notification); double tap bookmarks the moment or summarizes
  the last 10 minutes. On the desktop the BlueZ subscription to the button
  always failed; on the phone this is still untested.
- **Watcher:** in live mode it looks every 2 minutes, only when "Me" has said
  something new, and speaks up rarely. It has its own daily budget ($0.50 by
  default, adjustable).
- Answers are notifications. Spoken answers are a toggle, off by default.

## Milestone 5: transcription, voices and syncing, refined (2026-09-30)

**Transcription.** On the phone by default (Nemotron 3.5 ASR, int8): the
best of the on-device models measured (`tools/phone_vs_cloud.py`,
`tools/parakeet_local.py`). Optional cloud transcription with Parakeet v3
through OpenRouter (Device -> Transcription): it read best of six engines
tested on real Omi clips, about $0.09 per hour of speech. Only clips that
pass the speech check are sent; who spoke and who they are is always
worked out on the phone; a daily limit, and any failure falls back to the
phone. Recordings -> select -> More -> Redo in the cloud does it per clip,
and Compare with the cloud reports how the two differ, word by word.

- A segmentation-only **speech check** (~0.6 s) skips the recognizer for
  clips nobody speaks in: 49 of 51 empty clips caught, none with words missed.
- Clips heard live are transcribed first and newest first; a big download
  from the Omi waits for the phone's charger.
- **Words Boswell should know**: names in People, Omi, Boswell and your own
  words are fixed when the transcript nearly gets them ("omi", "Bozwell").
  Conservative by design; corrected lines keep what was heard.
- Models download compressed (the recognizer: 468 MB instead of 682 MB) and
  are verified after unpacking.

**Voices.** Every transcript keeps its voiceprints, so past recordings are
matched again whenever Boswell learns a voice (naming, review answers,
"Not them", reading the passage). People -> Review asks about voices close
to someone known ("Is this you?"), plays only that voice's parts, and
remembers each No.

**Syncing.** In Live mode, the Omi's stored backlog is collected when it goes
on its charger (setting, on by default), and the Omi's clock is set on every
connection: without a valid clock its firmware stores nothing. Battery
warnings for the Omi and phone offer a switch to Sync mode when very low.

## Milestone 6: the release, and loose ends (2026-09-30 to 2026-10-01)

- **Signed releases** on GitHub (Apache-2.0 with a NOTICE that requires
  credit). Every release is signed with the same key, so updates install over
  the top.
- **Reconnects as soon as the Omi is back.** After one quick retry, the phone
  waits with Android's auto-connect instead of ever-longer retries: about 10 s
  after walking back into range, not minutes.
- **The firmware's ghost frame.** Frames that wouldn't decode, all at the end
  of full stored packets, came from an off-by-one in the Omi's storage writer
  (see OMI-PROTOCOL.md). Skipping them took failures from 815 to 0, and
  removed 673 twenty-millisecond noise blips. Desktop Boswell had the same
  issue and got the same fix.
- **Light brightness** in Device -> Omi: off, 1%, then every 10%, written to
  the Omi (which keeps it) and read back.
- **Conversation cards** show only voices that said something: coughs and
  seconds of TV had their own unnameable "?" circles.

## Milestone 7: moving in for real (2026-10-01)

- **Backup and restore.** Device -> Storage -> Back up writes one zip:
  recordings, transcripts, the people/voice, to-do, memory and assistant
  databases (consistent copies via `VACUUM INTO`), and settings. Models are
  left out (they download again) and the archive index rebuilds itself. API
  keys are tied to the phone's keystore, so they're only included -- readable
  -- when asked. Setup offers "Restore from a backup", which stages the whole
  file before swapping anything, then restarts into setup at Permissions.
  Two bugs found by doing it for real: a restored Live mode started the
  connected-device service before Bluetooth permission existed (Android
  refuses, so the app crashed), and the restored API key was saved
  asynchronously just before the restart and lost.
- **R8** for releases: 90 -> 63 MB. Keep rules for sherpa-onnx and ONNX
  Runtime (their JNI finds classes and fields by name) and JavaMail (it finds
  its providers by name). Verified on the phone: transcription, voices, sound
  tags and the assistant all work in the shrunk build.
- **Email** for the assistant over IMAP/SMTP with an app password, so it
  works with Gmail, Outlook, iCloud, Yahoo and Fastmail without a Google
  Cloud project of our own. The inbox is opened read-only, so reading never
  marks mail as read; sending is held for confirmation like texts.

## Milestone 8: a day that reads at a glance (2026-10-01)

- **Titles and summaries** per conversation, from the assistant's routines a
  few minutes after a conversation ends; stored by conversation id in
  assistant.db (so they survive the archive index being rebuilt) and made
  again if the conversation grows.
- **Cloud "With others"**: diarization now runs before the engine is chosen,
  so a recording goes to Parakeet only when a voice that isn't the owner's
  (or a TV's) speaks for a second or more.
- **Which engine wrote what** is shown per conversation and per line; a
  notification reports how a Redo went, including any fallbacks and why. The
  first Redo looked like it had done nothing: it had worked, but the words
  were nearly the same and nothing said where they came from.
- **Automatic backups** weekly into a chosen folder (Storage Access
  Framework tree, persisted permission), while charging, newest 3 kept,
  never with API keys.
- **Accuracy check**: hand corrections are marked as such (`edited`), and a
  corrected recording becomes an answer key. Scores are word error rates
  against it, for the engine that transcribed it and for Parakeet and Nova-3.
- **Battery**: on a day of testing, Boswell's cost was almost all CPU (588 of
  591 mAh), i.e. on-device transcription; the BLE link and audio barely
  register. A clean off-charger day is still to be measured.
- **Speaker separation threshold** swept on 40 desktop recordings against
  pyannote 3.1: merge-at 0.45/0.50/0.55/0.60/0.65/0.70 gave median timeline
  agreement 80/81/84/88/89/87% and the right speaker count on 13/12/15/19/17/15
  of 40. The existing 0.60 is the best (0.65 ties within noise), so it stays.
  The remaining gap is the lighter pipeline itself; naming, when the phone
  names a voice, matched the desktop 14 of 15 times.
- **Titles feed the assistant**: day_conversations returns each
  conversation's title and summary, so briefs and recaps read the day from
  them and open only what matters. Titles only for conversations of a minute
  or more.

## Milestone 9: a better voice model, made optional (2026-10-02)

- **Search:** the best published speaker-embedding models are w2v-BERT 2.0 +
  MFA (VoxCeleb1-O 0.12% EER, ~600M parameters, GPU) and ReDimNet2-B6 (0.23%,
  12M parameters, MIT); the phone's WeSpeaker ResNet34-LM is ~0.72%. No
  hosted option fits: OpenRouter has no speaker-embedding models, Azure retired
  speaker recognition (2025-09-30), Amazon Voice ID ended (2026-05-20), and
  pyannoteAI's voiceprints are closed to outside matching.
- **Measured on desktop Boswell's archive** (`tools/embed_bench.py`,
  `tools/calibrate_voice.py`), 8 s cap: right person for 92.3% of hand-named
  voices vs 82.1%; 95.9% vs 93.0% over 387. At a 4 s cap the new model only
  matches the old one at full length, so the cap stays 8 s. Thresholds were
  set at equal false-accept rate: 0.78/0.64, margins 0.11/0.19, cluster 0.78,
  same voice 0.69, likely 0.73.
- **Cost on the phone:** 3-4x WeSpeaker per voice (int8 quantization broke the
  model: cosine ~0.09). About double the processing per recording, so it's an
  opt-in setting, "Better voice recognition".
- **Design:** WeSpeaker still splits speakers; the speaker-ID model only makes
  each speaker's final voiceprint. Voiceprints carry their size, so models
  never mix: different sizes score -1 (a 256 vs 192 comparison crashed the
  first re-check). VoiceMigration converts transcripts and voiceprints in
  either direction, then re-checks; it runs as a foreground job (in the
  background Android froze it). On the user's archive: every label kept, none
  changed, 7 more short phrases recognized as the owner.
- **Voiceprints from short speech** (under 3 s) are never saved as references,
  however they arrive; the 39 short ones already saved were removed at the
  user's request, their recordings kept labeled by assignment.

