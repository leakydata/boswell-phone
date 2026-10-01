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

    data class Cal(val id: Long, val name: String, val account: String, val color: Int, val writable: Boolean, val visible: Boolean,
                   val synced: Boolean = true, val accountType: String = "com.google")

    /** Every calendar on the phone. A phone with several accounts has several "primary" ones, so the user chooses. */
    fun calendars(c: Context): List<Cal> {
        if (!allowed(c)) return emptyList()
        return c.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_COLOR, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.VISIBLE,
                CalendarContract.Calendars.SYNC_EVENTS, CalendarContract.Calendars.ACCOUNT_TYPE),
            null, null, null,
        )?.use { cur ->
            buildList {
                while (cur.moveToNext()) add(Cal(cur.getLong(0), cur.getString(1) ?: "?", cur.getString(2) ?: "", cur.getInt(3),
                    cur.getInt(4) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR, cur.getInt(5) == 1,
                    cur.getInt(6) == 1, cur.getString(7) ?: "com.google"))
            }
        } ?: emptyList()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)

    /** The calendar new events go into; null until chosen. */
    fun chosen(c: Context): Cal? = prefs(c).getLong("calendar_id", -1).takeIf { it >= 0 }?.let { id -> calendars(c).firstOrNull { it.id == id } }
    /**
     * Use this calendar for new events. A calendar that isn't synced to the
     * phone would keep the events here and never upload them, so choosing
     * one switches its sync on (apps are allowed to), and asks for a sync.
     */
    fun choose(c: Context, id: Long?) {
        prefs(c).edit().putLong("calendar_id", id ?: -1).apply()
        val cal = id?.let { i -> calendars(c).firstOrNull { it.id == i } } ?: return
        if (!cal.synced || !cal.visible) runCatching {
            c.contentResolver.update(android.content.ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, cal.id),
                ContentValues().apply { put(CalendarContract.Calendars.SYNC_EVENTS, 1); put(CalendarContract.Calendars.VISIBLE, 1) }, null, null)
        }
        refresh(c, listOf(cal))
    }

    /**
     * Ask the accounts' calendar sync to run now. The phone only learns about
     * a calendar created elsewhere (say, on the web) when it next syncs, which
     * otherwise can be a day away.
     */
    fun refresh(c: Context, only: List<Cal>? = null) {
        val accounts = (only ?: calendars(c)).map { it.account to it.accountType }.distinct()
        for ((name, type) in accounts) try {
            android.content.ContentResolver.requestSync(android.accounts.Account(name, type), CalendarContract.AUTHORITY,
                android.os.Bundle().apply {
                    putBoolean(android.content.ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    putBoolean(android.content.ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                })
        } catch (e: Exception) {
            net.boswell.phone.capture.CaptureRepository.log("calendar refresh for $name not allowed: ${e.javaClass.simpleName}")
        }
    }

    /** Show calendar events alongside to-dos in the Day/Week/Month views. */
    fun showEvents(c: Context) = prefs(c).getBoolean("show_events", true)
    fun setShowEvents(c: Context, on: Boolean) = prefs(c).edit().putBoolean("show_events", on).apply()

    data class Event(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val color: Int, val calendar: String)

    /**
     * Events from every calendar the phone shows (the ones visible in the
     * calendar app), recurring ones expanded, between two epoch-millis.
     */
    fun events(c: Context, from: Long, to: Long, respectShow: Boolean = true): List<Event> {
        if (!allowed(c) || (respectShow && !showEvents(c))) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(uri, from)
        android.content.ContentUris.appendId(uri, to)
        return c.contentResolver.query(uri.build(),
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                CalendarContract.Instances.VISIBLE),
            null, null, CalendarContract.Instances.BEGIN)?.use { cur ->
            buildList {
                while (cur.moveToNext()) if (cur.getInt(6) == 1) add(Event(cur.getString(0) ?: "(no title)", cur.getLong(1), cur.getLong(2),
                    cur.getInt(3) == 1, cur.getInt(4), cur.getString(5) ?: ""))
            }
        } ?: emptyList()
    }

    /** Returns the new event's id, or an explanation of why not. Goes into the chosen calendar only. */
    fun add(c: Context, title: String, startEpoch: Double, minutes: Int, location: String?, notes: String?): Result<Long> {
        if (!allowed(c)) return Result.failure(IllegalStateException("calendar access has not been granted (Device → Assistant → Calendar)"))
        val cal = chosen(c)?.id ?: run {
            net.boswell.phone.assistant.AssistantNotify.post(c, net.boswell.phone.assistant.AssistantNotify.ANSWERS,
                "Choose a calendar", "The assistant tried to add \"$title\" but no calendar is chosen. Open Device → Assistant → Calendar.")
            return Result.failure(IllegalStateException("no calendar chosen yet: the user must pick one in Device → Assistant → Calendar"))
        }
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
