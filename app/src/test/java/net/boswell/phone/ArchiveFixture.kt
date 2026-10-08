package net.boswell.phone

import android.content.Context
import kotlinx.serialization.json.Json
import net.boswell.phone.archive.Archive
import net.boswell.phone.audio.writeAtomically
import net.boswell.phone.capture.CaptureService
import net.boswell.phone.capture.ClipTimes
import net.boswell.phone.process.ProcessingWorker
import net.boswell.phone.process.Segment
import net.boswell.phone.process.SpeakerId
import net.boswell.phone.process.Transcript
import net.boswell.phone.process.TranscriptJson
import net.boswell.phone.speakers.Matching
import java.io.File
import kotlin.random.Random

/**
 * A made-up archive in an app's own folders, written the way the phone
 * writes one: a sidecar per clip and a transcript per transcribed clip.
 */
class ArchiveFixture(val context: Context) {
    val clips: File get() = CaptureService.clipsDir(context)
    val transcripts: File get() = ProcessingWorker.transcriptsDir(context)

    /**
     * Files rewritten within one tick of the clock would look unchanged to the
     * index (it compares modification times); on the phone a rewrite comes
     * seconds later at least. Every write here moves time on by a second.
     */
    private var clock = System.currentTimeMillis() - 10_000_000L
    private fun stamp(f: File) { clock += 1000; f.setLastModified(clock) }

    fun sidecar(name: String, started: Double, ended: Double) {
        val f = File(clips, name.removeSuffix(".wav") + ".json")
        writeAtomically(f, Json.encodeToString(ClipTimes.serializer(),
            ClipTimes(started, ended, ended - started, "test", null, null, 1, null, true, 16_000, 0)).toByteArray())
        stamp(f)
    }

    fun transcript(t: Transcript) {
        val f = File(transcripts, t.clip.removeSuffix(".wav") + ".json")
        writeAtomically(f, TranscriptJson.json.encodeToString(Transcript.serializer(), t).toByteArray())
        stamp(f)
    }

    fun read(clip: String): Transcript = TranscriptJson.json.decodeFromString(Transcript.serializer(),
        File(transcripts, clip.removeSuffix(".wav") + ".json").readText())

    fun delete(clip: String) {
        File(clips, clip.removeSuffix(".wav") + ".json").delete()
        File(transcripts, clip.removeSuffix(".wav") + ".json").delete()
        net.boswell.phone.archive.ArchiveChanges.bump()
    }

    /** Every transcript as written, by file name: what a recheck changed shows here. */
    fun transcriptTexts(): Map<String, String> =
        transcripts.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }.associate { it.name to it.readText() }

    /** Both folders last changed long ago, so the archive trusts them as settled. */
    fun settle() {
        val past = System.currentTimeMillis() - 60_000
        clips.setLastModified(past); transcripts.setLastModified(past)
    }

    /** Throw everything away: files and both databases. */
    fun wipe() {
        clips.deleteRecursively(); transcripts.deleteRecursively()
        context.deleteDatabase("archive.db"); context.deleteDatabase("speakers.db")
    }

    companion object {
        fun unit(v: FloatArray) = Matching.unit(v)

        fun noisy(rnd: Random, center: FloatArray, noise: Double): FloatArray =
            unit(FloatArray(center.size) { center[it] + (noise * rnd.nextGaussian()).toFloat() / kotlin.math.sqrt(center.size.toFloat()) })

        fun randomUnit(rnd: Random, dim: Int) = unit(FloatArray(dim) { rnd.nextGaussian().toFloat() })

        private fun Random.nextGaussian(): Double {
            // Box-Muller: kotlin.random has no Gaussian.
            val u = nextDouble().coerceAtLeast(1e-12); val v = nextDouble()
            return kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)
        }

        /** A transcript with one line per voice, in label order. */
        fun transcriptOf(clip: String, voices: Map<String, Pair<SpeakerId, FloatArray?>>, extra: List<Segment> = emptyList()): Transcript {
            val segs = voices.keys.mapIndexed { i, label -> Segment(i * 2.0, i * 2.0 + 1.5, label, "words of $label in $clip") } + extra
            return Transcript(clip, 0.0, segs.sortedBy { it.start }, voices.mapValues { it.value.first },
                voices.filterValues { it.second != null }.mapValues { it.value.second!!.toList() }, "test", 0)
        }

        /** The whole index as it would show: conversations, which clip is in which, and every voice's key. */
        fun snapshot(a: Archive): String = buildString {
            val db = a.readableDatabase
            db.rawQuery("SELECT id, started, ended, day, clips, speech_seconds, speakers, snippet, sounds FROM conversations ORDER BY id", null).use { c ->
                while (c.moveToNext()) appendLine((0 until c.columnCount).joinToString(" | ") { c.getString(it) ?: "null" })
            }
            db.rawQuery("SELECT name, conversation FROM clips ORDER BY name", null).use { c ->
                while (c.moveToNext()) appendLine("${c.getString(0)} in ${c.getString(1)}")
            }
            db.rawQuery("SELECT clip, label, conv_key FROM clip_speakers ORDER BY clip, label", null).use { c ->
                while (c.moveToNext()) appendLine("${c.getString(0)} ${c.getString(1)} = ${c.getString(2)}")
            }
        }
    }
}
