package net.boswell.phone.todo

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.TimeZone

/**
 * Adds events to the phone's own calendar (which syncs wherever that calendar
 * syncs, usually Google). Needs the calendar permission, granted from the
 * Device page; without it the assistant is told so and says so.
 */
object Calendar {
    fun allowed(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** The primary writable calendar: the account's own, visible, owner-level calendar. */
    private fun calendarId(c: Context): Long? = c.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.VISIBLE),
        null, null, null,
    )?.use { cur ->
        val rows = buildList { while (cur.moveToNext()) add(listOf(cur.getLong(0), cur.getInt(1).toLong(), cur.getInt(2).toLong(), cur.getInt(3).toLong())) }
        val writable = rows.filter { it[2] >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR && it[3] == 1L }
        (writable.firstOrNull { it[1] == 1L } ?: writable.firstOrNull())?.get(0)
    }

    /** Returns the new event's id, or an explanation of why not. */
    fun add(c: Context, title: String, startEpoch: Double, minutes: Int, location: String?, notes: String?): Result<Long> {
        if (!allowed(c)) return Result.failure(IllegalStateException("calendar access has not been granted (Device → Assistant → Calendar)"))
        val cal = calendarId(c) ?: return Result.failure(IllegalStateException("no writable calendar on this phone"))
        val start = (startEpoch * 1000).toLong()
        val uri = c.contentResolver.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, cal)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, start + minutes.coerceAtLeast(5) * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            put(CalendarContract.Events.DESCRIPTION, listOfNotNull(notes, "Added by Boswell").joinToString("\n"))
        }) ?: return Result.failure(IllegalStateException("the calendar refused the event"))
        return Result.success(uri.lastPathSegment?.toLongOrNull() ?: -1)
    }
}
