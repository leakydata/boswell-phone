package net.boswell.phone

import net.boswell.phone.speakers.Matching
import net.boswell.phone.speakers.Matching.Decision
import net.boswell.phone.speakers.Matching.Reference
import net.boswell.phone.speakers.SpeakerStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatchingTest {
    @Test fun `decide follows the desktop rules`() {
        assertEquals(Decision.MATCHED, Matching.decide(0.80, 0.20))
        assertEquals(Decision.UNCERTAIN, Matching.decide(0.80, 0.10))   // high but not clear
        assertEquals(Decision.MATCHED, Matching.decide(0.65, 0.30))     // clear of the field
        assertEquals(Decision.UNCERTAIN, Matching.decide(0.65, 0.20))
        assertEquals(Decision.NONE, Matching.decide(0.50, 0.40))
        assertEquals(Decision.MATCHED, Matching.decide(0.76, null))     // only one person known
        assertEquals(Decision.UNCERTAIN, Matching.decide(0.70, null))
    }

    private fun v(vararg x: Float) = Matching.unit(x)

    @Test fun `the margin is between people, not rows`() {
        // Person 1 owns two near-identical rows; a row margin would be ~0.
        val refs = listOf(
            Reference(1, 1, v(1f, 0f, 0f)), Reference(2, 1, v(0.99f, 0.05f, 0f)),
            Reference(3, 2, v(0f, 1f, 0f)),
        )
        val r = Matching.match(v(0.97f, 0.1f, 0f), refs)
        assertEquals(Decision.MATCHED, r.decision)
        assertEquals(1L, r.personId)
        assert(r.margin!! > 0.8)
    }

    @Test fun `a voice below the cluster bar starts a new cluster`() {
        val refs = listOf(Reference(1, 7, v(1f, 0f)))
        assertEquals(7L, Matching.bestCluster(v(0.9f, 0.1f), refs).first)
        assertNull(Matching.bestCluster(v(0.5f, 0.5f), refs).first)
    }

    @Test fun `voiceprints are stored as little-endian float32 like the desktop`() {
        val x = floatArrayOf(0.25f, -1.5f, 3f)
        val b = SpeakerStore.pack(x)
        assertEquals(12, b.size)
        assertEquals(0x3E800000, java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertArrayEquals(x, SpeakerStore.unpack(b), 0f)
    }

    @Test fun `with one named person, being clear of the unnamed voices is enough`() {
        fun v(vararg x: Float) = Matching.unit(floatArrayOf(*x))
        val me = Matching.Reference(1, 1, v(1f, 0f, 0f))
        val voice = v(0.66f, 0.75f, 0f)             // ~0.66 like me: below the strict 0.75
        assertEquals(Matching.Decision.UNCERTAIN, Matching.match(voice, listOf(me)).decision)
        val farStranger = Matching.Reference(2, 9, v(0f, 0f, 1f))       // 0 like the voice
        assertEquals(Matching.Decision.MATCHED, Matching.match(voice, listOf(me), listOf(farStranger)).decision)
        val nearStranger = Matching.Reference(3, 9, v(0.5f, 0.86f, 0f))  // as like the voice as I am
        assertEquals(Matching.Decision.UNCERTAIN, Matching.match(voice, listOf(me), listOf(nearStranger)).decision)
    }

    @Test fun `an unnamed voice closer than the owner never undoes a clear match`() {
        fun v(vararg x: Float) = Matching.unit(floatArrayOf(*x))
        val me = Matching.Reference(1, 1, v(1f, 0f, 0f))
        val voice = v(0.86f, 0.51f, 0f)                                   // 0.86 like me: clears 0.75 alone
        val fragment = Matching.Reference(2, 9, v(0.9f, 0.43f, 0f))       // an unnamed voice even closer
        assertEquals(Matching.Decision.MATCHED, Matching.match(voice, listOf(me), listOf(fragment)).decision)
    }

    @Test fun `voiceprints from different models never match, and never crash`() {
        val old = FloatArray(256) { if (it == 0) 1f else 0f }
        val new = FloatArray(192) { if (it == 0) 1f else 0f }
        org.junit.Assert.assertEquals(-1.0, net.boswell.phone.speakers.Matching.dot(old, new), 0.0)
        val r = net.boswell.phone.speakers.Matching.match(old, listOf(net.boswell.phone.speakers.Matching.Reference(1, 7, new)))
        org.junit.Assert.assertEquals(net.boswell.phone.speakers.Matching.Decision.NONE, r.decision)
    }

    @Test fun `the owner is matched at likely when clear of everyone, nobody else is`() {
        // Each row scores its first coordinate against the voice.
        fun like(s: Double, axis: Int) = FloatArray(5).also { it[0] = s.toFloat(); it[axis] = kotlin.math.sqrt(1 - s * s).toFloat() }
        val voice = floatArrayOf(1f, 0f, 0f, 0f, 0f)
        val refs = listOf(Reference(1, 1, like(0.74, 1)), Reference(2, 2, like(0.60, 2)))   // 0.74, 0.14 ahead: uncertain today
        val field = listOf(Reference(3, 9, like(0.66, 3)))                                  // 0.08 behind
        val saved = Matching.model to Matching.owner
        try {
            Matching.model = net.boswell.phone.diarize.VoiceModel.SPEAKER_ID
            Matching.owner = null
            assertEquals(Decision.UNCERTAIN, Matching.match(voice, refs, field).decision)
            Matching.owner = 1
            val r = Matching.match(voice, refs, field)
            assertEquals(Decision.MATCHED, r.decision)
            assertEquals(1L, r.personId)
            // An unnamed voice nearly as close (0.03 behind): not sure enough.
            assertEquals(Decision.UNCERTAIN, Matching.match(voice, refs, listOf(Reference(4, 9, like(0.71, 3)))).decision)
            // Below "likely", or too little ahead of the next person: no.
            assertEquals(Decision.UNCERTAIN, Matching.match(voice, listOf(Reference(1, 1, like(0.72, 1)), refs[1]), field).decision)
            assertEquals(Decision.UNCERTAIN, Matching.match(voice, listOf(refs[0], Reference(2, 2, like(0.65, 2))), field).decision)
            // Only for the owner, and only with the field to be clear of.
            Matching.owner = 2
            assertEquals(Decision.UNCERTAIN, Matching.match(voice, refs, field).decision)
            Matching.owner = 1
            assertEquals(Decision.UNCERTAIN, Matching.match(voice, refs).decision)
        } finally { Matching.model = saved.first; Matching.owner = saved.second }
    }

    @Test fun `a short bit of the owner passes at 0_66 with a 0_12 lead, only when its length is known`() {
        fun like(s: Double, axis: Int) = FloatArray(5).also { it[0] = s.toFloat(); it[axis] = kotlin.math.sqrt(1 - s * s).toFloat() }
        val voice = floatArrayOf(1f, 0f, 0f, 0f, 0f)
        val refs = listOf(Reference(1, 1, like(0.68, 1)), Reference(2, 2, like(0.55, 2)))   // 0.68, 0.13 ahead
        val field = listOf(Reference(3, 9, like(0.66, 3)))                                  // no field margin to speak of
        val saved = Matching.model to Matching.owner
        try {
            Matching.model = net.boswell.phone.diarize.VoiceModel.SPEAKER_ID
            Matching.owner = 1
            val r = Matching.match(voice, refs, field, seconds = 2.0)
            assertEquals(Decision.MATCHED, r.decision)
            assertEquals(1L, r.personId)
            assertEquals(Decision.UNCERTAIN, Matching.match(voice, refs, field, seconds = 3.0).decision)   // not short
            assertEquals(Decision.UNCERTAIN, Matching.match(voice, refs, field).decision)                  // length unknown
            assertEquals(Decision.UNCERTAIN, Matching.match(voice, refs, seconds = 2.0).decision)          // no field: asked instead
            assertEquals(Decision.UNCERTAIN,                                                              // 0.10 ahead: not enough
                Matching.match(voice, listOf(refs[0], Reference(2, 2, like(0.58, 2))), field, seconds = 2.0).decision)
            assertEquals(Decision.NONE,                                                                   // 0.63: below 0.66 (and matchLow)
                Matching.match(voice, listOf(Reference(1, 1, like(0.63, 1)), Reference(2, 2, like(0.45, 2))), field, seconds = 2.0).decision)
            Matching.owner = 2
            assertEquals(Decision.UNCERTAIN, Matching.match(voice, refs, field, seconds = 2.0).decision)   // only the owner
            Matching.model = net.boswell.phone.diarize.VoiceModel.WESPEAKER
            Matching.owner = 1
            assertEquals(Decision.UNCERTAIN, Matching.match(voice, refs, field, seconds = 2.0).decision)   // measured with ReDimNet2 only
        } finally { Matching.model = saved.first; Matching.owner = saved.second }
    }
}
