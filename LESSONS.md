# What the desktop measured, and what it got wrong

Two kinds of thing here: **numbers that were derived from a real archive**
rather than guessed, and **failure shapes that recur**. Both are cheaper to
inherit than to rediscover.

---

## Numbers worth carrying

Every one of these replaced a guess that was measurably wrong.

### Conversations

**`CONVERSATION_GAP = 60` seconds.** A gap longer than a minute starts a new
conversation.

It was 300 seconds, in four separate copies that had drifted apart. At 300 an
entire evening became a single **371-clip, 183-minute "conversation" covering
six unrelated subjects**. At 60 the same evening resolves into conversations
with a p90 of twelve minutes. Name this once, in one place; the drift was the
bug as much as the value.

### Speaker identity

| constant | value | meaning |
|---|---|---|
| `MATCH_HIGH` | 0.75 | a name is applied above this |
| `MATCH_LOW` | 0.55 | below this, not even a candidate |
| `MARGIN_MIN` | 0.15 | must beat the next *person* by this |
| `MARGIN_STRONG` | 0.25 | |

Derived from 1,642 same-speaker and 2,141 different-speaker pairs harvested
from diarized conversations:

| | median | tail |
|---|--:|--:|
| same person, matched conditions | 0.863 | p10 0.715 |
| different people, same recording | 0.107 | p99 0.572 |

A separation of about 0.76 — not the 0.16 an early three-pair measurement
suggested. **Do not trust a threshold derived from a handful of samples.**

**Nothing is averaged.** Every sighting of a voice is kept as its own
voiceprint and matching takes the single closest one. A person does not have
*a* voice — they have one in a quiet room, one outdoors, one over a bad
connection, one at seven in the morning. An average of those describes nobody
and degrades with every addition. Top-1 against the set means a well-covered
person and a newly-named one compete on equal footing.

### Stitching and sectioning

A 30-second clip is a *transport unit*, not a human one — it cuts mid-sentence,
median seven words per transcript line. Two joins, deliberately separate:

- **Stitching** (mechanical): `JOIN_GAP = 2.0s`, capped at `MAX_SECONDS = 120`
  and `MAX_WORDS = 150`; `SAME_VOICE = 0.60` to call two diarized slots the
  same person across a boundary.
- **Sectioning** (judgement): TextTiling over sentence embeddings, scoring dips
  by *depth* rather than absolute value. **`MIN_DEPTH = 0.30`.**

`MIN_DEPTH` was 0.08, set against synthetic vectors in a test. Measured over
3,852 real gaps in 62 conversations — median depth 0.047, p90 0.241, p95 0.316
— **0.08 called 38% of all gaps a change of subject**, one section every 1.3
minutes, and cut a three-line exchange about a cicada noise into three separate
subjects. At 0.30 the same conversation resolves into four sections, about one
per four minutes.

### Attribution

Facts, tasks, events and notes are refused unless they name **who said it**, as
that person appears on the transcript line. Four things are rejected: an empty
speaker; a diarizer label like `SPEAKER_01` (it means a different voice in
every recording); a voice classified as media or ignored; and a voice nobody
has classified yet.

An earlier version judged the **clip** instead — refusing anything from a
recording that was mostly unnamed speech. It was measured against the live
archive and thrown away: Nathan talks at the screen while videos play, so his
words and a video's are interleaved in the same clips and the video usually
does most of the talking. That rule blocked his own dictated request at 4%
named-person.

**Judge the line by who said it, never the clip by how loud the room was.**

### Voice kinds

Every voice carries a kind: `person`, `media`, `ignored`, or nothing yet. It
lives on the **voice**, not the clip, so classifying one settles every
recording it appears in, past and future.

**A kind must never stop a voice being collected.** Tagging a voice "it's a
video" originally ended it — the voice was in neither the named-references path
nor the unknown-cluster path, so every later occurrence arrived as a fresh
stranger. Tag fifty video voices and you get fifty clusters today and fifty
more next week. Nathan's objection is the right way to think about the whole
feature: he will not use a label meaning "doesn't matter" if it stops that
voice being labelled properly some other day.

