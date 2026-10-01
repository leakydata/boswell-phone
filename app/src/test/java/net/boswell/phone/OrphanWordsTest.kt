package net.boswell.phone

import net.boswell.phone.asr.Word
import net.boswell.phone.diarize.Turn
import net.boswell.phone.process.Lines
import net.boswell.phone.process.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrphanWordsTest {
    @Test fun `a word past the last turn goes to the nearest speaker`() {
        val words = listOf(Word("hello", 1.0, 1.4), Word("there", 1.5, 1.9), Word("yes", 5.0, 5.3), Word("so", 7.5, 7.7))
        val turns = listOf(Turn(0, 0.8, 2.0), Turn(1, 4.8, 5.5))
        val segs = Lines.build(words, turns)
        assertEquals(listOf("SPEAKER_00", "SPEAKER_01", "SPEAKER_01"), segs.map { it.speaker })
    }

    @Test fun `with one speaker every word is theirs`() {
        val segs = Lines.build(listOf(Word("a", 1.0, 1.2), Word("b", 20.0, 20.2)), listOf(Turn(0, 0.9, 1.5)))
        assert(segs.all { it.speaker == "SPEAKER_00" })
    }

    @Test fun `a word far from everyone stays unattributed`() {
        val words = listOf(Word("x", 1.0, 1.2), Word("y", 3.0, 3.2), Word("far", 20.0, 20.2))
        val segs = Lines.build(words, listOf(Turn(0, 0.9, 1.3), Turn(1, 2.9, 3.3)))
        assertNull(segs.last().speaker)
    }

    @Test fun `older transcripts are repaired the same way`() {
        val segs = listOf(Segment(0.0, 2.0, "SPEAKER_00", "hi"), Segment(2.5, 3.0, null, "so"), Segment(10.0, 12.0, "SPEAKER_01", "ok"))
        assertEquals("SPEAKER_00", Lines.attributeOrphans(segs)!![1].speaker)
        assertNull(Lines.attributeOrphans(listOf(Segment(0.0, 1.0, "SPEAKER_00", "a"))))
    }
}
