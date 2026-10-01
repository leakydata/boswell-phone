package net.boswell.phone

import net.boswell.phone.asr.SpeechOnly
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechOnlyTest {
    private val spans = listOf(2.0 to 4.0, 4.6 to 5.0, 20.0 to 22.0)
    private val pieces = SpeechOnly.plan(spans, 30.0)

    @Test fun `close stretches join and long silences shrink`() {
        // 1.5-5.5 (two spans 0.6 s apart join), 19.5-22.5
        assertEquals(2, pieces.size)
        assertEquals(1.5, pieces[0].clip, 1e-9); assertEquals(4.0, pieces[0].len, 1e-9)
        assertEquals(19.5, pieces[1].clip, 1e-9); assertEquals(3.0, pieces[1].len, 1e-9)
        assertEquals(4.0, pieces[1].sent, 1e-9)
    }

    @Test fun `times map back onto the clip`() {
        assertEquals(2.0, SpeechOnly.toClip(0.5, pieces), 1e-9)     // 0.5 s into what was sent = 2.0 s into the clip
        assertEquals(20.0, SpeechOnly.toClip(4.5, pieces), 1e-9)    // second piece starts at 4.0 sent / 19.5 clip
        assertEquals(22.5, SpeechOnly.toClip(99.0, pieces), 1e-9)   // past the end stays inside the last piece
    }

    @Test fun `padding stays inside the clip`() {
        val p = SpeechOnly.plan(listOf(0.1 to 1.0, 29.8 to 30.0), 30.0)
        assertEquals(0.0, p[0].clip, 1e-9); assertEquals(30.0, p[1].clip + p[1].len, 1e-9)
    }
}
