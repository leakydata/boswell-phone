package net.boswell.phone

import net.boswell.phone.capture.Problems
import net.boswell.phone.capture.Quiet
import net.boswell.phone.capture.Slow
import net.boswell.phone.capture.logged
import net.boswell.phone.capture.timed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProblemsTest {
    private val lines = mutableListOf<String>()
    private var now = 1_000_000L

    @Before fun setUp() {
        Problems.sink = { lines += it }; Problems.quiet.clock = { now }
        Slow.sink = { lines += it }; Slow.quiet.clock = { now }; Slow.clock = { now }
    }

    @After fun tearDown() {
        Problems.sink = {}; Problems.quiet.clock = System::currentTimeMillis
        Slow.sink = {}; Slow.quiet.clock = System::currentTimeMillis; Slow.clock = { System.nanoTime() / 1_000_000 }
    }

    @Test fun quietLetsOneThroughPerPeriodAndCountsTheRest() {
        val q = Quiet(60_000L) { now }
        assertEquals(0, q.allow("a"))
        assertNull(q.allow("a"))
        assertNull(q.allow("a"))
        assertEquals(0, q.allow("b"))          // another key isn't held back
        now += 59_999
        assertNull(q.allow("a"))
        now += 1
        assertEquals(3, q.allow("a"))          // three held back since the last one
        assertNull(q.allow("a"))
    }

    @Test fun aFailureIsLoggedWithItsClassAndMessage() {
        val r = logged("reading a transcript") { throw java.io.IOException("disk full") }
        assertNull(r)
        assertEquals(listOf("problem: reading a transcript: IOException: disk full"), lines)
    }

    @Test fun successIsNotLoggedAndKeepsItsValue() {
        assertEquals(4, logged("adding") { 2 + 2 })
        assertEquals(4, runCatching { 2 + 2 }.logged("adding").getOrNull())
        assertTrue(lines.isEmpty())
    }

    @Test fun theSameFailureIsLoggedOnceAMinute() {
        repeat(5) { runCatching<Unit> { error("locked") }.logged("watcher") }
        logged("watcher") { throw java.io.IOException("other kind") }
        assertEquals(2, lines.size)
        now += 60_000
        runCatching<Unit> { error("locked") }.logged("watcher")
        assertEquals("problem: watcher: IllegalStateException: locked (+4 since)", lines.last())
    }

    @Test fun detailShowsButDoesNotMakeANewKey() {
        Problems.report("processing a recording", RuntimeException("bad"), "omi_1.wav")
        Problems.report("processing a recording", RuntimeException("bad"), "omi_2.wav")
        assertEquals(listOf("problem: processing a recording (omi_1.wav): RuntimeException: bad"), lines)
    }

    @Test fun cancellationIsNotAProblem() {
        Problems.report("watcher", kotlinx.coroutines.CancellationException("stopped"))
        assertTrue(lines.isEmpty())
    }

    @Test fun slowWorkIsLoggedAndQuickWorkIsNot() {
        assertEquals("ok", timed("quick") { now += 2_000; "ok" })
        assertTrue(lines.isEmpty())
        timed("label checks") { now += 2_500 }
        assertEquals(listOf("slow: label checks took 2500 ms"), lines)
        timed("diarize", limitMs = 20_000) { now += 5_000 }
        assertEquals(1, lines.size)
    }

    @Test fun slowFailingWorkIsStillTimedAndRethrown() {
        val e = runCatching { timed("home analyze") { now += 3_000; throw java.io.IOException("timeout") } }.exceptionOrNull()
        assertTrue(e is java.io.IOException)
        assertEquals(listOf("slow: home analyze took 3000 ms"), lines)
    }

    @Test fun slowLinesAreRateLimitedPerStep() {
        repeat(3) { timed("watcher") { now += 3_000 } }
        assertEquals(1, lines.size)
        now += 60_000
        timed("watcher") { now += 3_000 }
        assertEquals("slow: watcher took 3000 ms (+2 more since)", lines.last())
    }

    @Test fun problemLinesAreRecognized() {
        assertTrue(Problems.isProblem("12:00:01  problem: watcher: IOException: x"))
        assertTrue(Problems.isProblem("12:00:01  slow: diarize took 30000 ms"))
        assertFalse(Problems.isProblem("12:00:01  link lost: timeout"))
    }
}
