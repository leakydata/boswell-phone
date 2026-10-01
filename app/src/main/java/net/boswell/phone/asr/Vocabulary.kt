package net.boswell.phone.asr

import android.content.Context

/**
 * Words Boswell should know: names and terms the recognizer tends to get
 * nearly right ("omi", "boss well"). The phone's recognizer can't be steered
 * toward them (sherpa-onnx contextual biasing doesn't work with it, and with
 * Parakeet's on-device build it broke transcripts outright in testing), so
 * they are fixed afterwards, conservatively:
 *
 *  1. the same letters in other case or with spaces ("omi", "boss well") --
 *     safe, so always;
 *  2. a run of two or three words that sounds like the term and is spelled
 *     close to it ("bos well");
 *  3. a single word one letter off a term of seven letters or more
 *     ("Bozwell"); shorter names are too easily someone else ("Nethan" was
 *     "and Ethan" in testing).
 *
 * Never a common word that merely sounds alike ("home" for "Omi"): a wrong
 * correction is worse than none. The recognizer's own text is kept beside
 * any line changed, as for edits by hand.
 */
object Vocabulary {
    /** Terms every install knows. */
    val BUILT_IN = listOf("Omi", "Boswell")

    private fun prefs(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)

    fun custom(c: Context): List<String> =
        prefs(c).getString("vocabulary", null)?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    fun setCustom(c: Context, words: List<String>) =
        prefs(c).edit().putString("vocabulary", words.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")).apply()

    /** Everything in force: built in, added by hand, and the people named in People (whole names and first names). */
    fun all(c: Context): List<String> {
        val store = net.boswell.phone.speakers.SpeakerStore(c)
        val names = try { store.people().mapNotNull { it.name } } finally { store.close() }
        val firsts = names.mapNotNull { n -> n.split(" ").firstOrNull()?.takeIf { it.length >= 3 && n.contains(" ") } }
        return (BUILT_IN + custom(c) + names + firsts).map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
    }

    // --------------------------------------------------------------- fixing

    /** Letters only, lowercase. */
    fun letters(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * A rough sound-alike key: first letter kept (vowels as 'a'), then
     * consonants only with common spellings of one sound merged. Rough on
     * purpose: it only ever has to agree with a spelling check too.
     */
    fun soundKey(s: String): String {
        var w = letters(s).replace("ph", "f").replace("ck", "k").replace("gh", "g").replace("wh", "w")
            .replace(Regex("c(?=[eiy])"), "s").replace('c', 'k').replace('q', 'k').replace('z', 's').replace("x", "ks")
        if (w.isEmpty()) return ""
        val first = if (w[0] in "aeiouy") 'a' else w[0]
        val rest = w.drop(1).filter { it !in "aeiouyhw" }
        val sb = StringBuilder().append(first)
        for (ch in rest) if (sb.last() != ch) sb.append(ch)
        return sb.toString()
    }

    fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    private fun similarity(a: String, b: String): Double =
        if (a.isEmpty() && b.isEmpty()) 1.0 else 1.0 - distance(a, b).toDouble() / maxOf(a.length, b.length)

    /** Words common enough that they are never taken for a name or term, however they sound. */
    private val COMMON = setOf(
        "a", "i", "me", "my", "oh", "on", "or", "am", "an", "and", "are", "as", "at", "be", "but", "by", "do", "for", "go", "he", "her",
        "him", "his", "how", "if", "in", "is", "it", "its", "no", "not", "of", "ok", "okay", "one", "our", "out", "she", "so", "the", "to",
        "too", "two", "up", "us", "was", "we", "well", "were", "what", "when", "who", "why", "will", "with", "yes", "you", "your",
        "home", "omen", "own", "army", "money", "mommy", "amy", "any", "many", "boss", "bossy", "nathan", "never", "more", "mean",
        "some", "time", "same", "name", "game", "came", "come", "may", "may", "make", "made", "night", "neither", "nothing",
    )

    /** One change: words [from, to) of the input become [term]. */
    data class Fix(val from: Int, val to: Int, val term: String)

    /** Where [words] should change to put in [terms]. Words are as transcribed (punctuation attached). */
    fun fixes(words: List<String>, terms: List<String>): List<Fix> {
        val out = mutableListOf<Fix>()
        val used = BooleanArray(words.size)
        // Longer terms first, so "Sam Lee" wins over "Sam".
        for (term in terms.sortedByDescending { it.length }) {
            val tl = letters(term)
            if (tl.length < 3) continue
            val tKey = soundKey(term)
            val termWords = term.split(" ").size
            var i = 0
            while (i < words.size) {
                var hit: Fix? = null
                for (n in maxOf(1, termWords - 1)..(termWords + 1)) {
                    if (i + n > words.size || (i until i + n).any { used[it] }) continue
                    val span = words.subList(i, i + n)
                    val sl = letters(span.joinToString(""))
                    if (sl.isEmpty()) continue
                    val text = span.joinToString(" ") { it.trim { c -> !c.isLetterOrDigit() } }
                    if (text == term) break                                       // already right
                    val ok = when {
                        sl == tl -> true                                         // 1: case or spaces only
                        n == 1 && sl in COMMON -> false
                        n >= 2 && span.all { letters(it) in COMMON } -> false     // "oh me": every piece is a real word
                        n >= 2 -> kotlin.math.abs(sl.length - tl.length) <= 2 && soundKey(sl) == tKey && similarity(sl, tl) >= 0.7   // 2
                        else -> tl.length >= 7 && distance(sl, tl) <= 1 && sl.first() == tl.first()   // 3
                    }
                    if (ok) { hit = Fix(i, i + n, term); break }
                }
                if (hit != null) {
                    out += hit
                    for (k in hit.from until hit.to) used[k] = true
                    i = hit.to
                } else i++
            }
        }
        return out.sortedBy { it.from }
    }

    /**
     * Apply [fixes] to recognized words with times, keeping the punctuation
     * that trailed the last replaced word and the times of the span.
     */
    fun apply(words: List<Word>, terms: List<String>): List<Word> {
        if (terms.isEmpty() || words.isEmpty()) return words
        val fs = fixes(words.map { it.text }, terms)
        if (fs.isEmpty()) return words
        val out = mutableListOf<Word>()
        var i = 0
        for (f in fs) {
            while (i < f.from) out += words[i++]
            val lastText = words[f.to - 1].text
            val tail = lastText.takeLastWhile { !it.isLetterOrDigit() }
            out += Word(f.term + tail, words[f.from].start, words[f.to - 1].end)
            i = f.to
        }
        while (i < words.size) out += words[i++]
        return out
    }
}
