package net.boswell.phone.capture

import net.boswell.phone.omi.Offload

/**
 * When Live mode stops to fetch what the Omi stored while it was out of range.
 *
 * What the firmware does (Omi CV 1, firmware/omi/src/lib/core/transport.c,
 * pusher()): each encoded frame goes to the phone if a phone is connected and
 * subscribed to audio, to storage only if nothing is connected at all, and
 * nowhere if a phone is connected but not subscribed. A fetch is that third
 * case -- reading storage can't share the connection with the live stream
 * (Offload) -- so whatever is said during a fetch is lost, not stored. Nothing
 * is added to the backlog while a fetch runs, so there is nothing to chase.
 *
 * So the rules are about picking a moment when nobody is talking: after the
 * link has settled, once the room has been quiet for a while (the CV 1's mic
 * sleeps through silence, so no frames means no sound), and kept short, so a
 * big backlog goes in slices with live in between. There is no cap that goes
 * anyway: losing what's said now to fetch what was said earlier is the wrong
 * trade, so a room that never goes quiet (a TV on) leaves the backlog for the
 * charger, late but whole.
 *
 * Only a backlog worth a reconnect is fetched: the few seconds stored while
 * the phone switches between a fetch and live are left for the next one (or
 * the charger), or every fetch would leave a little behind and start another.
 *
 * And only on a good link. A second of transfer at R bytes/s brings back
 * R / 5,093 s of audio (444 bytes per 87 ms packet), and a slice also costs
 * a few seconds of switching each way. The first real run (10-10) began at
 * 4.8 kB/s, under a second of audio per second off the air, and then lost the
 * link: 1.6 min fetched for 84 s off the air, and no live for 40 min after
 * (the edge of range, most likely, but the fetch chose the moment). The live
 * stream can't tell such a link from a good one -- one 20 ms frame per
 * notification needs only a few kB/s, and it arrived whole at 50 packets/s --
 * so the checks are the link's RSSI before a slice
 * ([linkOk]) and the rate of its first, small batch (OmiSync.PROBE_BATCH,
 * [SLOW_BYTES_PER_SECOND]) once it starts. A slow or failed slice backs off,
 * doubling, and slices are spaced well apart: the charger has all night, so a
 * big backlog mostly waits for it, and in range only a good link chips at it.
 */
object RangeFetch {
    /** Less audio than this waiting isn't worth a reconnect. */
    const val WORTH_SECONDS = 20.0
    /** Streaming at least this long first: someone walking back into range is often mid-sentence. */
    const val SETTLE_MS = 20_000L
    /** No sound for this long: the room is quiet. */
    const val QUIET_MS = 15_000L
    /** One fetch holds the radio this long at most, then live again; the rest comes in the next quiet slice. */
    const val VISIT_SECONDS = 60L
    /**
     * Live between two slices of one backlog at least this long: at most a
     * minute in eleven off the air. On a good link (89 kB/s) a slice brings
     * back about 17 min, so 200 min still comes back within a few hours.
     */
    const val BETWEEN_SLICES_MS = 10 * 60_000L
    /** After a fetch that failed or took nothing, wait this long, doubling, up to [RETRY_MAX_MS]. */
    const val RETRY_MIN_MS = 5 * 60_000L
    const val RETRY_MAX_MS = 60 * 60_000L
    /**
     * Slower than this, a slice isn't worth its live time: 13 kB/s brings back
     * 2.5 s of audio per second off the air, before the switching (the
     * break-even in docs/OMI-PROTOCOL.md). A first batch slower than this ends
     * the slice, and the next waits on the failure backoff.
     */
    const val SLOW_BYTES_PER_SECOND = 13_000.0
    /**
     * Live RSSI weaker than this, no slice starts. The desktop measured 89 kB/s
     * at -61 dBm and 3 kB/s at -84; -75 is a first guess between, to be
     * tuned from the "out of range fetch" lines, which log both.
     */
    const val WEAK_RSSI_DBM = -75
    /** The link check reads RSSI every 30 s; older than this, the reads are failing, which is its own answer. */
    const val RSSI_FRESH_MS = 90_000L
    /**
     * Silence mid-batch that ends a slice. A disconnect is heard at once
     * (OmiConnection); this is a link that stays up but stops carrying, where
     * the charger's 20 s would be a third of the slice.
     */
    const val IDLE_MS = 8_000L

    fun seconds(packets: Long): Double = packets * Offload.SECONDS_PER_PACKET

    fun worth(packets: Long?): Boolean = packets != null && seconds(packets) >= WORTH_SECONDS

    /**
     * Whether to stop streaming and fetch now. [lastAudio] is when the last
     * frame arrived (null: none yet); [busy] is a button question or voice
     * setup listening, which a disconnect would cut off.
     */
    fun due(now: Long, streamingSince: Long, lastAudio: Long?, retryAt: Long, busy: Boolean): Boolean {
        if (busy || now < retryAt) return false
        val streamed = now - streamingSince
        if (streamed < SETTLE_MS) return false
        val quiet = now - maxOf(lastAudio ?: 0L, streamingSince)
        return quiet >= QUIET_MS
    }

    /**
     * Whether the link looks good enough to start a slice. [rssi] is the last
     * live reading and [rssiAt] when it was taken (null: never read, so the
     * first batch decides).
     */
    fun linkOk(now: Long, rssi: Int?, rssiAt: Long?): Boolean {
        if (rssi == null || rssiAt == null) return true
        if (now - rssiAt > RSSI_FRESH_MS) return false
        return rssi >= WEAK_RSSI_DBM
    }

    /** Failed or slow slices in a row, and when the next fetch may start (0: whenever one is due). */
    data class Next(val fails: Int, val retryAt: Long)

    /**
     * After a slice. Slow counts as failed: a weak link usually stays weak for
     * a while (a desk at the edge of range), and each try costs live time.
     */
    fun after(now: Long, fails: Int, emptied: Boolean, failed: Boolean, took: Long, bytesPerSecond: Double): Next = when {
        emptied -> Next(0, 0L)
        failed || took == 0L || bytesPerSecond < SLOW_BYTES_PER_SECOND -> (fails + 1).let { Next(it, now + backoff(it)) }
        else -> Next(0, now + BETWEEN_SLICES_MS)
    }

    fun backoff(fails: Int): Long =
        (RETRY_MIN_MS shl (fails - 1).coerceIn(0, 10)).coerceAtMost(RETRY_MAX_MS)
}
