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
        assertEquals(listOf(-1L), AsNorm.cohort(named, listOf(ownerish, other), owner = 1).who.toList())   // one print is no self-cluster
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

    /** A unit vector [c] like axis [a], the rest on axis [b]. */
    private fun mix(a: Int, c: Double, b: Int) = FloatArray(40).also { it[a] = c.toFloat(); it[b] = sqrt(1 - c * c).toFloat() }

    @Test fun `a named person's self-cluster leaves the cohort, only for them`() {
        // Owner 1 (axis 2), person 2 (axis 0), person 3 (axis 1).
        val named = listOf(Reference(1, 1, mix(2, 1.0, 39), "o.wav"), Reference(2, 2, mix(0, 1.0, 39), "p.wav"), Reference(3, 3, mix(1, 1.0, 39), "q.wav"))
        var id = 100L
        fun cluster(pid: Long, n: Int, axis: Int, c: Double, from: Int) = List(n) { Reference(id++, pid, mix(axis, c, from + it), "c${pid}_$it.wav") }
        val self = cluster(10, 5, 0, 0.9, 5)          // person 2 unnamed: 5 prints, 0.90 like them
        val few = cluster(11, 4, 0, 0.9, 10)          // as like them, but only 4 prints
        val loose = cluster(12, 5, 0, 0.75, 14)       // 5 prints, only 0.75 like them
        val mine = cluster(13, 5, 2, 0.9, 19)         // the owner's own unnamed voices
        val c = AsNorm.cohort(named, self + few + loose + mine, owner = 1)
        assertEquals(14, c.size)                      // the owner's left out as before
        assertEquals(List(5) { 2L } + List(9) { -1L }, c.who.toList())
        // Without an owner, the owner-like cluster is just someone's self-cluster.
        assertEquals(List(5) { 2L } + List(9) { -1L } + List(5) { 1L }, AsNorm.cohort(named, self + few + loose + mine, owner = null).who.toList())

        // Scores against person 2 leave their self-cluster out; against anyone else, it stays.
        val v = mix(0, 1.0, 39)
        val all = AsNorm.printStat(v, c, "x.wav")!!
        val for2 = AsNorm.printStat(v, c, "x.wav", person = 2)!!
        assertEquals(0.9, all.mean, 0.06)
        assertEquals((List(4) { 0.9 } + List(5) { 0.75 }).average(), for2.mean, 1e-6)
        assertEquals(all, AsNorm.printStat(v, c, "x.wav", person = 3))
        assertEquals(all, AsNorm.printStat(v, c, "x.wav", person = 1))      // the owner's scores as before
        assertEquals(for2, AsNorm.voiceStat(v, c, "x.wav", null, person = 2))
        assertEquals(all, AsNorm.voiceStat(v, c, "x.wav", null, person = 1))
    }

    @Test fun `a voice's scoring against each person matches leaving their self-clusters out by hand`() {
        val rnd = java.util.Random(7)
        fun r() = Matching.unit(FloatArray(40) { rnd.nextGaussian().toFloat() })
        val n = 200
        val c = AsNorm.Cohort(List(n) { r() }, List(n) { "omi_${1791100000 + 60 * it}.wav" }, DoubleArray(n) { 1791100000.0 + 60 * it },
            LongArray(n) { if (it % 3 == 0) -1L else (it % 3).toLong() + 1 })
        val norm = AsNorm.Norm(c)
        repeat(5) { k ->
            val v = r(); val time = 1791100000.0 + 60 * rnd.nextInt(n)
            val scorer = norm.forVoice(v, "omi_x.wav", time)!!
            for (p in 1L..4L) {
                val ref = Reference(100L * (k + 1) + p, p, r(), "omi_${1792000000 + p}.wav")
                val want = AsNorm.normalized(0.5, AsNorm.voiceStat(v, c, "omi_x.wav", time, p)!!, AsNorm.printStat(ref.vec, c, ref.clip, p)!!)
                assertEquals(want, scorer.score(0.5, ref), 1e-12)
            }
        }
    }

    @Test fun `a person's self-cluster no longer crushes the scores against them`() {
        // Person 2's print, and twenty unnamed copies of it (their self-cluster) among thirty strangers.
        val p2 = axis(1, 0.0)
        val copies = List(20) { Matching.unit(FloatArray(40).also { a -> a[1] = 1f; a[2 + it] = 0.3f }) }
        val strangers = List(30) { axis(2 + it, 0.1) }
        val vecs = copies + strangers
        val c = AsNorm.Cohort(vecs, List(vecs.size) { "omi_${1791100000 + it}.wav" }, DoubleArray(vecs.size) { 1791100000.0 + it },
            LongArray(vecs.size) { if (it < 20) 2L else -1L })
        val voice = Matching.unit(FloatArray(40).also { it[1] = 1f; it[39] = 0.5f })
        val ref = Reference(1, 2, p2, "omi_1792000000.wav")
        val raw = Matching.dot(voice, p2)
        val now = AsNorm.Norm(c).forVoice(voice, "omi_1793000000.wav", 1793000000.0)!!.score(raw, ref)
        val before = AsNorm.Norm(AsNorm.Cohort(c.vecs, c.clips, c.times)).forVoice(voice, "omi_1793000000.wav", 1793000000.0)!!.score(raw, ref)
        assertTrue("before $before, now $now", now > before + 0.2)
        assertTrue("now $now", now >= 0.64)       // MATCH_LOW of ReDimNet2
        // Against anyone else, the copies stay in.
        assertEquals(before, AsNorm.Norm(c).forVoice(voice, "omi_1793000000.wav", 1793000000.0)!!.score(raw, ref.copy(voiceprintId = 2, personId = 3)), 1e-12)
    }
}
