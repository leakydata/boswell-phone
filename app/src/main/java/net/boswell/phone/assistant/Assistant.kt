package net.boswell.phone.assistant

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.boswell.phone.archive.Archive
import net.boswell.phone.speakers.SpeakerStore
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Answer(val text: String, val cost: Double, val error: Boolean = false)

/**
 * The agent: a model with tools over the archive on this phone.
 *
 * Tools are read-only except for setting a reminder, and they return text,
 * never audio. The last few minutes of what was said go in with every
 * question, so "what did she just say?" works without a tool call.
 */
class Assistant(private val context: Context) {
    private val zone = ZoneId.systemDefault()
    private val clock = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a")

    private val tools = buildJsonArray {
        add(Llm.tool("search_transcripts", "Full-text search over everything recorded and transcribed. Returns matching lines with time and speaker.",
            mapOf("query" to ("string" to "words to search for"), "days" to ("integer" to "only the last N days (optional)")), listOf("query")))
        add(Llm.tool("recent_lines", "What was said in the last N minutes, in order, with speakers.",
            mapOf("minutes" to ("integer" to "how far back, 1-240")), listOf("minutes")))
        add(Llm.tool("day_conversations", "The conversations on one day: time, length, people and opening lines, with ids for read_conversation.",
            mapOf("date" to ("string" to "YYYY-MM-DD, or 'today' / 'yesterday'")), listOf("date")))
        add(Llm.tool("read_conversation", "Every line of one conversation.",
            mapOf("id" to ("integer" to "conversation id from day_conversations or search")), listOf("id")))
        add(Llm.tool("people", "Everyone the phone knows by name, and when each was last heard.", emptyMap()))
        add(Llm.tool("set_reminder", "Remind the user later with a phone notification.",
            mapOf("text" to ("string" to "what to remind them of"), "minutes_from_now" to ("integer" to "when, in minutes from now")),
            listOf("text", "minutes_from_now")))
    }

    fun ready(): Boolean = Secrets.get(context, Secrets.OPENROUTER) != null

    /**
     * Answer one question. [source] is how it was asked (typed, button, …).
     * Up to six rounds of tool use, then a short answer suited to a notification.
     */
    fun ask(question: String, source: String): Answer {
        val key = Secrets.get(context, Secrets.OPENROUTER) ?: return Answer("Add an OpenRouter key under Device → Assistant first.", 0.0, true)
        val model = AssistantPrefs.model(context)
        val llm = Llm(key, model)
        val store = AssistantStore(context)
        val archive = Archive(context)
        val speakers = SpeakerStore(context)
        var cost = 0.0
        try {
            archive.sync(speakers)
            val messages = mutableListOf(
                Llm.system(systemPrompt(archive, speakers)),
                Llm.user(question),
            )
            repeat(MAX_ROUNDS) {
                val reply = try {
                    llm.chat(messages, tools)
                } catch (e: Exception) {
                    store.logCall("ask", model, null, e.message)
                    throw e
                }
                store.logCall("ask", model, reply)
                cost += reply.cost
                if (reply.toolCalls.isEmpty()) {
                    val text = reply.text?.trim().orEmpty().ifEmpty { "I don't have an answer for that." }
                    store.addExchange(source, question, text, cost)
                    return Answer(text, cost)
                }
                messages += reply.message
                for (call in reply.toolCalls) {
                    val result = runCatching { runTool(call, archive, speakers) }.getOrElse { "error: ${it.message}" }
                    messages += Llm.toolResult(call.id, result.take(12_000))
                }
            }
            val text = "I looked but ran out of steps before finding an answer."
            store.addExchange(source, question, text, cost, error = true)
            return Answer(text, cost, true)
        } catch (e: Exception) {
            val text = "Couldn't reach the assistant: ${e.message?.take(160)}"
            store.addExchange(source, question, text, cost, error = true)
            return Answer(text, cost, true)
        } finally {
            speakers.close(); archive.close(); store.close()
        }
    }

    private fun ownerName(speakers: SpeakerStore): String? = AssistantPrefs.owner(context)?.let(speakers::nameOf)

    private fun systemPrompt(archive: Archive, speakers: SpeakerStore): String {
        val me = ownerName(speakers)
        val recent = lines(archive, speakers, System.currentTimeMillis() / 1000.0 - 10 * 60)
        return buildString {
            appendLine("You are Boswell, a personal assistant on ${me ?: "the user"}'s phone. The phone records the conversations around them through a wearable microphone and transcribes them on the device; you can look through that record with tools.")
            appendLine("It is now ${LocalDateTime.now().format(clock)} (${zone.id}).")
            if (me != null) appendLine("Lines marked (me) are ${me}, the person you are helping.")
            appendLine("Answers appear as a phone notification: be direct and brief, one to three sentences, unless asked for detail. Say so plainly when the record does not contain the answer; do not invent what was said. Transcripts are machine-made and may contain errors.")
            appendLine()
            appendLine("What was said in the last 10 minutes:")
            append(recent.ifBlank { "(nothing)" })
        }
    }

