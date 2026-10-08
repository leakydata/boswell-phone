package net.boswell.phone

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Collections
import java.util.WeakHashMap

/**
 * What every database helper shares. Screens, processing and the workers each
 * open their own copy at once, so each database keeps a write ahead log:
 * reading goes on during another copy's write, a big read sees one moment
 * throughout, and a writer waits its turn instead of failing.
 *
 * Every helper is tracked, so a restore can close them all before it swaps
 * the files: a copy still open on the old file would, when it finally closed,
 * delete "<name>-wal" by name, which by then is the restored database's log.
 */
object Databases {
    private val open: MutableSet<SQLiteOpenHelper> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /** From a helper's init: a write ahead log, and tracked for [closeAll]. */
    fun share(h: SQLiteOpenHelper) {
        h.setWriteAheadLoggingEnabled(true)
        open += h
    }

    /** From a helper's onConfigure: wait up to 30 s for another copy's write, as Archive does. */
    fun waitForWriters(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA busy_timeout = 30000", null).use { it.moveToFirst() }
    }

    /** Close every tracked helper for [name]. One used again afterwards opens again, on whatever file is there then. */
    fun closeAll(name: String) {
        val helpers = synchronized(open) { open.filter { it.databaseName == name } }
        for (h in helpers) runCatching { h.close() }
    }
}
