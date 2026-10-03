package net.boswell.phone

import net.boswell.phone.assistant.DirectAsks
import net.boswell.phone.assistant.Exchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatcherAsksTest {
    private val now = 1_790_900_000.0
    private fun ex(source: String, from: Double?, to: Double?, at: Double, q: String = "What are rubies used for in watchmaking?", error: Boolean = false) =
        Exchange(1, at, source, q, "Rubies are jewel bearings [L12]: they cut friction.", 0.001, error, from, to)

    @Test fun `a question just asked on the Omi is left to its answer`() {
        // Asked 12:18:00-12:18:05, answered at 12:18:12; the watcher looks two minutes later.
        val asked = DirectAsks.recent(listOf(ex("button", now - 125, now - 120, now - 108)), now)
        val spans = DirectAsks.spans(asked)
        assertTrue(DirectAsks.within(now - 124, spans))           // the question's own line
        assertTrue(DirectAsks.within(now - 126.5, spans))         // placed a little before the tap
        assertFalse(DirectAsks.within(now - 60, spans))           // something new said after
        assertFalse(DirectAsks.within(now - 300, spans))
        val p = DirectAsks.prompt(asked, "Sam")
        assertTrue(p.contains("Q: What are rubies used for in watchmaking?"))
        assertTrue(p.contains("A: Rubies are jewel bearings: they cut friction."))
    }

    @Test fun `only direct questions, recent and answered, count`() {
        val list = listOf(
            ex("typed", null, null, now - 30),
            ex(net.boswell.phone.assistant.Assistant.TRIGGER, now - 50, now - 48, now - 40),
            ex(net.boswell.phone.assistant.Assistant.CAPTURE, now - 90, now - 85, now - 80),
            ex("watcher", null, null, now - 20),
            ex("recap", null, null, now - 20),
            ex("button", now - 1_000, now - 995, now - 990),
            ex("button", now - 20, now - 15, now - 10, error = true),
        )
        assertEquals(listOf("typed", "trigger", "capture"), DirectAsks.recent(list, now).map { it.source })
        // A typed question has no lines; its span is just when it was answered.
        val typed = DirectAsks.spans(listOf(list[0])).single()
        assertTrue(now - 30 in typed)
        assertEquals("", DirectAsks.prompt(emptyList(), "Sam"))
    }
}
