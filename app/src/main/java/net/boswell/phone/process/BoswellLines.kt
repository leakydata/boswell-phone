package net.boswell.phone.process

import net.boswell.phone.asr.Word

/**
 * Boswell's own spoken answers, heard back through the Omi's microphone.
 *
 * The phone knows exactly when it spoke and what it said (AssistantStore's
 * spoken table), so a recording's words that fall in one of those windows
 * and read like what was said are Boswell's: kept in the transcript, under
 * their own reserved speaker, and out of everything that is about people.
 * Recognizing synthetic speech is usually near perfect, but not quite, so
 * the words are lined up against the spoken text in order and a few
 * misheard ones are allowed; words that don't line up (the owner talking
 * over it, someone else in the room) keep their own speaker.
 *
 * Pure, so it can be tested on the desktop JVM.
 */
object BoswellLines {
    /** The reserved speaker label in a transcript, its name, and its decision (SpeakerId.decision). */
    const val LABEL = "BOSWELL"
    const val NAME = "Boswell"
    const val DECISION = "boswell"
    /** Its conversation-level key in the archive (people are "p<id>", other voices "v<n>"). */
    const val KEY = "boswell"
    /** How the AI is told about these lines when it reads the day. */
    const val AS_SAID_BY = "Boswell (the assistant)"

    /**
     * How far before the phone started speaking a word may seem to start.
     * A live clip is placed by when its last frame arrived (Clipper), so its
     * words are, if anything, late; but the Omi stops sending in silence and
     * the gap isn't in the audio, so speech before a short pause in the same
     * clip is placed a little early.
     */
    const val BEFORE = 1.0

    /**
     * How long after the phone stopped speaking its words may still seem to
     * come: BLE notification and decode delay, the phone's own audio output
     * latency (more over Bluetooth), and the Omi's buffering, which together
     * are well under this.
     */
    const val AFTER = 3.0

    /** A diarized voice whose words are at least this much Boswell's is Boswell's voice. */
    const val MOSTLY = 0.8

    /** One spoken answer: wall-clock start and end (epoch seconds), what was said, and the TTS voice that said it. */
    data class Spoken(val started: Double, val ended: Double, val text: String, val voice: String? = null)

    fun isBoswell(id: SpeakerId?): Boolean = id?.decision == DECISION

    /**
     * A person someone named for Boswell's voice before it knew itself
     * ("Boswell Male Voice"): a name starting with Boswell. Never the owner,
     * which the caller checks.
     */
    fun isBoswellName(name: String?): Boolean = name?.trim()?.startsWith("boswell", ignoreCase = true) == true

    /**
     * The transcript with these diarized voices made Boswell's: their lines
     * move to [LABEL] (remembering the voice, for "Not Boswell"), and the
     * voices themselves are no longer anyone. Null if nothing changed.
     */
    fun relabel(t: Transcript, labels: Set<String>): Transcript? {
        val voices = labels.filter { it != LABEL && it in t.speakers && !isBoswell(t.speakers[it]) }.toSet()
        if (voices.isEmpty() && t.segments.none { it.speaker in labels && it.speaker != LABEL }) return null
        val segments = t.segments.map { if (it.speaker != null && it.speaker != LABEL && it.speaker in labels) it.copy(speaker = LABEL, diarized = it.speaker) else it }
        val speakers = LinkedHashMap<String, SpeakerId>()
        for ((label, sp) in t.speakers) speakers[label] = if (label in voices) SpeakerId(NAME, 1.0, DECISION, null, emptyList(), null, sp.seconds) else sp
        val mine = segments.filter { it.speaker == LABEL }
        if (mine.isNotEmpty()) speakers[LABEL] = SpeakerId(NAME, 1.0, DECISION, null, emptyList(), null, mine.sumOf { it.end - it.start })
        return t.copy(segments = segments, speakers = speakers)
    }

    private val NUMBERS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty")
        .withIndex().associate { (i, w) -> w to i.toString() } +
        mapOf("thirty" to "30", "forty" to "40", "fifty" to "50", "sixty" to "60", "seventy" to "70", "eighty" to "80", "ninety" to "90")

    /** One word as compared: lower case, letters and digits only, small numbers as digits. */
    fun normalize(word: String): String {
        val w = word.lowercase().filter { it.isLetterOrDigit() }
        return NUMBERS[w] ?: w
    }

    /** The spoken text as words to compare. */
    fun tokens(text: String): List<String> = text.split(Regex("[\\s\\-–—/]+")).map(::normalize).filter { it.isNotEmpty() }

    /** The same word, allowing a letter or two misheard in a longer one. */
    fun same(a: String, b: String): Boolean {
        if (a == b) return true
        val n = minOf(a.length, b.length)
        if (n < 4 || a.any { it.isDigit() } || b.any { it.isDigit() }) return false
        return edits(a, b) <= if (n <= 6) 1 else 2
    }

