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
    /** Corrected by the person (not by the vocabulary): the text is an answer key. */
    val edited: Boolean = false,
    /**
     * Phone addition: for a line of Boswell's own voice ([BoswellLines.LABEL]), the
     * diarized speaker its words came from, so "Not Boswell" can give them back.
     */
    val diarized: String? = null,
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

    /** How far from an attributed word an orphan may still be given to its speaker. */
    const val ORPHAN_REACH = 3.0

    private fun nearestSpeaker(words: List<Word>, spk: List<Int?>, i: Int): Int? {
        var best: Int? = null
        var bestGap = ORPHAN_REACH
        for (j in words.indices) {
            val s = spk[j] ?: continue
            if (j == i) continue
            val gap = if (j < i) words[i].start - words[j].end else words[j].start - words[i].end
            if (gap.coerceAtLeast(0.0) <= bestGap) { bestGap = gap.coerceAtLeast(0.0); best = s }
        }
        return best
    }

    /**
     * The same rule for transcripts written before it: an unattributed line
     * takes the speaker of the nearest attributed line in its clip (within
     * ORPHAN_REACH), or the clip's only speaker. Returns null if nothing changed.
     */
    fun attributeOrphans(segments: List<Segment>): List<Segment>? {
        if (segments.none { it.speaker == null }) return null
        // Boswell's lines are its own words, matched to what it said: nothing else joins them.
        val labels = segments.mapNotNull { it.speaker }.distinct().filter { it != BoswellLines.LABEL }
        if (labels.isEmpty()) return null
        var changed = false
        val out = segments.mapIndexed { i, s ->
            if (s.speaker != null) return@mapIndexed s
            val to = if (labels.size == 1) labels[0] else segments.withIndex().filter { it.value.speaker != null && it.value.speaker != BoswellLines.LABEL && it.index != i }
                .map { (j, o) -> o.speaker to (if (j < i) s.start - o.end else o.start - s.end).coerceAtLeast(0.0) }
                .filter { it.second <= ORPHAN_REACH }.minByOrNull { it.second }?.first
            if (to != null) { changed = true; s.copy(speaker = to) } else s
        }
        return if (changed) out else null
    }

    /**
     * Give every word to the speaker whose turn it falls in (the one overlapping
     * it most, when two talk at once), or the nearest turn within a second;
     * a word outside every turn (a clip's edge, a gap the segmenter called
     * silence) goes to whoever spoke nearest it, rather than to nobody.
     */
    fun speakers(words: List<Word>, turns: List<Turn>): List<Int?> {
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
        val spk = words.map(::speakerFor).toMutableList()
        val known = spk.filterNotNull().distinct()
        for (i in words.indices) if (spk[i] == null) {
            spk[i] = if (known.size == 1) known[0] else nearestSpeaker(words, spk, i)
        }
        return spk
    }

    /**
     * Join consecutive words of one speaker ([speakers]) into lines, breaking
     * on a change of speaker or a pause. Words marked in [boswell] are
     * Boswell's own voice: their lines are [BoswellLines.LABEL]'s, and
     * remember the diarized speaker they came from. A line also breaks where
     * one of [keep] (lines corrected by hand, in a transcript made again)
     * begins or ends, so a correction lands on lines of its own (CarryOver).
     */
    fun build(words: List<Word>, turns: List<Turn>, pause: Double = 1.0, maxLine: Double = 20.0, boswell: BooleanArray? = null,
              keep: List<Pair<Double, Double>> = emptyList()): List<Segment> {
        val diar = speakers(words, turns)
        val out = mutableListOf<Segment>()
        var cur: MutableList<Word>? = null
        var curSpk: Int? = null
        var curDiar: Int? = null
        var curKeep = -1
        fun keepOf(w: Word): Int { val m = (w.start + w.end) / 2; return keep.indexOfFirst { m >= it.first - CarryOver.EDGE && m <= it.second + CarryOver.EDGE } }
        fun flush() {
            val c = cur ?: return
            out += if (curSpk == BOSWELL) Segment(c.first().start, c.last().end, BoswellLines.LABEL, c.joinToString(" ") { it.text }, diarized = curDiar?.let(::label))
                else Segment(c.first().start, c.last().end, curSpk?.let(::label), c.joinToString(" ") { it.text })
            cur = null
        }
        for ((wi, w) in words.withIndex()) {
            val s = if (boswell?.getOrNull(wi) == true) BOSWELL else diar[wi]
            val c = cur
            val k = if (keep.isEmpty()) -1 else keepOf(w)
            if (c == null || s != curSpk || (s == BOSWELL && diar[wi] != curDiar) || k != curKeep || w.start - c.last().end > pause || w.end - c.first().start > maxLine) {
                flush()
                cur = mutableListOf(w)
                curSpk = s
                curDiar = diar[wi]
                curKeep = k
            } else c += w
        }
        flush()
        return out
    }

    /** Boswell's own words, in [build]: not a diarized speaker index. */
    private const val BOSWELL = -1
}
