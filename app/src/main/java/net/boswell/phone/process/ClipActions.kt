package net.boswell.phone.process

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import net.boswell.phone.archive.Archive
import net.boswell.phone.audio.Wav
import net.boswell.phone.capture.CaptureService
import net.boswell.phone.capture.logged
import net.boswell.phone.speakers.SpeakerStore
import java.io.File

/** What can be done to recordings by hand: delete, share, transcribe again. */
object ClipActions {

    /**
     * Delete clips completely: audio, timing, transcript, and the voiceprints
     * learned automatically from them. Permanent.
     */
    fun delete(context: Context, clips: Collection<String>) {
        val cdir = CaptureService.clipsDir(context)
        val tdir = ProcessingWorker.transcriptsDir(context)
        for (name in clips) {
            val base = name.removeSuffix(".wav")
            net.boswell.phone.audio.ClipAudio.delete(cdir, "$base.wav")
            File(cdir, "$base.json").delete()
            File(tdir, "$base.json").delete()
        }
        net.boswell.phone.archive.ArchiveChanges.bump()
        Redone.delete(context, clips)
        val speakers = SpeakerStore(context)
        val archive = Archive(context)
        try {
            speakers.forgetClips(clips)
            // Only these clips' own voices change, and their files are gone: regrouping around them is enough.
            archive.sync(speakers)
        } finally { speakers.close(); archive.close() }
    }

    /**
     * Correct one line by hand. The transcriber's version is kept in
     * `original` the first time, so it can always be restored; saving the
     * original text again removes the correction.
     */
    fun editLine(context: Context, clip: String, start: Double, text: String) {
        val f = File(ProcessingWorker.transcriptsDir(context), clip.removeSuffix(".wav") + ".json")
        val t = TranscriptJson.json.decodeFromString(Transcript.serializer(), f.readText())
        val i = t.segments.indexOfFirst { kotlin.math.abs(it.start - start) < 0.001 }
        if (i < 0) return
        val seg = t.segments[i]
        val heard = seg.original ?: seg.text
        val clean = text.trim().replace(Regex("\\s+"), " ")
        val updated = if (clean == heard || clean.isEmpty()) seg.copy(text = heard, original = null, edited = false)
            else seg.copy(text = clean, original = heard, edited = true)
        net.boswell.phone.audio.writeAtomically(f, TranscriptJson.json.encodeToString(Transcript.serializer(),
            t.copy(segments = t.segments.toMutableList().also { it[i] = updated })).toByteArray())
        val speakers = SpeakerStore(context)
        val archive = Archive(context)
        try { archive.sync(speakers) } finally { speakers.close(); archive.close() }
    }

    /** File a line nobody was attributed to under one of its clip's speakers. */
    fun setLineSpeaker(context: Context, clip: String, start: Double, label: String) {
        val f = File(ProcessingWorker.transcriptsDir(context), clip.removeSuffix(".wav") + ".json")
        val t = TranscriptJson.json.decodeFromString(Transcript.serializer(), f.readText())
        val i = t.segments.indexOfFirst { kotlin.math.abs(it.start - start) < 0.001 }
        if (i < 0 || label !in t.speakers) return
        net.boswell.phone.audio.writeAtomically(f, TranscriptJson.json.encodeToString(Transcript.serializer(),
            t.copy(segments = t.segments.toMutableList().also { it[i] = it[i].copy(speaker = label) })).toByteArray())
        val speakers = SpeakerStore(context)
        val archive = Archive(context)
        // One transcript changed: regrouping around it is enough.
        try { archive.sync(speakers) } finally { speakers.close(); archive.close() }
    }