    /** Lines since [from], "HH:MM Speaker: text", with the owner marked. */
    fun lines(archive: Archive, speakers: SpeakerStore, from: Double, to: Double = Double.MAX_VALUE): String {
        val owner = AssistantPrefs.owner(context)
        val names = HashMap<Long, String?>()
        val t = DateTimeFormatter.ofPattern("h:mm")
        return archive.readableDatabase.rawQuery("""
            SELECT l.t0, s.conv_key, l.text FROM lines l LEFT JOIN clip_speakers s ON s.clip = l.clip AND s.label = l.label
            WHERE l.t0 >= ? AND l.t0 < ? ORDER BY l.t0 LIMIT 400""", arrayOf(from.toString(), to.toString())).use { c ->
            buildString {
                while (c.moveToNext()) {
                    val key = if (c.isNull(1)) null else c.getString(1)
                    val pid = key?.takeIf { it.startsWith("p") }?.drop(1)?.toLongOrNull()
                    val who = pid?.let { p -> names.getOrPut(p) { speakers.nameOf(p) } } ?: "someone"
                    val mark = if (pid != null && pid == owner) " (me)" else ""
                    appendLine("${Instant.ofEpochSecond(c.getDouble(0).toLong()).atZone(zone).format(t)} $who$mark: ${c.getString(2)}")
                }
            }
        }
    }

    private fun runTool(call: ToolCall, archive: Archive, speakers: SpeakerStore): String {
        val args: JsonObject = runCatching { Llm.json.parseToJsonElement(call.arguments).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        fun str(k: String) = args[k]?.jsonPrimitive?.contentOrNull
        fun int(k: String) = args[k]?.jsonPrimitive?.intOrNull ?: str(k)?.toIntOrNull()
        val t = DateTimeFormatter.ofPattern("yyyy-MM-dd h:mm a")
        fun at(epoch: Double) = Instant.ofEpochSecond(epoch.toLong()).atZone(zone).format(t)
        return when (call.name) {
            "search_transcripts" -> {
                val days = int("days")
                val since = days?.let { System.currentTimeMillis() / 1000.0 - it * 86_400 } ?: 0.0
                val hits = archive.search(str("query") ?: "", 60).filter { it.line.t0 >= since }.take(25)
                if (hits.isEmpty()) "no matches" else hits.joinToString("\n") { h ->
                    val who = h.line.personId?.let(speakers::nameOf) ?: "someone"
                    "${at(h.line.t0)} (conversation ${h.conversation}) $who: ${h.line.text}"
                }
            }
            "recent_lines" -> lines(archive, speakers, System.currentTimeMillis() / 1000.0 - (int("minutes") ?: 10).coerceIn(1, 240) * 60).ifBlank { "nothing was said" }
            "day_conversations" -> {
                val d = when (val s = str("date")?.lowercase()) {
                    null, "today" -> LocalDate.now(); "yesterday" -> LocalDate.now().minusDays(1)
                    else -> runCatching { LocalDate.parse(s) }.getOrDefault(LocalDate.now())
                }
                archive.conversations(d).sortedBy { it.started }.joinToString("\n") { c ->
                    val who = c.speakers.mapNotNull { k -> k.takeIf { it.startsWith("p") }?.drop(1)?.toLongOrNull()?.let(speakers::nameOf) }
                    "id ${c.id}: ${at(c.started)}, ${((c.ended - c.started) / 60).toInt()} min, with ${who.ifEmpty { listOf("unidentified voices") }.joinToString()} — \"${c.snippet.take(160)}\""
                }.ifBlank { "no conversations on $d" }
            }
            "read_conversation" -> {
                val c = archive.conversation(int("id")?.toLong() ?: return "missing id") ?: return "no such conversation"
                lines(archive, speakers, c.started - 1, c.ended + 1)
            }
            "people" -> speakers.people().filter { it.name != null }.joinToString("\n") { p ->
                "${p.name}${if (p.id == AssistantPrefs.owner(context)) " (me)" else ""}: last heard ${p.lastHeard?.let(::at) ?: "never"}"
            }.ifBlank { "nobody has been named yet" }
            "set_reminder" -> {
                val text = str("text") ?: return "missing text"
                val minutes = (int("minutes_from_now") ?: return "missing minutes_from_now").coerceIn(1, 60 * 24 * 14)
                Reminders.schedule(context, text, minutes.toLong())
                "reminder set for ${at(System.currentTimeMillis() / 1000.0 + minutes * 60)}"
            }
            else -> "unknown tool ${call.name}"
        }
    }

    companion object {
        const val MAX_ROUNDS = 6
    }
}
