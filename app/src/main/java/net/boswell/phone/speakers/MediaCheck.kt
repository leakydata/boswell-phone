package net.boswell.phone.speakers

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.boswell.phone.HeavyWork
import net.boswell.phone.archive.Archive
import net.boswell.phone.archive.Conversation
import net.boswell.phone.archive.LineRow
import net.boswell.phone.assistant.AssistantPrefs
import net.boswell.phone.capture.CaptureRepository
import net.boswell.phone.capture.logged
import net.boswell.phone.home.HomeServer
import net.boswell.phone.process.ClipActions
import java.util.concurrent.TimeUnit

/**
 * Voices nobody has labeled, checked for being a TV, video or radio rather than
 * someone in the room. The home server asks TypeSafe's Jev (boswell-server's
 * media.py, which holds the key); the phone decides what to do with the answer.
 *
 * Measured 2026-10-11 on 208 voices from the owner's archive, each voice with up to
 * 1,500 words of the conversation around it (this file's window): every voice it put
 * at 0.5 or more was a device (67 of 67, the one apparent miss was the radio), and it
 * found about 60% of them -- the rest, hosts chatting casually, it calls live with
 * confidence. So above [MARK] a voice is marked a TV the way "It's a TV" does it, and
 * from [SUGGEST] it's offered on the voice sheet; below, nothing changes.
 *
 * Each voice is asked about once ([SpeakerStore.mediaAnswers]): one marked, then
 * unmarked by hand, stays unmarked. Only unnamed voices that aren't the owner and
 * haven't been labeled are asked about; named people never are.
 */
object MediaCheck {
    const val MARK = 0.80
    const val SUGGEST = 0.50
    /** Fewer words than this from a voice: too little to judge. */
    const val MIN_WORDS = 40
    /** What Jev sees: from a few lines before the voice first speaks, this many words. */
    const val MAX_WORDS = 1_500
    const val LINES_BEFORE = 6
    const val PER_REQUEST = 25
    /** At most this many a run, newest first, so the archive's backlog spreads over a few hours. */
    const val PER_RUN = 100

    private fun p(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)
    fun on(c: Context) = p(c).getBoolean("media_check", true)
    fun setOn(c: Context, on: Boolean) = p(c).edit().putBoolean("media_check", on).apply()

    /** One voice to ask about: [id] is what its answer is kept under. */
    data class Voice(val id: String, val conversation: Long, val key: String, val personId: Long?,
                     val target: String, val lines: List<Pair<String, String>>, val sounds: List<String>, val words: Int)

    /**
     * The key an answer is kept under: the person for a filed voice, wherever they're heard,
     * and the voice's first clip and label for one never filed (its conversation key, "v3",
     * is only a number that a regroup can change).
     */
    fun idOf(key: String, lines: List<LineRow>): String? = when {
        key.startsWith("p") -> key
        else -> lines.firstOrNull { it.speaker == key && it.label != null }?.let { "s${it.clip}|${it.label}" }
    }

    fun letter(i: Int): String = if (i < 26) ('A' + i).toString() else "S${i + 1}"

    /**
     * The voices of one conversation worth asking about, each with the window Jev sees.
     * [open] says whether a conversation voice (its key and person) has no label yet;
     * [asked] is what's been asked already.
     */
    fun candidates(conv: Conversation, lines: List<LineRow>, open: (String, Long?) -> Boolean, asked: Set<String>): List<Voice> {
        val letters = LinkedHashMap<String, String>()
        for (l in lines) letters.getOrPut(l.speaker ?: "?") { letter(letters.size) }
        val words = lines.groupingBy { it.speaker ?: "?" }.fold(0) { n, l -> n + l.text.split(Regex("\\s+")).count { it.isNotBlank() } }
        return words.filter { (key, n) -> n >= MIN_WORDS && key != "?" }.mapNotNull { (key, n) ->
            val personId = key.takeIf { it.startsWith("p") }?.drop(1)?.toLongOrNull()
            if (!open(key, personId)) return@mapNotNull null
            val id = idOf(key, lines) ?: return@mapNotNull null
            if (id in asked) return@mapNotNull null
            val first = lines.indexOfFirst { it.speaker == key }
            var count = 0
            val window = buildList {
                for (l in lines.drop(maxOf(0, first - LINES_BEFORE))) {
                    if (count >= MAX_WORDS) break
                    val text = l.text.trim()
                    if (text.isEmpty()) continue
                    add(letters.getValue(l.speaker ?: "?") to text)
                    count += text.split(Regex("\\s+")).size
                }
            }
            Voice(id, conv.id, key, personId, letters.getValue(key), window, conv.sounds.take(8), n)
        }
    }

