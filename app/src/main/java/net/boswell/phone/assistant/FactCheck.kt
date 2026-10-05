package net.boswell.phone.assistant

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.boswell.phone.archive.Archive
import net.boswell.phone.process.BoswellLines
import net.boswell.phone.speakers.SpeakerStore
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Checks facts said aloud, in live mode.
 *
 * About once a minute, if anyone has said something new (never Boswell's own
 * lines, nor questions the owner just asked it), a cheap pass reads the new
 * lines with a little before them and picks out the claims worth checking:
 * specific, verifiable facts, not opinions, plans or anecdotes. Each claim not
 * like one checked in the last day goes to a web-searching model, which says
 * TRUE, FALSE, MISLEADING, UNCLEAR or MISHEARD (the transcript may be wrong,
 * not the speaker). False and misleading ones are a notification; every check
 * is an exchange in Ask and a badge on its line. At most a few checks an hour
 * and its own daily budget; what it did is kept in [FactStatus].
 *
 * Independent of Listen along ([Watcher]), which is told about these checks
 * so it doesn't repeat them as hints.
 */
class FactCheck(private val context: Context) {
    private var lastLine = System.currentTimeMillis() / 1000.0

    fun tick() {
        if (!AssistantPrefs.factCheck(context)) return
        // Spotting may run at home; verifying needs OpenRouter's web search, so without a key there's nothing to do.
        val web = Llm.forAssistant(context, web = true) ?: return FactStatus.skip(context, "needs an OpenRouter key to search the web (Device → Assistant)")
        val llm = Llm.forAssistant(context) ?: return FactStatus.skip(context, "no AI to spot claims with")
        val store = AssistantStore(context)
        val archive = Archive(context)
        val speakers = SpeakerStore(context)
        try {
            val budget = AssistantPrefs.factBudget(context)
            fun spent() = store.spentToday(PURPOSE) + store.spentToday(SPOT)
            if (spent() >= budget) return FactStatus.skip(context, "today's budget is spent")
            archive.sync(speakers)
            val now = System.currentTimeMillis() / 1000.0
            val spans = DirectAsks.spans(DirectAsks.recent(store.exchangesSince(now - DirectAsks.RECENT_S), now))
            val since = maxOf(lastLine, now - NEW_AT_MOST_S)
            val all = lines(archive, speakers, since - CONTEXT_S)
            val fresh = all.filter { it.t0 > since }
            if (fresh.isEmpty()) return
            lastLine = fresh.maxOf { it.t0 }
            // Anyone's words but Boswell's, and not a question just put to it.
            val new = fresh.filter { it.key != Archive.BOSWELL && !DirectAsks.within(it.t0, spans) }
            if (new.sumOf { it.text.split(' ').size } < MIN_WORDS) return
            val earlier = all.filter { it.t0 <= since }
            val checked = store.factChecksSince(now - Claims.MEMORY_S)

            val t = DateTimeFormatter.ofPattern("h:mm")
            fun show(l: Claims.Line) = "[L${l.id}] ${Instant.ofEpochSecond(l.t0.toLong()).atZone(zone).format(t)} ${l.speaker}: ${l.text}"
            val prompt = """
                You read a live transcript of a conversation, transcribed automatically (some words may be misheard), and pick out the factual claims worth checking on the web.
                Worth checking: specific, verifiable statements of fact -- numbers and statistics, dates, history, science, geography, who did or said what, prices, quotes.
                Not worth checking: opinions, plans, jokes, hypotheticals, questions, hedged guesses, personal anecdotes and anything about the speakers' own lives ("I talked to Sam yesterday"), and things nobody would doubt. Lines by ${BoswellLines.AS_SAID_BY} are never claims.
                Most stretches of conversation have none; then reply {"claims": []}. Only the NEW lines count; the earlier ones are there so you can tell what "it" and "that" mean.
                Reply with JSON only: {"claims": [{"line": "L123", "time": "3:05", "speaker": "as shown", "words": "the exact words from that line", "claim": "the claim restated so it stands on its own: who or what, spelled out"}]}. At most 3.
            """.trimIndent() +
                (if (earlier.isNotEmpty()) "\n\nEarlier, for context only:\n" + earlier.takeLast(30).joinToString("\n", transform = ::show) else "") +
                "\n\nNEW lines:\n" + new.takeLast(60).joinToString("\n", transform = ::show) +
                (if (checked.isNotEmpty()) "\n\nAlready checked lately; don't list these again:\n" + checked.takeLast(15).joinToString("\n") { "- ${it.claim}" } else "")
            FactStatus.looked(context)
            val reply = try {
                llm.chat(listOf(Llm.user(prompt)), maxTokens = 500, temperature = 0.1)
            } catch (e: Exception) {
                store.logCall(SPOT, llm.name, null, e.message)
                return FactStatus.error(context, e.message ?: e.toString())
            }
            store.logCall(SPOT, llm.name, reply)
            val spotted = Claims.parse(reply.text.orEmpty()) ?: return FactStatus.error(context, "the reply wasn't the JSON asked for: ${reply.text.orEmpty().take(80)}")
            val earlierClaims = checked.map { it.claim }.toMutableList()
            for (s in spotted) {
                val line = Claims.locate(s, new) ?: continue
                if (Claims.repeats(s.claim, earlierClaims)) continue
                earlierClaims += s.claim
                FactStatus.spotted(context)
                // Calls, not records: a check that failed was still a search.
                val recent = store.callTimes(PURPOSE, now - 3600)
                if (FactLimit.room(System.currentTimeMillis() / 1000.0, recent, AssistantPrefs.factPerHour(context)) <= 0) {
                    FactStatus.overLimit(context); continue
                }
                if (spent() >= budget) { FactStatus.skip(context, "today's budget is spent"); break }
                verify(web, store, s, line, all)
            }
        } catch (e: Exception) {
            FactStatus.error(context, e.message ?: e.toString())
            throw e
        } finally {
            speakers.close(); archive.close(); store.close()
        }
    }

