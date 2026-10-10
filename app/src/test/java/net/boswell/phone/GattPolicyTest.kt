package net.boswell.phone

import net.boswell.phone.omi.GattPolicy
import net.boswell.phone.omi.Offload
import net.boswell.phone.omi.RefusedInARow
import net.boswell.phone.sync.OmiSync
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GattPolicyTest {
    private val now = 1_000_000L

    @Test fun a_refusal_with_nothing_hung_gets_a_short_retry() {
        assertEquals(now + GattPolicy.REFUSED_RETRY_MS, GattPolicy.retryUntil(now, 0L))
    }

    @Test fun after_a_timeout_the_next_operation_waits_for_the_stack_to_let_go() {
        // Ours timed out 6 s ago; the stack holds that request up to 30 s.
        val hung = now - 6_000
        assertEquals(hung + GattPolicy.STACK_HOLDS_MS, GattPolicy.retryUntil(now, hung))
        assertTrue(GattPolicy.retryUntil(now, hung) - now > 20_000)
    }

    @Test fun a_timeout_long_past_holds_nothing() {
        assertEquals(now + GattPolicy.REFUSED_RETRY_MS, GattPolicy.retryUntil(now, now - 60_000))
    }

    @Test fun retries_start_quick_and_settle() {
        assertEquals(300L, GattPolicy.pause(1))
        assertEquals(600L, GattPolicy.pause(2))
        assertEquals(1_500L, GattPolicy.pause(5))
        assertEquals(1_500L, GattPolicy.pause(40))
    }

    @Test fun a_few_refusals_in_a_row_give_up_on_the_connection() {
        val r = RefusedInARow(3)
        assertFalse(r.refused())
        assertFalse(r.refused())
        assertTrue(r.refused())
    }

    @Test fun any_operation_taken_clears_the_count() {
        val r = RefusedInARow(3)
        r.refused(); r.refused()
        r.taken()
        assertEquals(0, r.count)
        assertFalse(r.refused())
        assertFalse(r.refused())
        assertTrue(r.refused())
    }

    @Test fun the_stop_command_is_the_firmwares_0x03() {
        assertArrayEquals(byteArrayOf(0x03), Offload.stopCommand())
    }

    @Test fun the_first_batch_is_a_small_probe() {
        assertEquals(OmiSync.PROBE_BATCH, OmiSync.batchFor(60_000, null, 120_000))
        assertEquals(37, OmiSync.batchFor(60_000, null, 37))
    }

    @Test fun later_batches_fill_the_time_left_at_the_measured_rate() {
        // 28 kB/s with 30 s left: about 1,890 packets' worth, capped.
        assertEquals(OmiSync.MAX_BATCH, OmiSync.batchFor(30_000, 28_000.0, 120_000))
        // 28 kB/s with 5 s left: 315 packets, so the batch ends near the deadline instead of 30 s past it.
        assertEquals(315, OmiSync.batchFor(5_000, 28_000.0, 120_000))
        // Almost no time left still asks for something worth a round trip.
        assertEquals(OmiSync.MIN_BATCH, OmiSync.batchFor(100, 28_000.0, 120_000))
        assertEquals(12, OmiSync.batchFor(30_000, 89_000.0, 12))
    }
}
