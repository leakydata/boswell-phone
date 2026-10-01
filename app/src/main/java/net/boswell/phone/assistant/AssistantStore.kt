package net.boswell.phone.assistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.ZoneId

data class Exchange(val id: Long, val at: Double, val source: String, val question: String?, val answer: String, val cost: Double, val error: Boolean)
data class Bookmark(val id: Long, val at: Double, val note: String?)

/**
 * What the assistant said and what it cost. Every call to a model is logged
 * here -- when, why, which model, tokens, cost -- so what left the phone is
 * always visible, and the daily budget has something to count.
 */
class AssistantStore(context: Context) : SQLiteOpenHelper(context, "assistant.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE calls (id INTEGER PRIMARY KEY, at REAL, purpose TEXT, model TEXT, prompt_tokens INTEGER, completion_tokens INTEGER, cost REAL, error TEXT)")
        db.execSQL("CREATE TABLE exchanges (id INTEGER PRIMARY KEY, at REAL, source TEXT, question TEXT, answer TEXT, cost REAL, error INTEGER)")
        db.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY, at REAL, note TEXT)")
        db.execSQL(TRIGGER_HITS)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL(TRIGGER_HITS)
    }

    /** True the first time a (clip, line, trigger) is seen; a line never fires the same trigger twice. */
    fun claimTrigger(clip: String, start: Double, trigger: Long): Boolean =
        writableDatabase.insertWithOnConflict("trigger_hits", null, ContentValues().apply {
            put("clip", clip); put("start", start); put("trigger", trigger); put("at", now())
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L

    private fun now() = System.currentTimeMillis() / 1000.0

    fun logCall(purpose: String, model: String, reply: LlmReply?, error: String? = null) {
        writableDatabase.insert("calls", null, ContentValues().apply {
            put("at", now()); put("purpose", purpose); put("model", model)
            put("prompt_tokens", reply?.promptTokens ?: 0); put("completion_tokens", reply?.completionTokens ?: 0)
            put("cost", reply?.cost ?: 0.0); put("error", error)
        })
    }

    fun spentToday(purpose: String? = null): Double {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond().toDouble()
        val sql = "SELECT COALESCE(SUM(cost), 0) FROM calls WHERE at >= ?" + if (purpose != null) " AND purpose = ?" else ""
        val args = listOfNotNull(start.toString(), purpose).toTypedArray()
        return readableDatabase.rawQuery(sql, args).use { c -> c.moveToFirst(); c.getDouble(0) }
    }

    data class Usage(val calls: Int, val cost: Double, val tokens: Long, val errors: Int)

    fun usage(from: Double, to: Double = Double.MAX_VALUE): Usage = readableDatabase.rawQuery(
        "SELECT COUNT(*), COALESCE(SUM(cost),0), COALESCE(SUM(prompt_tokens + completion_tokens),0), SUM(error IS NOT NULL) FROM calls WHERE at >= ? AND at < ?",
        arrayOf(from.toString(), to.toString())).use { c -> c.moveToFirst(); Usage(c.getInt(0), c.getDouble(1), c.getLong(2), c.getInt(3)) }

    /** Cost and call count per purpose since [from], most expensive first. */
    fun byPurpose(from: Double): List<Triple<String, Int, Double>> = readableDatabase.rawQuery(
        "SELECT purpose, COUNT(*), COALESCE(SUM(cost),0) FROM calls WHERE at >= ? GROUP BY purpose ORDER BY SUM(cost) DESC",
        arrayOf(from.toString())).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getInt(1), c.getDouble(2))) } }

    /** Cost per local day for the last [days] days, oldest first, zeros included. */
    fun perDay(days: Int): List<Pair<LocalDate, Double>> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        return (days - 1 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            val from = d.atStartOfDay(zone).toEpochSecond().toDouble()
            d to usage(from, d.plusDays(1).atStartOfDay(zone).toEpochSecond().toDouble()).cost
        }
    }

    fun addExchange(source: String, question: String?, answer: String, cost: Double, error: Boolean = false): Long =
        writableDatabase.insert("exchanges", null, ContentValues().apply {
            put("at", now()); put("source", source); put("question", question); put("answer", answer); put("cost", cost); put("error", if (error) 1 else 0)
        })

    fun exchanges(limit: Int = 100): List<Exchange> = readableDatabase.rawQuery(
        "SELECT id, at, source, question, answer, cost, error FROM exchanges ORDER BY at DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
        buildList { while (c.moveToNext()) add(Exchange(c.getLong(0), c.getDouble(1), c.getString(2), if (c.isNull(3)) null else c.getString(3), c.getString(4), c.getDouble(5), c.getInt(6) == 1)) }
    }

    fun addBookmark(at: Double, note: String? = null): Long =
        writableDatabase.insert("bookmarks", null, ContentValues().apply { put("at", at); put("note", note) })

    fun bookmarks(from: Double, to: Double): List<Bookmark> = readableDatabase.rawQuery(
        "SELECT id, at, note FROM bookmarks WHERE at >= ? AND at < ? ORDER BY at", arrayOf(from.toString(), to.toString())).use { c ->
        buildList { while (c.moveToNext()) add(Bookmark(c.getLong(0), c.getDouble(1), if (c.isNull(2)) null else c.getString(2))) }
    }
}

