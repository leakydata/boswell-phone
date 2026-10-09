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

    /** Each row scores its first coordinate against the voice. */
    private fun like(s: Double, axis: Int) = FloatArray(6).also { it[0] = s.toFloat(); it[axis] = kotlin.math.sqrt(1 - s * s).toFloat() }
    private val voice = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f)

    private fun asOwner(owner: Long?, block: () -> Unit) {
        val saved = Matching.model to Matching.owner
        try {
            Matching.model = net.boswell.phone.diarize.VoiceModel.SPEAKER_ID
            Matching.owner = owner
            block()
        } finally { Matching.model = saved.first; Matching.owner = saved.second }
    }

    @Test fun `the owner at 0_71, 0_08 ahead of the next person and of the unnamed voices`() {
        val refs = listOf(Reference(1, 1, like(0.74, 1)), Reference(2, 2, like(0.60, 2)))   // 0.74, 0.14 ahead: uncertain by the general rule
        val field = listOf(Reference(3, 9, like(0.64, 3)))                                  // 0.10 behind
        asOwner(null) { assertEquals(Decision.UNCERTAIN, Matching.match(voice, refs, field).decision) }
        asOwner(1) {
            val r = Matching.match(voice, refs, field)
            assertEquals(Decision.MATCHED, r.decision)
            assertEquals(1L, r.personId)
            // An unnamed voice nearly as close (0.04 behind): not sure enough.
            assertEquals(Decision.UNCERTAIN, Matching.match(voice, refs, listOf(Reference(4, 9, like(0.70, 3)))).decision)
            // Below 0.71, or too little ahead of the next person: no.
            assertEquals(Decision.UNCERTAIN, Matching.match(voice, listOf(Reference(1, 1, like(0.70, 1)), refs[1]), field).decision)
            assertEquals(Decision.UNCERTAIN, Matching.match(voice, listOf(refs[0], Reference(2, 2, like(0.68, 2))), field).decision)
        }
        // Only for the owner.
        asOwner(2) { assertEquals(Decision.UNCERTAIN, Matching.match(voice, refs, field).decision) }
    }

    @Test fun `the general rule no longer decides the owner`() {
        val refs = listOf(Reference(1, 1, like(0.84, 1)), Reference(2, 2, like(0.60, 2)))   // 0.24 ahead of the next person
        val field = listOf(Reference(3, 9, like(0.79, 3)))                                  // but only 0.05 of an unnamed voice
        asOwner(2) { assertEquals(Decision.MATCHED, Matching.match(voice, refs, field).decision) }
        asOwner(1) { assertEquals(Decision.UNCERTAIN, Matching.match(voice, refs, field).decision) }
    }

    @Test fun `the owner at 0_85 or more needs only a lead over named people`() {
        // The owner's own unfiled voices close behind no longer refuse a voice this clear.
        val field = listOf(Reference(3, 9, like(0.84, 3)))
        asOwner(1) {
            assertEquals(Decision.MATCHED, Matching.match(voice, listOf(Reference(1, 1, like(0.86, 1)), Reference(2, 2, like(0.60, 2))), field).decision)
            // Still not without the lead over the next named person.
            assertEquals(Decision.UNCERTAIN, Matching.match(voice, listOf(Reference(1, 1, like(0.86, 1)), Reference(2, 2, like(0.80, 2))), field).decision)
        }
    }

    @Test fun `a short bit of the owner passes at 0_56 with a 0_14 lead, only when its length is known`() {
        val refs = listOf(Reference(1, 1, like(0.62, 1)), Reference(2, 2, like(0.46, 2)))   // 0.62, 0.16 ahead
        val field = listOf(Reference(3, 9, like(0.61, 3)))                                  // no field margin to speak of
        asOwner(1) {
            val r = Matching.match(voice, refs, field, seconds = 2.0)
            assertEquals(Decision.MATCHED, r.decision)
            assertEquals(1L, r.personId)
            assertEquals(Decision.NONE, Matching.match(voice, refs, field, seconds = 3.0).decision)   // not short
            assertEquals(Decision.NONE, Matching.match(voice, refs, field).decision)                  // length unknown
            assertEquals(Decision.NONE,                                                              // 0.12 ahead: not enough
                Matching.match(voice, listOf(refs[0], Reference(2, 2, like(0.50, 2))), field, seconds = 2.0).decision)
            assertEquals(Decision.NONE,                                                              // 0.55: below 0.56
                Matching.match(voice, listOf(Reference(1, 1, like(0.55, 1)), Reference(2, 2, like(0.35, 2))), field, seconds = 2.0).decision)
        }
        asOwner(2) { assertEquals(Decision.NONE, Matching.match(voice, refs, field, seconds = 2.0).decision) }   // only the owner
        asOwner(1) {
            Matching.model = net.boswell.phone.diarize.VoiceModel.WESPEAKER
            org.junit.Assert.assertNotEquals(Decision.MATCHED, Matching.match(voice, refs, field, seconds = 2.0).decision)   // measured with ReDimNet2 only
        }
    }

    @Test fun `the near voice is the owner at 0_40, a far one never`() {
        val refs = listOf(Reference(1, 1, like(0.45, 1)), Reference(2, 2, like(0.40, 2)))   // 0.45, 0.05 ahead
        val field = listOf(Reference(3, 9, like(0.50, 3)))                                  // an unnamed voice closer still
        asOwner(1) {
            assertEquals(Decision.MATCHED, Matching.match(voice, refs, field, snr = 25.0).decision)
            assertEquals(Decision.NONE, Matching.match(voice, refs, field, snr = 22.0).decision)      // not near enough
            assertEquals(Decision.NONE, Matching.match(voice, refs, field).decision)                  // loudness unknown
            assertEquals(Decision.NONE,                                                              // 0.03 ahead: not enough
                Matching.match(voice, listOf(refs[0], Reference(2, 2, like(0.42, 2))), field, snr = 25.0).decision)
            // Far from the microphone: never the owner, however alike.
            val clear = listOf(Reference(1, 1, like(0.90, 1)), Reference(2, 2, like(0.40, 2)))
            assertEquals(Decision.MATCHED, Matching.match(voice, clear, field, snr = 8.0).decision)
            assertEquals(Decision.UNCERTAIN, Matching.match(voice, clear, field, snr = 7.9).decision)
        }
    }
}
