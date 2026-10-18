package net.boswell.phone.assistant

import android.content.Context
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.boswell.phone.archive.Archive
import net.boswell.phone.capture.CaptureRepository
import net.boswell.phone.capture.Problems
import net.boswell.phone.speakers.SpeakerStore

/**
 * A guest in the room: listens to everyone, and now and then says something worth noting aloud.
 *
 * Not the watcher (which hints at the owner, by notification, from the owner's own words) and not
 * fact checking (which corrects). Every couple of minutes, if people are talking with each other,
 * the last ten minutes of the room go to the model with one question: is there something
 * genuinely interesting here that nobody has said? Silence is the default; the answer is nearly
 * always no. A yes isn't spoken at once: it waits for a pause in the room ([deliver]), and goes
 * stale if the conversation moves on first. How often it may speak is the owner's choice
 * ([AssistantPrefs.ChimePace]); it remembers what it said, and what the watcher and fact checks
 * already told, so nothing is said twice. Never into a call, never over a TV or video playing in
 * the background (voices marked as one are left out), never over its own speech.
 */
class Chime(private val context: Context) {
    private var lastLine = System.currentTimeMillis() / 1000.0

    /** A chime worked out and waiting for a pause. */
    private class Waiting(val text: String, val cost: Double, val at: Long)
    @Volatile private var waiting: Waiting? = null

    fun isWaiting() = waiting != null

    fun tick() {
        if (!AssistantPrefs.chime(context)) { waiting = null; return }
        if (waiting != null) return
        val llm = Llm.forAssistant(context) ?: return ChimeStatus.skip(context, "no AI to ask (no OpenRouter key, or home chosen with no fallback)")
        val store = AssistantStore(context)
        val archive = Archive(context)
        val speakers = SpeakerStore(context)
        try {
            if (store.spentToday(PURPOSE) >= AssistantPrefs.chimeBudget(context)) return ChimeStatus.skip(context, "today's budget is spent")
            archive.sync(speakers)
            val now = System.currentTimeMillis() / 1000.0
            val pace = AssistantPrefs.chimePace(context)
            val mine = store.exchangesSince(now - Hints.MEMORY_S).filter { it.source == PURPOSE && !it.error }
            mine.lastOrNull()?.let { if (now - it.at < pace.gapSeconds) return ChimeStatus.skip(context, "waiting: chimed in ${((now - it.at) / 60).toInt()} min ago") }
            val asked = DirectAsks.recent(store.exchangesSince(now - DirectAsks.RECENT_S), now)
            val spans = DirectAsks.spans(asked)
            val media = HashMap<Long, Boolean>()
            fun tv(l: Claims.Line) = l.key?.takeIf { it.startsWith("p") }?.drop(1)?.toLongOrNull()?.let { id -> media.getOrPut(id) { speakers.kindOf(id) == "media" } } == true
            // The people in the room: not Boswell, not a TV, not a question just put to Boswell.
            val room = FactCheck.lines(context, archive, speakers, now - WINDOW_S)
                .filter { it.key != Archive.BOSWELL && !tv(it) && !DirectAsks.within(it.t0, spans) }
            val fresh = room.filter { it.t0 > lastLine }
            if (fresh.isEmpty()) return
            lastLine = fresh.maxOf { it.t0 }
            if (!ChimeRules.talking(room, fresh)) return
            val me = AssistantPrefs.owner(context)?.let { speakers.nameOf(it) }
            val shown = java.time.format.DateTimeFormatter.ofPattern("h:mm")
            val zone = java.time.ZoneId.systemDefault()
            val checks = store.exchangesSince(now - Hints.MEMORY_S).filter { it.source == FactCheck.PURPOSE && !it.error }
            val hints = store.exchangesSince(now - Hints.MEMORY_S).filter { it.source == Watcher.PURPOSE && !it.error }
            val prompt = """
                You are a well-read guest in the room, listening to the people in it through the phone, and now and then you say something out loud. You are not an assistant waiting for tasks, and not a fact checker (another process corrects mistakes; leave claims to it). Chime in only with something genuinely worth noting that nobody has said: a surprising connection between things they said, a striking related fact or story, a good counterpoint, a better way to see what they are discussing. Think of the friend whose rare remark makes everyone say "huh, I didn't know that". Most of the time there is nothing worth saying, and then the answer is silence.
                ${pace.prompt}
                It is spoken aloud to everyone in the room: one or two short, natural sentences, no list, no markdown, no greeting, no "did you know" opener. Speak to the room, not to one person. Don't comment on the conversation itself or on who said what. Stay out of private matters (health, money, relationships, arguments), small talk and logistics.
                ${me?.let { "Lines marked (me) are $it. " } ?: ""}Lines by ${net.boswell.phone.process.BoswellLines.AS_SAID_BY} are your own earlier words: never chime about those.
                Reply with JSON only: {"chime": false} or {"chime": true, "text": "what you would say, at most 40 words"}.

                The last ${(WINDOW_S / 60).toInt()} minutes in the room:
                ${room.takeLast(80).joinToString("\n") { "${java.time.Instant.ofEpochSecond(it.t0.toLong()).atZone(zone).format(shown)} ${it.speaker}: ${it.text}" }}
            """.trimIndent() + Hints.prompt(mine) + Hints.prompt(hints) + Hints.factChecks(checks)
            val reply = try {
                llm.chat(listOf(Llm.user(prompt)), maxTokens = 200, temperature = 0.4)
            } catch (e: Exception) {
                Problems.report("chime: AI call", e)
                store.logCall(PURPOSE, llm.name, null, e.message)
                return ChimeStatus.error(context, e.message ?: e.toString())
            }
            store.logCall(PURPOSE, llm.name, reply)
            val text = ChimeRules.parse(reply.text.orEmpty())
                ?: return if (ChimeRules.silent(reply.text.orEmpty())) ChimeStatus.looked(context, chimed = false)
                else ChimeStatus.error(context, "the reply wasn't the JSON asked for: ${reply.text.orEmpty().take(80)}")
            if (Hints.repeats(text, (mine + hints + checks).map { "${it.question.orEmpty()} ${it.answer}" })) return ChimeStatus.skip(context, "dropped a repeat")
            ChimeStatus.looked(context, chimed = false)
            waiting = Waiting(text, reply.cost, System.currentTimeMillis())
        } catch (e: Exception) {
            ChimeStatus.error(context, e.message ?: e.toString())
            throw e
        } finally {
            speakers.close(); archive.close(); store.close()
        }
    }

