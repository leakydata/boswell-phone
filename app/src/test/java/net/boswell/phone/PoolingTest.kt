package net.boswell.phone

import net.boswell.phone.diarize.VoiceModel
import net.boswell.phone.speakers.Matching
import net.boswell.phone.speakers.Pooling
import net.boswell.phone.speakers.Pooling.Voice
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

class PoolingTest {
    private val before = Matching.model
    @Before fun model() { Matching.model = VoiceModel.SPEAKER_ID }
    @After fun restore() { Matching.model = before }

    private fun v(vararg x: Float) = Matching.unit(x)
    private val me = v(1f, 0f, 0f)
    private val clip = "omi_1791162549.wav"

    @Test fun `a recording's time is in its name`() {
        assertEquals(1791162549.0, Pooling.clipTime(clip)!!, 0.0)
        assertEquals(1791162549.0, Pooling.clipTime("omi_1791162549-1.wav")!!, 0.0)
        assertNull(Pooling.clipTime("question.wav"))
    }

    @Test fun `a clean voice is averaged with the clean voices like it just before, by their speech`() {
        val earlier = listOf(
            Voice("omi_1791162539.wav", 1791162539.0, v(0f, 1f, 0f), 20.0, 15.0),   // 10 s before, clean, 0 like: no
            Voice("omi_1791162529.wav", 1791162529.0, v(0.6f, 0.8f, 0f), 20.0, 15.0), // 20 s before, 0.6 like, 20 s (counts as 8)
        )
        val p = Pooling.pooled(me, 2.0, 14.0, clip, earlier)
        // 2 x (1, 0, 0) + 8 x (0.6, 0.8, 0) = (6.8, 6.4, 0)
        val n = sqrt(6.8 * 6.8 + 6.4 * 6.4)
        assertArrayEquals(floatArrayOf((6.8 / n).toFloat(), (6.4 / n).toFloat(), 0f), p, 1e-6f)
    }

    @Test fun `only clean voices, only before, only within 30 s, only alike`() {
        val like = v(0.9f, 0.43f, 0f)
        fun one(clip: String, t: Double, vec: FloatArray = like, snr: Double = 15.0) = listOf(Voice(clip, t, vec, 3.0, snr))
        val t = 1791162549.0
        assertArrayEquals(me, Pooling.pooled(me, 2.0, 11.9, clip, one("omi_1.wav", t - 10)), 0f)              // this voice isn't clean
        assertArrayEquals(me, Pooling.pooled(me, 2.0, null, clip, one("omi_1.wav", t - 10)), 0f)              // or its loudness unknown
        assertArrayEquals(me, Pooling.pooled(me, 2.0, 14.0, clip, one("omi_1.wav", t - 10, snr = 11.9)), 0f)  // that one isn't
        assertArrayEquals(me, Pooling.pooled(me, 2.0, 14.0, clip, one("omi_1.wav", t - 31)), 0f)              // too long before
        assertArrayEquals(me, Pooling.pooled(me, 2.0, 14.0, clip, one("omi_1.wav", t + 5)), 0f)               // after
        assertArrayEquals(me, Pooling.pooled(me, 2.0, 14.0, clip, one(clip, t - 10)), 0f)                     // the same recording
        assertArrayEquals(me, Pooling.pooled(me, 2.0, 14.0, clip, one("omi_1.wav", t - 10, v(0.39f, 0.92f, 0f))), 0f)   // under 0.40 alike
        assert(Pooling.pooled(me, 2.0, 14.0, clip, one("omi_1.wav", t - 30))[1] > 0.1f)                      // 30 s exactly is in
        Matching.model = VoiceModel.WESPEAKER
        assertArrayEquals(me, Pooling.pooled(me, 2.0, 14.0, clip, one("omi_1.wav", t - 10)), 0f)              // measured with ReDimNet2 only
    }
}
