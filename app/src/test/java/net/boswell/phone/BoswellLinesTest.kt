package net.boswell.phone

import net.boswell.phone.asr.Word
import net.boswell.phone.diarize.Turn
import net.boswell.phone.diarize.VoiceModel
import net.boswell.phone.process.BoswellLines
import net.boswell.phone.process.BoswellLines.Spoken
import net.boswell.phone.process.Lines
import net.boswell.phone.process.Segment
import net.boswell.phone.process.SpeakerId
import net.boswell.phone.process.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoswellLinesTest {
    private val clipStart = 1_790_000_000.0
    private val answer = "Your next meeting is with Sam at the library tomorrow at 3."

    /** Words a third of a second apart from [at] seconds into the clip. */
    private fun words(text: String, at: Double): List<Word> =
        text.split(" ").mapIndexed { i, w -> Word(w, at + i * 0.35, at + i * 0.35 + 0.3) }

    /** Spoken from [at] seconds into the clip, as long as its words take. */
    private fun spoken(text: String, at: Double) =
        Spoken(clipStart + at, clipStart + at + text.split(" ").size * 0.35, text, "en-us-x-sfg-local")

    private fun claimed(by: IntArray) = by.map { it >= 0 }

    @Test fun `what was said, heard exactly, is Boswell's`() {
        val heard = words("your next meeting is with sam at the library tomorrow at three", 2.0)
        // Heard a little late, as live audio is.
        val by = BoswellLines.claim(heard, clipStart, listOf(spoken(answer, 1.4)))
        assertEquals(List(heard.size) { true }, claimed(by))
    }

    @Test fun `a few misheard words still line up`() {
        val heard = words("your next meting is with psalm at the library to morrow at three", 2.0)
        val by = BoswellLines.claim(heard, clipStart, listOf(spoken(answer, 2.0)))
        // "meting" is close enough, "psalm" stands in for "sam"; the split "to morrow"
        // has one word in place of "tomorrow" and one extra of unknown voice.
        assertEquals(listOf(true, true, true, true, true, true, true, true, true, false, true, true, true), claimed(by))
        // With the diarizer's voices, the extra word in Boswell's voice is Boswell's too.
        val withVoices = BoswellLines.claim(heard, clipStart, listOf(spoken(answer, 2.0)), speakerOf = List(heard.size) { 1 })
        assertTrue(claimed(withVoices).all { it })
    }

    @Test fun `the owner talking over Boswell keeps their words`() {
        val boswell = words("your next meeting is with sam at the library tomorrow at three", 2.0)
        val owner = listOf(Word("wait", 3.1, 3.25), Word("which", 3.3, 3.45), Word("sam", 3.5, 3.6))
        val heard = (boswell + owner).sortedBy { it.start }
        val speakerOf = heard.map { if (it in owner) 0 else 1 }
        val by = BoswellLines.claim(heard, clipStart, listOf(spoken(answer, 2.0)), speakerOf = speakerOf)
        for ((i, w) in heard.withIndex()) assertEquals(w.text + " at " + w.start, w !in owner, by[i] >= 0)

        // And the lines split by word: the owner's words keep their speaker.
        val turns = listOf(Turn(0, 3.08, 3.62), Turn(1, 1.9, 6.5))
        val segs = Lines.build(heard, turns, boswell = BooleanArray(heard.size) { by[it] >= 0 })
        assertEquals(setOf(BoswellLines.LABEL, "SPEAKER_00"), segs.mapNotNull { it.speaker }.toSet())
        assertEquals("wait which sam", segs.filter { it.speaker == "SPEAKER_00" }.joinToString(" ") { it.text })
        assertTrue(segs.filter { it.speaker == BoswellLines.LABEL }.all { it.diarized == "SPEAKER_01" })
    }

    @Test fun `other speech while Boswell talks is not its`() {
        val heard = words("can you pass me the salt please it is on the table", 2.0)
        val by = BoswellLines.claim(heard, clipStart, listOf(spoken(answer, 2.0)))
        assertTrue(by.all { it < 0 })
        // Nor a couple of common words strung through someone else's sentence.
        val near = words("at the moment we are with the kids at the park", 2.0)
        assertTrue(BoswellLines.claim(near, clipStart, listOf(spoken(answer, 2.0))).all { it < 0 })
    }

    @Test fun `with nothing said, or said at another time, nothing is Boswell's`() {
        val heard = words("your next meeting is with sam at the library tomorrow at three", 2.0)
        assertTrue(BoswellLines.claim(heard, clipStart, emptyList()).all { it < 0 })
        // The same words, but Boswell said them a minute earlier: someone repeating it.
        assertTrue(BoswellLines.claim(heard, clipStart, listOf(spoken(answer, -60.0))).all { it < 0 })
        // Late by more than the latency allows.
        assertTrue(BoswellLines.claim(heard, clipStart, listOf(spoken(answer, 2.0 - 4.6 - 4.0))).all { it < 0 })
    }

    @Test fun `an answer cut by the clip's edge lines up with the part that is here`() {
        val long = "$answer Bring the notes from Tuesday and the budget sheet, and ask Sam about the venue for Friday's dinner."
        // Only the first four words made it into this clip; the rest is in the next.
        val heard = words("your next meeting is", 28.6)
        val by = BoswellLines.claim(heard, clipStart, listOf(spoken(long, 28.6)), clipSeconds = 30.0)
        assertTrue(by.all { it >= 0 })
        // In the middle of a clip, four words of a long answer is too little to be sure.
        val mid = words("your next meeting is", 10.0)
        assertTrue(BoswellLines.claim(mid, clipStart, listOf(spoken(long, 10.0)), clipSeconds = 30.0).all { it < 0 })
    }

    @Test fun `a voice is Boswell's only when clearly more like it than anyone`() {
        for (m in VoiceModel.entries) {
            assertTrue(BoswellLines.soundsLikeBoswell(0.9, 0.4, m.matchHigh, m.marginStrong))
            assertFalse(BoswellLines.soundsLikeBoswell(m.matchHigh - 0.01, 0.0, m.matchHigh, m.marginStrong))
            assertFalse(BoswellLines.soundsLikeBoswell(0.9, 0.9 - m.marginStrong + 0.01, m.matchHigh, m.marginStrong))
        }
    }

    @Test fun `words compare without case, punctuation or spelled-out numbers`() {
        assertEquals(listOf("its", "3", "pm"), BoswellLines.tokens("It's three p.m."))
        assertTrue(BoswellLines.same("library", "librery"))
        assertFalse(BoswellLines.same("sam", "ham"))
        assertFalse(BoswellLines.same("3", "4"))
    }

    @Test fun `a person named for Boswell's voice is found by name`() {
        assertTrue(BoswellLines.isBoswellName("Boswell Male Voice"))
        assertTrue(BoswellLines.isBoswellName(" boswell"))
        assertFalse(BoswellLines.isBoswellName("Nathan Jones"))
        assertFalse(BoswellLines.isBoswellName("Mr Boswell"))
        assertFalse(BoswellLines.isBoswellName(null))
    }

    @Test fun `an old transcript's voice becomes Boswell's, once`() {
        fun id(name: String?, pid: Long?, secs: Double) = SpeakerId(name, 0.9, "matched", 0.3, emptyList(), pid, secs)
        val t = Transcript("omi_1.wav", 0.0,
            listOf(Segment(0.0, 2.0, "SPEAKER_00", "what are rubies for"), Segment(3.0, 9.0, "SPEAKER_01", "rubies are jewel bearings", original = "rubys are jewel bearings", edited = true)),
            mapOf("SPEAKER_00" to id("Nathan", 1, 2.0), "SPEAKER_01" to id("Boswell Male Voice", 7, 6.0)),
            mapOf("SPEAKER_01" to listOf(1f, 0f)), "test", 0)
        val u = BoswellLines.relabel(t, setOf("SPEAKER_01"))!!
        assertEquals(listOf("SPEAKER_00", BoswellLines.LABEL), u.segments.map { it.speaker })
        assertEquals("SPEAKER_01", u.segments[1].diarized)
        assertTrue(u.segments[1].edited)                       // a hand fix stays
        assertTrue(BoswellLines.isBoswell(u.speakers["SPEAKER_01"]))
        assertEquals(null, u.speakers["SPEAKER_01"]!!.personId)
        assertEquals(6.0, u.speakers[BoswellLines.LABEL]!!.seconds, 1e-9)
        assertEquals(t.speakers["SPEAKER_00"], u.speakers["SPEAKER_00"])
        assertEquals(t.embeddings, u.embeddings)                // kept, for "Not Boswell"
        assertEquals(null, BoswellLines.relabel(u, setOf("SPEAKER_01")))
        assertEquals(null, BoswellLines.relabel(t, emptySet()))
    }
}
