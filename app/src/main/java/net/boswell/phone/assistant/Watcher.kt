package net.boswell.phone.assistant

import android.content.Context
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.boswell.phone.archive.Archive
import net.boswell.phone.speakers.SpeakerStore

/**
 * Listens along in live mode and speaks up when it has something useful.
 *
 * Every couple of minutes, if the owner has said something new, the last stretch of
 * conversation goes to the model with one question: is there a hint worth a
 * notification right now? It sees its own recent hints, so a topic still being
 * talked about isn't explained again, and a hint that repeats one anyway is dropped
 * ([Hints.repeats]). How often it may speak is the owner's choice ([AssistantPrefs.Pace]).
 * It never sees audio, never runs without an owner set, and stops for the day at the
 * budget. What it did is kept in [WatchStatus], so "it went quiet" can be looked at.
 */
class Watcher(private val context: Context) {
    private var lastOwnerLine = System.currentTimeMillis() / 1000.0

    fun tick() {
        if (!AssistantPrefs.watcher(context)) return
        val owner = AssistantPrefs.owner(context) ?: return WatchStatus.skip(context, "no \"Me\" set")
        val llm = Llm.forAssistant(context) ?: return WatchStatus.skip(context, "no AI to ask (no OpenRouter key, or home chosen with no fallback)")
        val store = AssistantStore(context)
        val archive = Archive(context)
        val speakers = SpeakerStore(context)
        try {
            if (store.spentToday(PURPOSE) >= AssistantPrefs.budget(context)) return WatchStatus.skip(context, "today's budget is spent")
            archive.sync(speakers)
            val now = System.currentTimeMillis() / 1000.0
            // What the owner just asked Boswell themselves, already answered: not the watcher's to repeat.
            val asked = DirectAsks.recent(store.exchangesSince(now - DirectAsks.RECENT_S), now)
            val spans = DirectAsks.spans(asked)
            // Only when the owner has said something since the last look -- besides asking.
            val said = archive.readableDatabase.rawQuery(
                "SELECT l.t0 FROM lines l JOIN clip_speakers s ON s.clip = l.clip AND s.label = l.label WHERE s.conv_key = ? AND l.t0 > ?",
                arrayOf("p$owner", lastOwnerLine.toString())).use { c -> buildList { while (c.moveToNext()) add(c.getDouble(0)) } }
            if (said.isEmpty()) return
            lastOwnerLine = said.max()
            if (said.all { DirectAsks.within(it, spans) }) return
            val pace = AssistantPrefs.pace(context)
            val mine = store.exchangesSince(now - Hints.MEMORY_S).filter { it.source == PURPOSE && !it.error }
            // Fact checks (a separate setting) already told them: not to be repeated as hints.
            val checks = store.exchangesSince(now - Hints.MEMORY_S).filter { it.source == FactCheck.PURPOSE && !it.error }
            // Not again so soon: the owner's chosen pace, between one hint and the next.
            mine.lastOrNull()?.let { if (now - it.at < pace.gapSeconds) return WatchStatus.skip(context, "waiting: a hint ${((now - it.at) / 60).toInt()} min ago") }
            val me = speakers.nameOf(owner) ?: "the user"
            val context15 = Assistant(context).lines(archive, speakers, now - 15 * 60, skip = spans)
            val model = llm.name
            val prompt = """
                You follow ${me}'s conversations through their phone, like a well-read friend listening in, and may offer ONE short hint as a notification. Worth a hint: the word, name, title or fact they're reaching for or can't remember; a little more on an idea they're exploring (a connection, a key point, a source); a correction of something clearly wrong; a reminder of something they said they'd do; a helpful next step. Lines marked (me) are ${me}. Lines by ${net.boswell.phone.process.BoswellLines.AS_SAID_BY} are your own earlier answers, read aloud by the phone: never hint about those.
                ${pace.prompt}
                Never comment on the conversation itself, never be chatty, and never tell them something you've already told them (your recent hints are listed below): a hint must add something new.
                Reply with JSON only: {"notify": false} or {"notify": true, "title": "3-6 words", "text": "one or two sentences"}.

                The last 15 minutes:
                $context15
            """.trimIndent() + Hints.prompt(mine) + Hints.factChecks(checks) + DirectAsks.prompt(asked, me)
            val reply = try {
                llm.chat(listOf(Llm.user(prompt)), maxTokens = 200, temperature = 0.2)
            } catch (e: Exception) {
                store.logCall(PURPOSE, model, null, e.message)
                return WatchStatus.error(context, e.message ?: e.toString())
            }
            store.logCall(PURPOSE, model, reply)
            val j = runCatching {
                val raw = reply.text.orEmpty()
                Llm.json.parseToJsonElement(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)).jsonObject
            }.getOrNull() ?: return WatchStatus.error(context, "the reply wasn't the JSON asked for: ${reply.text.orEmpty().take(80)}")
            if (j["notify"]?.jsonPrimitive?.booleanOrNull != true) return WatchStatus.looked(context, hinted = false)
            val title = j["title"]?.jsonPrimitive?.contentOrNull ?: "Boswell"
            val text = j["text"]?.jsonPrimitive?.contentOrNull ?: return WatchStatus.looked(context, hinted = false)
            // Told already, whatever the model thinks: dropped.
            if (Hints.repeats("$title $text", (mine + checks).map { "${it.question.orEmpty()} ${it.answer}" }))
                return WatchStatus.skip(context, "dropped a repeat: $title")
            store.addExchange(PURPOSE, title, text, reply.cost)
            AssistantNotify.post(context, AssistantNotify.SUGGESTIONS, title, text)
            WatchStatus.looked(context, hinted = true)
        } catch (e: Exception) {
            // Was swallowed by the caller, so a watcher failing every time ("database is locked") looked like one with nothing to say.
            WatchStatus.error(context, e.message ?: e.toString())
            throw e
        } finally {
            speakers.close(); archive.close(); store.close()
        }
    }

    companion object {
        const val PURPOSE = "watcher"
        const val EVERY_SECONDS = 120
    }
}

