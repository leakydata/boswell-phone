package net.boswell.phone.assistant

import android.content.Context
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.boswell.phone.archive.Archive
import net.boswell.phone.speakers.SpeakerStore

/**
 * Listens along in live mode and speaks up only when it has something useful.
 *
 * Every few minutes, if the owner has said something new, the last stretch of
 * conversation goes to the model with one question: is there a hint worth a
 * notification right now? Almost always the answer is no, and it is told that
 * silence is the default. It never sees audio, never runs without an owner
 * set, and stops for the day at the budget.
 */
class Watcher(private val context: Context) {
    private var lastOwnerLine = System.currentTimeMillis() / 1000.0

    fun tick() {
        if (!AssistantPrefs.watcher(context)) return
        val owner = AssistantPrefs.owner(context) ?: return
        val llm = Llm.forAssistant(context) ?: return
        val store = AssistantStore(context)
        val archive = Archive(context)
        val speakers = SpeakerStore(context)
        try {
            if (store.spentToday(PURPOSE) >= AssistantPrefs.budget(context)) return
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
            val me = speakers.nameOf(owner) ?: "the user"
            val context15 = Assistant(context).lines(archive, speakers, now - 15 * 60, skip = spans)
            val model = llm.name
            val prompt = """
                You quietly follow ${me}'s conversations through their phone and may offer ONE short, genuinely useful hint as a notification: a fact they seem to be reaching for, a correction of something clearly wrong, a reminder of something they said they'd do, or a helpful next step. Lines marked (me) are ${me}. Lines by ${net.boswell.phone.process.BoswellLines.AS_SAID_BY} are your own earlier answers, read aloud by the phone: never hint about those.
                Most of the time there is nothing worth interrupting for. Default to silence. Never comment on the conversation itself, never be chatty, never repeat a hint.
                Reply with JSON only: {"notify": false} or {"notify": true, "title": "3-6 words", "text": "one or two sentences"}.

                The last 15 minutes:
                $context15
            """.trimIndent() + DirectAsks.prompt(asked, me)
            val reply = try {
                llm.chat(listOf(Llm.user(prompt)), maxTokens = 200, temperature = 0.2)
            } catch (e: Exception) {
                store.logCall(PURPOSE, model, null, e.message); return
            }
            store.logCall(PURPOSE, model, reply)
            val j = runCatching {
                val raw = reply.text.orEmpty()
                Llm.json.parseToJsonElement(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)).jsonObject
            }.getOrNull() ?: return
            if (j["notify"]?.jsonPrimitive?.booleanOrNull != true) return
            val title = j["title"]?.jsonPrimitive?.contentOrNull ?: "Boswell"
            val text = j["text"]?.jsonPrimitive?.contentOrNull ?: return
            store.addExchange("watcher", title, text, reply.cost)
            AssistantNotify.post(context, AssistantNotify.SUGGESTIONS, title, text)
        } finally {
            speakers.close(); archive.close(); store.close()
        }
    }

    companion object {
        const val PURPOSE = "watcher"
        const val EVERY_SECONDS = 120
    }
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
