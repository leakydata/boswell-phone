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
}