    /** One claim, checked on the web, recorded, and told if it calls for it. */
    private fun verify(web: Llm, store: AssistantStore, s: Claims.Spotted, line: Claims.Line, around: List<Claims.Line>) {
        val t = DateTimeFormatter.ofPattern("h:mm a")
        val near = around.filter { it.t0 in line.t0 - 60..line.t0 + 30 && it.key != Archive.BOSWELL }
            .joinToString("\n") { "${it.speaker}: ${it.text}" }
        val system = """
            You check facts said aloud in a conversation. It is ${LocalDateTime.now().toLocalDate()}.
            Search the web and weigh what you find: prefer primary and reputable sources, notice when they disagree, and allow for casual rounding.
            The words come from automatic speech recognition and may be misheard. If the claim is wrong only in a way a transcription slip explains (a number or name that sounds like the right one, "fifteen" for "fifty", "Austria" for "Australia") and the right version is true, the verdict is MISHEARD, not FALSE.
            Verdicts: TRUE (accurate), FALSE (wrong), MISLEADING (right only in part, or true but leaves a wrong impression), UNCLEAR (the evidence is mixed or missing, or the claim can't be pinned down), MISHEARD.
            Think about the evidence briefly, then end with JSON on its own: {"verdict": "TRUE|FALSE|MISLEADING|UNCLEAR|MISHEARD", "explanation": "one sentence: the correction, or what confirms it", "source": "the one URL that best supports the verdict"}
        """.trimIndent()
        val user = "Claim: ${s.claim}\nSaid by ${line.speaker} at ${Instant.ofEpochSecond(line.t0.toLong()).atZone(zone).format(t)}: \"${s.words}\"\n\nAround it:\n$near"
        val reply = try {
            web.chat(listOf(Llm.system(system), Llm.user(user)), maxTokens = 700, temperature = 0.1, extra = mapOf("plugins" to buildJsonArray {
                add(buildJsonObject { put("id", JsonPrimitive("web")); put("max_results", JsonPrimitive(WEB_RESULTS)) })
            }))
        } catch (e: Exception) {
            store.logCall(PURPOSE, web.name, null, e.message)
            return FactStatus.error(context, e.message ?: e.toString())
        }
        store.logCall(PURPOSE, web.name, reply)
        val v = Claims.verdict(reply.text.orEmpty(), citations(reply.message))
            ?: return FactStatus.error(context, "the check's reply wasn't the JSON asked for: ${reply.text.orEmpty().take(80)}")
        val who = if (line.me) "you" else line.speaker
        val answer = v.answer(line.id)
        val exchange = store.addExchange(PURPOSE, "$who: “${s.words}”", answer, reply.cost)
        store.addFactCheck(FactCheckRow(0, System.currentTimeMillis() / 1000.0, s.claim, s.words, line.speaker, line.clip, line.offset, line.t0, line.t1,
            line.conversation, v.verdict.name, v.explanation, v.source, reply.cost, exchange))
        FactStatus.checked(context, v.verdict)
        if (!v.verdict.notifies(AssistantPrefs.factTrue(context))) return
        val site = v.source?.let { Claims.site(it) }
        AssistantNotify.post(context, AssistantNotify.FACT_CHECKS, "Fact check: ${v.verdict.label.lowercase()}",
            "${v.explanation}${site?.let { " Source: $it." } ?: ""} Said by $who. [L${line.id}]", speak = false)
        if (AssistantPrefs.factAloud(context)) AssistantNotify.speak(context, "Fact check: ${v.verdict.label.lowercase()}. ${v.explanation}")
    }

