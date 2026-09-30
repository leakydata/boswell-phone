package net.boswell.phone.sync

import net.boswell.phone.audio.OpusFrameDecoder
import net.boswell.phone.audio.Wav
import net.boswell.phone.audio.writeAtomically
import net.boswell.phone.capture.ClipTimes
import net.boswell.phone.capture.Clipper
import net.boswell.phone.omi.Offload
import net.boswell.phone.omi.StoredPacket
import java.io.File

data class DrainResult(val clips: List<File>, val frames: Int, val bad: Int, val undated: Int, val kept: Boolean)

/**
 * Turns spooled packets into clips, filed at the time the device heard them.
 *
 * Offloaded audio knows when it happened -- every stored packet carries the
 * device's own timestamp -- which is the whole difference from the live
 * stream, so these clips are `time_known = true`.
 *
 * A spool file is deleted only once its audio is somewhere else. If any packet
 * would not decode, or bytes trail a partial packet, the file moves to
 * `kept/` instead: the device has already let go of those bytes and nothing
 * can ask for them again (desktop host/omi_sync.py drain_spool).
 */
class SpoolDrainer(
    private val clipsDir: File,
    private val deviceId: String,
    private val clipSeconds: Int = 30,
    /** A jump in the device's timestamps larger than this is a new sitting, not a longer clip. */
    private val gapSeconds: Long = 3,
) {
    private val sr = 16_000

    fun drain(spool: File, keptDir: File, arrivedEpoch: Double = spool.lastModified() / 1000.0): DrainResult {
        val blob = spool.readBytes()
        val n = blob.size / Offload.STORED_PACKET_BYTES
        val decoder = OpusFrameDecoder()
        val clips = mutableListOf<File>()
        var bad = 0
        var frames = 0
        var undatedPackets = 0

        val pcm = ArrayList<ShortArray>()
        var have = 0
        var firstTs = 0L
        var lastTs = 0L
        val undated = ArrayList<ShortArray>()

        fun flush() {
            if (have == 0) return
            val audio = join(pcm, have)
            val seconds = have / sr.toDouble()
            clips += write(firstTs.toDouble(), firstTs + seconds, audio, timeKnown = true,
                firstMs = firstTs * 1000, lastMs = lastTs * 1000, frameCount = pcm.size)
            pcm.clear(); have = 0
        }

        for (i in 0 until n) {
            val p = StoredPacket.parse(blob.copyOfRange(i * Offload.STORED_PACKET_BYTES, (i + 1) * Offload.STORED_PACKET_BYTES)) ?: continue
            if (p.frames.isEmpty()) continue
            val decoded = p.frames.mapNotNull { f -> runCatching { decoder.decode(f) }.getOrNull().also { if (it == null) bad++ } }
            frames += decoded.size
            if (p.timestamp == 0L) {
                // Stored before the device had a clock: placed by arrival, and said so.
                undatedPackets++
                undated += decoded
                continue
            }
            if (have > 0 && (p.timestamp - lastTs > gapSeconds || p.timestamp < lastTs)) {
                flush()
                decoder.reset()
            }
            if (have == 0) firstTs = p.timestamp
            lastTs = p.timestamp
            for (d in decoded) { pcm += d; have += d.size }
            if (have >= clipSeconds * sr) flush()
        }
        flush()

        // Undated audio, cut into ordinary clips that end at arrival and run in order.
        if (undated.isNotEmpty()) {
            val audio = join(undated, undated.sumOf { it.size })
            val step = clipSeconds * sr
            val chunks = (audio.indices step step).map { audio.copyOfRange(it, minOf(it + step, audio.size)) }
            var end = arrivedEpoch
            for (chunk in chunks.reversed()) {
                val secs = chunk.size / sr.toDouble()
                clips += write(end - secs, end, chunk, timeKnown = false, firstMs = null, lastMs = null, frameCount = chunk.size / 320)
                end -= secs
            }
        }

        val trailing = blob.size % Offload.STORED_PACKET_BYTES
        val keep = bad > 0 || trailing > 0
        if (keep) {
            keptDir.mkdirs()
            spool.renameTo(File(keptDir, spool.name))
        } else {
            spool.delete()
        }
        return DrainResult(clips, frames, bad, undatedPackets, keep)
    }

    private fun join(parts: List<ShortArray>, total: Int): ShortArray {
        val out = ShortArray(total)
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    private fun write(started: Double, ended: Double, audio: ShortArray, timeKnown: Boolean, firstMs: Long?, lastMs: Long?, frameCount: Int): File {
        var wav = File(clipsDir, "omi_${ended.toLong()}.wav")
        var k = 1
        while (wav.exists()) wav = File(clipsDir, "omi_${ended.toLong()}-${k++}.wav")
        Wav.write(wav, audio, sr)
        val times = ClipTimes(
            started = started, ended = ended, seconds = audio.size / sr.toDouble(), source = "omi-card",
            firstMs = firstMs, lastMs = lastMs, bootId = 1, deviceId = deviceId,
            timeKnown = timeKnown, sampleRate = sr, frames = frameCount,
        )
        writeAtomically(File(clipsDir, wav.nameWithoutExtension + ".json"),
            Clipper.json.encodeToString(ClipTimes.serializer(), times).toByteArray())
        return wav
    }
}
