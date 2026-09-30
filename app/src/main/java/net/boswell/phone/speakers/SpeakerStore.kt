package net.boswell.phone.speakers

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Person(val id: Long, val name: String?, val voiceprints: Int, val seconds: Double)

/**
 * Speaker identity on the phone: the desktop's schema and rules.
 *
 * One row per voiceprint, never an average: a voice in a quiet room and the
 * same voice outdoors are far apart, and an average of them matches neither.
 * Unidentified voices are people rows with a NULL name, so naming a stranger
 * is one UPDATE that turns every voiceprint gathered under them into a
 * reference. Vectors are float32, unit length, stored exactly as the desktop
 * stores them, so the two stores can be merged later.
 *
 * Starts empty. Nothing is imported from the desktop.
 */
class SpeakerStore(context: Context) : SQLiteOpenHelper(context, "speakers.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE people (
                id      INTEGER PRIMARY KEY,
                name    TEXT UNIQUE,              -- NULL = an unidentified recurring voice
                kind    TEXT,
                created REAL
            )""")
        db.execSQL("""
            CREATE TABLE voiceprints (
                id        INTEGER PRIMARY KEY,
                person_id INTEGER NOT NULL REFERENCES people(id) ON DELETE CASCADE,
                vec       BLOB NOT NULL,          -- float32, unit length
                dim       INTEGER NOT NULL,
                seconds   REAL,
                clip      TEXT,
                speaker   TEXT,
                origin    TEXT NOT NULL,          -- manual | confirmed | auto
                redundant INTEGER NOT NULL DEFAULT 0,
                impure    INTEGER NOT NULL DEFAULT 0,
                created   REAL
            )""")
        db.execSQL("CREATE INDEX vp_person ON voiceprints(person_id)")
        db.execSQL("""
            CREATE TABLE matches (
                id            INTEGER PRIMARY KEY,
                clip          TEXT,
                speaker       TEXT,
                voiceprint_id INTEGER,
                person_id     INTEGER,
                score         REAL,
                margin        REAL,
                decision      TEXT,
                corrected     INTEGER NOT NULL DEFAULT 0,
                created       REAL
            )""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)

    private fun now() = System.currentTimeMillis() / 1000.0

    private fun refs(named: Boolean): List<Matching.Reference> {
        val sql = """SELECT v.id, v.person_id, v.vec FROM voiceprints v JOIN people p ON p.id = v.person_id
                     WHERE p.name IS ${if (named) "NOT NULL" else "NULL"} AND v.impure = 0 ORDER BY v.id"""
        return readableDatabase.rawQuery(sql, null).use { c ->
            buildList { while (c.moveToNext()) add(Matching.Reference(c.getLong(0), c.getLong(1), unpack(c.getBlob(2)))) }
        }
    }

    /** Match against named people only: unnamed clusters are the question, not the answer. */
    fun match(vec: FloatArray): Matching.Result = Matching.match(vec, refs(named = true))

    fun logMatch(clip: String, speaker: String, r: Matching.Result) {
        writableDatabase.insert("matches", null, ContentValues().apply {
            put("clip", clip); put("speaker", speaker)
            r.candidates.firstOrNull()?.let { put("voiceprint_id", it.voiceprintId); put("person_id", it.personId) }
            put("score", r.score); r.margin?.let { put("margin", it) }
            put("decision", r.decision.name.lowercase()); put("created", now())
        })
    }

    /**
     * File a voice nobody could name: join the closest unnamed cluster if it
     * clears CLUSTER_MIN, or start a new one. Attached to nobody with a name,
     * so an automatic reference can never reinforce a wrong label.
     */
    fun ingestUnknown(vec: FloatArray, clip: String, speaker: String, seconds: Double): Long {
        val (existing, _) = Matching.bestCluster(vec, refs(named = false))
        val pid = existing ?: newPerson(null)
        addVoiceprint(pid, vec, seconds, clip, speaker, "auto")
        return pid
    }

    fun newPerson(name: String?): Long = writableDatabase.insertOrThrow("people", null, ContentValues().apply {
        put("name", name); put("created", now())
    })

    fun addVoiceprint(personId: Long, vec: FloatArray, seconds: Double?, clip: String?, speaker: String?, origin: String): Long {
        require(Matching.usable(vec)) { "unusable voiceprint" }
        val v = Matching.unit(vec)
        return writableDatabase.insertOrThrow("voiceprints", null, ContentValues().apply {
            put("person_id", personId); put("vec", pack(v)); put("dim", v.size)
            seconds?.let { put("seconds", it) }; put("clip", clip); put("speaker", speaker)
            put("origin", origin); put("created", now())
        })
    }

    /**
     * Name a cluster, or rename someone. Naming a stranger as somebody already
     * known is a merge -- the good case: their voiceprints join that person's
     * and cover conditions that were missing. Returns the surviving person id.
     */
    fun name(personId: Long, name: String): Long {
        val db = writableDatabase
        val existing = db.rawQuery("SELECT id FROM people WHERE name = ?", arrayOf(name)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
        db.beginTransaction()
        try {
            if (existing != null && existing != personId) {
                db.execSQL("UPDATE voiceprints SET person_id = ? WHERE person_id = ?", arrayOf<Any>(existing, personId))
                db.execSQL("DELETE FROM people WHERE id = ?", arrayOf<Any>(personId))
                db.setTransactionSuccessful()
                return existing
            }
            db.execSQL("UPDATE people SET name = ? WHERE id = ?", arrayOf<Any>(name, personId))
            db.setTransactionSuccessful()
            return personId
        } finally {
            db.endTransaction()
        }
    }

    fun people(): List<Person> = readableDatabase.rawQuery("""
        SELECT p.id, p.name, COUNT(v.id), COALESCE(SUM(v.seconds), 0) FROM people p
        LEFT JOIN voiceprints v ON v.person_id = p.id GROUP BY p.id ORDER BY p.name IS NULL, p.name, p.id""", null).use { c ->
        buildList { while (c.moveToNext()) add(Person(c.getLong(0), if (c.isNull(1)) null else c.getString(1), c.getInt(2), c.getDouble(3))) }
    }

    fun nameOf(personId: Long): String? = readableDatabase.rawQuery("SELECT name FROM people WHERE id = ?", arrayOf(personId.toString())).use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
    }

    companion object {
        fun pack(v: FloatArray): ByteArray = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) } }.array()
        fun unpack(b: ByteArray): FloatArray { val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN); return FloatArray(b.size / 4) { bb.float } }
    }
}
