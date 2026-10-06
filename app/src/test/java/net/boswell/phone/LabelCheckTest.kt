package net.boswell.phone

import net.boswell.phone.diarize.VoiceModel
import net.boswell.phone.speakers.LabelCheck
import net.boswell.phone.speakers.LabelCheck.Print
import net.boswell.phone.speakers.Matching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

class LabelCheckTest {
    // The model is global: other tests expect the default back.
    private val before = Matching.model
    @Before fun model() { Matching.model = VoiceModel.SPEAKER_ID }
    @After fun restore() { Matching.model = before }

    private val dims = 256
    private var nextDim = 8          // 0..7 are people's "centers"; each voice gets its own spare dimension
    private var nextId = 1L

    /** A voice [c] like center [center]: about c*c like another such voice. */
    private fun near(center: Int, c: Double): FloatArray {
        val v = FloatArray(dims)
        v[center] = c.toFloat()
        v[nextDim++] = sqrt(1 - c * c).toFloat()
        return v
    }

    /** A voice like nobody else's. */
    private fun alone(): FloatArray = FloatArray(dims).also { it[nextDim++] = 1f }

    private fun print(person: Long, vec: FloatArray, clip: String? = "clip${nextId}.wav", speaker: String? = "SPEAKER_00") =
        Print(nextId++, person, clip, speaker, vec, 5.0)

    /** [n] voices of [person], each 0.95 like their center: about 0.90 like one another. */
    private fun person(person: Long, center: Int, n: Int) = List(n) { print(person, near(center, 0.95)) }

    @Test fun `one recording under two people is asked about first`() {
        val a = person(1, 0, 4)
        val b = person(2, 1, 4)
        val shared = near(0, 0.95)
        val items = LabelCheck.find(a + b + print(1, shared, "x.wav", "SPEAKER_01") + print(2, shared, "x.wav", "SPEAKER_01"), emptyMap())
        val t = items.first() as LabelCheck.Twice
        assertEquals(setOf(1L, 2L), t.people.toSet())
        assertEquals("x.wav|SPEAKER_01", t.sample.voice)
    }

    @Test fun `a voiceprint that matches someone else better is an outlier`() {
        val a = person(1, 0, 4)
        val b = person(2, 1, 4)
        val stray = print(1, near(1, 0.9))           // filed as 1, sounds like 2
        val items = LabelCheck.find(a + b + stray, emptyMap())
        assertEquals(1, items.size)
        val o = items.single() as LabelCheck.Outlier
        assertEquals(stray.id, o.sample.id)
        assertEquals(2L, o.like)
        assertTrue(o.likeScore > 0.85)
    }

    @Test fun `a voiceprint unlike the rest is an outlier, but not in a person who is all over the place`() {
        val a = person(1, 0, 5)
        val odd = print(1, alone())
        val items = LabelCheck.find(a + odd + person(2, 1, 4), emptyMap())
        val o = items.single() as LabelCheck.Outlier
        assertEquals(odd.id, o.sample.id)
        assertEquals(null, o.like)
        // Nobody's voices agree: no odd one out.
        val scattered = List(5) { print(3, alone()) }
        assertTrue(LabelCheck.find(scattered, emptyMap()).isEmpty())
    }

    @Test fun `a person with too few other voices has no outliers`() {
        val a = person(1, 0, LabelCheck.MIN_OTHER_VOICES - 1)
        assertTrue(LabelCheck.find(a + print(1, alone()), emptyMap()).isEmpty())
    }

    @Test fun `copies of one voice don't vouch for each other`() {
        val a = person(1, 0, 4)
        val v = alone()
        // The same recording's voice confirmed twice: still unlike the rest.
        val items = LabelCheck.find(a + print(1, v, "y.wav", "SPEAKER_00") + print(1, v, "y.wav", "SPEAKER_00"), emptyMap())
        assertEquals("y.wav|SPEAKER_00", (items.single() as LabelCheck.Outlier).sample.voice)
    }

    @Test fun `an unnamed voice likely someone known and clear of the rest is asked about`() {
        val named = person(1, 0, 4) + person(2, 1, 4)
        val likely = mapOf(10L to listOf(print(10, near(0, 0.92))))            // ~0.87 like person 1
        val u = LabelCheck.find(named, likely).single() as LabelCheck.Unnamed
        assertEquals(1L, u.personId)
        assertTrue(u.score >= Matching.model.likely)
        // Below "likely": left to the review.
        assertTrue(LabelCheck.find(named, mapOf(11L to listOf(print(11, near(0, 0.72))))).isEmpty())
        // Close to two people at once: not clearly anyone.
        val twoAlike = named + List(4) { print(3, near(0, 0.9)) }
        assertTrue(LabelCheck.find(twoAlike, likely).none { it is LabelCheck.Unnamed })
    }

    @Test fun `an unnamed voice said not to be someone isn't put to them again`() {
        val named = person(1, 0, 4) + person(2, 1, 4)
        val c = mapOf(10L to listOf(print(10, near(0, 0.92))))
        assertTrue(LabelCheck.find(named, c, rejected = { setOf(1L) }).isEmpty())
    }

    @Test fun `two people who keep matching each other are one question, then their voices one by one`() {
        val a = person(1, 0, 5)
        val b = person(2, 1, 5)
        val strays = List(LabelCheck.OVERLAP_MIN) { print(1, near(1, 0.9)) }
        val items = LabelCheck.find(a + b + strays, emptyMap())
        val o = items.single() as LabelCheck.Overlap
        assertEquals(LabelCheck.pairKey(1, 2), o.key)
        assertEquals(LabelCheck.OVERLAP_MIN, o.crossings)
        assertEquals(1L, o.sample.personId)
        assertEquals(2L, o.sampleB.personId)
        // "Different": the stray voiceprints are asked about themselves.
        val after = LabelCheck.find(a + b + strays, emptyMap(), answered = setOf(o.key))
        assertEquals(strays.map { it.id }.toSet(), after.map { (it as LabelCheck.Outlier).sample.id }.toSet())
    }

    @Test fun `an answer is remembered`() {
        val a = person(1, 0, 4)
        val b = person(2, 1, 4)
        val stray = print(1, near(1, 0.9))
        assertTrue(LabelCheck.find(a + b + stray, emptyMap(), answered = setOf("p${stray.id}")).isEmpty())
    }

    @Test fun `the list stays short`() {
        val many = person(1, 0, 60) + List(LabelCheck.MAX_ITEMS + 5) { print(1, alone()) }
        assertEquals(LabelCheck.MAX_ITEMS, LabelCheck.find(many, emptyMap()).size)
        assertTrue(LabelCheck.find(person(1, 0, 6), emptyMap()).isEmpty())
    }
}
