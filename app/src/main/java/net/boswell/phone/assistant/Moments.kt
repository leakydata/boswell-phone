package net.boswell.phone.assistant

import android.content.Context
import net.boswell.phone.archive.Archive

/**
 * Moments in answers. Tools label each line they return [L<id>]; the model is
 * asked to put that label after anything it quotes, and the app turns it into
 * "hear it" -- the conversation opened and played from that line. In a
 * notification the labels are taken out of the text and the first becomes a
 * "Hear it" button.
 */
object Moments {
    private val TAG = Regex("""\s*\[L(\d+)]""")

    fun ids(text: String): List<Long> = TAG.findAll(text).mapNotNull { it.groupValues[1].toLongOrNull() }.distinct().toList()

    fun strip(text: String): String = TAG.replace(text, "").replace(Regex("""\s+([.,;:!?])"""), "$1").trim()

    data class Where(val line: Long, val conversation: Long, val at: Double, val clip: String)

    fun resolve(context: Context, line: Long): Where? {
        val a = Archive(context)
        return try {
            a.readableDatabase.rawQuery("SELECT l.t0, c.conversation, l.clip FROM lines l JOIN clips c ON c.name = l.clip WHERE l.id = ?", arrayOf(line.toString())).use { c ->
                if (c.moveToFirst() && !c.isNull(1)) Where(line, c.getLong(1), c.getDouble(0), c.getString(2)) else null
            }
        } finally { a.close() }
    }

    /** What MainActivity's "open" extra carries to land on a moment. */
    fun openExtra(w: Where) = "conversation:${w.conversation}:${w.line}"
}
