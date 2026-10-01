package net.boswell.phone.assistant

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import net.boswell.phone.archive.Archive
import net.boswell.phone.speakers.SpeakerStore
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The assistant's tools beyond the archive and to-dos: details pulled from
 * what was said, talk stats, facts about people, timers and alarms, message
 * drafts, the web, and quick logs. Nothing here sends anything on its own: a
 * message is a draft the person opens and sends themselves.
 */
class MoreTools(private val context: Context) {
    private val zone = ZoneId.systemDefault()
    private val t = DateTimeFormatter.ofPattern("EEE MMM d h:mm a")
    private fun at(epoch: Double) = Instant.ofEpochSecond(epoch.toLong()).atZone(zone).format(t)
    private fun now() = System.currentTimeMillis() / 1000.0

    fun defs(): List<JsonObject> = listOf(
        Llm.tool("find_details", "Phone numbers, emails, links, addresses or other numbers someone said recently, with when and who, and a [L<id>] moment reference.",
            mapOf("kind" to ("string" to "phone | email | link | address | number | any"), "hours" to ("integer" to "how far back, default 24"))),
        Llm.tool("talk_stats", "Who the user talked with and for how long over the last N days: per person, conversations and minutes of speech, plus totals.",
            mapOf("days" to ("integer" to "default 7"))),
        Llm.tool("remember_fact", "Remember a lasting fact about a person (family, birthday, job, likes, plans) for later.",
            mapOf("person" to ("string" to "their name"), "fact" to ("string" to "the fact, short")), listOf("person", "fact")),
        Llm.tool("facts_about", "What has been remembered about a person (or everyone, without a name).", mapOf("person" to ("string" to "name (optional)"))),
        Llm.tool("set_timer", "Start a timer that rings on the phone and buzzes the Omi.",
            mapOf("minutes" to ("number" to "length in minutes (decimals allowed)"), "label" to ("string" to "what it's for (optional)")), listOf("minutes")),
        Llm.tool("set_alarm", "Set an alarm at a local time that rings on the phone and buzzes the Omi.",
            mapOf("time" to ("string" to "local date-time YYYY-MM-DDTHH:MM"), "label" to ("string" to "optional")), listOf("time")),
        Llm.tool("draft_message", "Prepare a text message or email for the user to review and send themselves (it is never sent automatically).",
            mapOf("to" to ("string" to "a name from their contacts, or a number / email"), "text" to ("string" to "the message"),
                "via" to ("string" to "sms or email, default sms")), listOf("to", "text")),
        Llm.tool("web_search", "Look something up on the web: weather, news, facts, opening hours. Returns a short answer with sources.",
            mapOf("query" to ("string" to "what to look up")), listOf("query")),
        Llm.tool("log_entry", "Record a quick log entry: medication taken, an expense, mileage, where they parked, a habit.",
            mapOf("kind" to ("string" to "short kind, e.g. medication, expense, parking, mileage, exercise"), "note" to ("string" to "details (optional)"),
                "amount" to ("number" to "a number if there is one, e.g. 42.50 (optional)")), listOf("kind")),
        Llm.tool("read_log", "Read back log entries, optionally of one kind and over the last N days, with totals of amounts.",
            mapOf("kind" to ("string" to "optional"), "days" to ("integer" to "default 7"))),
        Llm.tool("calendar_events", "The user's calendar events over a range of days (all their visible calendars).",
            mapOf("date" to ("string" to "first day, YYYY-MM-DD or 'today' / 'tomorrow'"), "days" to ("integer" to "how many days, default 1"))),
    )

    val names = setOf("find_details", "talk_stats", "remember_fact", "facts_about", "set_timer", "set_alarm", "draft_message", "web_search", "log_entry", "read_log", "calendar_events")

