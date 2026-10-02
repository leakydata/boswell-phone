package net.boswell.phone.archive

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.json.Json
import net.boswell.phone.capture.CaptureService
import net.boswell.phone.capture.ClipTimes
import net.boswell.phone.process.ProcessingWorker
import net.boswell.phone.process.Transcript
import net.boswell.phone.process.TranscriptJson
import net.boswell.phone.sound.Sounds
import net.boswell.phone.speakers.Matching
import net.boswell.phone.speakers.SpeakerStore
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ClipRow(val name: String, val started: Double, val ended: Double, val speech: Boolean, val verdict: String?,
                   val audio: Boolean, val conversation: Long?, val topSound: String?, val bytes: Long)

data class Conversation(val id: Long, val started: Double, val ended: Double, val clips: Int, val speechSeconds: Double,
                        val speakers: List<String>, val snippet: String, val sounds: List<String>,
                        /** From the assistant (ConversationNotes), when made. */
                        val title: String? = null, val summary: String? = null)

data class LineRow(val id: Long, val clip: String, val t0: Double, val t1: Double, val offset: Double,
                   val speaker: String?, val personId: Long?, val text: String, val original: String? = null,
                   /** Transcribed in the cloud (Parakeet) rather than on the phone. */
                   val cloud: Boolean = false)

data class SearchHit(val line: LineRow, val conversation: Long?, val snippet: String)

/**
 * The phone's index of its archive: clips, the speakers in each, lines with
 * full-text search, sounds, and conversations.
 *
 * Built from the files -- the clip sidecars and transcript JSON stay the
 * source of truth -- so it can always be thrown away and rebuilt, and nothing
 * here is the only copy of anything.
 *
 * A conversation is a run of clips with speech in them, each starting within
 * CONVERSATION_GAP of the previous one ending: the desktop's measured value,
 * after 300 s turned an evening into one 183-minute "conversation".
 */