    private fun edits(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /**
     * Which words are Boswell's: for each of [words] (times in the clip, which
     * started at [clipStart]), the index in [spoken] of the answer it was part
     * of, or -1. [speakerOf] is each word's diarized speaker, when known: a
     * misheard word counts as Boswell's only if the diarizer heard it in the
     * same voice as the words that matched.
     *
     * Each answer is lined up against the words overlapping its window
     * (BEFORE to AFTER) by a local alignment, so an answer cut by a clip's
     * edge still lines up with the part that is here. It counts when enough
     * of it matches in order: at least three words (all of a shorter answer,
     * with nothing between them), and at least 40% of the answer unless the
     * clip begins or ends inside it. Then the matched words are Boswell's, and
     * so are misheard ones in between -- a word in place of one of the
     * answer's, or an extra one or two in the same voice. Words with no
     * counterpart in another voice (or of unknown voice) stay where they were.
     */
    fun claim(words: List<Word>, clipStart: Double, spoken: List<Spoken>, speakerOf: List<Int?>? = null, clipSeconds: Double? = null): IntArray {
        val out = IntArray(words.size) { -1 }
        if (words.isEmpty()) return out
        val norm = words.map { normalize(it.text) }
        for ((ui, u) in spoken.withIndex()) {
            val said = tokens(u.text)
            if (said.isEmpty()) continue
            val cand = words.indices.filter { i ->
                out[i] < 0 && norm[i].isNotEmpty() &&
                    clipStart + words[i].end >= u.started - BEFORE && clipStart + words[i].start <= u.ended + AFTER
            }
            if (cand.isEmpty()) continue
            val a = align(said, cand.map { norm[it] }) ?: continue
            val matched = a.filter { it.first >= 0 && it.second >= 0 && same(said[it.first], norm[cand[it.second]]) }
            val heardSpan = a.mapNotNull { it.second.takeIf { j -> j >= 0 } }
            val saidSpan = a.mapNotNull { it.first.takeIf { i -> i >= 0 } }
            if (heardSpan.isEmpty() || saidSpan.isEmpty()) continue
            val short = said.size < 3
            if (short) {
                if (matched.size != said.size || heardSpan.size != said.size) continue
            } else {
                if (matched.size < 3) continue
                val edge = clipSeconds != null && (words[cand[heardSpan.first()]].start < EDGE ||
                    words[cand[heardSpan.last()]].end > clipSeconds - EDGE)
                if (!edge && matched.size < 0.4 * said.size) continue
                // Mostly matches, not a few common words strung through other speech.
                if (matched.size < 0.5 * heardSpan.size || matched.size < 0.5 * saidSpan.size) continue
            }
            val voice = speakerOf?.let { sp -> matched.mapNotNull { sp[cand[it.second]] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key }
            fun sameVoice(i: Int) = speakerOf == null || speakerOf[i] == null || speakerOf[i] == voice
            // Runs of extra heard words (no counterpart in the answer), to allow one or two.
            var k = 0
            while (k < a.size) {
                val (si, hj) = a[k]
                if (hj < 0) { k++; continue }
                val i = cand[hj]
                when {
                    si >= 0 && same(said[si], norm[i]) -> { out[i] = ui; k++ }
                    si >= 0 -> { if (sameVoice(i)) out[i] = ui; k++ }
                    else -> {
                        var e = k
                        while (e < a.size && a[e].first < 0) e++
                        val run = (k until e).map { cand[a[it].second] }
                        if (speakerOf != null && run.size <= 2 && run.all { speakerOf[it] != null && speakerOf[it] == voice }) for (w in run) out[w] = ui
                        k = e
                    }
                }
            }
        }
        return out
    }

    /** A word this close to a clip's start or end may belong to an answer the clip only caught part of. */
    private const val EDGE = 1.5

    /**
     * Smith-Waterman: the best-scoring stretch of [said] lined up with a
     * stretch of [heard], as (said index or -1, heard index or -1) pairs in
     * order. Null if nothing lines up at all.
     */
    private fun align(said: List<String>, heard: List<String>): List<Pair<Int, Int>>? {
        val n = said.size; val m = heard.size
        val h = Array(n + 1) { IntArray(m + 1) }
        var best = 0; var bi = 0; var bj = 0
        for (i in 1..n) for (j in 1..m) {
            val diag = h[i - 1][j - 1] + if (same(said[i - 1], heard[j - 1])) MATCH else MISMATCH
            val v = maxOf(0, diag, h[i - 1][j] + GAP, h[i][j - 1] + GAP)
            h[i][j] = v
            if (v > best) { best = v; bi = i; bj = j }
        }
        if (best == 0) return null
        val path = ArrayList<Pair<Int, Int>>()
        var i = bi; var j = bj
        while (i > 0 && j > 0 && h[i][j] > 0) {
            val s = if (same(said[i - 1], heard[j - 1])) MATCH else MISMATCH
            when {
                h[i][j] == h[i - 1][j - 1] + s -> { path += (i - 1) to (j - 1); i--; j-- }
                h[i][j] == h[i - 1][j] + GAP -> { path += (i - 1) to -1; i-- }
                else -> { path += -1 to (j - 1); j-- }
            }
        }
        path.reverse()
        return path
    }

    private const val MATCH = 2
    private const val MISMATCH = -1
    private const val GAP = -1

    /**
     * A diarized voice that sounds like Boswell's learned voice: as alike as a
     * confident match ([matchHigh]) and clear of the closest real person by
     * the strong margin ([marginStrong]), the bar a lower score needs to name
     * someone. Strict on purpose: calling a person Boswell would hide them.
     */
    fun soundsLikeBoswell(score: Double, rival: Double, matchHigh: Double, marginStrong: Double): Boolean =
        score >= matchHigh && score - rival >= marginStrong
}
