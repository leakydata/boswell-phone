package net.boswell.phone

import net.boswell.phone.omi.LivePacket
import net.boswell.phone.omi.RingInfo
import net.boswell.phone.omi.RunTracker
import net.boswell.phone.omi.StoredPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class ProtocolTest {
    @Test fun `live packet header is little-endian counter then index`() {
        val p = LivePacket.parse(byteArrayOf(0x34, 0x12, 0, 9, 8, 7))!!
        assertEquals(0x1234, p.counter)
        assertEquals(0, p.index)
        assertArrayEquals(byteArrayOf(9, 8, 7), p.opus)
    }

    @Test fun `a header with no payload is not a packet`() {
        assertNull(LivePacket.parse(byteArrayOf(1, 0, 0)))
    }

    @Test fun `counter going backwards is a reboot`() {
        var ids = 100
        val t = RunTracker { ids++ }
        assertFalse(t.note(500))
        assertFalse(t.note(501))
        val before = t.id
        assertTrue(t.note(3))
        assertEquals(before + 1, t.id)
        assertEquals(3L, t.extended)
    }

    @Test fun `small reordering is not a reboot`() {
        val t = RunTracker()
        t.note(500)
        assertFalse(t.note(495))
    }

    @Test fun `the 16-bit counter wrapping is not a reboot, and time keeps counting`() {
        val t = RunTracker()
        val id = t.id
        t.note(65_534)
        t.note(65_535)
        assertFalse(t.note(0))
        assertFalse(t.note(1))
        assertEquals(id, t.id)
        assertEquals(65_537L, t.extended)
    }

    @Test fun `ring info is big-endian`() {
        val b = ByteBuffer.allocate(RingInfo.SIZE)
        b.put(0x02).putLong(1_000).putLong(1_400).putInt(1_115_064).putLong(7).putShort(444)
        val r = RingInfo.parse(b.array())!!
        assertEquals(1_000L, r.read)
        assertEquals(1_400L, r.write)
        assertEquals(1_115_064L, r.capacity)
        assertEquals(7L, r.dropped)
        assertEquals(444, r.packetBytes)
        assertEquals(400L, r.pending)
    }

    @Test fun `ring info refuses other message kinds`() {
        assertNull(RingInfo.parse(ByteArray(RingInfo.SIZE).also { it[0] = 0x03 }))
    }

    @Test fun `stored packet stops at the first zero length and ignores padding`() {
        val data = ByteArray(444)
        ByteBuffer.wrap(data).putInt(0x6553F100)
        data[4] = 2; data[5] = 11; data[6] = 12
        data[7] = 1; data[8] = 13
        data[9] = 0
        data[10] = 5   // padding garbage after the terminator
        val p = StoredPacket.parse(data)!!
        assertEquals(0x6553F100L, p.timestamp)
        assertEquals(2, p.frames.size)
        assertArrayEquals(byteArrayOf(11, 12), p.frames[0])
        assertArrayEquals(byteArrayOf(13), p.frames[1])
    }

    @Test fun `the firmware's exact-fit ghost frame is skipped`() {
        // 444-byte stored packet: 4-byte timestamp, then [len][frame]... in 440 bytes.
        // Three real frames (1+99 bytes each = 300), then a "frame" that would end at
        // exactly 440: the firmware's off-by-one writes only its length byte and
        // leaves stale bytes, putting the real frame in the next packet.
        val p = ByteArray(444)
        p[3] = 7
        var o = 4
        repeat(3) { p[o] = 99; p[o + 1] = 0xb8.toByte(); o += 100 }
        p[o] = (444 - o - 1).toByte()                    // ends exactly at the end: a ghost
        for (k in o + 1 until 444) p[k] = 0x2b
        val sp = net.boswell.phone.omi.StoredPacket.parse(p)!!
        org.junit.Assert.assertEquals(3, sp.frames.size)
        assert(sp.frames.all { it[0] == 0xb8.toByte() })
    }

    @Test fun `a last frame that leaves padding is kept`() {
        val p = ByteArray(444)
        p[4] = 72; p[5] = 0xb8.toByte()                  // one 72-byte frame, zero padding after
        org.junit.Assert.assertEquals(1, net.boswell.phone.omi.StoredPacket.parse(p)!!.frames.size)
    }
}