    fun run(name: String, args: JsonObject, archive: Archive, speakers: SpeakerStore): String {
        fun str(k: String) = args[k]?.jsonPrimitive?.contentOrNull
        fun int(k: String) = args[k]?.jsonPrimitive?.intOrNull ?: str(k)?.toIntOrNull()
        fun num(k: String) = args[k]?.jsonPrimitive?.doubleOrNull ?: str(k)?.toDoubleOrNull()
        return when (name) {
            "find_details" -> findDetails(archive, speakers, str("kind") ?: "any", (int("hours") ?: 24).coerceIn(1, 24 * 30))
            "talk_stats" -> talkStats(archive, speakers, (int("days") ?: 7).coerceIn(1, 365))
            "remember_fact" -> LifeStore(context).use { it.addFact(str("person") ?: return "missing person", str("fact") ?: return "missing fact"); "remembered" }
            "facts_about" -> LifeStore(context).use { s ->
                s.facts(str("person")).joinToString("\n") { "${it.person}: ${it.fact} (noted ${at(it.at)})" }.ifBlank { "nothing remembered" + (str("person")?.let { " about $it" } ?: "") }
            }
            "set_timer" -> {
                val m = num("minutes") ?: return "missing minutes"
                val whenAt = now() + (m * 60).coerceIn(5.0, 7.0 * 86_400)
                Alarms.schedule(context, whenAt, str("label") ?: "Timer", timer = true)
                "timer set for ${if (m < 1) "${(m * 60).toInt()} seconds" else "%.0f minutes".format(m)}, ringing at ${at(whenAt)}"
            }
            "set_alarm" -> {
                val whenAt = runCatching { LocalDateTime.parse(str("time")!!.trim().replace(" ", "T").take(16)).atZone(zone).toEpochSecond().toDouble() }.getOrNull()
                    ?: return "could not read the time; use YYYY-MM-DDTHH:MM"
                if (whenAt <= now()) return "that time has passed"
                Alarms.schedule(context, whenAt, str("label") ?: "Alarm", timer = false)
                "alarm set for ${at(whenAt)}"
            }
            "draft_message" -> Drafts.prepare(context, str("to") ?: return "missing recipient", str("text") ?: return "missing text", str("via") ?: "sms")
            "web_search" -> webSearch(str("query") ?: return "missing query")
            "log_entry" -> LifeStore(context).use { s -> s.addLog(str("kind") ?: return "missing kind", str("note"), num("amount")); "logged ${str("kind")} at ${at(now())}" }
            "read_log" -> LifeStore(context).use { s ->
                val entries = s.logs(str("kind"), now() - (int("days") ?: 7).coerceIn(1, 3650) * 86_400.0)
                if (entries.isEmpty()) "nothing logged" else entries.joinToString("\n") { e ->
                    "${at(e.at)} ${e.kind}" + (e.note?.let { ": $it" } ?: "") + (e.amount?.let { " (${"%.2f".format(it)})" } ?: "")
                } + entries.mapNotNull { it.amount }.takeIf { it.isNotEmpty() }?.let { "\ntotal of amounts: ${"%.2f".format(it.sum())}" }.orEmpty()
            }
            "calendar_events" -> {
                val first = when (val d = str("date")?.lowercase()) {
                    null, "today" -> java.time.LocalDate.now(); "tomorrow" -> java.time.LocalDate.now().plusDays(1)
                    else -> runCatching { java.time.LocalDate.parse(d) }.getOrDefault(java.time.LocalDate.now())
                }
                val from = first.atStartOfDay(zone).toInstant().toEpochMilli()
                val to = first.plusDays((int("days") ?: 1).coerceIn(1, 31).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
                if (!net.boswell.phone.todo.Calendar.allowed(context)) "the calendar isn't shared with Boswell"
                else net.boswell.phone.todo.Calendar.events(context, from, to, respectShow = false).sortedBy { it.begin }.joinToString("\n") { e ->
                    (if (e.allDay) "${Instant.ofEpochMilli(e.begin).atZone(java.time.ZoneOffset.UTC).toLocalDate()} all day" else at(e.begin / 1000.0)) + ": ${e.title} (${e.calendar})"
                }.ifBlank { "nothing in the calendar" }
            }
            else -> "unknown tool $name"
        }
    }

    // ------------------------------------------------------------- details

    private val patterns = mapOf(
        "phone" to Regex("""(?<!\d)(?:\+?1[\s.-]?)?\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}(?!\d)"""),
        "email" to Regex("""[\w.+-]+\s?(?:@|\bat\b)\s?[\w-]+\s?(?:\.|\bdot\b)\s?[a-z]{2,}""", RegexOption.IGNORE_CASE),
        "link" to Regex("""\b(?:https?://)?[\w-]+(?:\.|\s+dot\s+)(?:com|org|net|io|gov|edu|co)(?:/\S*)?""", RegexOption.IGNORE_CASE),
        "address" to Regex("""\b\d{1,6}\s+(?:[A-Z][a-z]+\s){1,3}(?:Street|St|Avenue|Ave|Road|Rd|Drive|Dr|Lane|Ln|Boulevard|Blvd|Court|Ct|Way|Place|Pl)\b"""),
        "number" to Regex("""(?<![\w.])\$?\d[\d,]*(?:\.\d+)?(?![\w.])"""),
    )

    private fun findDetails(archive: Archive, speakers: SpeakerStore, kind: String, hours: Int): String {
        val kinds = if (kind == "any") patterns.keys.toList() else listOf(kind)
        val since = now() - hours * 3600.0
        val out = StringBuilder()
        archive.readableDatabase.rawQuery("""
            SELECT l.id, l.t0, l.text, s.conv_key FROM lines l LEFT JOIN clip_speakers s ON s.clip = l.clip AND s.label = l.label
            WHERE l.t0 >= ? ORDER BY l.t0""", arrayOf(since.toString())).use { c ->
            while (c.moveToNext()) {
                val text = c.getString(2)
                for (k in kinds) for (m in (patterns[k] ?: continue).findAll(text)) {
                    if (k == "number" && m.value.filter { it.isDigit() }.length < 2) continue
                    val who = c.getString(3)?.takeIf { it.startsWith("p") }?.drop(1)?.toLongOrNull()?.let(speakers::nameOf) ?: "someone"
                    out.appendLine("$k: ${m.value.trim()} — ${at(c.getDouble(1))} $who [L${c.getLong(0)}]: \"${text.take(160)}\"")
                }
            }
        }
        return out.toString().ifBlank { "no ${if (kind == "any") "details" else kind + "s"} found in the last $hours hours" }
    }

    // ---------------------------------------------------------------- stats

    private fun talkStats(archive: Archive, speakers: SpeakerStore, days: Int): String {
        val since = now() - days * 86_400.0
        val db = archive.readableDatabase
        val (convs, minutes) = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(speech_seconds), 0) / 60.0 FROM conversations WHERE started >= ?", arrayOf(since.toString())).use { c ->
            c.moveToFirst(); c.getInt(0) to c.getDouble(1)
        }
        val per = db.rawQuery("""
            SELECT s.conv_key, SUM(s.seconds) / 60.0, COUNT(DISTINCT c.conversation) FROM clip_speakers s JOIN clips c ON c.name = s.clip
            WHERE c.started >= ? AND s.conv_key LIKE 'p%' GROUP BY s.conv_key ORDER BY 2 DESC LIMIT 20""", arrayOf(since.toString())).use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getDouble(1), c.getInt(2))) }
        }
        val owner = AssistantPrefs.owner(context)
        val lines = per.mapNotNull { (key, mins, n) ->
            val pid = key.drop(1).toLongOrNull() ?: return@mapNotNull null
            val name = speakers.nameOf(pid) ?: "an unnamed voice #$pid"
            "$name${if (pid == owner) " (me)" else ""}: ${"%.0f".format(mins)} min of speech in $n conversations"
        }
        return "Last $days days: $convs conversations, ${"%.0f".format(minutes)} min of speech.\n" + lines.joinToString("\n")
    }

    // ------------------------------------------------------------------ web

    /** One call to a web-enabled model (OpenRouter's web plugin, a few cents at most), logged like any other. */
    private fun webSearch(query: String): String {
        val key = Secrets.get(context, Secrets.OPENROUTER) ?: return "no OpenRouter key"
        if (!AssistantPrefs.webSearch(context)) return "web search is turned off in settings"
        val model = AssistantPrefs.model(context)
        val reply = runCatching {
            Llm(key, model).chat(listOf(
                Llm.system("Answer from the web, briefly (2-4 sentences), for someone on the go. It is ${LocalDateTime.now()} in ${zone.id}. End with the main source's site name."),
                Llm.user(query)), maxTokens = 400, extra = mapOf("plugins" to kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.buildJsonObject { put("id", kotlinx.serialization.json.JsonPrimitive("web")); put("max_results", kotlinx.serialization.json.JsonPrimitive(3)) })
            }))
        }
        AssistantStore(context).use { it.logCall("web", model, reply.getOrNull(), reply.exceptionOrNull()?.message) }
        return reply.fold({ it.text ?: "no answer" }, { "web search failed: ${it.message}" })
    }
}
