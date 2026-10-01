package net.boswell.phone.assistant

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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.boswell.phone.archive.Archive
import net.boswell.phone.speakers.SpeakerStore
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * The assistant's own routines, checked every quarter hour:
 *
 *  - a morning brief at the chosen hour (today's calendar, what's due, loose
 *    ends from yesterday) and an evening recap (who, what was decided, what
 *    was promised);
 *  - promises and facts noticed in what was said since the last look (every
 *    few hours): promises become to-dos in "Promises", facts are remembered;
 *  - a short brief before each calendar event, from what was last said about
 *    its people or topic.
 *
 * Each happens once (LifeStore.markSeen), and each is a setting.
 */
class RoutinesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val c = applicationContext
    private fun now() = System.currentTimeMillis() / 1000.0

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!Assistant(c).ready()) return@withContext Result.success()
        val life = LifeStore(c)
        try {
            val hour = LocalTime.now().hour
            val today = LocalDate.now().toString()
            AssistantPrefs.briefHour(c).takeIf { it >= 0 && hour >= it && hour < it + 3 }?.let {
                if (life.markSeen("brief:$today")) brief(morning = true)
            }
            AssistantPrefs.recapHour(c).takeIf { it >= 0 && hour >= it && hour < it + 3 }?.let {
                if (life.markSeen("recap:$today")) brief(morning = false)
            }
            if (AssistantPrefs.promises(c)) notice(life)
            if (AssistantPrefs.meetingBriefs(c)) meetings(life)
            runCatching { ConversationNotes.run(c) }
        } finally { life.close() }
        Result.success()
    }

    private fun brief(morning: Boolean) {
        val q = if (morning)
            "Morning brief. Using calendar_events for today, list_todos (what's due today or overdue) and what was said yesterday (day_conversations for yesterday), " +
                "give me: today's schedule, what's due, and any loose ends or promises from yesterday. Short, scannable, at most 6 lines."
        else
            "Evening recap of today. Using day_conversations for today (its summaries first; read_conversation only where a detail matters) and list_todos: who I talked with, what was decided, " +
                "what I promised and what others promised me, and anything I should do tomorrow. Short, scannable, at most 7 lines; cite moments."
        val a = Assistant(c).ask(q, if (morning) "brief" else "recap", display = if (morning) "Morning brief" else "Evening recap")
        if (!a.error && !a.text.startsWith("I don't have an answer")) AssistantNotify.post(c, AssistantNotify.ANSWERS, if (morning) "Good morning" else "Today, in short", a.text)
    }

    /**
     * Promises and facts in what was said since the last look. One plain
     * model call over the new lines (no tools), JSON out. Every few hours, and
     * only when there's something new to read.
     */
    private fun notice(life: LifeStore) {
        val prefs = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)
        val last = prefs.getLong("noticed_until", (now() - 3 * 3600).toLong()).toDouble()
        if (now() - last < NOTICE_EVERY_S) return
        val archive = Archive(c); val speakers = SpeakerStore(c)
        val text = try { Assistant(c).lines(archive, speakers, last, now()) } finally { archive.close(); speakers.close() }
        prefs.edit().putLong("noticed_until", now().toLong()).apply()
        if (text.lines().count { it.isNotBlank() } < 3) return
        val key = Secrets.get(c, Secrets.OPENROUTER) ?: return
        val model = AssistantPrefs.model(c)
        val me = AssistantPrefs.owner(c)?.let { id -> SpeakerStore(c).use { it.nameOf(id) } } ?: "the user"
        val reply = runCatching {
            Llm(key, model).chat(listOf(
                Llm.system("From this transcript (lines marked (me) are $me), extract JSON only, no prose: " +
                    "{\"promises\":[{\"by\":\"me\" or a name,\"to\":\"me\" or a name,\"what\":\"short, actionable\",\"due\":\"YYYY-MM-DDTHH:MM or null\"}]," +
                    "\"facts\":[{\"person\":\"name\",\"fact\":\"lasting fact: family, birthday, job, likes, plans\"}]}. " +
                    "Only clear commitments to do something (\"I'll send it Friday\", \"can you call me back\" agreed to), not chatter or TV. " +
                    "Only facts about named people other than $me, stated plainly. Empty lists if none. Today is ${LocalDate.now()}."),
                Llm.user(text.take(12_000))), maxTokens = 700, temperature = 0.1)
        }
        AssistantStore(c).use { it.logCall("notice", model, reply.getOrNull(), reply.exceptionOrNull()?.message) }
        val body = reply.getOrNull()?.text ?: return
        val obj = runCatching { Llm.json.parseToJsonElement(body.substringAfter("```json").substringAfter("```").substringBeforeLast("```").trim().let {
            if (it.startsWith("{")) it else body.substring(body.indexOf('{'), body.lastIndexOf('}') + 1) }).jsonObject }.getOrNull() ?: return
        val todos = net.boswell.phone.todo.TodoStore(c)
        try {
            for (p in (obj["promises"] as? JsonArray).orEmpty()) {
                val o = p.jsonObject
                val what = o["what"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty { continue }
                val by = o["by"]?.jsonPrimitive?.contentOrNull ?: "me"
                val to = o["to"]?.jsonPrimitive?.contentOrNull
                val text = if (by.equals("me", true)) what + (to?.takeIf { !it.equals("me", true) }?.let { " (for $it)" } ?: "")
                    else "$by: $what" + (if (to.equals("me", true)) " (promised to you)" else "")
                if (!life.markSeen("promise:" + text.lowercase().filter { it.isLetterOrDigit() })) continue
                val due = o["due"]?.jsonPrimitive?.contentOrNull?.let { d -> runCatching {
                    java.time.LocalDateTime.parse(d.take(16)).atZone(java.time.ZoneId.systemDefault()).toEpochSecond().toDouble() }.getOrNull() }
                val id = todos.add(text, "Promises", due, "noticed")
                due?.let { net.boswell.phone.todo.TodoReminders.schedule(c, id, it) }
            }
        } finally { todos.close() }
        for (f in (obj["facts"] as? JsonArray).orEmpty()) {
            val o = f.jsonObject
            val person = o["person"]?.jsonPrimitive?.contentOrNull ?: continue
            val fact = o["fact"]?.jsonPrimitive?.contentOrNull ?: continue
            life.addFact(person, fact)
        }
    }

    /** A brief for each event starting in the next 10-45 minutes, once. */
    private fun meetings(life: LifeStore) {
        val nowMs = System.currentTimeMillis()
        for (e in net.boswell.phone.todo.Calendar.events(c, nowMs + 10 * 60_000, nowMs + 45 * 60_000, respectShow = false)) {
            if (e.allDay || e.begin < nowMs + 10 * 60_000) continue
            if (!life.markSeen("meeting:${e.title}:${e.begin}")) continue
            val whenText = java.time.Instant.ofEpochMilli(e.begin).atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString()
            val a = Assistant(c).ask("Brief me for \"${e.title}\" at $whenText. Search what was said recently with or about the people or topic in it " +
                "(search_transcripts, facts_about, list_todos). 2-4 short lines; if there is nothing relevant, say only what and when.", "meeting",
                display = "Before ${e.title}")
            if (!a.error && !a.text.startsWith("I don't have an answer")) AssistantNotify.post(c, AssistantNotify.ANSWERS, "Before ${e.title} ($whenText)", a.text)
        }
    }

    companion object {
        const val NOTICE_EVERY_S = 3 * 3600
        private const val WORK = "assistant-routines"

        fun schedule(c: Context) {
            WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RoutinesWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
