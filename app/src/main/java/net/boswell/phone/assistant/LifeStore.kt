package net.boswell.phone.assistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Fact(val id: Long, val person: String, val fact: String, val at: Double)
data class LogEntry(val id: Long, val kind: String, val note: String?, val amount: Double?, val at: Double)

/**
 * What the assistant keeps besides to-dos: facts about people ("Sam's
 * daughter is Ava"), quick logs ("took my pills", "gas $42"), and which
 * promises and meetings it has already handled, so nothing is suggested
 * twice. Everything is on the phone.
 */
class LifeStore(context: Context) : SQLiteOpenHelper(context, "life.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE facts (id INTEGER PRIMARY KEY, person TEXT NOT NULL, fact TEXT NOT NULL, at REAL NOT NULL)")
        db.execSQL("CREATE TABLE logs (id INTEGER PRIMARY KEY, kind TEXT NOT NULL, note TEXT, amount REAL, at REAL NOT NULL)")
        db.execSQL("CREATE TABLE seen (key TEXT PRIMARY KEY, at REAL NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun now() = System.currentTimeMillis() / 1000.0

    fun addFact(person: String, fact: String): Long {
        val dup = readableDatabase.rawQuery("SELECT id FROM facts WHERE person = ? COLLATE NOCASE AND fact = ? COLLATE NOCASE", arrayOf(person, fact)).use { if (it.moveToFirst()) it.getLong(0) else null }
        return dup ?: writableDatabase.insert("facts", null, ContentValues().apply { put("person", person.trim()); put("fact", fact.trim()); put("at", now()) })
    }

    fun facts(person: String? = null): List<Fact> = readableDatabase.rawQuery(
        if (person == null) "SELECT id, person, fact, at FROM facts ORDER BY person, at"
        else "SELECT id, person, fact, at FROM facts WHERE person LIKE ? ORDER BY at",
        if (person == null) null else arrayOf("%${person.trim()}%")).use { c ->
        buildList { while (c.moveToNext()) add(Fact(c.getLong(0), c.getString(1), c.getString(2), c.getDouble(3))) }
    }

    fun deleteFact(id: Long) { writableDatabase.delete("facts", "id = ?", arrayOf(id.toString())) }

    fun addLog(kind: String, note: String?, amount: Double?): Long = writableDatabase.insert("logs", null, ContentValues().apply {
        put("kind", kind.trim().lowercase()); put("note", note?.trim()); amount?.let { put("amount", it) }; put("at", now())
    })

    fun logs(kind: String? = null, since: Double = 0.0): List<LogEntry> = readableDatabase.rawQuery(
        "SELECT id, kind, note, amount, at FROM logs WHERE at >= ?" + (if (kind != null) " AND kind LIKE ?" else "") + " ORDER BY at DESC LIMIT 500",
        listOfNotNull(since.toString(), kind?.let { "%${it.trim().lowercase()}%" }).toTypedArray()).use { c ->
        buildList { while (c.moveToNext()) add(LogEntry(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getString(2), if (c.isNull(3)) null else c.getDouble(3), c.getDouble(4))) }
    }

    fun deleteLog(id: Long) { writableDatabase.delete("logs", "id = ?", arrayOf(id.toString())) }

    /** Remember that [key] was handled (a promise filed, a meeting briefed). Returns false if it already was. */
    fun markSeen(key: String): Boolean =
        writableDatabase.insertWithOnConflict("seen", null, ContentValues().apply { put("key", key); put("at", now()) }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
}