private const val TRIGGER_HITS = "CREATE TABLE IF NOT EXISTS trigger_hits (clip TEXT, start REAL, trigger INTEGER, at REAL, PRIMARY KEY (clip, start, trigger))"

/** Assistant settings. The API key itself lives in [Secrets]. */
object AssistantPrefs {
    private fun p(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)

    fun model(c: Context): String = p(c).getString("llm_model", null) ?: Llm.DEFAULT_MODEL
    fun setModel(c: Context, m: String) = p(c).edit().putString("llm_model", m.trim().ifEmpty { Llm.DEFAULT_MODEL }).apply()

    /** The person who is "me": the watcher listens for them, answers address them. */
    fun owner(c: Context): Long? = p(c).getLong("owner_person", -1).takeIf { it >= 0 }
    fun setOwner(c: Context, id: Long?) = p(c).edit().putLong("owner_person", id ?: -1).apply()

    fun voice(c: Context) = p(c).getBoolean("voice_answers", false)
    fun setVoice(c: Context, on: Boolean) = p(c).edit().putBoolean("voice_answers", on).apply()

    /** The text-to-speech voice for spoken answers (a TextToSpeech voice name), or null for the system default. */
    fun ttsVoice(c: Context): String? = p(c).getString("tts_voice", null)
    fun setTtsVoice(c: Context, name: String?) = p(c).edit().putString("tts_voice", name).apply()

    /** Button questions transcribed by Parakeet in the cloud (the phone if it can't). On unless turned off. */
    fun cloudQuestions(c: Context) = p(c).getBoolean("cloud_questions", true)
    fun setCloudQuestions(c: Context, on: Boolean) = p(c).edit().putBoolean("cloud_questions", on).apply()

    /** "New topic": earlier exchanges stop being carried into new questions from this moment. */
    fun topicSince(c: Context): Double = p(c).getLong("topic_since", 0L).toDouble()
    fun newTopic(c: Context) = p(c).edit().putLong("topic_since", System.currentTimeMillis() / 1000).apply()

    /** The assistant may look things up on the web (OpenRouter's web plugin, a few cents a search). */
    fun webSearch(c: Context) = p(c).getBoolean("web_search", true)
    fun setWebSearch(c: Context, on: Boolean) = p(c).edit().putBoolean("web_search", on).apply()

    /** Morning brief and evening recap: the hour of day, or -1 for off. */
    fun briefHour(c: Context) = p(c).getInt("brief_hour", 8)
    fun setBriefHour(c: Context, h: Int) = p(c).edit().putInt("brief_hour", h).apply()
    fun recapHour(c: Context) = p(c).getInt("recap_hour", 21)
    fun setRecapHour(c: Context, h: Int) = p(c).edit().putInt("recap_hour", h).apply()

    /** Notice promises in what was said and file them as to-dos. */
    fun promises(c: Context) = p(c).getBoolean("promises", true)
    fun setPromises(c: Context, on: Boolean) = p(c).edit().putBoolean("promises", on).apply()

    /** A short brief before calendar events. */
    fun meetingBriefs(c: Context) = p(c).getBoolean("meeting_briefs", true)
    fun setMeetingBriefs(c: Context, on: Boolean) = p(c).edit().putBoolean("meeting_briefs", on).apply()

    fun watcher(c: Context) = p(c).getBoolean("watcher", false)
    fun setWatcher(c: Context, on: Boolean) = p(c).edit().putBoolean("watcher", on).apply()

    /** Dollars per day the watcher may spend. Questions you ask are never cut off by it. */
    fun budget(c: Context): Double = p(c).getFloat("watcher_budget", 0.50f).toDouble()
    fun setBudget(c: Context, d: Double) = p(c).edit().putFloat("watcher_budget", d.toFloat()).apply()

    enum class DoubleTap { TODO, BOOKMARK, SUMMARIZE }
    fun doubleTap(c: Context): DoubleTap = runCatching { DoubleTap.valueOf(p(c).getString("double_tap", "TODO")!!) }.getOrDefault(DoubleTap.TODO)
    fun setDoubleTap(c: Context, d: DoubleTap) = p(c).edit().putString("double_tap", d.name).apply()
}
