package net.boswell.phone

import net.boswell.phone.assistant.QuestionCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class QuestionCaptureTest {
    private fun quiet() = ShortArray(320) { ((it % 7) - 3).toShort() }
    private fun voice(k: Int) = ShortArray(320) { (6000 * sin((k * 320 + it) * 0.07)).toInt().toShort() }

    @Test fun `ends after a pause that follows speech`() {
        val q = QuestionCapture()
        repeat(15) { q.add(quiet()) }
        repeat(100) { q.add(voice(it)) }
        assertTrue(q.speechStarted)
        repeat(99) { q.add(quiet()) }          // a 2 s pause mid-question doesn't end it...
        assertFalse(q.done)
        q.add(quiet())                          // ...one past 2 s does
        assertTrue(q.done)
        assertEquals((15 + 100 + 100) * 320, q.audio().size)
    }

    @Test fun `silence alone is no question`() {
        val q = QuestionCapture()
        repeat(300) { q.add(quiet()) }
        assertTrue(q.done)
        assertFalse(q.speechStarted)
    }

    @Test fun `the stream stopping after speech ends it`() {
        val q = QuestionCapture()
        repeat(10) { q.add(quiet()) }
        repeat(50) { q.add(voice(it)) }
        q.idle()
        assertTrue(q.done)
    }

    @Test fun `the stream stopping before speech does not`() {
        val q = QuestionCapture()
        repeat(10) { q.add(quiet()) }
        q.idle()
        assertFalse(q.done)
    }

    @Test fun `soft speech at the Omi's level still counts`() {
        val q = QuestionCapture()
        repeat(15) { q.add(ShortArray(320) { ((it % 7 - 3) * 0.0006 * 32768).toInt().toShort() }) }   // room ~0.0012
        repeat(50) { k -> q.add(ShortArray(320) { (kotlin.math.sin((k * 320 + it) * 0.09) * 0.008 * 32768).toInt().toShort() }) } // ~0.0057 rms
        assertTrue(q.speechStarted)
    }
}