    fun request(voices: List<Voice>): JsonArray = buildJsonArray {
        for (v in voices) add(buildJsonObject {
            put("id", v.id); put("target", v.target)
            putJsonArray("lines") { for ((who, text) in v.lines) add(buildJsonObject { put("speaker", who); put("text", text) }) }
            putJsonArray("sounds") { v.sounds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        })
    }

    /** What to do with an answer: mark it a TV, offer it, or leave it. */
    fun outcome(broadcast: Double): String = when {
        broadcast >= MARK -> "marked"
        broadcast >= SUGGEST -> "suggested"
        else -> "live"
    }

    /** One pass; returns how many voices it marked as TVs. */
    fun run(c: Context): Int {
        if (!on(c) || !HomeServer.enabled(c)) return 0
        if (!HomeServer.canJudgeMedia(c)) {
            CaptureRepository.log("TV check: the home server can't check for TVs (unreachable, or no TypeSafe key there)")
            return 0
        }
        val archive = Archive(c); val store = SpeakerStore(c)
        try {
            val todo = HeavyWork.run("TV check: finding voices") { find(c, archive, store) }
            if (todo.isEmpty()) { CaptureRepository.log("TV check: no new voices to ask about"); return 0 }
            CaptureRepository.log("TV check: asking about ${todo.size} voices")
            var marked = 0; var suggested = 0; var asked = 0
            val touched = mutableSetOf<Long>()
            val spans = mutableListOf<Pair<Double, Double>>()
            for (batch in todo.chunked(PER_REQUEST)) {
                val reply = runCatching { HomeServer.judgeMedia(c, request(batch)) }.logged("TV check: asking the home server")
                val answers = reply.getOrNull()?.get("answers")?.jsonObject ?: break
                HeavyWork.run("TV check: labeling") {
                    for (v in batch) {
                        val a = answers[v.id]?.jsonObject ?: continue
                        val p = a["broadcast"]?.jsonPrimitive?.doubleOrNull ?: continue
                        val medium = a["medium"]?.jsonPrimitive?.contentOrNull
                        val what = outcome(p)
                        asked++
                        if (what == "marked") {
                            archive.spanOf(v.conversation)?.let { spans += it }
                            touched += mark(c, store, archive, v)
                            marked++
                        } else if (what == "suggested") suggested++
                        store.setMediaAnswer(v.id, p, medium, what)
                    }
                }
            }
            if (asked > 0) CaptureRepository.log("TV check: $asked voices asked about, $marked marked as TV, video or radio, $suggested suggested")
            if (marked > 0) HeavyWork.run("TV check: regrouping") {
                for (span in spans) runCatching { archive.sync(store, people = touched, span = span) }.logged("TV check: regrouping")
            }
            return marked
        } finally { archive.close(); store.close() }
    }

    /** Voices to ask about, newest conversations first. */
    private fun find(c: Context, archive: Archive, store: SpeakerStore): List<Voice> {
        val now = System.currentTimeMillis() / 1000.0
        val asked = store.mediaAnswers().keys.toMutableSet()
        val people = store.people().associateBy { it.id }
        val owner = AssistantPrefs.owner(c)
        fun open(key: String, personId: Long?): Boolean = when {
            key == Archive.BOSWELL -> false
            key.startsWith("v") -> true
            personId == null || personId == owner -> false
            else -> people[personId]?.let { it.name == null && it.kind == null } ?: false
        }
        val out = mutableListOf<Voice>()
        for ((day, _) in archive.days().sortedByDescending { it.first }) {
            for (conv in archive.conversations(day).sortedByDescending { it.started }) {
                // Finished: a conversation still going may grow, and its voices with it.
                if (now - conv.ended <= Archive.CONVERSATION_GAP * 3) continue
                val seen = "c${conv.id}:${conv.clips}"
                if (seen in asked) continue
                val found = candidates(conv, archive.lines(conv.id), ::open, asked)
                found.forEach { asked += it.id }
                out += found
                // Nothing left to ask in it as it stands: not read again until it changes.
                if (found.isEmpty()) store.setMediaAnswer(seen, 0.0, null, "seen")
                if (out.size >= PER_RUN) return out.take(PER_RUN)
            }
        }
        return out
    }

    /** Marked a TV as "It's a TV" would, but as the phone's guess (origin auto), not as said by hand. */
    private fun mark(c: Context, store: SpeakerStore, archive: Archive, v: Voice): Set<Long> {
        if (v.personId != null) { store.setKind(v.personId, "media"); return setOf(v.personId) }
        val tv = store.newPerson(null)
        store.setKind(tv, "media")
        return ClipActions.fileSlots(c, store, archive.slotsOf(v.conversation, v.key), tv, null, origin = "auto")
    }

    /** Whether a conversation voice was suggested as a TV, and how surely (null: not). */
    fun suggestion(store: SpeakerStore, conversationKey: String, lines: List<LineRow>): Double? {
        val id = idOf(conversationKey, lines) ?: return null
        return store.mediaAnswer(id)?.takeIf { it.outcome == "suggested" }?.broadcast
    }

    /** "Not a TV" on a suggestion: kept, so it isn't offered again. */
    fun dismiss(store: SpeakerStore, conversationKey: String, lines: List<LineRow>) {
        val id = idOf(conversationKey, lines) ?: return
        val a = store.mediaAnswer(id) ?: return
        store.setMediaAnswer(id, a.broadcast, a.medium, "dismissed")
    }

    private const val WORK = "media-check"

    /** Every 30 minutes, and once now (when the app starts): a conversation already looked at costs nothing. */
    fun schedule(c: Context) {
        val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val wm = WorkManager.getInstance(c)
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MediaCheckWorker>(30, TimeUnit.MINUTES).setConstraints(online).build())
        wm.enqueueUniqueWork("$WORK-now", androidx.work.ExistingWorkPolicy.KEEP,
            androidx.work.OneTimeWorkRequestBuilder<MediaCheckWorker>().setConstraints(online).build())
    }
}

class MediaCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { MediaCheck.run(applicationContext) }.logged("TV check")
        Result.success()
    }
}
