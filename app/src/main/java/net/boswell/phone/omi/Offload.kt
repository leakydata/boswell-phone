package net.boswell.phone.omi

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The storage (offload) protocol. All multi-byte fields are big-endian.
 *
 * READING CONSUMES: the device advances its own read pointer as it sends, and
 * a packet handed over cannot be asked for again. This file therefore only
 * builds the ring-info query, which reads nothing. Commands that move audio
 * (0x11 read, 0x12 advance, 0x13 clear) are deliberately absent until spooling
 * to durable storage exists to receive them.
 */
object Offload {
    const val CMD_RING_INFO: Byte = 0x10

    const val MSG_ACK: Byte = 0x01
    const val MSG_INFO: Byte = 0x02
    const val MSG_DATA: Byte = 0x03
    const val MSG_DONE: Byte = 0x04
    const val MSG_BEGIN: Byte = 0x05

    /** Stored packets are a fixed 444 bytes. */
    const val STORED_PACKET_BYTES = 444

    fun ringInfoCommand(): ByteArray = byteArrayOf(CMD_RING_INFO)
}

/** `0x02 read:u64 write:u64 cap:u32 dropped:u64 pktbytes:u16` */
data class RingInfo(
    val read: Long,
    val write: Long,
    val capacity: Long,
    val dropped: Long,
    val packetBytes: Int,
) {
    /** Packets written and not yet read: what an offload would move. */
    val pending: Long get() = (write - read).coerceAtLeast(0)

    companion object {
        const val SIZE = 1 + 8 + 8 + 4 + 8 + 2

        fun parse(data: ByteArray): RingInfo? {
            if (data.size < SIZE || data[0] != Offload.MSG_INFO) return null
            val b = ByteBuffer.wrap(data, 1, SIZE - 1).order(ByteOrder.BIG_ENDIAN)
            return RingInfo(
                read = b.long,
                write = b.long,
                capacity = b.int.toLong() and 0xffffffffL,
                dropped = b.long,
                packetBytes = b.short.toInt() and 0xffff,
            )
        }
    }
}

/**
 * A 444-byte stored packet: `[timestamp:u32 BE][len:u8][opus]...` then padding.
 * A zero length byte ends the frames; the padding is not a frame.
 */
data class StoredPacket(val timestamp: Long, val frames: List<ByteArray>) {
    companion object {
        fun parse(data: ByteArray): StoredPacket? {
            if (data.size < 5) return null
            val ts = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
            val frames = mutableListOf<ByteArray>()
            var i = 4
            while (i < data.size) {
                val len = data[i].toInt() and 0xff
                if (len == 0 || i + 1 + len > data.size) break
                frames += data.copyOfRange(i + 1, i + 1 + len)
                i += 1 + len
            }
            return StoredPacket(ts, frames)
        }
    }
}
