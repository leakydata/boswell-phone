package net.boswell.phone.capture

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * An Omi whose radio has locked up, said out loud instead of shown as "away".
 *
 * Seen twice on 2026-10-11 (fw 3.0.21, on its charger): the link drops with a
 * supervision timeout a minute or two into a healthy stream, and the Omi
 * never comes back. Its light keeps the "connected"
 * pattern (blinking blue), so the firmware never heard the disconnect; its
 * watchdog is fed from a loop that is still running, so it doesn't reboot.
 * Only holding the button for 3 s (off) and pressing it again brings it
 * back, and nothing the phone sends can reach it. To the phone it looks
 * exactly like walking out of range -- except that the link was strong a
 * moment before, and nobody walks away from a strong link that fast.
 */
object FrozenOmi {
    /** A link at least this strong just before the drop: the Omi was close. */
    const val STRONG_RSSI_DBM = -70
    /** The reading that says so must be this recent (the link check reads RSSI every 30 s). */
    const val RSSI_RECENT_MS = 90_000L
    /** Streaming at least this long first: a drop while connecting says nothing. */
    const val STREAMED_MS = 30_000L
    /** Still gone this long after such a drop: say it's frozen. Walking back into range takes longer to be wrong about. */
    const val AFTER_MS = 3 * 60_000L
    private const val ID = 92

    /** At a drop: whether it looks like a freeze rather than walking away. */
    fun suspicious(now: Long, streamedMs: Long, rssi: Int?, rssiAt: Long?): Boolean =
        streamedMs >= STREAMED_MS && rssi != null && rssiAt != null &&
            now - rssiAt <= RSSI_RECENT_MS && rssi >= STRONG_RSSI_DBM

    /** Whether a suspicious drop at [since] (null: none) has gone on long enough to say so. */
    fun frozen(now: Long, since: Long?): Boolean = since != null && now - since >= AFTER_MS

    const val TITLE = "The Omi seems frozen"
    const val TEXT = "It stopped answering right beside the phone. If its light blinks blue, " +
        "hold its button for 3 seconds until the light goes out, then press it once."

    fun tell(c: Context) = BatteryWatch.post(c, ID, TITLE, TEXT, offerSync = false)

    fun clear(c: Context) {
        runCatching { NotificationManagerCompat.from(c).cancel(ID) }
    }
}
