package net.boswell.phone

import net.boswell.phone.diarize.Turn
import net.boswell.phone.speakers.Snr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sin

class SnrTest {
    /** A quiet room, someone close from 1 to 3 s, someone far from 4 to 5 s. */
    private val audio = FloatArray(16000 * 6) { i -> (0.001 * sin(i * 1.7) * sin(i * 0.013)).toFloat() }.also { a ->
        for (i in 16000 until 48000) a[i] += (0.1 * sin(i * 0.2) * (0.5 + 0.5 * sin(i * 0.0007))).toFloat()
        for (i in 64000 until 80000) a[i] += (0.01 * sin(i * 0.05)).toFloat()
    }

    @Test fun `the same numbers as the measurement and the home server`() {
        // snr_cost.snr_db (the measurement's own, and pipeline.snr_db) on this recording.
        assertEquals(40.64926524949682, Snr.db(audio, listOf(Turn(0, 1.0, 3.0)))!!, 1e-6)
        assertEquals(23.27665674068814, Snr.db(audio, listOf(Turn(1, 4.0, 4.5), Turn(1, 4.6, 4.95)))!!, 1e-6)
        assertEquals(26.99018963392914, Snr.db(audio, listOf(Turn(0, 2.0, 2.01)))!!, 1e-6)      // shorter than a frame
    }

    @Test fun `numpy's percentile`() {
        val v = doubleArrayOf(4.0, 1.0, 3.0, 2.0)
        assertEquals(1.3, Snr.percentile(v, 10.0), 1e-12)
        assertEquals(1.9, Snr.percentile(v, 30.0), 1e-12)
        assertEquals(4.0, Snr.percentile(v, 100.0), 1e-12)
    }

    @Test fun `no turns, no number`() = assertNull(Snr.db(audio, emptyList()))
}
