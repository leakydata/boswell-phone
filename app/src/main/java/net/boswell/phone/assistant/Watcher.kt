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
            // Only when the owner has said something since the last look.
            val newest = archive.readableDatabase.rawQuery(
                "SELECT MAX(l.t0) FROM lines l JOIN clip_speakers s ON s.clip = l.clip AND s.label = l.label WHERE s.conv_key = ? AND l.t0 > ?",
                arrayOf("p$owner", lastOwnerLine.toString())).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0) else null } ?: return
            lastOwnerLine = newest
            val me = speakers.nameOf(owner) ?: "the user"
            val context15 = Assistant(context).lines(archive, speakers, System.currentTimeMillis() / 1000.0 - 15 * 60)
            val model = llm.name
            val prompt = """
                You quietly follow ${me}'s conversations through their phone and may offer ONE short, genuinely useful hint as a notification: a fact they seem to be reaching for, a correction of something clearly wrong, a reminder of something they said they'd do, or a helpful next step. Lines marked (me) are ${me}.
                Most of the time there is nothing worth interrupting for. Default to silence. Never comment on the conversation itself, never be chatty, never repeat a hint.
                Reply with JSON only: {"notify": false} or {"notify": true, "title": "3-6 words", "text": "one or two sentences"}.

                The last 15 minutes:
                $context15
            """.trimIndent()
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