    /** The web plugin's citations, in case the reply's JSON leaves the source out. */
    private fun citations(message: JsonObject): List<String> = runCatching {
        message["annotations"]?.jsonArray.orEmpty().mapNotNull { a ->
            a.jsonObject["url_citation"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
        }
    }.getOrDefault(emptyList())

    /** Lines since [from], oldest first, with who said them. */
    private fun lines(archive: Archive, speakers: SpeakerStore, from: Double): List<Claims.Line> {
        val owner = AssistantPrefs.owner(context)
        val names = HashMap<Long, String?>()
        return archive.readableDatabase.rawQuery("""
            SELECT l.id, l.t0, l.t1, l.clip, l.offset, s.conv_key, l.text, c.conversation FROM lines l
            LEFT JOIN clip_speakers s ON s.clip = l.clip AND s.label = l.label LEFT JOIN clips c ON c.name = l.clip
            WHERE l.t0 >= ? ORDER BY l.t0 LIMIT 300""", arrayOf(from.toString())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val key = if (c.isNull(5)) null else c.getString(5)
                    val pid = key?.takeIf { it.startsWith("p") }?.drop(1)?.toLongOrNull()
                    val who = if (key == Archive.BOSWELL) BoswellLines.AS_SAID_BY else pid?.let { p -> names.getOrPut(p) { speakers.nameOf(p) } } ?: "someone"
                    val me = pid != null && pid == owner
                    add(Claims.Line(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getString(3), c.getDouble(4), key,
                        if (me) "$who (me)" else who, c.getString(6), if (c.isNull(7)) null else c.getLong(7), me))
                }
            }
        }
    }

    private val zone get() = ZoneId.systemDefault()

    companion object {
        /** Verifying: the web-searched check, and the budget's name. */
        const val PURPOSE = "factcheck"
        /** Spotting: the cheap pass that picks out claims. */
        const val SPOT = "factcheck-spot"
        const val EVERY_SECONDS = 60
        /** Earlier lines shown for context. */
        const val CONTEXT_S = 3 * 60.0
        /** After a gap (just turned on, back in live mode), only this much of what's new is read. */
        const val NEW_AT_MOST_S = 10 * 60.0
        /** Fewer new words than this, together, can't hold a claim worth a call. */
        const val MIN_WORDS = 6
        /** Web results per check: OpenRouter charges per result. */
        const val WEB_RESULTS = 3
    }
}

/** A check on record (assistant.db's fact_checks): what was said, where, and what was found. */
data class FactCheckRow(val id: Long, val created: Double, val claim: String, val words: String, val speaker: String, val clip: String,
                        val offset: Double, val lineT0: Double, val lineT1: Double, val conversation: Long?, val verdict: String,
                        val explanation: String, val source: String?, val cost: Double, val exchange: Long?) {
    val v: Verdict? get() = Verdict.parse(verdict)
    /** Said in [clip] within [t0, t1]: the line it is about (lines are remade on a re-transcription, so not by id). */
    fun on(clip: String, t0: Double, t1: Double) = clip == this.clip && lineT0 <= t1 + 0.5 && lineT1 >= t0 - 0.5
}

