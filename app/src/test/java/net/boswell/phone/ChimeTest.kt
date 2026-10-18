package net.boswell.phone

import net.boswell.phone.assistant.AssistantPrefs
import net.boswell.phone.assistant.ChimeRules
import net.boswell.phone.assistant.Claims
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChimeTest {
    private var n = 0L
    private fun line(key: String?, words: Int, t0: Double = n.toDouble()) =
        Claims.Line(++n, t0, t0 + 3, "clip", 0.0, key, key ?: "someone", List(words) { "word" }.joinToString(" "))

    @Test fun it_looks_only_when_two_or_more_voices_are_talking_and_something_is_new() {
        val room = listOf(line("p1", 30), line("p2", 30), line("p1", 10))
        assertTrue(ChimeRules.talking(room, room.takeLast(2)))
        // One voice alone (dictating, a lecture): not a room.
        val alone = listOf(line("p1", 40), line("p1", 40))
        assertFalse(ChimeRules.talking(alone, alone))
        // Too little said, or nothing new since the last look.
        val brief = listOf(line("p1", 10), line("p2", 10))
        assertFalse(ChimeRules.talking(brief, brief))
        assertFalse(ChimeRules.talking(room, listOf(line("p2", 3))))
    }

    @Test fun a_pause_is_four_seconds_without_audio() {
        assertTrue(ChimeRules.quiet(10_000, null))
        assertTrue(ChimeRules.quiet(10_000, 6_000))
        assertFalse(ChimeRules.quiet(10_000, 7_000))
    }

    @Test fun silence_and_odd_replies_say_nothing_and_a_chime_is_read() {
        assertNull(ChimeRules.parse("""{"chime": false}"""))
        assertTrue(ChimeRules.silent("""{"chime": false}"""))
        assertFalse(ChimeRules.silent("sure, here you go"))
        assertNull(ChimeRules.parse("not json"))
        assertNull(ChimeRules.parse("""{"chime": true, "text": "  "}"""))
        assertEquals("Octopuses have three hearts.", ChimeRules.parse("""Here: {"chime": true, "text": " Octopuses have three hearts. "}"""))
        // Too long to be a remark.
        assertNull(ChimeRules.parse("""{"chime": true, "text": "${List(60) { "w" }.joinToString(" ")}"}"""))
    }

    @Test fun the_slower_the_pace_the_longer_the_gap() {
        val gaps = AssistantPrefs.ChimePace.entries.map { it.gapSeconds }
        assertEquals(gaps.sortedDescending(), gaps)
    }
}