class Archive(private val context: Context) : SQLiteOpenHelper(context, "archive.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE clips (
            name TEXT PRIMARY KEY, started REAL, ended REAL, seconds REAL, time_known INTEGER,
            speech INTEGER, verdict TEXT, audio INTEGER, bytes INTEGER, conversation INTEGER,
            top_sound TEXT, indexed_mtime INTEGER, cloud INTEGER)""")
        db.execSQL("CREATE INDEX clips_started ON clips(started)")
        db.execSQL("CREATE INDEX clips_conv ON clips(conversation)")
        db.execSQL("""CREATE TABLE clip_speakers (
            clip TEXT, label TEXT, person_id INTEGER, seconds REAL, decision TEXT, score REAL,
            candidate_id INTEGER, candidate_score REAL, emb BLOB, conv_key TEXT,
            PRIMARY KEY (clip, label))""")
        db.execSQL("""CREATE TABLE lines (
            id INTEGER PRIMARY KEY, clip TEXT, t0 REAL, t1 REAL, offset REAL, label TEXT, text TEXT, original TEXT)""")
        db.execSQL("CREATE INDEX lines_clip ON lines(clip)")
        db.execSQL("CREATE INDEX lines_t0 ON lines(t0)")
        db.execSQL("CREATE VIRTUAL TABLE lines_fts USING fts4(text)")
        db.execSQL("CREATE TABLE sounds (clip TEXT, label TEXT, score REAL, t REAL)")
        db.execSQL("CREATE INDEX sounds_clip ON sounds(clip)")
        db.execSQL("""CREATE TABLE conversations (
            id INTEGER PRIMARY KEY, started REAL, ended REAL, day TEXT, clips INTEGER,
            speech_seconds REAL, speakers TEXT, snippet TEXT, sounds TEXT)""")
        db.execSQL("CREATE INDEX conv_day ON conversations(day)")
    }

    /** The index is rebuilt from the files, so an upgrade simply starts it over. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        for (t in listOf("clips", "clip_speakers", "lines", "lines_fts", "sounds", "conversations")) db.execSQL("DROP TABLE IF EXISTS $t")
        onCreate(db)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun dayOf(epoch: Double): LocalDate = Instant.ofEpochMilli((epoch * 1000).toLong()).atZone(zone).toLocalDate()

    // ------------------------------------------------------------------ sync

    /**
     * Bring the index up to date with the files: index what is new or has
     * changed, forget what is gone, and rebuild conversations. Cheap when
     * nothing changed -- one stat per file.
     */
    @Synchronized
    fun sync(speakers: SpeakerStore, force: Boolean = false) {
        val clipsDir = CaptureService.clipsDir(context)
        val tDir = ProcessingWorker.transcriptsDir(context)
        val db = writableDatabase
        val known = HashMap<String, Long>()
        db.rawQuery("SELECT name, indexed_mtime FROM clips", null).use { c -> while (c.moveToNext()) known[c.getString(0)] = c.getLong(1) }

        // A clip exists while its sidecar does; its audio may have been cleaned up.
        val sidecars = clipsDir.listFiles { f -> f.extension == "json" }.orEmpty().associateBy { it.nameWithoutExtension + ".wav" }
        var changed = false
        db.beginTransaction()
        try {
            for ((name, side) in sidecars) {
                val t = File(tDir, side.nameWithoutExtension + ".json")
                val wav = File(clipsDir, name)
                // The sound moving from WAV to its compact copy changes the size the index shows.
                val sound = net.boswell.phone.audio.ClipAudio.file(clipsDir, name)
                val stamp = maxOf(side.lastModified(), if (t.exists()) t.lastModified() else 0L) +
                    (if (sound == null) 1 else if (sound.extension == "ogg") 2 else 0)
                if (known[name] == stamp) continue
                index(db, name, side, t, wav, stamp)
                changed = true
            }
            for (name in known.keys - sidecars.keys) {
                forget(db, name)
                changed = true
            }
            if (changed || force) rebuildConversations(db, speakers)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun forget(db: SQLiteDatabase, name: String) {
        db.execSQL("DELETE FROM lines_fts WHERE docid IN (SELECT id FROM lines WHERE clip = ?)", arrayOf(name))
        for (t in listOf("lines", "sounds", "clip_speakers")) db.execSQL("DELETE FROM $t WHERE clip = ?", arrayOf(name))
        db.execSQL("DELETE FROM clips WHERE name = ?", arrayOf(name))
    }

    private fun index(db: SQLiteDatabase, name: String, side: File, tFile: File, wav: File, stamp: Long) {
        forget(db, name)
        val times = runCatching { json.decodeFromString(ClipTimes.serializer(), side.readText()) }.getOrNull() ?: return
        val t = if (tFile.exists()) runCatching { TranscriptJson.json.decodeFromString(Transcript.serializer(), tFile.readText()) }.getOrNull() else null
        val top = t?.sounds?.firstOrNull { it.label !in Sounds.AMBIENT && it.label !in Sounds.VOICE && it.score >= Sounds.EVENT_FLOOR }
        db.insert("clips", null, ContentValues().apply {
            put("name", name); put("started", times.started); put("ended", times.ended); put("seconds", times.seconds)
            put("time_known", if (times.timeKnown) 1 else 0)
            put("speech", if (t?.segments?.isNotEmpty() == true) 1 else 0)
            put("verdict", t?.verdict ?: if (t == null) null else if (t.segments.isNotEmpty()) "keep" else null)
            val sound = net.boswell.phone.audio.ClipAudio.file(wav.parentFile!!, name)
            put("audio", if (sound != null) 1 else 0); put("bytes", sound?.length() ?: 0L)
            put("top_sound", top?.label); put("indexed_mtime", stamp)
            // Whose words: the cloud engine's ("parakeet-… (cloud)") or the phone's.
            put("cloud", if (t?.engine?.contains("(cloud)") == true) 1 else 0)
        })
        t ?: return
        for (seg in t.segments) {
            val id = db.insert("lines", null, ContentValues().apply {
                put("clip", name); put("t0", times.started + seg.start); put("t1", times.started + seg.end)
                put("offset", seg.start); put("label", seg.speaker); put("text", seg.text); put("original", seg.original)
            })
            db.insert("lines_fts", null, ContentValues().apply { put("docid", id); put("text", seg.text) })
        }
        for ((label, id) in t.speakers) {
            val emb = t.embeddings[label]
            db.insert("clip_speakers", null, ContentValues().apply {
                put("clip", name); put("label", label); id.personId?.let { put("person_id", it) }
                put("seconds", id.seconds); put("decision", id.decision); put("score", id.score)
                id.candidates.firstOrNull()?.let { put("candidate_id", it.personId); put("candidate_score", it.score) }
                emb?.let { put("emb", SpeakerStore.pack(it.toFloatArray())) }
            })
        }
        for (s in t.sounds.orEmpty()) db.insert("sounds", null, ContentValues().apply {
            put("clip", name); put("label", s.label); put("score", s.score); put("t", times.started + s.at)
        })
    }

    /**
     * Conversations, and one set of speakers per conversation.
     *
     * Inside a conversation a voice keeps one identity across its 30 s clips:
     * a voice filed under a person (named or an unnamed cluster) is that
     * person; anything else is linked to the conversation's voices by
     * SAME_VOICE, the desktop's threshold for calling two diarized slots one
     * speaker across a clip boundary. Without this every clip would restart
     * at "Voice 1".
     */
    private fun rebuildConversations(db: SQLiteDatabase, speakers: SpeakerStore) {
        db.execSQL("DELETE FROM conversations")
        db.execSQL("UPDATE clips SET conversation = NULL")
        data class C(val name: String, val started: Double, val ended: Double, val seconds: Double)
        val speech = db.rawQuery("SELECT name, started, ended, seconds FROM clips WHERE speech = 1 ORDER BY started", null).use { c ->
            buildList { while (c.moveToNext()) add(C(c.getString(0), c.getDouble(1), c.getDouble(2), c.getDouble(3))) }
        }
        var i = 0
        while (i < speech.size) {
            var j = i
            while (j + 1 < speech.size && speech[j + 1].started - speech[j].ended <= CONVERSATION_GAP) j++
            val group = speech.subList(i, j + 1)
            val id = (group.first().started * 1000).toLong()
            for (c in group) db.execSQL("UPDATE clips SET conversation = ? WHERE name = ?", arrayOf<Any>(id, c.name))
            linkSpeakers(db, group.map { it.name }, speakers)

            val talk = HashMap<String, Double>()
            // Only voices that said something transcribed: a cough or a second of TV
            // gets a voice of its own but no line, and could never be named from the conversation.
            db.rawQuery("SELECT conv_key, SUM(seconds) FROM clip_speakers cs WHERE clip IN (${group.joinToString(",") { "?" }}) AND conv_key IS NOT NULL " +
                "AND EXISTS (SELECT 1 FROM lines l WHERE l.clip = cs.clip AND l.label = cs.label) GROUP BY conv_key",
                group.map { it.name }.toTypedArray()).use { c -> while (c.moveToNext()) talk[c.getString(0)] = c.getDouble(1) }
            val snippet = db.rawQuery("SELECT text FROM lines WHERE clip IN (${group.joinToString(",") { "?" }}) ORDER BY t0 LIMIT 3",
                group.map { it.name }.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }.joinToString(" ")
            val sounds = db.rawQuery("SELECT label, MAX(score) FROM sounds WHERE clip IN (${group.joinToString(",") { "?" }}) GROUP BY label ORDER BY MAX(score) DESC",
                group.map { it.name }.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getDouble(1)) } }
                .filter { (l, s) -> l !in Sounds.VOICE && l !in Sounds.AMBIENT && s >= Sounds.EVENT_FLOOR }.map { it.first }.take(4)
            db.insert("conversations", null, ContentValues().apply {
                put("id", id); put("started", group.first().started); put("ended", group.last().ended)
                put("day", dayOf(group.first().started).toString()); put("clips", group.size)
                put("speech_seconds", talk.values.sum())
                put("speakers", talk.entries.sortedByDescending { it.value }.joinToString("|") { it.key })
                put("snippet", snippet.take(240)); put("sounds", sounds.joinToString("|"))
            })
            i = j + 1
        }
    }

    private fun linkSpeakers(db: SQLiteDatabase, clips: List<String>, speakers: SpeakerStore) {
        data class Voice(val key: String, val emb: FloatArray)
        val voices = mutableListOf<Voice>()
        var anon = 0
        for (clip in clips) {
            val rows = db.rawQuery("SELECT label, person_id, emb FROM clip_speakers WHERE clip = ?", arrayOf(clip)).use { c ->
                buildList { while (c.moveToNext()) add(Triple(c.getString(0), if (c.isNull(1)) null else c.getLong(1), c.getBlob(2)?.let(SpeakerStore::unpack))) }
            }
            for ((label, recorded, emb) in rows) {
                val pid = speakers.currentPerson(clip, label, recorded)
                val key = when {
                    pid != null -> "p$pid"
                    emb != null -> voices.map { it to Matching.dot(Matching.unit(emb), it.emb) }.maxByOrNull { it.second }
                        ?.takeIf { it.second >= SAME_VOICE }?.first?.key ?: "v${++anon}"
                    else -> "v${++anon}"
                }
                if (emb != null) voices += Voice(key, Matching.unit(emb))
                db.execSQL("UPDATE clip_speakers SET conv_key = ? WHERE clip = ? AND label = ?", arrayOf(key, clip, label))
            }
        }
    }

    // --------------------------------------------------------------- queries

    /** The voices heard in a clip: (label, conversation key). */
    fun speakersInClip(clip: String): List<Pair<String, String?>> = readableDatabase.rawQuery(
        "SELECT label, conv_key FROM clip_speakers WHERE clip = ? ORDER BY label", arrayOf(clip)).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0) to (if (c.isNull(1)) null else c.getString(1))) }
    }

    /** What one voice said in one clip, in order. */
    fun linesOf(clip: String, label: String): List<String> = readableDatabase.rawQuery(
        "SELECT text FROM lines WHERE clip = ? AND label = ? ORDER BY t0", arrayOf(clip, label)).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    /**
     * When one voice speaks in one clip, as (start, end) seconds into the clip:
     * its lines, with pauses under [joinGap] closed so a sentence plays whole.
     */
    fun spansOf(clip: String, label: String, joinGap: Double = 0.6): List<Pair<Double, Double>> {
        val raw = readableDatabase.rawQuery("SELECT offset, t1 - t0 FROM lines WHERE clip = ? AND label = ? ORDER BY t0", arrayOf(clip, label)).use { c ->
            buildList { while (c.moveToNext()) add(c.getDouble(0) to c.getDouble(0) + c.getDouble(1)) }
        }
        val out = mutableListOf<Pair<Double, Double>>()
        for (s in raw) {
            val last = out.lastOrNull()
            if (last != null && s.first - last.second <= joinGap) out[out.lastIndex] = last.first to maxOf(last.second, s.second)
            else out += s
        }
        return out
    }

    /**
     * The phone's own words for a clip, as the recognizer wrote them (before
     * any hand edits), with when the clip started.
     */
    fun asrText(clip: String): String = readableDatabase.rawQuery(
        "SELECT COALESCE(original, text) FROM lines WHERE clip = ? ORDER BY t0", arrayOf(clip)).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }.joinToString(" ")
    }

    /** The most recent clips the phone heard at least [minWords] words in, newest first. */
    fun recentSpeechClips(limit: Int, minWords: Int = 8): List<String> = readableDatabase.rawQuery("""
        SELECT l.clip, SUM(LENGTH(COALESCE(l.original, l.text)) - LENGTH(REPLACE(COALESCE(l.original, l.text), ' ', '')) + 1) AS words
        FROM lines l JOIN clips c ON c.name = l.clip WHERE c.audio = 1
        GROUP BY l.clip HAVING words >= CAST(? AS INTEGER) ORDER BY MAX(l.t0) DESC LIMIT CAST(? AS INTEGER)""", arrayOf(minWords.toString(), limit.toString())).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    fun clipStarted(clip: String): Double? = readableDatabase.rawQuery("SELECT started FROM clips WHERE name = ?", arrayOf(clip)).use { c ->
        if (c.moveToFirst()) c.getDouble(0) else null
    }

    fun days(): List<Pair<LocalDate, Int>> = readableDatabase.rawQuery(
        "SELECT day, COUNT(*) FROM conversations GROUP BY day ORDER BY day DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(LocalDate.parse(c.getString(0)) to c.getInt(1)) }
    }

    fun conversations(day: LocalDate): List<Conversation> = readableDatabase.rawQuery(
        "SELECT * FROM conversations WHERE day = ? ORDER BY started DESC", arrayOf(day.toString())).use { c ->
        buildList { while (c.moveToNext()) add(c.toConversation()) }
    }

    fun conversation(id: Long): Conversation? = readableDatabase.rawQuery(
        "SELECT * FROM conversations WHERE id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.toConversation() else null }

    fun conversationsWith(key: String): List<Conversation> = readableDatabase.rawQuery(
        "SELECT * FROM conversations WHERE ('|' || speakers || '|') LIKE ? ORDER BY started DESC LIMIT 200", arrayOf("%|$key|%")).use { c ->
        buildList { while (c.moveToNext()) add(c.toConversation()) }
    }

    private fun Cursor.toConversation() = Conversation(
        id = getLong(getColumnIndexOrThrow("id")), started = getDouble(getColumnIndexOrThrow("started")),
        ended = getDouble(getColumnIndexOrThrow("ended")), clips = getInt(getColumnIndexOrThrow("clips")),
        speechSeconds = getDouble(getColumnIndexOrThrow("speech_seconds")),
        speakers = getString(getColumnIndexOrThrow("speakers")).split("|").filter { it.isNotEmpty() },
        snippet = getString(getColumnIndexOrThrow("snippet")),
        sounds = getString(getColumnIndexOrThrow("sounds")).split("|").filter { it.isNotEmpty() },
    )

    /** Every clip of a day, for the ribbon: talk, sounds and the gaps between. */
    fun clips(day: LocalDate): List<ClipRow> {
        val from = day.atStartOfDay(zone).toEpochSecond().toDouble()
        val to = day.plusDays(1).atStartOfDay(zone).toEpochSecond().toDouble()
        return clipsBetween(from, to)
    }

    fun clipsBetween(from: Double, to: Double): List<ClipRow> = readableDatabase.rawQuery(
        "SELECT name, started, ended, speech, verdict, audio, conversation, top_sound, bytes FROM clips WHERE started >= ? AND started < ? ORDER BY started",
        arrayOf(from.toString(), to.toString())).use { c -> buildList { while (c.moveToNext()) add(c.toClip()) } }

    fun clipsOf(conversation: Long): List<ClipRow> = readableDatabase.rawQuery(
        "SELECT name, started, ended, speech, verdict, audio, conversation, top_sound, bytes FROM clips WHERE conversation = ? ORDER BY started",
        arrayOf(conversation.toString())).use { c -> buildList { while (c.moveToNext()) add(c.toClip()) } }

    private fun Cursor.toClip() = ClipRow(getString(0), getDouble(1), getDouble(2), getInt(3) == 1, if (isNull(4)) null else getString(4),
        getInt(5) == 1, if (isNull(6)) null else getLong(6), if (isNull(7)) null else getString(7), getLong(8))

    /** Lines of a conversation with each one's conversation-level speaker key. */
    fun lines(conversation: Long): List<LineRow> = readableDatabase.rawQuery("""
        SELECT l.id, l.clip, l.t0, l.t1, l.offset, s.conv_key, s.person_id, l.text, l.original, c.cloud FROM lines l
        JOIN clips c ON c.name = l.clip LEFT JOIN clip_speakers s ON s.clip = l.clip AND s.label = l.label
        WHERE c.conversation = ? ORDER BY l.t0""", arrayOf(conversation.toString())).use { c ->
        buildList { while (c.moveToNext()) add(c.toLine()) }
    }

    private fun Cursor.toLine(): LineRow {
        val key = if (isNull(5)) null else getString(5)
        val originalIdx = getColumnIndex("original")
        return LineRow(getLong(0), getString(1), getDouble(2), getDouble(3), getDouble(4), key,
            key?.takeIf { it.startsWith("p") }?.drop(1)?.toLongOrNull(), getString(7),
            if (originalIdx >= 0 && !isNull(originalIdx)) getString(originalIdx) else null,
            getColumnIndex("cloud").let { i -> i >= 0 && !isNull(i) && getInt(i) == 1 })
    }

    /** The best guess for a voice nobody named: its top candidate, if any. */
    fun guess(conversation: Long, key: String): Pair<Long, Double>? = readableDatabase.rawQuery("""
        SELECT s.candidate_id, MAX(s.candidate_score) FROM clip_speakers s JOIN clips c ON c.name = s.clip
        WHERE c.conversation = ? AND s.conv_key = ? AND s.candidate_id IS NOT NULL""", arrayOf(conversation.toString(), key)).use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) to c.getDouble(1) else null
    }

    /** The loudest-evidence voiceprint of a conversation voice, to enrol it when someone names it. */
    /** Every clip voice in a conversation that carries [key]: (clip, label, voiceprint or null, seconds). */
    fun slotsOf(conversation: Long, key: String): List<Triple<Pair<String, String>, FloatArray?, Double>> = readableDatabase.rawQuery("""
        SELECT s.clip, s.label, s.emb, s.seconds FROM clip_speakers s JOIN clips c ON c.name = s.clip WHERE c.conversation = ? AND s.conv_key = ?""",
        arrayOf(conversation.toString(), key)).use { c ->
        buildList { while (c.moveToNext()) add(Triple(c.getString(0) to c.getString(1), c.getBlob(2)?.let(SpeakerStore::unpack), c.getDouble(3))) }
    }

    fun voiceOf(conversation: Long, key: String): Triple<FloatArray, Double, String>? = readableDatabase.rawQuery("""
        SELECT s.emb, s.seconds, s.clip FROM clip_speakers s JOIN clips c ON c.name = s.clip
        WHERE c.conversation = ? AND s.conv_key = ? AND s.emb IS NOT NULL ORDER BY s.seconds DESC LIMIT 1""",
        arrayOf(conversation.toString(), key)).use { c ->
        if (c.moveToFirst()) Triple(SpeakerStore.unpack(c.getBlob(0)), c.getDouble(1), c.getString(2)) else null
    }

    fun search(query: String, limit: Int = 100): List<SearchHit> {
        val q = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            .joinToString(" ") { it.replace("\"", "") + "*" }
        if (q.isBlank()) return emptyList()
        return readableDatabase.rawQuery("""
            SELECT l.id, l.clip, l.t0, l.t1, l.offset, s.conv_key, s.person_id, l.text, c.conversation,
                   snippet(lines_fts, '[', ']', '…', -1, 12)
            FROM lines_fts f JOIN lines l ON l.id = f.docid JOIN clips c ON c.name = l.clip
            LEFT JOIN clip_speakers s ON s.clip = l.clip AND s.label = l.label
            WHERE lines_fts MATCH ? ORDER BY l.t0 DESC LIMIT ?""", arrayOf(q, limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(SearchHit(c.toLine(), if (c.isNull(8)) null else c.getLong(8), c.getString(9))) }
        }
    }

    // --------------------------------------------------------------- storage

    data class Usage(val clips: Int, val audioBytes: Long, val quietClips: Int, val quietBytes: Long)

    /** Clips whose audio may go: no speech, the tagger found nothing but background, and older than [olderThanDays]. */
    fun quietCandidates(olderThanDays: Int): List<ClipRow> {
        val cutoff = System.currentTimeMillis() / 1000.0 - olderThanDays * 86_400
        return readableDatabase.rawQuery(
            "SELECT name, started, ended, speech, verdict, audio, conversation, top_sound, bytes FROM clips " +
                "WHERE speech = 0 AND verdict = 'empty' AND audio = 1 AND ended < ? ORDER BY started", arrayOf(cutoff.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.toClip()) }
        }
    }

    fun usage(olderThanDays: Int): Usage {
        val (n, bytes) = readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(SUM(bytes), 0) FROM clips WHERE audio = 1", null).use { c ->
            c.moveToFirst(); c.getInt(0) to c.getLong(1)
        }
        val q = quietCandidates(olderThanDays)
        return Usage(n, bytes, q.size, q.sumOf { it.bytes })
    }

    /** Delete audio only; the sidecar, transcript and tags stay, so the day's record keeps the clip. */
    fun deleteAudio(names: List<String>) {
        val dir = CaptureService.clipsDir(context)
        for (n in names) net.boswell.phone.audio.ClipAudio.delete(dir, n)
    }

    companion object {
        const val CONVERSATION_GAP = 60.0
        /** web/threads.py SAME_VOICE: two diarized slots are one speaker across a clip boundary. */
        val SAME_VOICE get() = net.boswell.phone.speakers.Matching.model.sameVoice
    }
}
