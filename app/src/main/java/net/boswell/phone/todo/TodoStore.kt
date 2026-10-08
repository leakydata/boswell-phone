package net.boswell.phone.todo

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Todo(
    val id: Long,
    val text: String,
    val category: String,
    /** Epoch seconds, or null for no reminder. */
    val due: Double?,
    val done: Boolean,
    val created: Double,
    val doneAt: Double?,
    /** typed | voice | assistant */
    val source: String,
)

/**
 * The to-do list: one that only grows, with categories the assistant picks.
 * Done items are kept and can be un-checked; nothing is deleted unless you
 * delete it.
 */
class TodoStore(context: Context) : SQLiteOpenHelper(context, "todo.db", null, 1) {
    init { net.boswell.phone.Databases.share(this) }

    override fun onConfigure(db: SQLiteDatabase) = net.boswell.phone.Databases.waitForWriters(db)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE todos (id INTEGER PRIMARY KEY, text TEXT NOT NULL, category TEXT NOT NULL DEFAULT 'Inbox',
            due REAL, done INTEGER NOT NULL DEFAULT 0, created REAL, done_at REAL, source TEXT)""")
        db.execSQL("CREATE INDEX todo_done ON todos(done, due)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun now() = System.currentTimeMillis() / 1000.0

    fun add(text: String, category: String?, due: Double?, source: String): Long =
        writableDatabase.insert("todos", null, ContentValues().apply {
            put("text", text.trim()); put("category", normalize(category)); due?.let { put("due", it) }
            put("created", now()); put("source", source)
        })

    fun setDone(id: Long, done: Boolean) =
        writableDatabase.execSQL("UPDATE todos SET done = ?, done_at = ? WHERE id = ?",
            arrayOf<Any?>(if (done) 1 else 0, if (done) now() else null, id))

    fun update(id: Long, text: String, category: String, due: Double?) =
        writableDatabase.execSQL("UPDATE todos SET text = ?, category = ?, due = ? WHERE id = ?", arrayOf<Any?>(text.trim(), normalize(category), due, id))

    fun delete(id: Long) = writableDatabase.execSQL("DELETE FROM todos WHERE id = ?", arrayOf<Any>(id))

    fun get(id: Long): Todo? = readableDatabase.rawQuery("SELECT * FROM todos WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.todo() else null }

    fun all(includeDone: Boolean = true): List<Todo> = readableDatabase.rawQuery(
        "SELECT * FROM todos ${if (includeDone) "" else "WHERE done = 0"} ORDER BY done, due IS NULL, due, created DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(c.todo()) }
    }

    fun categories(): List<String> = readableDatabase.rawQuery(
        "SELECT category, COUNT(*) FROM todos GROUP BY category ORDER BY COUNT(*) DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    private fun Cursor.todo() = Todo(
        getLong(getColumnIndexOrThrow("id")), getString(getColumnIndexOrThrow("text")), getString(getColumnIndexOrThrow("category")),
        getColumnIndexOrThrow("due").let { if (isNull(it)) null else getDouble(it) }, getInt(getColumnIndexOrThrow("done")) == 1,
        getDouble(getColumnIndexOrThrow("created")), getColumnIndexOrThrow("done_at").let { if (isNull(it)) null else getDouble(it) },
        getString(getColumnIndexOrThrow("source")) ?: "typed",
    )

    /** "shopping " and "Shopping" are one category; nothing becomes blank. */
    private fun normalize(c: String?): String = c?.trim()?.takeIf { it.isNotEmpty() }
        ?.split(" ")?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } ?: "Inbox"
}