    /** Says the waiting chime once the room is quiet; lets it go if the moment passed. Cheap: called every few seconds. */
    fun deliver() {
        val w = waiting ?: return
        val now = System.currentTimeMillis()
        if (!AssistantPrefs.chime(context)) { waiting = null; return }
        if (now - w.at > STALE_MS) { waiting = null; return ChimeStatus.skip(context, "let one go: the conversation moved on") }
        if (!ChimeRules.quiet(now, CaptureRepository.state.value.lastAudioMillis) || AssistantNotify.speakingNow() || AssistantNotify.inCall(context)) return
        waiting = null
        val aloud = AssistantPrefs.chimeAloud(context)
        AssistantStore(context).use { it.addExchange(PURPOSE, "Chimed in", w.text, w.cost) }
        if (aloud) AssistantNotify.speak(context, w.text)
        else AssistantNotify.post(context, AssistantNotify.SUGGESTIONS, "Boswell chimed in", w.text, speak = false)
        ChimeStatus.looked(context, chimed = true)
    }

    companion object {
        const val PURPOSE = "chime"
        const val EVERY_SECONDS = 120
        /** Seconds into each [EVERY_SECONDS] that a look starts, so it doesn't run with the watcher's. */
        const val OFFSET_SECONDS = 60
        /** What the model sees of the room. */
        const val WINDOW_S = 10 * 60.0
        /** A chime waiting longer than this for a pause is about something they've moved on from. */
        const val STALE_MS = 3 * 60_000L
    }
}

