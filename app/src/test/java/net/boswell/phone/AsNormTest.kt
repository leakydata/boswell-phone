package net.boswell.phone

import net.boswell.phone.speakers.AsNorm
import net.boswell.phone.speakers.Matching
import net.boswell.phone.speakers.Matching.Reference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class AsNormTest {
    @Test fun `back on the cosine scale, in order, flat past the ends`() {
        var last = -2.0
        var n = -40.0
        while (n <= 30.0) { val c = AsNorm.toCosine(n); assertTrue(c >= last); last = c; n += 0.01 }
        assertEquals(AsNorm.toCosine(-33.2), AsNorm.toCosine(-100.0), 0.0)
        assertEquals(1.0, AsNorm.toCosine(100.0), 0.0)
        // asnorm.qmap's own knots: the measurement's numbers.
        assertEquals(-0.315230, AsNorm.toCosine(-33.10047), 1e-6)
        assertEquals(1.0, AsNorm.toCosine(24.39928), 1e-5)
        // Halfway between two knots is halfway between their cosines.
        val a = AsNorm.toCosine(-20.21780); val b = AsNorm.toCosine(-18.83444)
        assertEquals((a + b) / 2, AsNorm.toCosine((-20.21780 - 18.83444) / 2), 1e-9)
    }

    @Test fun `a score as far above both prints' cohorts as usual is normalized the same`() {
        val s = AsNorm.Stat(0.3, 0.05)
        assertEquals(AsNorm.toCosine(4.0), AsNorm.normalized(0.5, s, s), 1e-12)
        assertEquals(AsNorm.toCosine(0.5 * (4.0 + 2.0)), AsNorm.normalized(0.5, s, AsNorm.Stat(0.4, 0.05)), 1e-12)
    }

    private fun axis(i: Int, c: Double, dims: Int = 40) = FloatArray(dims).also { it[0] = c.toFloat(); it[i] = sqrt(1 - c * c).toFloat() }

    @Test fun `the best 20 of the cohort, without the print's own recording or the voice's ten minutes`() {
        // 30 cohort voices, voice i scores 0.01 * i with the print (0, 0.01, ... 0.29).
        val vecs = List(30) { axis(it + 1, 0.01 * it) }
        val clips = List(30) { "omi_${1791160000 + 100 * it}.wav" }
        val c = AsNorm.Cohort(vecs, clips, DoubleArray(30) { 1791160000.0 + 100 * it })
        val v = FloatArray(40).also { it[0] = 1f }
        fun meanSd(xs: List<Double>): AsNorm.Stat { val m = xs.average(); return AsNorm.Stat(m, sqrt(xs.sumOf { (it - m) * (it - m) } / xs.size) + 1e-6) }
        val all = AsNorm.printStat(v, c, "omi_x.wav")!!
        val want = meanSd((10 until 30).map { 0.01 * it })
        assertEquals(want.mean, all.mean, 1e-6); assertEquals(want.sd, all.sd, 1e-6)
        // Its own recording (the best) left out.
        assertEquals(meanSd((9 until 29).map { 0.01 * it }).mean, AsNorm.printStat(v, c, clips[29])!!.mean, 1e-6)
        // A new voice at cohort voice 29's time: 23..29 are within 600 s.
        assertEquals(meanSd((3 until 23).map { 0.01 * it }).mean, AsNorm.voiceStat(v, c, "omi_y.wav", 1791160000.0 + 2900)!!.mean, 1e-6)
        assertNull(AsNorm.voiceStat(v, AsNorm.Cohort(emptyList(), emptyList(), DoubleArray(0)), "omi_y.wav", null))
    }

    @Test fun `the cohort is the unnamed voices that aren't closest to the owner`() {
        val named = listOf(Reference(1, 1, axis(1, 0.0), "a.wav"), Reference(2, 2, axis(2, 0.0), "b.wav"))
        val ownerish = Reference(10, 10, Matching.unit(floatArrayOf(0f, 1f, 0.2f) + FloatArray(37)), "c.wav")
        val other = Reference(11, 11, Matching.unit(floatArrayOf(0f, 0.2f, 1f) + FloatArray(37)), "d.wav")
        assertEquals(listOf("d.wav"), AsNorm.cohort(named, listOf(ownerish, other), owner = 1).clips)
        assertEquals(2, AsNorm.cohort(named, listOf(ownerish, other), owner = null).size)
        // Its own recording's prints don't count: a copy of it under person 2 doesn't make it theirs.
        val twin = Reference(3, 2, ownerish.vec, "c.wav")
        assertEquals(1, AsNorm.cohort(named + twin, listOf(ownerish, other), owner = 1).size)
    }

    @Test fun `a voice's scores are normalized against the cohort, each print's worked out once`() {
        val c = AsNorm.Cohort(List(25) { axis(it + 1, 0.02 * it) }, List(25) { "omi_${1791100000 + it}.wav" }, DoubleArray(25) { 1791100000.0 + it })
        val norm = AsNorm.Norm(c)
        val v = FloatArray(40).also { it[0] = 1f }
        val scorer = norm.forVoice(v, "omi_1791200000.wav", 1791200000.0)
        assertNotNull(scorer)
        val r = Reference(5, 1, axis(30, 0.8), "omi_1791300000.wav")
        val s = scorer!!.score(0.8, r)
        val x = AsNorm.voiceStat(v, c, "omi_1791200000.wav", 1791200000.0)!!
        val y = AsNorm.printStat(r.vec, c, r.clip)!!
        assertEquals(AsNorm.normalized(0.8, x, y), s, 1e-12)
        assertEquals(s, scorer.score(0.8, r.copy(vec = axis(31, 0.1))), 0.0)       // kept by voiceprint id
    }
}
