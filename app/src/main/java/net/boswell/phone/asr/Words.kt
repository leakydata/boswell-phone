package net.boswell.phone.asr

/** One recognised word and when it was said, in seconds from the start of the audio. */
data class Word(val text: String, val start: Double, val end: Double)

object Words {
    /**
     * Join sentencepiece tokens into words. A token that begins with a space
     * starts a new word; anything else continues the previous one. A word runs
     * from its first token's timestamp to the next word's start, capped so a
     * word before a long silence does not swallow it.
     */
    fun fromTokens(tokens: Array<String>, timestamps: FloatArray, audioSeconds: Double, maxWord: Double = 1.0): List<Word> {
        data class Acc(val text: StringBuilder, val start: Double)
        val acc = mutableListOf<Acc>()
        for (i in tokens.indices) {
            val tok = tokens[i]
            val t = timestamps.getOrElse(i) { timestamps.lastOrNull() ?: 0f }.toDouble()
            if (tok.isBlank()) {
                // A bare separator: the next token starts a word.
                acc += Acc(StringBuilder(), t)
                continue
            }
            if (tok.startsWith(" ") || acc.isEmpty()) acc += Acc(StringBuilder(tok.trimStart()), t)
            else if (acc.last().text.isEmpty()) acc[acc.lastIndex] = Acc(StringBuilder(tok), acc.last().start)
            else acc.last().text.append(tok)
        }
        val words = acc.filter { it.text.isNotBlank() }
        return words.mapIndexed { i, w ->
            val next = words.getOrNull(i + 1)?.start ?: audioSeconds
            Word(w.text.toString(), w.start, minOf(next, w.start + maxWord).coerceAtLeast(w.start))
        }
    }
}
