package net.boswell.phone.assistant

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Voice triggers: phrases that, when said -- by you, unless a trigger says
 * anyone -- hand what was said to the assistant with an instruction.
 *
 * They run on transcripts, so they act seconds after you pause in live mode,
 * or at the next sync; the assistant is told when the words were said, so
 * "in an hour" means an hour after that, not after it was processed.
 */
@Serializable
data class Trigger(
    val id: Long,
    val phrases: List<String>,
    val action: Action,
    /** For TODO: the category to file under (the assistant picks when blank). */
    val category: String? = null,
    /** For CUSTOM: what to do, in the user's words. */
    val instruction: String? = null,
    val ownerOnly: Boolean = true,
    val enabled: Boolean = true,
) {
    enum class Action { TODO, CALENDAR, ANSWER, CUSTOM }

    val label: String get() = phrases.firstOrNull() ?: "trigger"
}

object Triggers {
    private fun p(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ser = ListSerializer(Trigger.serializer())

    val DEFAULTS = listOf(
        Trigger(1, listOf("remind me", "don't let me forget"), Trigger.Action.TODO),
        Trigger(2, listOf("put it on my calendar", "add to my calendar", "put that on my calendar"), Trigger.Action.CALENDAR),
        Trigger(3, listOf("add to my shopping list", "add to the shopping list", "we need more"), Trigger.Action.TODO, category = "Shopping"),
        Trigger(4, listOf("note to self"), Trigger.Action.TODO, category = "Notes"),
        Trigger(5, listOf("hey boswell"), Trigger.Action.ANSWER),
    )

    fun all(c: Context): List<Trigger> = p(c).getString("triggers", null)
        ?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: DEFAULTS

    fun save(c: Context, list: List<Trigger>) = p(c).edit().putString("triggers", json.encodeToString(ser, list)).apply()

    /** Master switch. Turning it on starts the clock: nothing recorded before then fires. */
    fun enabled(c: Context) = p(c).getBoolean("triggers_on", false)
    fun setEnabled(c: Context, on: Boolean) {
        val e = p(c).edit().putBoolean("triggers_on", on)
        if (on) e.putLong("triggers_since", System.currentTimeMillis() / 1000)
        e.apply()
    }
    fun since(c: Context): Long = p(c).getLong("triggers_since", Long.MAX_VALUE)

    /** Lowercase, apostrophes kept, everything else that is not a letter or digit becomes a space. */
    fun normalize(s: String): String =
        s.lowercase().replace('’', '\'').replace(Regex("[^\\p{L}\\p{N}']+"), " ").trim().replace(Regex("\\s+"), " ")

    /** The first phrase of [t] said in [text], matched on whole words, or null. */
    fun match(t: Trigger, text: String): String? {
        val hay = " ${normalize(text)} "
        return t.phrases.firstOrNull { ph -> normalize(ph).takeIf { it.isNotEmpty() }?.let { hay.contains(" $it ") } == true }
    }
}
