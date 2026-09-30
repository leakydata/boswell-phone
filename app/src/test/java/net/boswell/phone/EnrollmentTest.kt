package net.boswell.phone

import net.boswell.phone.setup.Enrollment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

/** The Omi's real levels: room ~0.002 rms, a person reading ~0.006 median. */
class EnrollmentTest {
    private val rnd = Random(1)
    private fun room() = ShortArray(320) { (rnd.nextDouble(-1.0, 1.0) * 0.0035 * 32768).toInt().toShort() }
    private fun voice(k: Int, amp: Double = 0.009) = ShortArray(320) { ((sin((k * 320 + it) * 0.09) * amp * 1.41 + rnd.nextDouble(-1.0, 1.0) * 0.002) * 32768).toInt().toShort() }

    @Test fun `quiet Omi speech reaches the target and finishes`() {
        Enrollment.start()
        repeat(100) { Enrollment.feed(room()) }           // 2 s of room first
        var k = 0
        while (Enrollment.state.value.active && k < 2_000) { Enrollment.feed(voice(k++)); if (k % 50 == 0) Enrollment.feed(room()) }
        assertFalse("should have finished", Enrollment.state.value.active)
        assertTrue(Enrollment.state.value.heardSeconds >= 15.0)
    }

    @Test fun `room noise alone is not counted as voice`() {
        Enrollment.start()
        repeat(1_500) { Enrollment.feed(room()) }
        assertTrue(Enrollment.state.value.active)
        assertTrue("heard ${Enrollment.state.value.heardSeconds}", Enrollment.state.value.heardSeconds < 3.0)
    }

    @Test fun `stopping reading after enough speech finishes it`() {
        Enrollment.start()
        repeat(100) { Enrollment.feed(room()) }
        repeat(500) { Enrollment.feed(voice(it)) }           // ~10 s of voice
        Enrollment.paused()
        assertFalse(Enrollment.state.value.active)
    }

    @Test fun `stopping too early does not finish it`() {
        Enrollment.start()
        repeat(100) { Enrollment.feed(room()) }
        repeat(150) { Enrollment.feed(voice(it)) }           // ~3 s
        Enrollment.paused()
        assertTrue(Enrollment.state.value.active)
    }
}