/** The watcher's own recent hints: told to it, and checked against, so it doesn't say them twice. */
object Hints {
    /** How far back its own hints are remembered. */
    const val MEMORY_S = 4 * 3600.0
    /** Share of a new hint's content words already in an earlier one that makes it a repeat. */
    const val SAME = 0.5

    private val STOP = setOf(
        "about", "after", "again", "also", "because", "been", "being", "could", "does", "doing", "from", "have", "here", "into",
        "just", "like", "more", "most", "much", "only", "other", "over", "said", "same", "should", "some", "such", "than", "that",
        "their", "them", "then", "there", "these", "they", "this", "those", "through", "very", "want", "were", "what", "when",
        "where", "which", "while", "will", "with", "would", "your", "you're", "it's", "idea", "ideas", "think", "talking", "mentioned")

    /** The words that carry what a hint is about: 4+ letters, lowercased, endings trimmed, common words left out. */
    fun words(text: String): Set<String> = Regex("[\\p{L}']{4,}").findAll(text.lowercase()).map { it.value.trim('\'') }
        .filter { it.length >= 4 && it !in STOP }.map { it.removeSuffix("'s").removeSuffix("s") }.toSet()

    /** Mostly what an earlier hint already said: half or more of its content words, or the same title words. */
    fun repeats(hint: String, earlier: List<String>): Boolean {
        val w = words(hint)
        if (w.isEmpty()) return false
        return earlier.any { e -> val o = words(e); o.isNotEmpty() && w.count { it in o }.toDouble() / w.size >= SAME }
    }

    /** For the watcher's prompt: claims fact checking already checked and told them about. Empty when none. */
    fun factChecks(checks: List<Exchange>): String {
        if (checks.isEmpty()) return ""
        return "\n\nFact checking already told them about these claims. Don't correct, confirm or repeat them in a hint:\n" +
            checks.takeLast(12).joinToString("\n") { "- ${it.question.orEmpty().take(160)}: ${Moments.strip(it.answer).take(240)}" }
    }

    /** For the watcher's prompt: what it already told the owner lately. Empty when nothing. */
    fun prompt(mine: List<Exchange>): String {
        if (mine.isEmpty()) return ""
        return "\n\nYou already told them these in the last few hours. Don't repeat or rephrase them; only something new is worth a hint:\n" +
            mine.takeLast(12).joinToString("\n") { "- ${it.question.orEmpty()}: ${it.answer.take(240)}" }
    }
}