enum class Verdict(val label: String) {
    TRUE("Checks out"), FALSE("False"), MISLEADING("Misleading"), UNCLEAR("Unclear"), MISHEARD("Misheard");

    /** False and misleading are told; true only if asked for; unclear and misheard are only recorded. */
    fun notifies(alsoTrue: Boolean) = when (this) { FALSE, MISLEADING -> true; TRUE -> alsoTrue; UNCLEAR, MISHEARD -> false }

    companion object {
        fun parse(s: String?): Verdict? {
            val w = s?.trim()?.uppercase()?.replace(Regex("[^A-Z ]"), "")?.trim() ?: return null
            return entries.firstOrNull { it.name == w } ?: when {
                w.startsWith("MOSTLY TRUE") || w == "CORRECT" || w == "ACCURATE" -> TRUE
                w.startsWith("PARTLY") || w.startsWith("PARTIALLY") || w.startsWith("MOSTLY FALSE") -> MISLEADING
                w.startsWith("MISTRANSCRI") || w.startsWith("MISHEAR") -> MISHEARD
                w.startsWith("INCORRECT") || w == "WRONG" -> FALSE
                w.startsWith("UNVERIFI") || w.startsWith("UNKNOWN") || w.startsWith("UNSURE") -> UNCLEAR
                else -> null
            }
        }

        /** The verdict of a fact check's answer in Ask (it starts with the label). */
        fun ofAnswer(answer: String): Verdict? = entries.firstOrNull { answer.startsWith(it.label + ":") }
    }
}

/**
 * The pure parts of fact checking, testable on the desktop: reading the
 * spotting reply, finding the line a claim came from, telling a claim already
 * checked, and reading the verdict.
 */
object Claims {
    /** How far back checked claims are remembered, so the same claim isn't checked twice. */
    const val MEMORY_S = 24 * 3600.0
    /** Share of the longer claim's words both share that makes them the same claim. */
    const val SAME = 0.6

    /** A line as fact checking sees it. [speaker] is how it's shown ("Ana", "Sam (me)"); [key] the archive's conv_key. */
    data class Line(val id: Long, val t0: Double, val t1: Double, val clip: String, val offset: Double, val key: String?,
                    val speaker: String, val text: String, val conversation: Long? = null, val me: Boolean = false)

    /** A claim the spotting pass picked out. [line] is the id it gave ("L123"), if any. */
    data class Spotted(val line: Long?, val time: String?, val speaker: String?, val words: String, val claim: String)

    data class Checked(val verdict: Verdict, val explanation: String, val source: String?) {
        /** What Ask shows: "False: …" (so the card knows its verdict), the source, and the moment. */
        fun answer(line: Long) = "${verdict.label}: $explanation" + (source?.let { "\nSource: $it" } ?: "") + " [L$line]"
    }

