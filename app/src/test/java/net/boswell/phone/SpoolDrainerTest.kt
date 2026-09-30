package net.boswell.phone

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import kotlinx.serialization.json.Json
import net.boswell.phone.capture.ClipTimes
import net.boswell.phone.omi.Offload
import net.boswell.phone.sync.SpoolDrainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.sin

class SpoolDrainerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val enc = OpusEncoder(16_000, 1, OpusApplication.OPUS_APPLICATION_AUDIO).apply { bitrate = 32_000 }
    private var phase = 0.0

    /** One real 20 ms Opus frame of a tone. */
    private fun frame(): ByteArray {
        val pcm = ShortArray(320) { (8000 * sin(phase + it * 2 * Math.PI * 440 / 16_000)).toInt().toShort() }
        phase += 320 * 2 * Math.PI * 440 / 16_000
        val out = ByteArray(400)
        val n = enc.encode(pcm, 0, 320, out, 0, out.size)
        return out.copyOf(n)
    }

    /** A 444-byte stored packet: timestamp, then as many [len][frame] as fit, then zero padding. */
    private fun packet(ts: Long, frames: Int): ByteArray {
        val b = ByteBuffer.allocate(Offload.STORED_PACKET_BYTES).putInt(ts.toInt())
        repeat(frames) { val f = frame(); if (b.remaining() > f.size + 1) { b.put(f.size.toByte()); b.put(f) } }
        return b.array()
    }

    private fun spool(vararg packets: ByteArray): File = tmp.newFile("dev_1.raw").apply { writeBytes(packets.reduce { a, b -> a + b }) }

    @Test fun `stored audio becomes clips at the device's own time`() {
        val clips = tmp.newFolder("clips"); val kept = File(tmp.root, "kept")
        // 60 s of audio: 20 ms frames, 4 per packet = 80 ms per packet, stamped by the second.
        val t0 = 1_790_000_000L
        val packets = (0 until 750).map { packet(t0 + it * 80 / 1000, 4) }.toTypedArray()
        val r = SpoolDrainer(clips, "c4b3fd7f1e91").drain(spool(*packets), kept)
        assertEquals(0, r.bad)
        assertEquals(2, r.clips.size)
        val first = Json.decodeFromString(ClipTimes.serializer(), File(clips, r.clips[0].nameWithoutExtension + ".json").readText())
        assertTrue(first.timeKnown)
        assertEquals(t0.toDouble(), first.started, 0.0)
        assertEquals(30.0, first.seconds, 0.01)
        assertEquals("omi-card", first.source)
        assertFalse(File(tmp.root, "dev_1.raw").exists())   // converted cleanly, so the spool goes
    }

    @Test fun `a gap in the device's time starts a new clip`() {
        val clips = tmp.newFolder("clips"); val kept = File(tmp.root, "kept")
        val r = SpoolDrainer(clips, "d").drain(spool(packet(1_000, 4), packet(1_000, 4), packet(1_600, 4)), kept)
        assertEquals(2, r.clips.size)
    }

    @Test fun `a spool with trailing bytes is kept, never deleted`() {
        val clips = tmp.newFolder("clips"); val kept = File(tmp.root, "kept")
        val s = spool(packet(1_000, 4), ByteArray(100))
        val r = SpoolDrainer(clips, "d").drain(s, kept)
        assertTrue(r.kept)
        assertTrue(File(kept, s.name).exists())
    }

    @Test fun `undated packets are placed by arrival and marked so`() {
        val clips = tmp.newFolder("clips"); val kept = File(tmp.root, "kept")
        val r = SpoolDrainer(clips, "d").drain(spool(packet(0, 4), packet(0, 4)), kept, arrivedEpoch = 5_000.0)
        assertEquals(1, r.clips.size)
        val t = Json.decodeFromString(ClipTimes.serializer(), File(clips, r.clips[0].nameWithoutExtension + ".json").readText())
        assertFalse(t.timeKnown)
        assertEquals(5_000.0, t.ended, 0.001)
    }
}