/**
 * What the watcher did today, for Device -> Assistant: when it last looked, how many
 * looks and hints, and the last reason it held back or failed.
 */
object WatchStatus {
    private fun p(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)
    private fun today() = java.time.LocalDate.now().toString()

    data class Today(val lastLook: Long?, val looks: Int, val hints: Int, val errors: Int, val note: String?)

    fun today(c: Context): Today {
        val p = p(c)
        val fresh = p.getString("watch_day", null) == today()
        return Today(p.getLong("watch_last", 0L).takeIf { it > 0 }, if (fresh) p.getInt("watch_looks", 0) else 0,
            if (fresh) p.getInt("watch_hints", 0) else 0, if (fresh) p.getInt("watch_errors", 0) else 0, p.getString("watch_note", null))
    }

    @Synchronized private fun bump(c: Context, look: Boolean, hint: Boolean, error: Boolean, note: String?) {
        val p = p(c)
        val e = p.edit()
        if (p.getString("watch_day", null) != today()) e.putString("watch_day", today()).putInt("watch_looks", 0).putInt("watch_hints", 0).putInt("watch_errors", 0)
        val fresh = p.getString("watch_day", null) == today()
        fun n(k: String) = if (fresh) p.getInt(k, 0) else 0
        if (look) e.putInt("watch_looks", n("watch_looks") + 1).putLong("watch_last", System.currentTimeMillis())
        if (hint) e.putInt("watch_hints", n("watch_hints") + 1)
        if (error) e.putInt("watch_errors", n("watch_errors") + 1)
        if (note != null || hint) e.putString("watch_note", note)
        e.apply()
    }

    fun looked(c: Context, hinted: Boolean) = bump(c, look = true, hint = hinted, error = false, note = null)
    fun skip(c: Context, why: String) = bump(c, look = false, hint = false, error = false, note = why)
    fun error(c: Context, why: String) = bump(c, look = true, hint = false, error = true, note = "failed: ${why.take(160)}")
}

/**
 * Questions the owner put to Boswell directly -- on the Omi's button, typed,
 * a double-tap capture, a voice trigger -- in the last few minutes. They were
 * answered already, so the watcher listening along leaves them out: their
 * lines are skipped (from the first word to the last, with the same slack
 * for audio arriving late as Boswell's own lines), and it is told what was
 * asked and answered, so a hint never repeats the answer. Anything said
 * outside those spans is the watcher's as usual.
 */
object DirectAsks {
    val SOURCES = setOf("button", "typed", Assistant.CAPTURE, Assistant.TRIGGER)
    /** How far back "just asked" reaches. */
    const val RECENT_S = 10 * 60.0
    /** A spoken question's lines may be placed a little before its tap, and arrive a little after it ended. */
    const val BEFORE_S = 2.0
    const val AFTER_S = net.boswell.phone.process.BoswellLines.AFTER

    fun recent(exchanges: List<Exchange>, now: Double): List<Exchange> =
        exchanges.filter { it.source in SOURCES && !it.error && now - it.at <= RECENT_S }

    fun spans(asked: List<Exchange>): List<ClosedFloatingPointRange<Double>> =
        asked.map { e -> ((e.askedFrom ?: e.at) - BEFORE_S)..((e.askedTo ?: e.at) + AFTER_S) }

    fun within(t: Double, spans: List<ClosedFloatingPointRange<Double>>) = spans.any { t in it }

    /** For the watcher's prompt: what was just asked and answered, not to be repeated. Empty when nothing was. */
    fun prompt(asked: List<Exchange>, me: String): String {
        if (asked.isEmpty()) return ""
        return "\n\nIn the last few minutes $me asked you these directly and already has the answers (their questions are left out above). " +
            "Don't repeat, rephrase or add to these answers; only something new said since is worth a hint:\n" +
            asked.joinToString("\n") { e -> "Q: ${e.question.orEmpty().take(200)}\nA: ${SpeechText.plain(Moments.strip(e.answer)).take(400)}" }
    }
}