    /**
     * A conversation voice's clip voices ([slots], as Archive.slotsOf gives them) filed by
     * hand under [person] ([refile]): the longest with a voiceprint long enough to be one
     * (Matching.MIN_PRINT_SECONDS) is kept as a sample of [origin], and every one --
     * including those too short to have a voiceprint, which naming used to silently
     * skip -- is theirs from now on. [from] is who the voice was shown as.
     */
    fun fileSlots(context: Context, store: SpeakerStore, slots: List<Triple<Pair<String, String>, FloatArray?, Double>>, person: Long,
                  from: Long?, origin: String = "confirmed"): Set<Long> {
        val sample = slots.filter { it.second != null && net.boswell.phone.speakers.Matching.printable(it.third) }.maxByOrNull { it.third }
            ?.let { (slot, emb, secs) -> SpeakerStore.Sample(slot.first, slot.second, emb!!, secs, origin) }
        return refile(context, store, slots.map { it.first }, person, sample, from)
    }

    /**
     * "It's a TV, video or radio" for a conversation voice ([key], shown as [personId]).
     * An unnamed voice is a TV wherever it's heard. A voice never filed, or filed as
     * someone (the owner too, who is never a TV), is moved off them: its parts here
     * become a new TV voice ([fileSlots]), as it's this voice that was the TV, not the
     * person, who'd otherwise show as one everywhere. Returns the people touched.
     */
    fun markMedia(context: Context, store: SpeakerStore, archive: Archive, conversation: Long, key: String, personId: Long?, named: Boolean): Set<Long> {
        if (personId != null && !named) { store.setKind(personId, "media"); return setOf(personId) }
        val tv = store.newPerson(null)
        store.setKind(tv, "media")
        return fileSlots(context, store, archive.slotsOf(conversation, key), tv, personId)
    }

    /**
     * One conversation voice ([key], shown as [from]) was really two: its clip voices
     * [moving] go to [target] ([fileSlots]) and the rest stay. A voice never filed holds
     * together only by sounding alike, so its rest would follow the moved parts on the
     * next regroup: it's filed as an unnamed voice of its own. Each side is "not them"
     * for the other, so two unnamed halves are never folded back into one
     * (VoiceReview.tidy) nor matched across. Returns the people touched.
     */
    fun split(context: Context, store: SpeakerStore, archive: Archive, conversation: Long, key: String, from: Long?,
              moving: Set<Pair<String, String>>, target: Long, origin: String = "confirmed"): Set<Long> {
        val all = archive.slotsOf(conversation, key)
        val chosen = all.filter { it.first in moving }
        if (chosen.isEmpty() || target == from) return emptySet()
        val rest = all.filter { it.first !in moving }
        val touched = mutableSetOf<Long>()
        val stays = if (from == null && rest.isNotEmpty()) store.newPerson(null).also { touched += fileSlots(context, store, rest, it, null, "auto") } else from
        for ((slot, _, _) in rest) store.reject(slot.first, slot.second, target)
        if (stays != null) for ((slot, _, _) in chosen) store.reject(slot.first, slot.second, stays)
        return touched + fileSlots(context, store, chosen, target, from, origin)
    }

    /**
     * Clip voices ([slots], clip and label) said by hand to be [to] (SpeakerStore.refile),
     * and their transcripts made to say so too: the person, their name, and no candidate
     * they were just said not to be, so a TV that sounded like someone isn't shown as
     * "TV · them?". Returns everyone they were and [to], for Archive.sync's people.
     */
    fun refile(context: Context, store: SpeakerStore, slots: Collection<Pair<String, String>>, to: Long,
               sample: SpeakerStore.Sample? = null, from: Long? = null): Set<Long> {
        val touched = store.refile(slots, to, sample, from)
        val name = store.nameOf(to)
        val tdir = ProcessingWorker.transcriptsDir(context)
        for ((clip, labels) in slots.groupBy({ it.first }, { it.second })) {
            val f = File(tdir, clip.removeSuffix(".wav") + ".json")
            val t = runCatching { TranscriptJson.json.decodeFromString(Transcript.serializer(), f.readText()) }
                .let { if (f.exists()) it.logged("refile: reading ${f.name}") else it }.getOrNull() ?: continue
            val ids = t.speakers.mapValues { (label, sp) ->
                if (label !in labels || BoswellLines.isBoswell(sp)) return@mapValues sp
                val no = store.rejected(clip, label)
                sp.copy(personId = to, name = name, candidates = sp.candidates.filter { store.resolve(it.personId) !in no })
            }
            if (ids != t.speakers) net.boswell.phone.audio.writeAtomically(f, TranscriptJson.json.encodeToString(Transcript.serializer(),
                t.copy(speakers = ids)).toByteArray())
        }
        return touched
    }

