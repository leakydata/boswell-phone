package net.boswell.phone.audio

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Ogg Opus files (RFC 7845), mono 16 kHz: how clips are kept once transcribed.
 *
 * The Omi already sends Opus, so a clip's own frames are written as they
 * arrived -- no second encoding, no loss, about a tenth of the WAV's size --
 * and decoding them gives back the very samples the WAV held. Any Android
 * player plays these files.
 */
object OggOpus {
    private const val SERIAL = 0x426f7377   // "Bosw"

    /** Opus packets -> an Ogg Opus file, written atomically. [preSkip] is in 48 kHz samples (RFC 7845). */
    fun write(file: File, packets: List<ByteArray>, inputRate: Int = 16_000, preSkip: Int = 0) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            var seq = 0
            fun page(packetsOnPage: List<ByteArray>, granule: Long, flags: Int) {
                val lacing = ByteArrayOutputStream(); val body = ByteArrayOutputStream()
                for (p in packetsOnPage) {
                    var left = p.size
                    while (left >= 255) { lacing.write(255); left -= 255 }
                    lacing.write(left)
                    body.write(p)
                }
                val seg = lacing.toByteArray()
                require(seg.size <= 255) { "too many segments on one page" }
                val h = ByteArray(27 + seg.size)
                "OggS".toByteArray().copyInto(h, 0)
                h[4] = 0; h[5] = flags.toByte()
                le(h, 6, granule, 8); le(h, 14, SERIAL.toLong(), 4); le(h, 18, (seq++).toLong(), 4)
                h[26] = seg.size.toByte(); seg.copyInto(h, 27)
                val data = body.toByteArray()
                val crc = crc(data, crc(h, 0))
                le(h, 22, crc.toLong() and 0xffffffffL, 4)
                out.write(h); out.write(data)
            }
            val head = ByteArray(19)
            "OpusHead".toByteArray().copyInto(head, 0)
            head[8] = 1; head[9] = 1
            le(head, 10, preSkip.toLong(), 2); le(head, 12, inputRate.toLong(), 4)
            page(listOf(head), 0, 0x02)
            val vendor = "Boswell Phone".toByteArray()
            val tags = ByteArray(8 + 4 + vendor.size + 4)
            "OpusTags".toByteArray().copyInto(tags, 0); le(tags, 8, vendor.size.toLong(), 4); vendor.copyInto(tags, 12)
            page(listOf(tags), 0, 0)

            var granule = 0L
            val onPage = mutableListOf<ByteArray>()
            var segs = 0
            for ((i, p) in packets.withIndex()) {
                val need = p.size / 255 + 1
                if (onPage.isNotEmpty() && (segs + need > 255 || onPage.size >= 50)) {
                    page(onPage.toList(), granule, 0); onPage.clear(); segs = 0
                }
                onPage += p; segs += need
                granule += samples48k(p)
                if (i == packets.lastIndex) page(onPage.toList(), granule, 0x04)
            }
            if (packets.isEmpty()) page(emptyList(), 0, 0x04)
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw java.io.IOException("could not write ${file.name}")
    }

    data class Stream(val packets: List<ByteArray>, val inputRate: Int, val preSkip: Int)

    /** The Opus packets in an Ogg Opus file (header packets removed). */
    fun readPackets(file: File): Stream {
        val b = file.readBytes()
        val packets = mutableListOf<ByteArray>()
        val partial = ByteArrayOutputStream()
        var pos = 0
        while (pos + 27 <= b.size) {
            require(b[pos] == 'O'.code.toByte() && b[pos + 1] == 'g'.code.toByte() && b[pos + 2] == 'g'.code.toByte() && b[pos + 3] == 'S'.code.toByte()) { "not an Ogg page at $pos" }
            val nseg = b[pos + 26].toInt() and 0xff
            var data = pos + 27 + nseg
            for (s in 0 until nseg) {
                val len = b[pos + 27 + s].toInt() and 0xff
                partial.write(b, data, len); data += len
                if (len < 255) { packets += partial.toByteArray(); partial.reset() }
            }
            pos = data
        }
        require(packets.size >= 2 && String(packets[0], 0, 8) == "OpusHead") { "not an Ogg Opus file" }
        val head = packets[0]
        val preSkip = (head[10].toInt() and 0xff) or ((head[11].toInt() and 0xff) shl 8)
        val rate = (head[12].toInt() and 0xff) or ((head[13].toInt() and 0xff) shl 8) or ((head[14].toInt() and 0xff) shl 16) or ((head[15].toInt() and 0xff) shl 24)
        return Stream(packets.drop(2), rate, preSkip)
    }

    /** Decode to 16 kHz mono PCM, pre-skip removed. Packets that won't decode count as silence of their length. */
    fun readPcm(file: File): ShortArray {
        val s = readPackets(file)
        val dec = OpusFrameDecoder()
        val parts = ArrayList<ShortArray>(s.packets.size)
        var total = 0
        for (p in s.packets) {
            val pcm = runCatching { dec.decode(p) }.getOrElse { ShortArray(samples48k(p) / 3) }
            parts += pcm; total += pcm.size
        }
        val skip = (s.preSkip / 3).coerceAtMost(total)
        val out = ShortArray(total - skip)
        var o = -skip
        for (p in parts) {
            for (x in p) { if (o >= 0) out[o] = x; o++ }
        }
        return out
    }

    /** A packet's duration in 48 kHz samples, from its TOC byte (RFC 6716 section 3.1). */
    fun samples48k(p: ByteArray): Int {
        if (p.isEmpty()) return 0
        val toc = p[0].toInt() and 0xff
        val config = toc shr 3
        val frame = when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]          // SILK 10/20/40/60 ms
            config < 16 -> intArrayOf(480, 960)[config and 1]                      // hybrid 10/20 ms
            else -> intArrayOf(120, 240, 480, 960)[config and 3]                   // CELT 2.5/5/10/20 ms
        }
        val count = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (p.size > 1) (p[1].toInt() and 0x3f) else 1
        }
        return frame * count
    }

    private fun le(a: ByteArray, at: Int, v: Long, n: Int) { for (i in 0 until n) a[at + i] = (v shr (8 * i)).toByte() }

    private val TABLE = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04c11db7 else r shl 1 }
        r
    }

    /** The Ogg CRC (poly 0x04c11db7, no reflection, initial 0), continued from [c] over [a]. */
    private fun crc(a: ByteArray, c0: Int): Int {
        var c = c0
        for (x in a) c = (c shl 8) xor TABLE[((c ushr 24) xor (x.toInt() and 0xff)) and 0xff]
        return c
    }
}
