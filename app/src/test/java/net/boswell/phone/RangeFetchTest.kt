package net.boswell.phone

import net.boswell.phone.capture.RangeFetch
import net.boswell.phone.omi.Offload
import net.boswell.phone.omi.StorageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RangeFetchTest {
    private val t0 = 1_000_000L

    private fun due(streamedMs: Long, quietMs: Long, retryAt: Long = 0L, busy: Boolean = false): Boolean {
        val now = t0 + streamedMs
        return RangeFetch.due(now, t0, now - quietMs, retryAt, busy)
    }

    @Test fun a_quiet_room_after_the_link_settles_starts_a_fetch() {
        assertTrue(due(streamedMs = 30_000, quietMs = 15_000))
    }

    @Test fun not_straight_after_reconnecting_even_if_quiet() {
        assertFalse(due(streamedMs = 10_000, quietMs = 10_000))
    }

    @Test fun not_while_someone_is_talking() {
        assertFalse(due(streamedMs = 60_000, quietMs = 500))
        assertFalse(due(streamedMs = 60_000, quietMs = 5_000))
    }

    @Test fun no_audio_yet_counts_as_quiet_since_streaming_began() {
        assertTrue(RangeFetch.due(t0 + 20_000, t0, null, 0L, false))
        assertFalse(RangeFetch.due(t0 + 19_000, t0, null, 0L, false))
    }

    @Test fun a_short_pause_is_never_enough_however_long_it_has_streamed() {
        // Speech during a fetch is lost, so only a quiet room starts one: a conversation's pauses don't.
        assertFalse(due(streamedMs = 2 * 60_000L, quietMs = 4_000))
        assertFalse(due(streamedMs = 30 * 60_000L, quietMs = 14_000))
    }

    @Test fun a_room_that_never_goes_quiet_never_fetches() {
        // A TV on all evening: the backlog waits for the charger rather than cost what's said now.
        assertFalse(due(streamedMs = 5 * 60_000L, quietMs = 0))
        assertFalse(due(streamedMs = 4 * 60 * 60_000L, quietMs = 0))
    }

    @Test fun never_during_a_question_or_before_the_retry_time() {
        assertFalse(due(streamedMs = 10 * 60_000L, quietMs = 60_000, busy = true))
        assertFalse(due(streamedMs = 10 * 60_000L, quietMs = 60_000, retryAt = t0 + 11 * 60_000L))
        assertTrue(due(streamedMs = 10 * 60_000L, quietMs = 60_000, retryAt = t0 + 9 * 60_000L))
    }

    @Test fun a_few_seconds_stored_while_switching_is_not_worth_a_fetch() {
        assertFalse(RangeFetch.worth(null))
        assertFalse(RangeFetch.worth(0))
        assertFalse(RangeFetch.worth(100))           // about 9 s
        assertTrue(RangeFetch.worth(300))            // about 26 s
        assertEquals(60.0, RangeFetch.seconds((60 / Offload.SECONDS_PER_PACKET).toLong() + 1), 0.1)
    }

    @Test fun failures_back_off_doubling_to_an_hour() {
        val now = 5_000_000L
        var n = RangeFetch.after(now, 0, emptied = false, failed = true, took = 0, bytesPerSecond = 0.0)
        assertEquals(1, n.fails); assertEquals(now + 5 * 60_000L, n.retryAt)
        n = RangeFetch.after(now, n.fails, emptied = false, failed = true, took = 0, bytesPerSecond = 0.0)
        assertEquals(2, n.fails); assertEquals(now + 10 * 60_000L, n.retryAt)
        assertEquals(60 * 60_000L, RangeFetch.backoff(7))
        assertEquals(60 * 60_000L, RangeFetch.backoff(40))
    }

    @Test fun a_visit_that_took_nothing_counts_as_failed() {
        val n = RangeFetch.after(0, 2, emptied = false, failed = false, took = 0, bytesPerSecond = 0.0)
        assertEquals(3, n.fails); assertEquals(20 * 60_000L, n.retryAt)
    }

    @Test fun emptied_clears_everything() {
        assertEquals(RangeFetch.Next(0, 0L), RangeFetch.after(9, 3, emptied = true, failed = false, took = 10, bytesPerSecond = 80_000.0))
    }

    @Test fun a_partial_slice_gives_live_a_turn_and_a_weak_link_a_longer_one() {
        val fast = RangeFetch.after(0, 1, emptied = false, failed = false, took = 5_000, bytesPerSecond = 80_000.0)
        assertEquals(RangeFetch.Next(0, RangeFetch.BETWEEN_SLICES_MS), fast)
        val slow = RangeFetch.after(0, 1, emptied = false, failed = false, took = 500, bytesPerSecond = 3_000.0)
        assertEquals(RangeFetch.Next(0, RangeFetch.RETRY_MIN_MS), slow)
    }

    private fun status(used: Long, unread: Long, free: Long, rtc: Long) =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(used.toInt()).putInt(unread.toInt()).putInt(free.toInt()).putInt(rtc.toInt()).array()

    @Test fun storage_status_is_four_little_endian_words() {
        val s = StorageStatus.parse(status(444L * 1234, 1234, 444L * 1000, 1))!!
        assertEquals(1234, s.unreadPackets)
        assertTrue(s.rtcValid)
    }

    @Test fun storage_status_that_is_not_that_shape_is_refused() {
        assertNull(StorageStatus.parse(ByteArray(8)))
        assertNull(StorageStatus.parse(status(5, 1234, 0, 1)))       // used doesn't match unread
        assertNull(StorageStatus.parse(status(0, 0, 0, 7)))           // not a flag
    }
}