/** The pure parts of chiming in, testable on the desktop. */
object ChimeRules {
    /** People in the room together talking about something: this many words in the window, from at least two voices. */
    const val MIN_WORDS = 60
    /** And this much new since the last look. */
    const val MIN_NEW_WORDS = 15
    /** Longest chime, in words. */
    const val MAX_WORDS = 45
    /** The mic sleeps after about 3 s of quiet; this long without audio is a pause in the room. */
    const val PAUSE_MS = 4_000L

    private fun words(lines: List<Claims.Line>) = lines.sumOf { l -> l.text.split(Regex("\\s+")).count { it.isNotBlank() } }

    /** Whether [room] holds a conversation worth a look: more than one voice, enough said, and something new in [fresh]. */
    fun talking(room: List<Claims.Line>, fresh: List<Claims.Line>): Boolean =
        room.mapNotNull { it.key }.toSet().size >= 2 && words(room) >= MIN_WORDS && words(fresh) >= MIN_NEW_WORDS

    /** A pause in the room: no audio for [PAUSE_MS] (or none yet). */
    fun quiet(nowMs: Long, lastAudioMs: Long?): Boolean = lastAudioMs == null || nowMs - lastAudioMs >= PAUSE_MS

    /** The model said to stay silent (a clean {"chime": false}), as opposed to a reply that wasn't the JSON asked for. */
    fun silent(raw: String): Boolean = json(raw)?.get("chime")?.jsonPrimitive?.booleanOrNull == false

    /** The words to say, or null for silence, a reply that isn't the JSON asked for, or a chime too long to be a remark. */
    fun parse(raw: String): String? {
        val j = json(raw) ?: return null
        if (j["chime"]?.jsonPrimitive?.booleanOrNull != true) return null
        val text = j["text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return text.takeIf { it.split(Regex("\\s+")).size <= MAX_WORDS }
    }

    private fun json(raw: String) = runCatching {
        Llm.json.parseToJsonElement(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)).jsonObject
    }.getOrNull()
}

/** What chiming in did today, for Device -> Assistant: looks, chimes, failures, and the last reason it held back. */
object ChimeStatus {
    private fun p(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)
    private fun today() = java.time.LocalDate.now().toString()

    data class Today(val lastLook: Long?, val looks: Int, val chimes: Int, val errors: Int, val note: String?)

    fun today(c: Context): Today {
        val p = p(c)
        val fresh = p.getString("chime_day", null) == today()
        fun n(k: String) = if (fresh) p.getInt(k, 0) else 0
        return Today(p.getLong("chime_last", 0L).takeIf { it > 0 }, n("chime_looks"), n("chime_chimes"), n("chime_errors"), p.getString("chime_note", null))
    }

    @Synchronized private fun bump(c: Context, look: Boolean, chime: Boolean, error: Boolean, note: String?) {
        val p = p(c)
        val e = p.edit()
        val fresh = p.getString("chime_day", null) == today()
        if (!fresh) e.putString("chime_day", today()).putInt("chime_looks", 0).putInt("chime_chimes", 0).putInt("chime_errors", 0)
        fun n(k: String) = if (fresh) p.getInt(k, 0) else 0
        if (look) e.putInt("chime_looks", n("chime_looks") + 1).putLong("chime_last", System.currentTimeMillis())
        if (chime) e.putInt("chime_chimes", n("chime_chimes") + 1)
        if (error) e.putInt("chime_errors", n("chime_errors") + 1)
        if (note != null || chime) e.putString("chime_note", note)
        e.apply()
    }

    fun looked(c: Context, chimed: Boolean) = bump(c, look = !chimed, chime = chimed, error = false, note = null)
    fun skip(c: Context, why: String) = bump(c, look = false, chime = false, error = false, note = why)
    fun error(c: Context, why: String) = bump(c, look = true, chime = false, error = true, note = "failed: ${why.take(160)}")
}
