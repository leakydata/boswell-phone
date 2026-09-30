package net.boswell.phone.process

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.boswell.phone.asr.Word
import net.boswell.phone.diarize.Turn

/**
 * A clip's transcript in desktop Boswell's own format (data/transcripts/<clip>.json),
 * so the phone commits to no structure of its own yet and an archive made here
 * imports into the desktop without translation.
 */
@Serializable
data class Transcript(
    val clip: String,
    val created: Double,
    val segments: List<Segment>,
    val speakers: Map<String, SpeakerId>,
    val embeddings: Map<String, List<Float>>,
    val engine: String,
    @SerialName("process_ms") val processMs: Long,
    /** Null until the clip has been listened to by the sound tagger. */
    val sounds: List<net.boswell.phone.sound.SoundTag>? = null,
    /** keep | empty (Sounds.verdict), with speech counted as keep. */
    val verdict: String? = null,
)

@Serializable
data class Segment(
    val start: Double,
    val end: Double,
    val speaker: String?,
    val text: String,
    /** What the transcriber heard, kept when the text was corrected by hand. */
    val original: String? = null,
)

@Serializable
data class Candidate(
    @SerialName("person_id") val personId: Long,
    val name: String?,
    val score: Double,
    @SerialName("voiceprint_id") val voiceprintId: Long,
)

@Serializable
data class SpeakerId(
    val name: String?,
    val score: Double,
    val decision: String,
    val margin: Double?,
    val candidates: List<Candidate>,
    /** Phone addition: the person (named or an unnamed cluster) this voice was filed under. */
    @SerialName("person_id") val personId: Long? = null,
    val seconds: Double,
)

object TranscriptJson {
    val json = Json { prettyPrint = false; encodeDefaults = true; ignoreUnknownKeys = true }
}

object Lines {
    fun label(i: Int) = "SPEAKER_%02d".format(i)

    /**
     * Give every word to the speaker whose turn it falls in (the one overlapping
     * it most, when two talk at once), or the nearest turn within a second,
     * then join consecutive words of one speaker into lines, breaking on a
     * change of speaker or a pause.
     */
    fun build(words: List<Word>, turns: List<Turn>, pause: Double = 1.0, maxLine: Double = 20.0): List<Segment> {
        fun speakerFor(w: Word): Int? {
            var best: Turn? = null
            var bestOv = 0.0
            for (t in turns) {
                val ov = minOf(w.end, t.end) - maxOf(w.start, t.start)
                if (ov > bestOv) { bestOv = ov; best = t }
            }
            if (best != null) return best.speaker
            val mid = (w.start + w.end) / 2
            val near = turns.minByOrNull { minOf(kotlin.math.abs(mid - it.start), kotlin.math.abs(mid - it.end)) } ?: return null
            val d = minOf(kotlin.math.abs(mid - near.start), kotlin.math.abs(mid - near.end))
            return if (d <= 1.0) near.speaker else null
        }
        val out = mutableListOf<Segment>()
        var cur: MutableList<Word>? = null
        var curSpk: Int? = null
        fun flush() {
            val c = cur ?: return
            out += Segment(c.first().start, c.last().end, curSpk?.let(::label), c.joinToString(" ") { it.text })
            cur = null
        }
        for (w in words) {
            val s = speakerFor(w)
            val c = cur
            if (c == null || s != curSpk || w.start - c.last().end > pause || w.end - c.first().start > maxLine) {
                flush()
                cur = mutableListOf(w)
                curSpk = s
            } else c += w
        }
        flush()
        return out
    }
}
