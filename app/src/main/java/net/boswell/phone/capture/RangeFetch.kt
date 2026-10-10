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
    /** Live between two slices of one backlog at least this long, so a slice never follows a slice. */
    const val BETWEEN_SLICES_MS = 60_000L
    /** After a fetch that failed or took nothing, wait this long, doubling, up to [RETRY_MAX_MS]. */
    const val RETRY_MIN_MS = 5 * 60_000L
    const val RETRY_MAX_MS = 60 * 60_000L
    /**
     * Slower than this, a slice costs more live time than it brings back (the
     * break-even in docs/OMI-PROTOCOL.md): a weak link waits [RETRY_MIN_MS]
     * between slices instead of [BETWEEN_SLICES_MS].
     */
    const val SLOW_BYTES_PER_SECOND = 13_000.0

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

    /** Failures in a row, and when the next fetch may start (0: whenever one is due). */
    data class Next(val fails: Int, val retryAt: Long)

    fun after(now: Long, fails: Int, emptied: Boolean, failed: Boolean, took: Long, bytesPerSecond: Double): Next = when {
        emptied -> Next(0, 0L)
        failed || took == 0L -> (fails + 1).let { Next(it, now + backoff(it)) }
        bytesPerSecond < SLOW_BYTES_PER_SECOND -> Next(0, now + RETRY_MIN_MS)
        else -> Next(0, now + BETWEEN_SLICES_MS)
    }

    fun backoff(fails: Int): Long =
        (RETRY_MIN_MS shl (fails - 1).coerceIn(0, 10)).coerceAtMost(RETRY_MAX_MS)
}
