package net.boswell.phone

import net.boswell.phone.diarize.Turn
import net.boswell.phone.process.BoswellLines
import net.boswell.phone.process.Segment
import net.boswell.phone.process.SpeakerId
import net.boswell.phone.process.Transcript
import net.boswell.phone.speakers.Snr
import net.boswell.phone.speakers.SnrBackfill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class SnrBackfillTest {
    /** A quiet room, someone close from 1 to 3 s, someone far from 4 to 5 s. */
    private val pcm = ShortArray(16000 * 6) { i ->
        var x = 0.001 * sin(i * 1.7) * sin(i * 0.013)
        if (i in 16000 until 48000) x += 0.1 * sin(i * 0.2) * (0.5 + 0.5 * sin(i * 0.0007))
        if (i in 64000 until 80000) x += 0.01 * sin(i * 0.05)
        (x * 32767).toInt().toShort()
    }
    private val audio = FloatArray(pcm.size) { pcm[it] / 32768f }

    private fun id(snr: Double? = null) = SpeakerId("Ann", 0.7, "matched", 0.2, emptyList(), 3L, 2.0, snr)

    private val old = Transcript("omi_1.wav", 1.0,
        listOf(Segment(1.0, 2.0, "SPEAKER_00", "hello"), Segment(4.0, 4.5, "SPEAKER_01", "hi"),
            Segment(2.0, 3.0, BoswellLines.LABEL, "it's noon", diarized = "SPEAKER_00"), Segment(4.6, 4.95, "SPEAKER_01", "thanks")),
        mapOf("SPEAKER_00" to id(), "SPEAKER_01" to id(), "SPEAKER_02" to id()),
        mapOf("SPEAKER_00" to listOf(0.1f, 0.2f)), "whisper", 100)

    @Test fun `each voice gets its own lines' SNR, Boswell's lines in its voice included`() {
        val u = SnrBackfill.fill(old, pcm)!!
        assertEquals(Snr.db(audio, listOf(Turn(0, 1.0, 2.0), Turn(0, 2.0, 3.0)))!!, u.speakers.getValue("SPEAKER_00").snrDb!!, 1e-9)
        assertEquals(Snr.db(audio, listOf(Turn(0, 4.0, 4.5), Turn(0, 4.6, 4.95)))!!, u.speakers.getValue("SPEAKER_01").snrDb!!, 1e-9)
        assertTrue(u.speakers.getValue("SPEAKER_00").snrDb!! > u.speakers.getValue("SPEAKER_01").snrDb!! + 10)
        // A voice without lines has nothing to measure.
        assertNull(u.speakers.getValue("SPEAKER_02").snrDb)
    }

    @Test fun `nothing else changes`() {
        val u = SnrBackfill.fill(old, pcm)!!
        assertEquals(old.copy(speakers = old.speakers.mapValues { (l, sp) -> sp.copy(snrDb = u.speakers.getValue(l).snrDb) }), u)
        assertEquals(old.segments, u.segments)
        assertEquals(old.embeddings, u.embeddings)
    }

    @Test fun `a known SNR is kept`() {
        val t = old.copy(speakers = old.speakers + ("SPEAKER_01" to id(12.5)))
        val u = SnrBackfill.fill(t, pcm)!!
        assertEquals(12.5, u.speakers.getValue("SPEAKER_01").snrDb!!, 0.0)
        assertNotNull(u.speakers.getValue("SPEAKER_00").snrDb)
    }

    @Test fun `a transcript already done is left alone`() {
        val done = SnrBackfill.fill(old, pcm)!!
        assertFalse(SnrBackfill.pending(done))
        assertNull(SnrBackfill.fill(done, pcm))
        assertTrue(SnrBackfill.pending(old))
    }
}
