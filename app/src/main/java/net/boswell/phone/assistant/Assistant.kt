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

    private fun tools(forCapture: Boolean = false) = buildJsonArray {
        val cats = net.boswell.phone.todo.TodoStore(context).let { t -> try { t.categories() } finally { t.close() } }
        val catHint = if (cats.isEmpty()) "e.g. Errands, Work, Home, Health, Shopping, Calls" else "reuse one of: ${cats.joinToString()} — or a new short one if none fits"
        add(Llm.tool("add_todo", "Add an item to the user's to-do list, optionally with a reminder at a due time.",
            mapOf("text" to ("string" to "the to-do, short and actionable, in the user's words"),
                "category" to ("string" to "one short category, $catHint"),
                "due" to ("string" to "local date-time to be reminded, YYYY-MM-DDTHH:MM (optional)"),
                "minutes_from_now" to ("integer" to "alternative to due: remind in N minutes (optional)")),
            listOf("text", "category")))
        add(Llm.tool("add_calendar_event", "Put an event in the user's calendar. Only for appointments, meetings or events with a specific time.",
            mapOf("title" to ("string" to "event title"), "start" to ("string" to "local start, YYYY-MM-DDTHH:MM"),
                "minutes" to ("integer" to "duration in minutes, default 60"), "location" to ("string" to "optional"),
                "notes" to ("string" to "optional")), listOf("title", "start")))
        if (forCapture) return@buildJsonArray
        add(Llm.tool("list_todos", "The user's open to-dos (and optionally done ones), with ids, categories and due times.",
            mapOf("category" to ("string" to "only this category (optional)"), "include_done" to ("boolean" to "include finished items (optional)"))))
        add(Llm.tool("complete_todo", "Check off a to-do by id.", mapOf("id" to ("integer" to "to-do id from list_todos")), listOf("id")))
        add(Llm.tool("search_transcripts", "Full-text search over everything recorded and transcribed. Returns matching lines with time and speaker.",
            mapOf("query" to ("string" to "words to search for"), "days" to ("integer" to "only the last N days (optional)")), listOf("query")))
        add(Llm.tool("recent_lines", "What was said in the last N minutes, in order, with speakers.",
            mapOf("minutes" to ("integer" to "how far back, 1-240")), listOf("minutes")))
        add(Llm.tool("day_conversations", "The conversations on one day: time, length, people and opening lines, with ids for read_conversation.",
            mapOf("date" to ("string" to "YYYY-MM-DD, or 'today' / 'yesterday'")), listOf("date")))
        add(Llm.tool("read_conversation", "Every line of one conversation.",
            mapOf("id" to ("integer" to "conversation id from day_conversations or search")), listOf("id")))
        add(Llm.tool("people", "Everyone the phone knows by name: when each was last heard, and for people linked to a phone contact, their number, email, birthday and whether they may be texted.", emptyMap()))
        for (d in MoreTools(context).defs()) add(d)
    }

    fun ready(): Boolean = Secrets.get(context, Secrets.OPENROUTER) != null

    /**
     * Answer one question. [source] is how it was asked (typed, button, …).
     * Up to six rounds of tool use, then a short answer suited to a notification.
     */
    /** The person's own words being answered: texting confirms against these, never the model's. */
    private var currentQuestion: String? = null

    fun ask(question: String, source: String, instruction: String? = null, saidAt: Double? = null, fileOnly: Boolean = source == CAPTURE, display: String? = null): Answer {
        currentQuestion = if (source == "typed" || source == "button") question else null
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
                Llm.system(systemPrompt(archive, speakers, source) + (instruction?.let { i ->
                    "\n\nThis came from a voice trigger. " + (saidAt?.let { "The words were said at ${Instant.ofEpochSecond(it.toLong()).atZone(zone).format(clock)}; read relative times (\"in an hour\", \"tomorrow\") from then. " } ?: "") +
                        "Your job: $i\nIf the words are not really a request of that kind (a phrase used in passing), call no tools and reply exactly NONE."
                } ?: "")),
            )
            // Follow-ups: the last few questions and answers of this chat, if
            // recent and since "New topic", so "and tomorrow?" means something.
            if (source == "typed" || source == "button") {
                val since = maxOf(System.currentTimeMillis() / 1000.0 - FOLLOW_UP_SECONDS, AssistantPrefs.topicSince(context))
                for (e in store.exchanges(FOLLOW_UPS * 2).filter { it.at >= since && !it.error && it.question != null && (it.source == "typed" || it.source == "button") }
                    .take(FOLLOW_UPS).reversed()) {
                    messages += Llm.user(e.question!!)
                    messages += kotlinx.serialization.json.buildJsonObject {
                        put("role", kotlinx.serialization.json.JsonPrimitive("assistant")); put("content", kotlinx.serialization.json.JsonPrimitive(e.answer))
                    }
                }
            }
            messages += Llm.user(question)
            var retried = false
            repeat(MAX_ROUNDS) {
                val reply = try {
                    llm.chat(messages, tools(forCapture = fileOnly))
                } catch (e: Exception) {
                    store.logCall(source, model, null, e.message)
                    throw e
                }
                store.logCall(source, model, reply)
                cost += reply.cost
                if (reply.toolCalls.isEmpty() && reply.text.isNullOrBlank() && !retried) {
                    // An empty answer: ask once more, plainly, before giving up.
                    retried = true
                    messages += Llm.user("Please answer now in plain text, briefly.")
                    return@repeat
                }
                if (reply.toolCalls.isEmpty()) {
                    val text = reply.text?.trim().orEmpty().ifEmpty { "I don't have an answer for that." }
                    // A trigger that turned out not to be a request: nothing to record or show.
                    if (instruction != null && text.trim().trimEnd('.').equals("NONE", ignoreCase = true)) return Answer(NONE, cost)
                    store.addExchange(source, display ?: question, text, cost)
                    return Answer(text, cost)
                }
                messages += reply.message
                for (call in reply.toolCalls) {
                    val result = runCatching { runTool(call, archive, speakers, source) }.getOrElse { "error: ${it.message}" }
                    messages += Llm.toolResult(call.id, result.take(12_000))
                }
            }
            val text = "I looked but ran out of steps before finding an answer."
            store.addExchange(source, display ?: question, text, cost, error = true)
            return Answer(text, cost, true)
        } catch (e: Exception) {
            val text = "Couldn't reach the assistant: ${e.message?.take(160)}"
            store.addExchange(source, display ?: question, text, cost, error = true)
            return Answer(text, cost, true)
        } finally {
            speakers.close(); archive.close(); store.close()
        }
    }

    private fun ownerName(speakers: SpeakerStore): String? = AssistantPrefs.owner(context)?.let(speakers::nameOf)

    private fun systemPrompt(archive: Archive, speakers: SpeakerStore, source: String): String {
        val me = ownerName(speakers)
        val recent = lines(archive, speakers, System.currentTimeMillis() / 1000.0 - 10 * 60)
        return buildString {
            appendLine("You are Boswell, a personal assistant on ${me ?: "the user"}'s phone. The phone records the conversations around them through a wearable microphone and transcribes them on the device; you can look through that record with tools.")
            appendLine("It is now ${LocalDateTime.now().format(clock)} (${zone.id}); today is ${LocalDate.now()}.")
            if (me != null) appendLine("Lines marked (me) are ${me}, the person you are helping.")
            if (source == "button") appendLine("${me ?: "The user"} asked this out loud just now by tapping the button on their Omi wearable; the phone heard it and transcribed it, so expect small transcription errors in the question. The question itself also appears in the recent lines below.")
            if (source == CAPTURE) appendLine("The user double-tapped the Omi to capture something to remember. File it with add_todo (pick a fitting category; set due only if they said when). Use add_calendar_event instead only if it is clearly an appointment or meeting at a specific time. Then reply with a very short confirmation like 'Added to Errands: pick up prescription (Thu 9:00)'.")
            appendLine("Answers appear as a phone notification: be direct and brief, one to three sentences, unless asked for detail. Say so plainly when the record does not contain the answer; do not invent what was said. Transcripts are machine-made and may contain errors.")
            appendLine("For general questions (a film, a fact, how something works), answer from what you know; use web_search when it needs current information. Don't say you have no answer just because it isn't in the record.")
            appendLine("Every line you're given carries a moment label like [L123]. When you quote or refer to something specific that was said, put its label right after it (e.g. Sam said the budget is due Friday [L123]): the app turns labels into a link that plays that moment. Never invent labels.")
            appendLine("You can also: set timers and alarms; draft a text or email (it is only a draft the user sends themselves -- say so); read and send texts with the contacts the user chose (send_text only holds the text: tell them to tap Send or say yes, and call confirm_send only after they do; for anyone else, offer a draft); read and send email if they set it up (send_email only holds it, like texts: confirm_email only after they say yes); look things up on the web for current information (weather, news, hours); remember facts about people when the user shares them, and recall them; keep quick logs (medication, expenses, parking, habits) and read them back; give talk stats; pull out numbers, emails, links and addresses that were said; translate what someone said. Named lists (\"read later\", \"gift ideas\", shopping) are to-do categories: add with add_todo and read with list_todos.")
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
            SELECT l.t0, s.conv_key, l.text, l.id FROM lines l LEFT JOIN clip_speakers s ON s.clip = l.clip AND s.label = l.label
            WHERE l.t0 >= ? AND l.t0 < ? ORDER BY l.t0 LIMIT 400""", arrayOf(from.toString(), to.toString())).use { c ->
            buildString {
                while (c.moveToNext()) {
                    val key = if (c.isNull(1)) null else c.getString(1)
                    val pid = key?.takeIf { it.startsWith("p") }?.drop(1)?.toLongOrNull()
                    val who = pid?.let { p -> names.getOrPut(p) { speakers.nameOf(p) } } ?: "someone"
                    val mark = if (pid != null && pid == owner) " (me)" else ""
                    appendLine("${Instant.ofEpochSecond(c.getDouble(0).toLong()).atZone(zone).format(t)} [L${c.getLong(3)}] $who$mark: ${c.getString(2)}")
                }
            }
        }
    }

    /** "2026-10-01T09:00" (or with a space, or seconds) in local time -> epoch seconds. */
    private fun parseLocal(s: String): Double? = runCatching {
        LocalDateTime.parse(s.trim().replace(" ", "T").let { if (it.length == 16) it else it.take(19) }).atZone(zone).toEpochSecond().toDouble()
    }.getOrNull()

    private fun runTool(call: ToolCall, archive: Archive, speakers: SpeakerStore, source: String): String {
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
                    "${at(h.line.t0)} (conversation ${h.conversation}) [L${h.line.id}] $who: ${h.line.text}"
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
                val link = speakers.linkOf(p.id)
                val info = link?.let { Contacts.info(context, it.contact) }
                "${p.name}${if (p.id == AssistantPrefs.owner(context)) " (me)" else ""}: last heard ${p.lastHeard?.let(::at) ?: "never"}" +
                    (info?.let { i -> "; contact: " + listOfNotNull(i.phones.firstOrNull()?.let { "phone $it" }, i.emails.firstOrNull()?.let { "email $it" },
                        i.birthday?.let { "birthday $it" }).joinToString(", ") + "; texting ${when (link.mayText) { "auto" -> "send right away"; "ask" -> "ask first"; else -> "off" }}" } ?: "")
            }.ifBlank { "nobody has been named yet" }
            "add_todo" -> {
                val text = str("text") ?: return "missing text"
                val due = str("due")?.let(::parseLocal) ?: int("minutes_from_now")?.let { System.currentTimeMillis() / 1000.0 + it.coerceIn(1, 60 * 24 * 365) * 60 }
                val store = net.boswell.phone.todo.TodoStore(context)
                val id = try { store.add(text, str("category"), due, if (source == CAPTURE || source == "button") "voice" else "assistant") } finally { store.close() }
                due?.let { net.boswell.phone.todo.TodoReminders.schedule(context, id, it) }
                "added to-do $id" + (due?.let { " with a reminder at ${at(it)}" } ?: "")
            }
            "list_todos" -> {
                val store = net.boswell.phone.todo.TodoStore(context)
                val all = try { store.all(includeDone = args["include_done"]?.jsonPrimitive?.contentOrNull == "true") } finally { store.close() }
                val cat = str("category")
                all.filter { cat == null || it.category.equals(cat, ignoreCase = true) }.joinToString("\n") { t ->
                    "id ${t.id} [${t.category}] ${t.text}" + (t.due?.let { " (due ${at(it)})" } ?: "") + if (t.done) " — done" else ""
                }.ifBlank { "no to-dos" }
            }
            "complete_todo" -> {
                val id = int("id")?.toLong() ?: return "missing id"
                val store = net.boswell.phone.todo.TodoStore(context)
                try { store.get(id) ?: return "no to-do $id"; store.setDone(id, true) } finally { store.close() }
                net.boswell.phone.todo.TodoReminders.cancel(context, id)
                "checked off $id"
            }
            "add_calendar_event" -> {
                val title = str("title") ?: return "missing title"
                val start = str("start")?.let(::parseLocal) ?: return "could not read the start time; use YYYY-MM-DDTHH:MM"
                net.boswell.phone.todo.Calendar.add(context, title, start, int("minutes") ?: 60, str("location"), str("notes")).fold(
                    onSuccess = { "added to the calendar \"${net.boswell.phone.todo.Calendar.chosen(context)?.name}\": $title at ${at(start)}" },
                    onFailure = { "not added: ${it.message}" })
            }
            in MoreTools(context).names -> MoreTools(context, currentQuestion).run(call.name, args, archive, speakers)
            else -> "unknown tool ${call.name}"
        }
    }

    companion object {
        /** How long a chat stays one conversation, and how many earlier turns it carries. */
        const val FOLLOW_UP_SECONDS = 15 * 60
        const val FOLLOW_UPS = 4

        const val MAX_ROUNDS = 6
        /** Double tap: file what was said, don't chat. */
        const val CAPTURE = "capture"
        const val TRIGGER = "trigger"
        const val NONE = "NONE"
    }
}
