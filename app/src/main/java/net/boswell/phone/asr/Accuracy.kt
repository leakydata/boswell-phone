package net.boswell.phone.asr

import android.content.Context
import net.boswell.phone.process.ProcessingWorker
import net.boswell.phone.process.Transcript
import net.boswell.phone.process.TranscriptJson

/**
 * An answer key from the person's own corrections: every recording with a
 * line they fixed by hand, taken as right as it now reads (the lines they
 * left alone included -- they read them and let them stand). Against it,
 * each engine's word error rate is a measurement, not a guess.
 */
object Accuracy {
    /** A corrected recording: what was really said, and what each engine wrote that's already known. */
    data class Key(val clip: String, val reference: String, val known: Map<String, String>)

    const val PHONE = "Phone"

    fun keys(c: Context): List<Key> = ProcessingWorker.transcriptsDir(c).listFiles { f -> f.extension == "json" }.orEmpty()
        .filter { f -> f.length() < 2_000_000 && f.readText().contains("\"edited\":true") }
        .mapNotNull { f -> runCatching { TranscriptJson.json.decodeFromString(Transcript.serializer(), f.readText()) }.getOrNull() }
        .filter { t -> t.segments.any { it.edited } }
        .map { t ->
            val reference = t.segments.joinToString(" ") { it.text }
            // What the engine that transcribed it wrote: the heard text of corrected lines, the rest as they stand.
            val heard = t.segments.joinToString(" ") { if (it.edited) it.original ?: it.text else it.text }
            val by = if (t.engine.contains("(cloud)")) CloudAsr.Engine.PARAKEET.label else PHONE
            Key(t.clip, reference, mapOf(by to heard))
        }
        .sortedByDescending { it.clip }

    /** Word error rate of [hyp] against [ref], and the reference's length in words. */
    fun errors(ref: String, hyp: String): Pair<Int, Int> = CloudAsr.edits(CloudAsr.words(ref), CloudAsr.words(hyp))
}