---

## Failure shapes that recur

These cost days. They are not Boswell-specific; they are what always goes
wrong in a system that collects from a device it does not control.

### A stale reading presented as current is a lie

The status file published `battery: 1%` from a reading taken 61 minutes
earlier, while the device sat on a charger at 93%. It was believed and acted
on. Every number a device reports must carry **when it was read**, and anything
consuming it must be able to say "as of".

The ring depth has the same problem in a nastier form: it is read *during the
sync*, so it goes stale exactly when sync starts failing — which is exactly
when someone is looking at it.

### A hang is indistinguishable from health from the outside

The collector wedged on a dead D-Bus connection and sat in `select()` for five
hours. `systemctl` reported the service active. The status file froze
mid-sentence. It ignored SIGTERM. `Restart=always` was set and did nothing,
because the process never exited.

**The fix is a liveness signal that means progress, not announcement.** The
first attempt marked the daemon alive inside the status-publishing call — but a
heartbeat publishes on a timer whether or not work is moving, so a hung session
would have gone on announcing "recording" forever. Progress must be: a loop
pass, a frame count that actually changed, or bytes moving.

### A failure nobody can see in the log is a failure nobody finds

Transcription failures were reported through a notifier wired to the websocket
broadcaster. Every failure was announced to whatever browser happened to be
open, and to nothing else. **5,991 clips failed over seven hours** with nothing
in the journal but a sweep line. The visible symptom was a count that had not
moved.

The underlying cause was unrelated and mundane — a broken `LD_LIBRARY_PATH`
made `ffmpeg` exit 127 — but it was invisible for a day because of where the
error went.

### A long job on the event loop takes the whole server down

Naming a voice triggered a whole-archive re-match running synchronously inside
an async request handler. The server stopped accepting connections entirely —
thirteen queued on a listening socket, `systemctl` reporting healthy. Found by
stack dump, not by reasoning.

### The cure can cost more than the disease

An automatic "reset the Bluetooth stack when sync fails" ran **86 times in
eight days**, each one dropping every BLE device on the machine including the
user's keyboard. He eventually switched Bluetooth off to get his keyboard back,
which stranded five hours of audio on an unreachable recorder. *The fix for
losing recordings caused a loss of recordings.*

Two rules came out of it: **reach for the smallest remedy that can work**
(disconnect one device, not restart the stack), and **stop when it isn't
working** — a remedy that has failed three times should stop firing and say so.

### "Away" is not "broken"

A flat battery overnight was reported as *401 sync failures*, 379 of which were
the daemon correctly noticing that a switched-off recorder was not there.
Nothing is being lost while a recorder is away, because a recorder that is away
is not recording. Distinguish the two in whatever you show the user.

### The recurring real-world cause is the battery

Three separate multi-hour gaps in one month were a flat Omi, not software. A
dead device is invisible to every alarm, because it looks exactly like one that
is out of range. Any status display should make battery age and charge
prominent.

---

## An architectural question worth reopening

The desktop diarizes **per 30-second clip**, so `SPEAKER_00` means a different
person in every clip. That single fact is *why* voiceprints, thresholds,
margins and purity checks all exist — and it is where the remaining bugs live
(clusters fragmenting across boundaries, a narrator landing in someone's slot).

Hosted diarizers that maintain stable speaker assignments across a **stream**
(NVIDIA's Nemotron-3-Diarization does this with arrival-order speaker caching)
would make `speaker_0` one person for the length of a conversation. You would
still need voiceprints to carry identity *between* conversations, but you would
stop fighting the boundary inside one.

On the desktop this is not worth the re-architecture: diarization is only ~17%
of per-clip cost there. **On a phone, where the work is hosted anyway, the
calculus is different.** Worth deciding deliberately rather than inheriting the
clip-at-a-time shape by default.

Note the trade: that model returns labels and timestamps but **no embeddings**,
and caps at eight speakers.
