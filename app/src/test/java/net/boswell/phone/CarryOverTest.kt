package net.boswell.phone

import net.boswell.phone.asr.Word
import net.boswell.phone.diarize.Turn
import net.boswell.phone.process.BoswellLines
import net.boswell.phone.process.CarryOver
import net.boswell.phone.process.CarryOver.Row
import net.boswell.phone.process.Lines
import net.boswell.phone.process.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarryOverTest {
    private val s0 = "SPEAKER_00"
    private val s1 = "SPEAKER_01"
    private val s2 = "SPEAKER_02"

    private fun voices(old: List<Segment>, new: List<Segment>) = CarryOver.speakers(CarryOver.spans(old), CarryOver.spans(new))

    @Test fun `an edited line whose times shift slightly keeps its correction`() {
        val old = listOf(
            Segment(0.0, 3.0, s0, "Meet Sam at the trailhead", original = "meet psalm at the trail head", edited = true),
            Segment(3.5, 6.0, s1, "sounds good"))
        val new = listOf(
            Segment(0.12, 3.05, s0, "meet sam at the trail head"),
            Segment(3.6, 6.1, s1, "sounds good to me"))
        val out = CarryOver.lines(old, new, voices(old, new))
        assertEquals(2, out.size)
        assertEquals(Segment(0.12, 3.05, s0, "Meet Sam at the trailhead", original = "meet sam at the trail head", edited = true), out[0])
        // A line nobody corrected is the new transcriber's.
        assertEquals(new[1], out[1])
    }

    @Test fun `a named voice whose label changes from S1 to S0 follows its speech`() {
        val old = listOf(Segment(0.0, 4.0, s0, "hi"), Segment(4.5, 9.0, s1, "hello there"))
        val new = listOf(Segment(0.1, 4.0, s1, "hi"), Segment(4.4, 9.2, s0, "hello there"))
        val map = voices(old, new)
        assertEquals(mapOf(s0 to s1, s1 to s0), map)
        // Swapped: the confirmed voiceprint and the name given both move with the voice.
        val print = Row(CarryOver.VOICEPRINTS, 7, s1, keep = true)
        val named = Row("assigned", 3, s1, keep = true)
        val other = Row("assigned", 4, s0, keep = true)
        val plan = CarryOver.relabel(listOf(print, named, other), map)
        assertEquals(mapOf(print to s0, named to s0, other to s1), plan.move)
        assertTrue(plan.unlink.isEmpty() && plan.drop.isEmpty())
    }

    @Test fun `the most shared speech wins, one label each`() {
        // Two old voices mostly in one new voice: only the larger goes there; the other has nowhere to go.
        val map = CarryOver.speakers(
            listOf(CarryOver.Span(s0, 0.0, 5.0), CarryOver.Span(s1, 5.0, 7.0)),
            listOf(CarryOver.Span(s0, 0.0, 7.0)))
        assertEquals(s0, map[s0])
        assertNull(map[s1])
    }

    @Test fun `a corrected line split in two by the new transcript becomes one line again`() {
        val old = listOf(Segment(0.0, 6.0, s0, "We'll take the 8:15 train to Boston", original = "will take the eight fifteen train to boston", edited = true))
        val new = listOf(Segment(0.0, 3.0, s0, "we'll take the eight"), Segment(3.1, 6.0, s1, "fifteen train to boston"))
        val out = CarryOver.lines(old, new, voices(old, new))
        assertEquals(listOf(Segment(0.0, 6.0, s0, "We'll take the 8:15 train to Boston",
            original = "we'll take the eight fifteen train to boston", edited = true)), out)
    }

    @Test fun `two corrected lines in one new line each keep their own`() {
        val old = listOf(
            Segment(0.0, 2.0, s0, "Hi Ana", original = "hi anna", edited = true),
            Segment(2.2, 5.0, s0, "How was Porto", original = "how was port oh", edited = true))
        val new = listOf(Segment(0.0, 5.0, s0, "hi anna how was porto"))
        val out = CarryOver.lines(old, new, voices(old, new))
        assertEquals(listOf("Hi Ana", "How was Porto"), out.map { it.text })
        // The second keeps to its own time (within CarryOver.EDGE), not the whole new line's.
        assertEquals(listOf(0.0, 2.0, 2.05, 5.0), out.flatMap { listOf(it.start, it.end) }.map { Math.round(it * 100) / 100.0 })
        assertTrue(out.all { it.edited && it.speaker == s0 })
    }

    @Test fun `new lines break where a corrected line begins and ends`() {
        val words = "one two three four five six".split(" ").mapIndexed { i, w -> Word(w, i * 0.5, i * 0.5 + 0.4) }
        val turns = listOf(Turn(0, 0.0, 3.0))
        val lines = Lines.build(words, turns, keep = listOf(1.0 to 1.9))
        assertEquals(listOf("one two", "three four", "five six"), lines.map { it.text })
    }

    @Test fun `a not them answer follows the voice, or goes with it`() {
        val old = listOf(Segment(0.0, 3.0, s1, "it's me"), Segment(5.0, 5.4, s2, "yes"))
        val new = listOf(Segment(0.0, 3.1, s0, "it's me"))
        val map = voices(old, new)
        assertEquals(s0, map[s1])
        assertNull(map[s2])
        val no = Row("rejections", 1, s1, keep = true)
        val gone = Row("rejections", 2, s2, keep = true)
        val plan = CarryOver.relabel(listOf(no, gone), map)
        assertEquals(mapOf(no to s0), plan.move)
        assertEquals(listOf(gone), plan.drop)
    }

    @Test fun `Boswell's lines map by the voice they were heard in, and Not Boswell's go back to it`() {
        // Before: Boswell's answer (heard in S1) was corrected; after "Not Boswell" the new transcript files it as an ordinary voice.
        val old = listOf(
            Segment(0.0, 2.0, s0, "what's next"),
            Segment(2.5, 6.0, BoswellLines.LABEL, "Your next meeting is with Sam", original = "your next meeting is with psalm", edited = true, diarized = s1))
        val new = listOf(Segment(0.0, 2.0, s1, "what's next"), Segment(2.4, 6.0, s0, "your next meeting is with sam"))
        val map = voices(old, new)
        assertEquals(mapOf(s0 to s1, s1 to s0), map)
        val out = CarryOver.lines(old, new, map)
        assertEquals(Segment(2.4, 6.0, s0, "Your next meeting is with Sam", original = "your next meeting is with sam", edited = true), out[1])
        // And a Boswell line heard again as Boswell's stays Boswell's, remembering its new voice.
        val again = listOf(new[0], Segment(2.4, 6.0, BoswellLines.LABEL, "your next meeting is with sam", diarized = s0))
        assertEquals(Segment(2.4, 6.0, BoswellLines.LABEL, "Your next meeting is with Sam", original = "your next meeting is with sam", edited = true, diarized = s0),
            CarryOver.lines(old, again, map)[1])
    }

    @Test fun `a speaker that disappears keeps what can be kept and drops nothing silently`() {
        val old = listOf(Segment(0.0, 4.0, s0, "so anyway"), Segment(10.0, 10.3, s2, "Okay", original = "oh kay", edited = true))
        val new = listOf(Segment(0.0, 4.0, s1, "so anyway"))
        val map = voices(old, new)
        assertNull(map[s2])
        // The correction stays, with no voice to put it to.
        val out = CarryOver.lines(old, new, map)
        assertEquals(Segment(10.0, 10.3, null, "Okay", original = "oh kay", edited = true), out.last())
        // A voiceprint named by hand stays a reference; an automatic sighting goes; the name given there goes, reported by SpeakerStore.
        val named = Row(CarryOver.VOICEPRINTS, 1, s2, keep = true)
        val auto = Row(CarryOver.VOICEPRINTS, 2, s2, keep = false)
        val given = Row("assigned", 3, s2, keep = true)
        val plan = CarryOver.relabel(listOf(named, auto, given), map)
        assertEquals(listOf(named), plan.unlink)
        assertEquals(listOf(auto, given), plan.drop)
    }

    @Test fun `words changed is word-level and over the longer text`() {
        val ten = "one two three four five six seven eight nine ten"
        assertEquals(0.0, CarryOver.wordsChanged(ten, "One two, three four five six seven eight nine ten."), 1e-9)
        assertEquals(0.1, CarryOver.wordsChanged(ten, ten.replace("five", "fife")), 1e-9)
        assertTrue(CarryOver.wordsChanged(ten, "one two three four") > CarryOver.NOTES_CHANGE)
        assertEquals(1.0, CarryOver.wordsChanged("", "something new"), 1e-9)
    }
}
