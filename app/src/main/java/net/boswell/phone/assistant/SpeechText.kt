package net.boswell.phone.assistant

/**
 * What text-to-speech should actually say. Answers are written for reading --
 * bold, bullets, links, moment labels -- and a voice reading "asterisk
 * asterisk" or a whole URL is noise. The written answer is left alone; only
 * what goes to the voice is cleaned, here, whatever the model wrote.
 */
object SpeechText {
    private val LINK = Regex("""\bhttps?://\S+|\bwww\.\S+""", RegexOption.IGNORE_CASE)
    private val MD_LINK = Regex("""\[([^\]]+)]\((?:[^)]+)\)""")          // [text](url) -> text
    private val MOMENT = Regex("""\s*\[L\d+]""")
    private val EMOJI = Regex("""[\x{1F000}-\x{1FAFF}\x{2600}-\x{27BF}\x{FE0F}\x{200D}]""")

    /**
     * For reading where formatting can't be shown (a notification): the
     * symbols go, the shape stays -- lines kept, list items as bullets, links
     * left in place to tap.
     */
    fun plain(text: String): String {
        var s = MOMENT.replace(text, "")
        s = MD_LINK.replace(s) { it.groupValues[1] }
        s = s.lines().filterNot { Regex("""^\s*\|?(?:\s*:?-{2,}:?\s*\|)+\s*$""").matches(it) }.joinToString("\n") { line ->
            line.replace(Regex("""^\s{0,3}#{1,6}\s*"""), "")
                .replace(Regex("""^\s*>\s?"""), "")
                .replace(Regex("""^(\s*)[-*+]\s+"""), "$1• ")
        }
        s = s.replace(Regex("""(\*{1,3}|_{2,3})(\S(?:.*?\S)?)\1""")) { it.groupValues[2] }
            .replace(Regex("""`+([^`]*)`+""")) { it.groupValues[1] }
            .replace(Regex("""(?m)^\s*\|\s*|\s*\|\s*$"""), "").replace(Regex("""\s*\|\s*"""), " · ")
        return s.replace(Regex("""[ \t]{2,}"""), " ").replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    /** Bold spans of [plain]-style text, for showing **bold** as bold: (start, end) pairs in the returned string. */
    fun boldSpans(text: String): Pair<String, List<IntRange>> {
        val spans = mutableListOf<IntRange>()
        val out = StringBuilder()
        var i = 0
        val re = Regex("""\*\*(\S(?:.*?\S)?)\*\*|__(\S(?:.*?\S)?)__""")
        for (m in re.findAll(text)) {
            out.append(text, i, m.range.first)
            val inner = m.groupValues[1].ifEmpty { m.groupValues[2] }
            spans += out.length until out.length + inner.length
            out.append(inner)
            i = m.range.last + 1
        }
        out.append(text.substring(i))
        return out.toString() to spans
    }

    fun clean(text: String): String {
        var s = text
        s = MOMENT.replace(s, "")
        s = MD_LINK.replace(s) { it.groupValues[1] }
        s = LINK.replace(s, "a link")
        s = s.lines().joinToString("\n") { line ->
            line.replace(Regex("""^\s{0,3}#{1,6}\s*"""), "")               // headings
                .replace(Regex("""^\s*>\s?"""), "")                         // quotes
                .replace(Regex("""^\s*(?:[-*+•]|\d+[.)])\s+"""), "")         // bullets and numbered items
                .replace(Regex("""^\s*\|?(?:\s*:?-{2,}:?\s*\|)+\s*$"""), "")  // table rules
        }
        s = s.replace("|", ", ")
            .replace(Regex("""(\*{1,3}|_{2,3})(\S(?:.*?\S)?)\1""")) { it.groupValues[2] }   // **bold**, *italic*, __bold__
            .replace(Regex("""`+([^`]*)`+""")) { it.groupValues[1] }
            .replace(Regex("""[*_#`~^=<>{}\[\]\\]"""), " ")
            .replace("&", " and ").replace("→", " to ").replace("->", " to ").replace("—", ", ").replace("–", " to ")
            .replace(Regex("""(?<=\s|^)\+(?=\s|\d)"""), " plus ")
        s = EMOJI.replace(s, "")
        // Lines become sentences, so the voice pauses where the eye would.
        s = s.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(" ") { if (it.last() in ".!?:;,") it else "$it." }
        return s.replace(Regex("""\s+([.,;:!?])"""), "$1").replace(Regex("""\s{2,}"""), " ").replace(Regex("""([.,])\1+"""), "$1").trim()
    }
}