    /** The claims in a spotting reply; null when it isn't the JSON asked for (an empty list is "nothing worth checking"). */
    fun parse(raw: String): List<Spotted>? {
        val j = json(raw) { "claims" in it } ?: return null
        val list = runCatching { j["claims"]!!.jsonArray }.getOrNull() ?: return null
        return list.mapNotNull { e ->
            val o = runCatching { e.jsonObject }.getOrNull() ?: return@mapNotNull null
            fun str(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            val claim = str("claim") ?: return@mapNotNull null
            Spotted(str("line")?.let { Regex("\\d+").find(it)?.value?.toLongOrNull() }, str("time"), str("speaker"), str("words") ?: claim, claim)
        }
    }

    /**
     * The new line a claim came from: the one it named, else the one holding its
     * words. Null if neither (from the context lines, or made up): not checked.
     */
    fun locate(s: Spotted, lines: List<Line>): Line? {
        s.line?.let { id -> lines.firstOrNull { it.id == id }?.let { return it } }
        fun norm(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").replace(Regex("\\s+"), " ").trim()
        val w = norm(s.words)
        if (w.length < 8) return null
        return lines.firstOrNull { norm(it.text).contains(w) }
    }

    /** The words that carry a claim: [Hints.words], plus its numbers (a different year is a different claim). */
    fun words(text: String): Set<String> = Hints.words(text) + Regex("\\d[\\d,.]*").findAll(text).map { it.value.replace(",", "").trimEnd('.') }

    /** The same claim as one checked already: most of the longer one's words in common. */
    fun repeats(claim: String, earlier: List<String>): Boolean {
        val w = words(claim)
        if (w.isEmpty()) return false
        return earlier.any { e -> val o = words(e); o.isNotEmpty() && w.count { it in o }.toDouble() / maxOf(w.size, o.size) >= SAME }
    }

    /** A check's reply: some reasoning, then the JSON. [citations] stand in for a missing source. */
    fun verdict(raw: String, citations: List<String> = emptyList()): Checked? {
        val j = json(raw) { "verdict" in it } ?: return null
        fun str(k: String) = runCatching { j[k]?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val v = Verdict.parse(str("verdict")) ?: return null
        val explanation = str("explanation") ?: return null
        val source = str("source")?.takeIf { it.startsWith("http") } ?: citations.firstOrNull { it.startsWith("http") }
        return Checked(v, explanation, source)
    }

    /** "en.wikipedia.org" from a URL, for saying where without reading out the whole link. */
    fun site(url: String): String = url.substringAfter("://").substringBefore('/').removePrefix("www.")

    /** The last JSON object in [raw] that [wanted] accepts: models put reasoning, or code fences, before it. */
    private fun json(raw: String, wanted: (JsonObject) -> Boolean): JsonObject? {
        val end = raw.lastIndexOf('}')
        if (end < 0) return null
        var i = raw.lastIndexOf('{', end)
        while (i >= 0) {
            runCatching { Llm.json.parseToJsonElement(raw.substring(i, end + 1)).jsonObject }.getOrNull()?.let { if (wanted(it)) return it }
            i = if (i == 0) -1 else raw.lastIndexOf('{', i - 1)
        }
        return null
    }
}

/** How many checks an hour may still take. */
object FactLimit {
    /** Checks left this hour, given when the earlier ones were (epoch seconds). */
    fun room(now: Double, earlier: List<Double>, perHour: Int): Int = perHour - earlier.count { now - it in 0.0..3600.0 }
}

/**
 * What fact checking did today, for Device -> Assistant: looks, claims spotted,
 * checks, how many were false or misleading, how many skipped over the hourly
 * limit, failures, and the last reason it held back or failed.
 */
object FactStatus {
    private fun p(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)
    private fun today() = java.time.LocalDate.now().toString()
    private val COUNTS = listOf("looks", "spotted", "checked", "flagged", "over", "errors")

    data class Today(val lastLook: Long?, val looks: Int, val spotted: Int, val checked: Int, val flagged: Int, val overLimit: Int, val errors: Int, val note: String?)

    fun today(c: Context): Today {
        val p = p(c)
        val fresh = p.getString("fact_day", null) == today()
        fun n(k: String) = if (fresh) p.getInt("fact_$k", 0) else 0
        return Today(p.getLong("fact_last", 0L).takeIf { it > 0 }, n("looks"), n("spotted"), n("checked"), n("flagged"), n("over"), n("errors"),
            p.getString("fact_note", null))
    }

    @Synchronized private fun bump(c: Context, vararg counts: String, note: String? = null, clearNote: Boolean = false) {
        val p = p(c)
        val fresh = p.getString("fact_day", null) == today()
        val e = p.edit()
        if (!fresh) { e.putString("fact_day", today()); for (k in COUNTS) e.putInt("fact_$k", 0) }
        for (k in counts) e.putInt("fact_$k", (if (fresh) p.getInt("fact_$k", 0) else 0) + 1)
        if ("looks" in counts) e.putLong("fact_last", System.currentTimeMillis())
        if (note != null || clearNote) e.putString("fact_note", note)
        e.apply()
    }

    fun looked(c: Context) = bump(c, "looks")
    fun spotted(c: Context) = bump(c, "spotted")
    fun checked(c: Context, v: Verdict) = if (v == Verdict.FALSE || v == Verdict.MISLEADING) bump(c, "checked", "flagged", clearNote = true) else bump(c, "checked", clearNote = true)
    fun overLimit(c: Context) = bump(c, "over", note = "skipped a claim: the hourly limit was reached")
    fun skip(c: Context, why: String) = bump(c, note = why)
    fun error(c: Context, why: String) = bump(c, "errors", note = "failed: ${why.take(160)}")
}
