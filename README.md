<div align="center">

# Boswell Phone

**A memory for your day, kept on your phone.**

Your [Omi](https://www.omi.me) listens. Boswell transcribes what's said, knows *who* said it,
and answers questions about it — with the speech recognition, speaker identification
and sound tagging all running on the phone itself.

![Android 13+](https://img.shields.io/badge/Android-13%2B-3ddc84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7f52ff?logo=kotlin&logoColor=white)
![On-device AI](https://img.shields.io/badge/AI-on--device-0a7ea4)
![Models](https://img.shields.io/badge/models-optional%20download-555)
![License](https://img.shields.io/badge/license-Apache%202.0-blue)
[![Release](https://img.shields.io/github/v/release/leakydata/boswell-phone?include_prereleases&label=download)](https://github.com/leakydata/boswell-phone/releases)

</div>

---

<p align="center">
  <img src="docs/screenshots/today.png" width="19%" alt="Today: the day's conversations, who was in them, and what else was heard">
  <img src="docs/screenshots/conversation.png" width="19%" alt="A conversation, line by line, by speaker">
  <img src="docs/screenshots/ask.png" width="19%" alt="Ask: answers that cite and play the moment">
  <img src="docs/screenshots/people.png" width="19%" alt="People: voices it knows, and voices to name">
  <img src="docs/screenshots/todo.png" width="19%" alt="To-do: including promises noticed in what was said">
</p>
<p align="center"><sub>A made-up day, for illustration.</sub></p>

> *James Boswell followed Samuel Johnson around London for twenty years, writing down
> what he said. This one fits in a pocket.*

Boswell Phone pairs with an Omi wearable, records the conversations around you, and turns
them into a day you can scroll, search and ask about: **who you talked with, what was
decided, what you promised.** Tap the Omi's button and ask out loud; the answer arrives
on your lock screen, or in your ear.

It is a standalone Android app — the phone-first sibling of the desktop
[Boswell](https://github.com/leakydata/boswell) project, built to be fully useful on its
own and to produce voiceprints **identical** to the desktop's, so the two archives can
one day merge.

## What it does

### 🎙️ Capture, the way you actually wear it
- **Live** — streams from the Omi over Bluetooth; conversations appear within seconds.
- **Sync** — the Omi records on its own and the phone collects its memory every so
  often, or whenever it comes into range. Easier on both batteries.
- **On the charger** — in Live mode, putting the Omi on its charger collects whatever it
  stored while you were out of range, then goes back to live.
- Recordings land **in the order they happened**, by the Omi's own clock, however late
  they download. Live capture restarts itself if Android ever stops the app.
- **Reconnects by itself** within seconds of coming back into range.
- **The Omi's light** can be dimmed to 1% or turned off, for when a glowing badge
  isn't welcome.

### 📝 Transcription — on the phone, or in the cloud if you'd rather
- **NVIDIA Nemotron 3.5 ASR** runs on the phone, ~8× faster than real time.
- A **speech check** (0.6 s) skips clips nobody speaks in, before the recognizer runs.
- Optional **cloud transcription** with NVIDIA **Parakeet v3** — sending only the
  speech, never the silences — for everything, or only **when someone besides you is
  talking** (where the phone is weakest), with a daily spending limit and automatic
  fallback to the phone. **Redo** any conversation in the cloud, and see which words
  came from where.
- **Check the accuracy yourself**: correct a few lines, and the app scores the phone,
  Parakeet and Nova-3 against what was really said.
- **Words Boswell should know**: names and terms it nearly gets right ("omi",
  "Bozwell") are corrected, conservatively, and can be undone.
- **Compare with the cloud** shows, word by word, where the phone and the cloud disagree.
- **Or on your own computer**: pair with [Boswell Server](https://github.com/leakydata/boswell-server)
  (scan its QR code in Device → Home server) and a GPU at home does the transcription,
  speakers, voiceprints and sounds over [Tailscale](https://tailscale.com) — recordings
  close every 10 seconds of speech for near-live transcripts, your vocabulary goes along
  as hot words, and the phone saves its battery. Your people and their voices stay on
  the phone. When home can't be reached, the phone does the work itself or waits, and says
  why ("Is Tailscale on?"); once home is back, what the phone did is **redone at home** in the
  background, keeping your corrections and named voices.

### 🗣️ Voices — who said what
- Speaker diarization and **voiceprints compatible with desktop Boswell** (cosine
  1.0000 against the desktop on 217 speakers).
- **Teach it your voice** by reading a short passage during setup.
- **Better voice recognition** (optional): a larger model, ReDimNet2, picked the right person
  for 92% of hand-checked voices against 82%, for about twice the processing per recording.
- **"Is this you?"** — a review of voices that are close but not certain, playing only
  that voice's words. One answer covers every recording of it, and every past recording
  is checked again.
- **Link people to your contacts**: numbers, emails and birthdays come from your phone's
  contacts, and voices get names.
- **Boswell knows its own voice**: when it reads an answer aloud and the Omi hears it, those
  words are labeled **Boswell**, matched by when it spoke and what it said, and kept out of
  the listening assistant, voice triggers, voiceprints and who-talked stats.

### 🤖 An assistant with your day as context
Every conversation gets a **title and a one-line summary**, so a day reads at a glance.
Tap the Omi and ask — *"What did Sam say about the deadline?"* — or type in the app.
Answers cite the moment, and **▶ plays it**. It can:

| | |
|---|---|
| 📅 To-dos, reminders and calendar events | 🔎 Search everything that was said |
| ⏱️ Timers and alarms (rings the phone, buzzes the Omi) | 💬 Read and send texts — only with people you choose, only when you confirm |
| ✉️ Read your inbox and send email — each one only after you confirm | 🗓️ What's on your calendar |
| 🧠 Remember facts about people | 📊 Who you talked with most, and for how long |
| 🌐 Look things up on the web | 📒 Quick logs — medication, expenses, parking |
| ☎️ Pull out numbers, emails, addresses that were said | 📝 Meeting notes: summary, decisions, action items |

**Listen along** in live mode: it quietly follows the conversation and sends a short hint when it
can help, such as the word or name you're reaching for, a little more on an idea, a correction or a reminder,
at the pace you choose (Quiet, Normal, Chatty), remembering what it already told you so it doesn't repeat itself. **Fact check** does the same for
claims: when something said is checkably wrong, it looks it up on the web and tells you, with a source.

And it works in the background: a **morning brief**, an **evening recap**, **promises
noticed** in what was said (yours and others') filed as to-dos, and a **brief before each
meeting** from what was last said with those people.

It runs on [OpenRouter](https://openrouter.ai) (a fraction of a cent a question), or, with
[Boswell Server](https://github.com/leakydata/boswell-server), on **a model on your own
computer** (Ollama): free, and nothing leaves the house. Web searches still use OpenRouter.

### 💾 Yours to keep
**Back up** everything — recordings, transcripts, people and their voices, to-dos,
what the assistant remembers — to one file, **automatically every week** into a folder
you choose, and **restore** it in setup on a new phone. Paired with
[Boswell Server](https://github.com/leakydata/boswell-server), it also backs up **every day
to your own computer** (the newest 7 kept there, API keys never included), and restores from
there in Device → Home server.

### 🔒 Private by default
Everything is recorded, transcribed, identified and stored **on the phone**. Nothing
leaves it unless you turn on the assistant (text only), cloud transcription, or your own
home server (over your private Tailscale network) — each
spelled out where you switch it on, each with its cost on an AI-usage screen. No
account, no server, no telemetry.

## How it works

```mermaid
flowchart LR
    Omi["Omi wearable<br/>Opus audio over BLE"] -->|live stream| Clip
    Omi -->|stored backlog<br/>Sync / charger| Spool[Spool on disk] --> Clip
    Clip["30 s clips<br/>Ogg Opus, Omi's own frames"] --> Check{Speech check<br/>pyannote seg.}
    Check -->|nobody speaking| Quiet[Timeline only]
    Check -->|speech| ASR["Words<br/>Nemotron on phone<br/>or Parakeet cloud"]
    Check -->|speech| Diar["Who spoke when<br/>segmentation + clustering"]
    Diar --> VP["Voiceprints<br/>WeSpeaker ResNet34"] --> ID[Match against people]
    ASR --> T[Transcript]
    ID --> T
    Clip --> Tags["Sound tags<br/>CED-Mini"] --> T
    T --> Index[(Archive index<br/>search · conversations)]
    Index --> UI[Today · People · To-do]
    Index --> AI["Assistant<br/>tools over your day"]
    Button["Omi button"] --> AI
```

- **Capture** keeps the Omi's own Opus frames as an Ogg file (no re-encoding, about a
  tenth of a WAV's size); a speech-free clip keeps only its place on the timeline.
- **Identity** follows the desktop's rules exactly: one row per voiceprint, the best row
  per person, a margin over the runner-up, and unnamed voices clustered until you name
  them. Your own voice, which is in nearly every recording and mostly in short bits, gets one
  looser rule measured on real recordings, so you rarely have to label yourself.
- **The assistant** is any model on [OpenRouter](https://openrouter.ai) (your key),
  with tools over the archive and the phone. Moments are labelled `[L123]` in what it
  reads, and it cites them back so the app can play them.

## Measured, not guessed

Every number below came from a script in [`tools/`](tools) run against real recordings.

| | Result |
|---|---|
| Voiceprints vs desktop Boswell | cosine **1.0000**, same name in **217/217** cases |
| Opus decoding vs libopus | **99.85%** of samples within ±1; voiceprints 0.9999 alike |
| On-device transcription (Pixel 10 Pro XL) | **0.12×** real time on 4 threads |
| Speech check on 185 clips | **49 of 51** empty clips skipped, **no** speech missed |
| Cloud engines on the owner's own reading | Parakeet v3 **0%** errors · Nova-3 3.2% · Whisper turbo 4.7% (and invents text over noise) |
| Storage | **482 MB → 30 MB**, by keeping the Omi's own frames |
| Speech-only cloud transcription | **80%** cheaper on a mostly-quiet clip, same words and times |
| End of a spoken question | **0.8–1.6 s** after the last word (speech model, not loudness) |
| Model download | **682 → 468 MB** for the recognizer, gzip-verified on the phone |
| Downloaded audio frames | **815 → 0** undecodable, after finding an off-by-one in the Omi's firmware |
| App download | **90 → 63 MB** with R8; the rest is the on-device speech engine |

The full story — including the dead ends — is in [`docs/DEVLOG.md`](docs/DEVLOG.md).

## Getting started

**You need:** an Android 13+ phone (arm64), an **Omi CV 1**, and — only for the
assistant or cloud transcription — an [OpenRouter](https://openrouter.ai) API key.

### Install

**[⬇ Download the latest APK](https://github.com/leakydata/boswell-phone/releases)**
(about 63 MB), open it on your phone, and allow installing from your browser or file
manager when Android asks. Play Protect may warn about an app it hasn't seen before;
choose *Install anyway*. Updates install over the top, keeping your data.

Or build it yourself (see [Building](#building)).

### First run

1. Open Boswell and follow the setup (or **restore a backup** from another phone): permissions,
   find your Omi, choose Live or Sync, download the models (~510 MB, once), read a short
   passage so it learns your voice, and optionally add your OpenRouter key.
2. **Wear the Omi.** Conversations show up on **Today**; name the voices you know in
   **People**.
3. **Tap the Omi's button** (a quick tap — it ignores long presses) and ask a question.
   It buzzes when it hears you, and again when the answer is on your phone.

Models are **not** in the app: they download from this repository's
[`models-v1`](https://github.com/leakydata/boswell-phone/releases/tag/models-v1) release,
are checked against their SHA-256, and can be removed any time.

## Building

```bash
# JDK 21 and the Android SDK (compileSdk 37); the sherpa-onnx AAR is fetched by Gradle
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk   # installs beside the release, as net.boswell.phone.debug

# A signed release reads its keystore from ~/.gradle/gradle.properties
# (BOSWELL_KEYSTORE, BOSWELL_KEY_ALIAS, BOSWELL_STORE_PASSWORD, BOSWELL_KEY_PASSWORD).
./gradlew :app:assembleRelease

./gradlew :app:testDebugUnitTest          # 75 unit tests, no device needed
```

The measurement scripts in `tools/` are a [uv](https://docs.astral.sh/uv/) project:
`cd tools && uv run python speech_check.py …`.

## Repository

```
app/src/main/java/net/boswell/phone/
  omi/         BLE protocol: live audio, storage offload, button, haptics, clock
  capture/     the capture service, clips, battery watch, charger drain
  sync/        Live / Sync modes, spool → clips
  asr/         on-device and cloud transcription, speech-only cloud, vocabulary
  diarize/     segmentation, clustering, speech check
  speakers/    voiceprints, matching, review and re-check
  audio/       Opus decode/encode, Ogg Opus, WAV, clip storage
  archive/     the searchable index: clips, lines, conversations
  assistant/   the agent, its tools, routines, texting, alarms, contacts
  process/     the transcription worker, cleanup, clip actions
  ui/  setup/  Compose screens
tools/         measurement and release scripts (Python, uv)
docs/          DEVLOG · OMI-PROTOCOL · LESSONS · REFERENCE-CODEBASE
```

- [`docs/OMI-PROTOCOL.md`](docs/OMI-PROTOCOL.md) — the Omi's Bluetooth protocol, exact
  and hard-won, including a firmware storage bug and how to read around it.
- [`docs/LESSONS.md`](docs/LESSONS.md) — numbers measured on a real archive, and
  failures that looked like health.

## Roadmap

- More wearables, and a desktop ↔ phone merge
- On-device Parakeet, once its contextual biasing works reliably in sherpa-onnx

## Credits

Built on [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx),
[ONNX Runtime](https://onnxruntime.ai), [Concentus](https://github.com/lostromb/concentus)
and Jetpack Compose / Media3. The models are downloaded separately, under their own
licenses:

| Model | Use | License |
|---|---|---|
| NVIDIA Nemotron 3.5 ASR (int8) | on-device transcription | OpenMDW-1.1 |
| pyannote segmentation 3.0 | speech check, who spoke when | MIT |
| WeSpeaker ResNet34-LM | voiceprints | CC-BY-4.0 |
| ReDimNet2-B6 *(optional)* | better voice recognition | MIT |
| CED-Mini | sound tags | Apache-2.0 |
| NVIDIA Parakeet TDT 0.6B v3 *(cloud, optional)* | cloud transcription | CC-BY-4.0 |

Boswell Phone is an independent project, not affiliated with Omi / Based Hardware.

## License

[Apache License 2.0](LICENSE) — use it, change it, share it, build products on it,
commercial or not. **Credit is required:** if you distribute Boswell Phone or anything
built from it, keep the copyright notice and include the [NOTICE](NOTICE) file, which
names this project. A link back here is appreciated.

Copyright 2026 Nathan Jones ([@leakydata](https://github.com/leakydata)).
