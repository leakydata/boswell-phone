package net.boswell.phone

import net.boswell.phone.audio.OpusFrameDecoder
import net.boswell.phone.omi.Offload
import net.boswell.phone.omi.StoredPacket
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a real Omi spool file (desktop Boswell's omi_spool/kept/ files, 444-byte
 * stored packets) with the phone's decoder and writes raw PCM, so
 * tools/opus_parity.py can compare it with libopus -- what the desktop decodes
 * with. Needs private data, so it only runs when pointed at a file:
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*OpusParity*' \
 *     -Dparity.raw=/path/to.raw -Dparity.out=/path/to/out.pcm
 */
class OpusParityTest {
    @Test fun decodeSpoolForParity() {
        val raw = System.getProperty("parity.raw")?.let(::File)
        val out = System.getProperty("parity.out")?.let(::File)
        assumeTrue("parity.raw not set", raw != null && raw.exists() && out != null)
        val maxPackets = System.getProperty("parity.packets")?.toInt() ?: 500

        val bytes = raw!!.readBytes()
        val dec = OpusFrameDecoder()
        val pcm = ByteBuffer.allocate(maxPackets * 3 * 320 * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        val skipped = mutableListOf<Int>()   // frame indices Concentus refused
        var frame = 0
        var off = 0
        var packets = 0
        while (off + Offload.STORED_PACKET_BYTES <= bytes.size && packets < maxPackets) {
            val p = StoredPacket.parse(bytes.copyOfRange(off, off + Offload.STORED_PACKET_BYTES))!!
            for (f in p.frames) {
                val decoded = runCatching { dec.decode(f) }.getOrNull()
                if (decoded == null) skipped += frame else for (s in decoded) pcm.putShort(s)
                frame++
            }
            off += Offload.STORED_PACKET_BYTES
            packets++
        }
        out!!.writeBytes(pcm.array().copyOf(pcm.position()))
        File(out.path + ".skipped").writeText(skipped.joinToString("\n"))
    }
}
