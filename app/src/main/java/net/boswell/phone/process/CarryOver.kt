package net.boswell.phone.process

/**
 * What the owner did by hand to a recording, carried over to a new transcript
 * of it (a Redo, or a recording caught up at home). A new transcript diarizes
 * again, so its speaker labels and line boundaries are new: hand work follows
 * the time it was about, not the label or line it was filed under.
 *
 *  - Voices: each old label goes to the new label it shares the most speech
 *    with, one to one ([speakers]); a voice with nothing left to go to has
 *    disappeared, and what was decided about it is kept where it can be
 *    ([relabel]) and reported, never dropped silently.
 *  - Lines: a corrected line keeps its text over the new lines heard in its
 *    time, which become one line with the corrected text ([lines]); new lines
 *    elsewhere are the new transcriber's.
 *
 * Pure, so it is tested on the desktop (CarryOverTest).
 */
object CarryOver {
    /** Some speech of one voice: a diarized turn, or a transcript line. */
    data class Span(val label: String, val start: Double, val end: Double)

    /** A line's diarized voice: Boswell's own lines remember the one they came from. */
    fun voiceOf(s: Segment): String? = s.diarized ?: s.speaker?.takeIf { it != BoswellLines.LABEL }

    /** The old transcript's voices, from its lines. */
    fun spans(segments: List<Segment>): List<Span> = segments.mapNotNull { s -> voiceOf(s)?.let { Span(it, s.start, s.end) } }

    /**
     * Old label -> new label, or null for a voice that disappeared. Pairs are
     * taken by most shared speech first, each label at most once, and a pair
     * must share at least [MIN_SHARED] seconds (or half the old voice's
     * speech, for a voice shorter than a second).
     */
    fun speakers(old: List<Span>, new: List<Span>): Map<String, String?> {
        val total = old.groupBy { it.label }.mapValues { (_, s) -> s.sumOf { it.end - it.start } }
        val shared = HashMap<Pair<String, String>, Double>()
        for (o in old) for (n in new) {
            val ov = minOf(o.end, n.end) - maxOf(o.start, n.start)
            if (ov > 0) shared.merge(o.label to n.label, ov, Double::plus)
        }
        val out = LinkedHashMap<String, String?>()
        for (label in total.keys) out[label] = null
        val taken = HashSet<String>()
        for ((pair, ov) in shared.entries.sortedByDescending { it.value }) {
            val (o, n) = pair
            if (out[o] != null || n in taken) continue
            if (ov < minOf(MIN_SHARED, 0.5 * total.getValue(o))) continue
            out[o] = n; taken += n
        }
        return out
    }

    /**
     * The new lines with the old corrections in them. A new line mostly
     * (by [ABSORB]) inside corrected lines is replaced: each corrected line
     * becomes one line over the new lines given to it (their times, within
     * [EDGE] of its own; the voice that says most of it -- the old voice's
     * new label when that one is among them), with the corrected text and
     * what was heard this time as `original`. A corrected line with no new line in its time (the new
     * transcriber heard nothing there) is kept as it was, under its voice's
     * new label. Lines without corrections are the new transcript's.
     */
    fun lines(old: List<Segment>, new: List<Segment>, voices: Map<String, String?>): List<Segment> {
        val edited = old.filter { it.edited }
        if (edited.isEmpty()) return new
        fun overlap(a: Segment, b: Segment) = (minOf(a.end, b.end) - maxOf(a.start, b.start)).coerceAtLeast(0.0)
        // Which corrected line each new line goes to, if any.
        val owner = arrayOfNulls<Int>(new.size)
        for ((i, n) in new.withIndex()) {
            val len = n.end - n.start
            val ovs = edited.map { overlap(n, it) }
            val inside = if (len <= 0) edited.indexOfFirst { (n.start + n.end) / 2 in it.start..it.end }.takeIf { it >= 0 }
                else ovs.indices.maxByOrNull { ovs[it] }?.takeIf { ovs.sum() >= ABSORB * len && ovs[it] > 0 }
            owner[i] = inside
        }
        val out = new.filterIndexed { i, _ -> owner[i] == null }.toMutableList()
        for ((e, fix) in edited.withIndex()) {
            val mine = new.filterIndexed { i, _ -> owner[i] == e }
            val want = voiceOf(fix)?.let { voices[it] }
            out += if (mine.isEmpty()) {
                // Nothing heard there this time: the correction stays, under its voice's new label.
                if (fix.speaker == BoswellLines.LABEL) fix.copy(diarized = want) else fix.copy(speaker = want, diarized = null)
            } else {
                val by = (mine.filter { voiceOf(it) == want && want != null }.ifEmpty { mine }).maxBy { overlap(it, fix) }
                val heard = mine.joinToString(" ") { it.original ?: it.text }.trim()
                // The new times, but never far outside the corrected line's own: a new line it shares with another stays theirs to split.
                Segment(maxOf(mine.minOf { it.start }, fix.start - EDGE), minOf(mine.maxOf { it.end }, fix.end + EDGE), by.speaker, fix.text,
                    original = heard.takeIf { it.isNotEmpty() && it != fix.text },
                    edited = true, diarized = by.diarized)
            }
        }
        return out.sortedBy { it.start }
    }

    /** One row of what was decided about a clip's voices (SpeakerStore), by the label it was filed under. */
    data class Row(val table: String, val id: Long, val label: String?,
                   /** Worth keeping without a voice: named or confirmed by hand, or a person or TV the owner decided on. */
                   val keep: Boolean)

    /** What happens to each row: moved to a new label, kept without one (unlinked), or dropped. */
    data class Plan(val move: Map<Row, String>, val unlink: List<Row>, val drop: List<Row>)

    /**
     * Voiceprints follow their voice; one of a voice that disappeared stays a
     * reference ([Row.keep]) without a label, or goes if it was only an
     * automatic sighting. A name given ("assigned"), a "not them" answer and
     * Boswell's learned voice are about one voice in one clip: they follow
     * it, or go with it.
     */
    fun relabel(rows: List<Row>, voices: Map<String, String?>): Plan {
        val move = LinkedHashMap<Row, String>()
        val unlink = mutableListOf<Row>()
        val drop = mutableListOf<Row>()
        for (r in rows) {
            val to = r.label?.let { voices[it] }
            when {
                r.label == null -> {}
                to != null -> if (to != r.label) move[r] = to
                r.table == VOICEPRINTS && r.keep -> unlink += r
                else -> drop += r
            }
        }
        return Plan(move, unlink, drop)
    }

    /**
     * How much of the words changed, from 0 (the same) to 1: word-level edit
     * distance over the longer of the two. Titles and summaries are made again
     * above [NOTES_CHANGE].
     */
    fun wordsChanged(before: String, after: String): Double {
        val a = words(before); val b = words(after)
        if (a.isEmpty() && b.isEmpty()) return 0.0
        var prev = IntArray(b.size + 1) { it }
        for (i in 1..a.size) {
            val cur = IntArray(b.size + 1); cur[0] = i
            for (j in 1..b.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.size].toDouble() / maxOf(a.size, b.size)
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }

    const val VOICEPRINTS = "voiceprints"
    /** Seconds two labels must share to be one voice. */
    const val MIN_SHARED = 0.5
    /** A new line this much inside corrected lines is theirs. */
    const val ABSORB = 0.5
    /** Titles and summaries are made again when more of the words than this changed. */
    const val NOTES_CHANGE = 0.15
    /** Seconds either side of a corrected line a new word may still belong to it (Lines.build breaks there). */
    const val EDGE = 0.15
}
