package net.boswell.phone.process

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import net.boswell.phone.archive.Archive
import net.boswell.phone.audio.Wav
import net.boswell.phone.capture.CaptureService
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
            File(cdir, "$base.wav").delete()
            File(cdir, "$base.json").delete()
            File(tdir, "$base.json").delete()
        }
        val speakers = SpeakerStore(context)
        val archive = Archive(context)
        try {
            speakers.forgetClips(clips)
            archive.sync(speakers, force = true)
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
        val updated = if (clean == heard || clean.isEmpty()) seg.copy(text = heard, original = null) else seg.copy(text = clean, original = heard)
        net.boswell.phone.audio.writeAtomically(f, TranscriptJson.json.encodeToString(Transcript.serializer(),
            t.copy(segments = t.segments.toMutableList().also { it[i] = updated })).toByteArray())
        val speakers = SpeakerStore(context)
        val archive = Archive(context)
        try { archive.sync(speakers) } finally { speakers.close(); archive.close() }
    }

    /** Throw the transcripts away so the background pass transcribes these clips again. Hand corrections go with them. */
    fun retranscribe(context: Context, clips: Collection<String>) {
        val tdir = ProcessingWorker.transcriptsDir(context)
        for (name in clips) File(tdir, name.removeSuffix(".wav") + ".json").delete()
        ProcessingWorker.enqueue(context)
    }

    /**
     * A share sheet with the transcript as text and the audio joined into one
     * WAV (clips without audio are skipped). Nothing leaves the phone unless
     * the person picks somewhere to send it.
     */
    fun shareIntent(context: Context, clips: List<String>, title: String, transcript: String): Intent {
        val cdir = CaptureService.clipsDir(context)
        val parts = clips.map { File(cdir, it) }.filter { it.exists() }.map { Wav.readPcm(it).first }
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