    /**
     * "Not Boswell": what was taken for Boswell's own voice in these clips
     * goes back to the voices it was heard in, and those are matched and
     * filed like any other voice. The clips are never labeled Boswell's again,
     * not even when transcribed again, and nothing learned from them is kept.
     */
    fun notBoswell(context: Context, clips: Collection<String>) {
        val tdir = ProcessingWorker.transcriptsDir(context)
        val speakers = SpeakerStore(context)
        val archive = Archive(context)
        try {
            for (clip in clips) {
                val f = File(tdir, clip.removeSuffix(".wav") + ".json")
                val t = runCatching { TranscriptJson.json.decodeFromString(Transcript.serializer(), f.readText()) }
                    .let { if (f.exists()) it.logged("not Boswell: reading ${f.name}") else it }.getOrNull() ?: continue
                if (t.speakers.values.none(BoswellLines::isBoswell)) continue
                speakers.notBoswell(listOf(t.clip))
                val segments = t.segments.map { if (it.speaker == BoswellLines.LABEL) it.copy(speaker = it.diarized, diarized = null) else it }
                val ids = LinkedHashMap<String, SpeakerId>()
                for ((label, sp) in t.speakers) {
                    if (label == BoswellLines.LABEL) continue
                    ids[label] = if (BoswellLines.isBoswell(sp)) ProcessingWorker.identify(speakers, t.clip, label, t.embeddings[label]?.toFloatArray(), sp.seconds) else sp
                }
                net.boswell.phone.audio.writeAtomically(f, TranscriptJson.json.encodeToString(Transcript.serializer(),
                    t.copy(segments = Lines.attributeOrphans(segments) ?: segments, speakers = ids)).toByteArray())
            }
            archive.sync(speakers, force = true)
        } finally { speakers.close(); archive.close() }
    }

    /**
     * Transcribe these clips again: each transcript is held aside (Redone.held)
     * and the background pass does the clip over, carrying over what was done
     * by hand -- corrected lines, voices named, "not them" answers -- to the
     * new one (CarryOver). A transcript that recorded an error is just dropped.
     */
    fun retranscribe(context: Context, clips: Collection<String>) {
        val tdir = ProcessingWorker.transcriptsDir(context)
        for (name in clips) {
            val f = File(tdir, name.removeSuffix(".wav") + ".json")
            if (!f.exists()) continue
            val ok = runCatching { TranscriptJson.json.decodeFromString(Transcript.serializer(), f.readText()) }.logged("redo: reading ${f.name}").isSuccess
            if (ok) f.renameTo(Redone.held(context, name)) else f.delete()
        }
        net.boswell.phone.archive.ArchiveChanges.bump()
        ProcessingWorker.enqueue(context)
    }

    /**
     * A share sheet with the transcript as text and the audio joined into one
     * WAV (clips without audio are skipped). Nothing leaves the phone unless
     * the person picks somewhere to send it.
     */
    fun shareIntent(context: Context, clips: List<String>, title: String, transcript: String): Intent {
        val cdir = CaptureService.clipsDir(context)
        val parts = clips.filter { net.boswell.phone.audio.ClipAudio.exists(cdir, it) }.map { net.boswell.phone.audio.ClipAudio.readPcm(cdir, it) }
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, transcript)
        if (parts.isEmpty()) return Intent.createChooser(intent.setType("text/plain"), "Share")
        val out = File(File(context.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } },
            title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '_').ifEmpty { "boswell" } + ".wav")
        val all = ShortArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(all, o); o += p.size }
        Wav.write(out, all, 16_000)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".share", out)
        return Intent.createChooser(intent.setType("audio/wav").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")
    }
}
