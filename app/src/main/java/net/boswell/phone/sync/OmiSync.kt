package net.boswell.phone.sync

import net.boswell.phone.omi.OmiConnection
import net.boswell.phone.omi.Offload
import net.boswell.phone.omi.RingInfo
import java.io.File
import java.io.FileOutputStream

data class SyncProgress(val took: Long, val target: Long, val bytesPerSecond: Double)
data class SyncOutcome(val spool: File?, val took: Long, val waiting: Long, val stoppedEarly: String?, val tooSlow: Boolean = false)

/**
 * One sync visit: pull stored packets from the device into a spool file.
 *
 * Every batch is written and fsynced before the device's read pointer is
 * advanced past it, so stopping anywhere -- the link dropping, the phone
 * killing the process, the time budget running out -- costs at most the
 * packets in flight, and the next visit resumes from the pointer.
 *
 * Bounded by [maxSeconds] rather than by the backlog: on a weak link the
 * desktop once faced a 29-hour drain, and a visit should cost throughput, not
 * the whole day. Batches are sized to the time left at the rate measured so
 * far ([batchFor]), so a visit fills its window without overrunning it. With
 * [minBytesPerSecond], a first batch slower than that ends the visit: on a
 * weak link the time is worth more to live than what it would bring back.
 */
class OmiSync(private val spoolDir: File, private val deviceId: String) {

    suspend fun visit(
        conn: OmiConnection,
        maxSeconds: Long,
        onRing: (RingInfo) -> Unit = {},
        onProgress: (SyncProgress) -> Unit = {},
        minBytesPerSecond: Double = 0.0,
        idleMs: Long = 20_000L,
    ): SyncOutcome {
        val info = conn.ringInfo()
        onRing(info)
        val waiting = info.pending
        if (waiting <= 0) return SyncOutcome(null, 0, 0, null)

        spoolDir.mkdirs()
        val spool = File(spoolDir, "${deviceId}_${System.currentTimeMillis() / 1000}.raw")
        val t0 = System.currentTimeMillis()
        val deadline = t0 + maxSeconds * 1000
        var seq = info.read
        val end = info.write
        var took = 0L
        var stopped: String? = null
        var slow = false
        var rate: Double? = null
        var batches = 0

        FileOutputStream(spool, true).use { out ->
            fun keep(bytes: ByteArray) {
                out.write(bytes)
                out.flush()
                out.fd.sync()          // durable before the device is told
            }
            while (seq < end) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) { stopped = "time budget reached"; break }
                val count = batchFor(left, rate, end - seq)
                val tb = System.currentTimeMillis()
                val (raw, next) = try {
                    conn.readStored(seq, count, idleMs = idleMs)
                } catch (e: OmiConnection.TransferInterrupted) {
                    if (e.salvaged.isNotEmpty()) {
                        keep(e.salvaged)
                        val got = e.salvaged.size / Offload.STORED_PACKET_BYTES
                        seq += got; took += got
                        runCatching { conn.advance(seq) }   // the device advances on its own as it confirms, so this may already be done
                    }
                    stopped = e.message
                    break
                }
                if (raw.isEmpty()) break
                keep(raw)
                val got = raw.size / Offload.STORED_PACKET_BYTES
                seq = if (next > seq) next else seq + got
                took += got
                // The batch's own rate, request to done: the round trip is part of what a batch costs.
                rate = raw.size / (maxOf(System.currentTimeMillis() - tb, 1L) / 1000.0)
                val ta = System.currentTimeMillis()
                // The firmware has already marked the batch taken at its done
                // (storage.c sync_checkpoint_advance); this confirms it. Losing
                // the link here loses nothing: the batch is on disk.
                val advanced = runCatching { conn.advance(seq) }
                android.util.Log.i("Boswell", "advance ${System.currentTimeMillis() - ta} ms")
                val secs = (System.currentTimeMillis() - t0) / 1000.0
                onProgress(SyncProgress(took, waiting, took * Offload.STORED_PACKET_BYTES / maxOf(secs, 0.01)))
                val lost = advanced.exceptionOrNull()
                if (lost != null) { stopped = "link lost: ${lost.message}"; break }
                if (++batches == 1 && seq < end && rate < minBytesPerSecond) {
                    stopped = "the link is too slow (%.1f kB/s)".format(rate / 1000)
                    slow = true
                    break
                }
            }
        }
        if (took == 0L) spool.delete()
        return SyncOutcome(if (took > 0) spool else null, took, waiting, stopped, slow)
    }

    companion object {
        /** The first batch, before anything is known of the link: small, so a weak link costs little to find out. */
        const val PROBE_BATCH = 200
        const val MIN_BATCH = 50
        /** Large enough that the round trip is a few percent of a batch on a good link (89 kB/s: 6 s). */
        const val MAX_BATCH = 1_200

        /**
         * Packets to ask for next: what the link has carried per second so far
         * ([bytesPerSecond], null before the first batch) times the [leftMs] of
         * the visit, within [MIN_BATCH]..[MAX_BATCH] and never more than
         * [remaining]. The time limit is checked only between batches, so a
         * fixed size either overran a slice or wasted its end.
         */
        fun batchFor(leftMs: Long, bytesPerSecond: Double?, remaining: Long): Int {
            val n = if (bytesPerSecond == null) PROBE_BATCH
            else (bytesPerSecond * leftMs / 1000 / Offload.STORED_PACKET_BYTES).toInt().coerceIn(MIN_BATCH, MAX_BATCH)
            return minOf(n.toLong(), remaining).toInt().coerceAtLeast(1)
        }
    }
}
