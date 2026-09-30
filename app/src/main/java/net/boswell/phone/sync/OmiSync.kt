package net.boswell.phone.sync

import net.boswell.phone.omi.OmiConnection
import net.boswell.phone.omi.Offload
import net.boswell.phone.omi.RingInfo
import java.io.File
import java.io.FileOutputStream

data class SyncProgress(val took: Long, val target: Long, val bytesPerSecond: Double)
data class SyncOutcome(val spool: File?, val took: Long, val waiting: Long, val stoppedEarly: String?)

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
 * the whole day.
 */
class OmiSync(private val spoolDir: File, private val deviceId: String) {

    suspend fun visit(
        conn: OmiConnection,
        maxSeconds: Long,
        onRing: (RingInfo) -> Unit = {},
        onProgress: (SyncProgress) -> Unit = {},
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

        FileOutputStream(spool, true).use { out ->
            fun keep(bytes: ByteArray) {
                out.write(bytes)
                out.flush()
                out.fd.sync()          // durable before the device is told
            }
            while (seq < end) {
                if (System.currentTimeMillis() >= deadline) { stopped = "time budget reached"; break }
                val count = minOf(Offload.BATCH.toLong(), end - seq).toInt()
                val (raw, next) = try {
                    conn.readStored(seq, count)
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
                val ta = System.currentTimeMillis()
                conn.advance(seq)
                android.util.Log.i("Boswell", "advance ${System.currentTimeMillis() - ta} ms")
                val secs = (System.currentTimeMillis() - t0) / 1000.0
                onProgress(SyncProgress(took, waiting, took * Offload.STORED_PACKET_BYTES / maxOf(secs, 0.01)))
            }
        }
        if (took == 0L) spool.delete()
        return SyncOutcome(if (took > 0) spool else null, took, waiting, stopped)
    }
}
