package net.boswell.phone

import net.boswell.phone.home.HomeServer.Fallback
import net.boswell.phone.process.ProcessingWorker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargerRuleTest {
    private val big = ProcessingWorker.BIG_DOWNLOAD + 1

    @Test fun the_phone_transcribes_without_home() {
        assertTrue(ProcessingWorker.phoneTranscribes(homeEnabled = false, homeReachable = true, fallback = Fallback.WAIT))
    }

    @Test fun home_reachable_takes_the_work() {
        assertFalse(ProcessingWorker.phoneTranscribes(homeEnabled = true, homeReachable = true, fallback = Fallback.PHONE))
    }

    @Test fun home_unreachable_hands_it_to_the_phone_only_with_the_phone_fallback() {
        assertTrue(ProcessingWorker.phoneTranscribes(homeEnabled = true, homeReachable = false, fallback = Fallback.PHONE))
        assertFalse(ProcessingWorker.phoneTranscribes(homeEnabled = true, homeReachable = false, fallback = Fallback.WAIT))
    }

    @Test fun a_big_download_waits_for_the_charger_only_when_the_phone_would_do_it() {
        assertTrue(ProcessingWorker.backlogWaits(rule = true, charging = false, phoneTranscribes = true, downloads = big))
        assertFalse(ProcessingWorker.backlogWaits(rule = true, charging = false, phoneTranscribes = false, downloads = big))
    }

    @Test fun charging_rule_off_or_small_download_never_waits() {
        assertFalse(ProcessingWorker.backlogWaits(rule = true, charging = true, phoneTranscribes = true, downloads = big))
        assertFalse(ProcessingWorker.backlogWaits(rule = false, charging = false, phoneTranscribes = true, downloads = big))
        assertFalse(ProcessingWorker.backlogWaits(rule = true, charging = false, phoneTranscribes = true, downloads = ProcessingWorker.BIG_DOWNLOAD))
    }
}
