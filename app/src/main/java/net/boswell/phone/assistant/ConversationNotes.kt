package net.boswell.phone.assistant

import android.content.Context
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.boswell.phone.archive.Archive
import net.boswell.phone.speakers.SpeakerStore
import java.time.LocalDate

/**
 * A short title and a one-sentence summary for each finished conversation,
 * so a day reads as "Planning Saturday's climb with Sam" instead of its first
 * few words. One small model call per conversation (about a tenth of a cent),
 * made again only if the conversation grows. Run with the assistant's routines.
 */
object ConversationNotes {
    /** At most this many a run, newest first, so a big backlog spreads out. */
    private const val PER_RUN = 12
    private const val MIN_LINES = 3
    private const val MIN_SECONDS = 60.0
    /** Notes made before the current prompt are made again (2026-10-01: "you", not the owner's name). */
    private const val PROMPT_SINCE = 1_790_885_000.0

    fun run(c: Context): Int {
        if (!AssistantPrefs.titles(c)) return 0
        val key = Secrets.get(c, Secrets.OPENROUTER) ?: return 0
        val now = System.currentTimeMillis() / 1000.0
        val archive = Archive(c); val speakers = SpeakerStore(c); val store = AssistantStore(c)
        var made = 0
        try {
            val days = listOf(LocalDate.now(), LocalDate.now().minusDays(1), LocalDate.now().minusDays(2))
            val convs = days.flatMap { archive.conversations(it) }
                // Finished: nothing new for a while, so the title won't be stale in a minute.
                .filter { now - it.ended > Archive.CONVERSATION_GAP * 3 }
                // A minute or more: shorter ones read fine from their opening words.
                .filter { it.ended - it.started >= MIN_SECONDS }
            val notes = store.notes(convs.map { it.id })
            for (conv in convs.filter { notes[it.id]?.let { n -> n.clips != it.clips || n.made < PROMPT_SINCE } ?: true }.take(PER_RUN)) {
                val text = Assistant(c).lines(archive, speakers, conv.started - 1, conv.ended + 1)
                if (text.lines().count { it.isNotBlank() } < MIN_LINES) continue
                val reply = runCatching {
                    Llm(key, AssistantPrefs.model(c)).chat(listOf(
                        Llm.system("Title and summarize this conversation for the user's own diary. Lines marked (me) are the user. Reply with JSON only: " +
                            "{\"title\":\"at most 7 words, about the topic, e.g. Planning Saturday's climb with Sam\"," +
                            "\"summary\":\"one sentence, at most 25 words, what was said or decided, e.g. You agreed to meet Sam at the trailhead at 8\"}. " +
                            "Never use the user's name: in the summary they are 'you', and the title needs no subject. Name other people as the transcript does; " +
                            "leave out unknown speakers rather than calling them 'someone'. If it's only the user talking, it's notes or thinking aloud: title the topic. " +
                            "If it's TV, music or chatter with nothing to it, say so plainly."),
                        Llm.user(text.take(8_000))), maxTokens = 400, temperature = 0.2)
                }
                store.logCall("titles", AssistantPrefs.model(c), reply.getOrNull(), reply.exceptionOrNull()?.message)
                val body = reply.getOrNull()?.text ?: continue
                val o = runCatching { Llm.json.parseToJsonElement(body.substring(body.indexOf('{'), body.lastIndexOf('}') + 1)).jsonObject }.getOrNull() ?: continue
                val title = o["title"]?.jsonPrimitive?.contentOrNull?.trim()?.trim('"')?.takeIf { it.isNotBlank() } ?: continue
                val summary = o["summary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                store.setNote(conv.id, conv.clips, SpeechText.plain(title).take(80), SpeechText.plain(summary).take(240))
                made++
            }
        } finally { archive.close(); speakers.close(); store.close() }
        return made
    }
}
