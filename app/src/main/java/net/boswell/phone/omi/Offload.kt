package net.boswell.phone.omi

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The storage (offload) protocol. All multi-byte fields are big-endian.
 *
 * READING CONSUMES: the device advances its own read pointer as it sends, and
 * a packet handed over cannot be asked for again. So everything read goes to
 * a spool file and is fsynced before the pointer is advanced (see OmiSync),
 * and 0x13 (clear) is deliberately not implemented at all.
 *
 * Never used underneath a live stream: the desktop sent a ring query one
 * second into an audio stream and recording stopped working -- sessions
 * connected, received no frames and dropped. Storage commands belong to sync
 * visits, when nothing is streaming.
 */
object Offload {
    const val CMD_RING_INFO: Byte = 0x10
    const val CMD_READ: Byte = 0x11
    const val CMD_ADVANCE: Byte = 0x12

    /** Packets per request: large enough that round trips are not the cost, small enough that an interruption loses little. */
    const val BATCH = 400

    val STATUS = mapOf(0 to "ok", 6 to "invalid command", 9 to "storage not ready", 10 to "sequence out of range")

    fun readCommand(start: Long, count: Int): ByteArray =
        ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN).put(CMD_READ).putLong(start).putInt(count).array()

    fun advanceCommand(seq: Long): ByteArray =
        ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN).put(CMD_ADVANCE).putLong(seq).array()

    /** 0x04 status:u8 next:u64 */
    fun parseDone(d: ByteArray): Pair<Int, Long>? =
        if (d.size >= 10 && d[0] == MSG_DONE) (d[1].toInt() and 0xff) to ByteBuffer.wrap(d, 2, 8).order(ByteOrder.BIG_ENDIAN).long else null

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
