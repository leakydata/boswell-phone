package net.boswell.phone.assistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.ZoneId

data class Exchange(val id: Long, val at: Double, val source: String, val question: String?, val answer: String, val cost: Double, val error: Boolean,
                    /** When the question was asked (wall clock): a spoken one from its first word to its last, a typed one when sent. Null before these were kept. */
                    val askedFrom: Double? = null, val askedTo: Double? = null)
data class Bookmark(val id: Long, val at: Double, val note: String?)

/**
 * What the assistant said and what it cost. Every call to a model is logged
 * here -- when, why, which model, tokens, cost -- so what left the phone is
 * always visible, and the daily budget has something to count.
 */
class AssistantStore(context: Context) : SQLiteOpenHelper(context, "assistant.db", null, 5) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE calls (id INTEGER PRIMARY KEY, at REAL, purpose TEXT, model TEXT, prompt_tokens INTEGER, completion_tokens INTEGER, cost REAL, error TEXT)")
        db.execSQL("CREATE TABLE exchanges (id INTEGER PRIMARY KEY, at REAL, source TEXT, question TEXT, answer TEXT, cost REAL, error INTEGER, asked_from REAL, asked_to REAL)")
        db.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY, at REAL, note TEXT)")
        db.execSQL(TRIGGER_HITS)
        db.execSQL(NOTES)
        db.execSQL(SPOKEN)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL(TRIGGER_HITS)
        if (oldVersion < 3) db.execSQL(NOTES)
        if (oldVersion < 4) db.execSQL(SPOKEN)
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE exchanges ADD COLUMN asked_from REAL")
            db.execSQL("ALTER TABLE exchanges ADD COLUMN asked_to REAL")
        }
    }

    /**
     * Something Boswell said aloud: when (wall clock) and the exact text, so
     * its voice can be told apart in recordings transcribed later. Kept for
     * [SPOKEN_DAYS], longer than any recording waits to be transcribed.
     */
    fun addSpoken(started: Double, ended: Double, text: String, voice: String?): Long {
        writableDatabase.execSQL("DELETE FROM spoken WHERE started < ?", arrayOf<Any>(now() - SPOKEN_DAYS * 86_400.0))
        return writableDatabase.insert("spoken", null, ContentValues().apply {
            put("started", started); put("ended", ended); put("text", text); put("voice", voice)
        })
    }

    /** The utterance finished (or was cut off) at [ended]. */
    fun endSpoken(id: Long, ended: Double) =
        writableDatabase.execSQL("UPDATE spoken SET ended = ? WHERE id = ?", arrayOf<Any>(ended, id))

    /** The text-to-speech voice Boswell last spoke with, up to [before]: whose learned voice to listen for. */
    fun lastVoice(before: Double): String? = readableDatabase.rawQuery(
        "SELECT voice FROM spoken WHERE started <= ? AND voice IS NOT NULL ORDER BY started DESC LIMIT 1", arrayOf(before.toString())).use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

    /** What Boswell said overlapping [from, to), in order. */
    fun spoken(from: Double, to: Double): List<net.boswell.phone.process.BoswellLines.Spoken> = readableDatabase.rawQuery(
        "SELECT started, ended, text, voice FROM spoken WHERE ended >= ? AND started < ? ORDER BY started", arrayOf(from.toString(), to.toString())).use { c ->
        buildList { while (c.moveToNext()) add(net.boswell.phone.process.BoswellLines.Spoken(c.getDouble(0), c.getDouble(1), c.getString(2), if (c.isNull(3)) null else c.getString(3))) }
    }

    /** A conversation's title and summary, made when it had [clips] recordings (more later means make it again). */
    data class Note(val title: String, val summary: String, val clips: Int, val made: Double = 0.0)

    fun setNote(conversation: Long, clips: Int, title: String, summary: String) {
        writableDatabase.insertWithOnConflict("conv_notes", null, ContentValues().apply {
            put("id", conversation); put("clips", clips); put("title", title); put("summary", summary); put("made", now())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun notes(ids: Collection<Long>): Map<Long, Note> {
        if (ids.isEmpty()) return emptyMap()
        return readableDatabase.rawQuery("SELECT id, title, summary, clips, made FROM conv_notes WHERE id IN (${ids.joinToString(",")})", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), Note(c.getString(1), c.getString(2), c.getInt(3), c.getDouble(4))) }
        }
    }

    /** True the first time a (clip, line, trigger) is seen; a line never fires the same trigger twice. */
    fun claimTrigger(clip: String, start: Double, trigger: Long): Boolean =
        writableDatabase.insertWithOnConflict("trigger_hits", null, ContentValues().apply {
            put("clip", clip); put("start", start); put("trigger", trigger); put("at", now())
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L

    private fun now() = System.currentTimeMillis() / 1000.0

    /** [model] is what was asked for; the reply's own model wins (it says "home: …" when the home server answered). */
    fun logCall(purpose: String, model: String, reply: LlmReply?, error: String? = null) {
        writableDatabase.insert("calls", null, ContentValues().apply {
            put("at", now()); put("purpose", purpose); put("model", reply?.model?.takeIf { it.isNotEmpty() } ?: model)
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

    /** Calls answered by the home server since [from] (logged as "home: <model>", free). */
    fun homeCalls(from: Double): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM calls WHERE at >= ? AND model LIKE 'home%'", arrayOf(from.toString())).use { c -> c.moveToFirst(); c.getInt(0) }

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

    fun addExchange(source: String, question: String?, answer: String, cost: Double, error: Boolean = false, asked: Pair<Double, Double>? = null): Long =
        writableDatabase.insert("exchanges", null, ContentValues().apply {
            put("at", now()); put("source", source); put("question", question); put("answer", answer); put("cost", cost); put("error", if (error) 1 else 0)
            asked?.let { put("asked_from", it.first); put("asked_to", it.second) }
        })

    fun exchanges(limit: Int = 100): List<Exchange> = readableDatabase.rawQuery(
        "SELECT $EXCHANGE FROM exchanges ORDER BY at DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
        buildList { while (c.moveToNext()) add(c.toExchange()) }
    }

    /** Questions and answers since [from], oldest first. */
    fun exchangesSince(from: Double): List<Exchange> = readableDatabase.rawQuery(
        "SELECT $EXCHANGE FROM exchanges WHERE at >= ? ORDER BY at", arrayOf(from.toString())).use { c ->
        buildList { while (c.moveToNext()) add(c.toExchange()) }
    }

    private fun android.database.Cursor.toExchange() = Exchange(getLong(0), getDouble(1), getString(2), if (isNull(3)) null else getString(3), getString(4),
        getDouble(5), getInt(6) == 1, if (isNull(7)) null else getDouble(7), if (isNull(8)) null else getDouble(8))

    /** Every past question and answer, gone (what they cost stays in the usage log). */
    fun clearExchanges() = writableDatabase.execSQL("DELETE FROM exchanges")

    fun addBookmark(at: Double, note: String? = null): Long =
        writableDatabase.insert("bookmarks", null, ContentValues().apply { put("at", at); put("note", note) })

    fun bookmarks(from: Double, to: Double): List<Bookmark> = readableDatabase.rawQuery(
        "SELECT id, at, note FROM bookmarks WHERE at >= ? AND at < ? ORDER BY at", arrayOf(from.toString(), to.toString())).use { c ->
        buildList { while (c.moveToNext()) add(Bookmark(c.getLong(0), c.getDouble(1), if (c.isNull(2)) null else c.getString(2))) }
    }
}

private const val TRIGGER_HITS = "CREATE TABLE IF NOT EXISTS trigger_hits (clip TEXT, start REAL, trigger INTEGER, at REAL, PRIMARY KEY (clip, start, trigger))"
private const val NOTES = "CREATE TABLE IF NOT EXISTS conv_notes (id INTEGER PRIMARY KEY, clips INTEGER, title TEXT, summary TEXT, made REAL)"
private const val SPOKEN = "CREATE TABLE IF NOT EXISTS spoken (id INTEGER PRIMARY KEY, started REAL, ended REAL, text TEXT, voice TEXT)"
private const val SPOKEN_DAYS = 30
private const val EXCHANGE = "id, at, source, question, answer, cost, error, asked_from, asked_to"

/** Assistant settings. The API key itself lives in [Secrets]. */
object AssistantPrefs {
    private fun p(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)

    fun model(c: Context): String = p(c).getString("llm_model", null) ?: Llm.DEFAULT_MODEL
    fun setModel(c: Context, m: String) = p(c).edit().putString("llm_model", m.trim().ifEmpty { Llm.DEFAULT_MODEL }).apply()

    /** Where the AI runs: OpenRouter, or the model on the paired home server (free, and nothing leaves the house). */
    enum class Where { OPENROUTER, HOME }
    /** When home can't answer: ask OpenRouter instead, or skip (the question fails, background work waits for next time). */
    enum class HomeFallback { OPENROUTER, SKIP }

    /** Home only while a home server is paired; unpairing quietly means OpenRouter again. */
    fun where(c: Context): Where =
        if (!net.boswell.phone.home.HomeServer.paired(c)) Where.OPENROUTER
        else runCatching { Where.valueOf(p(c).getString("ai_where", null)!!) }.getOrDefault(Where.OPENROUTER)
    fun setWhere(c: Context, w: Where) = p(c).edit().putString("ai_where", w.name).apply()
    fun homeFallback(c: Context): HomeFallback = runCatching { HomeFallback.valueOf(p(c).getString("ai_home_fallback", null)!!) }.getOrDefault(HomeFallback.OPENROUTER)
    fun setHomeFallback(c: Context, f: HomeFallback) = p(c).edit().putString("ai_home_fallback", f.name).apply()

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
    /** A title and one-line summary for each finished conversation (Today). */
    fun titles(c: Context) = p(c).getBoolean("conv_titles", true)
    fun setTitles(c: Context, on: Boolean) = p(c).edit().putBoolean("conv_titles", on).apply()

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
