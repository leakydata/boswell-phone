package net.boswell.phone

import kotlinx.serialization.json.Json
import net.boswell.phone.audio.Wav
import net.boswell.phone.capture.ClipTimes
import net.boswell.phone.capture.Clipper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClipperTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun frame(v: Short) = ShortArray(320) { v }

    @Test fun `a clip is filed when thirty seconds have been held`() {
        val c = Clipper(tmp.root, "c4b3fd7f1e91", now = { 1_790_000_000.0 })
        var written: File? = null
        for (i in 0 until 1500) {                 // 1500 x 20 ms = 30 s
            val f = c.add(i.toLong(), frame(i.toShort()))
            if (i < 1499) assertNull(f) else written = f
        }
        assertNotNull(written)
        val (pcm, rate) = Wav.readPcm(written!!)
        assertEquals(16_000, rate)
        assertEquals(480_000, pcm.size)
        assertEquals(0.toShort(), c.heldSeconds.toInt().toShort())

        val times = Json.decodeFromString(ClipTimes.serializer(),
            File(tmp.root, written.nameWithoutExtension + ".json").readText())
        assertEquals(30.0, times.seconds, 1e-9)
        assertEquals(0L, times.firstMs)
        assertEquals(1499L * 20, times.lastMs)
        assertFalse(times.timeKnown)
        assertEquals("c4b3fd7f1e91", times.deviceId)
    }

    @Test fun `flush writes what is held and names never collide`() {
        val c = Clipper(tmp.root, null, now = { 1_790_000_000.0 })
        c.add(0, frame(1)); val a = c.flush()
        c.add(1, frame(2)); val b = c.flush()
        assertNotNull(a); assertNotNull(b)
        assertEquals("omi_1790000000.wav", a!!.name)
        assertEquals("omi_1790000000-1.wav", b!!.name)
        assertArrayEquals(frame(2), Wav.readPcm(b).first)
        assertNull(c.flush())
    }

    @Test fun `no temp files are left behind`() {
        val c = Clipper(tmp.root, null)
        c.add(0, frame(3)); c.flush()
        assertEquals(emptyList<String>(), tmp.root.list()!!.filter { it.endsWith(".tmp") })
    }
}
