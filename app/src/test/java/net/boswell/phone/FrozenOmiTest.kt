package net.boswell.phone

import net.boswell.phone.capture.FrozenOmi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrozenOmiTest {
    private val now = 10_000_000L

    @Test fun a_drop_on_a_strong_link_while_streaming_is_suspicious() {
        // 10-11, 16:52: streaming 90 s on the charger beside the phone.
        assertTrue(FrozenOmi.suspicious(now, streamedMs = 90_000, rssi = -55, rssiAt = now - 20_000))
        assertTrue(FrozenOmi.suspicious(now, 90_000, FrozenOmi.STRONG_RSSI_DBM, now - FrozenOmi.RSSI_RECENT_MS))
    }

    @Test fun walking_away_is_not() {
        // The link was weak before it went: the edge of range.
        assertFalse(FrozenOmi.suspicious(now, 10 * 60_000L, rssi = -86, rssiAt = now - 15_000))
        assertFalse(FrozenOmi.suspicious(now, 10 * 60_000L, FrozenOmi.STRONG_RSSI_DBM - 1, now - 15_000))
    }

    @Test fun no_recent_reading_or_barely_streaming_says_nothing() {
        assertFalse(FrozenOmi.suspicious(now, 90_000, -55, now - FrozenOmi.RSSI_RECENT_MS - 1))
        assertFalse(FrozenOmi.suspicious(now, 90_000, null, null))
        assertFalse(FrozenOmi.suspicious(now, 10_000, -55, now - 5_000))
    }

    @Test fun it_is_said_only_after_a_few_minutes_without_it() {
        assertFalse(FrozenOmi.frozen(now, null))
        assertFalse(FrozenOmi.frozen(now, now - FrozenOmi.AFTER_MS + 1))
        assertTrue(FrozenOmi.frozen(now, now - FrozenOmi.AFTER_MS))
    }
}
