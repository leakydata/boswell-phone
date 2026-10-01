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
        // The Opus frames behind [pcm], kept as they came for the clip's compact copy.
        val opus = ArrayList<ByteArray>()
        var have = 0
        var firstTs = 0L
        var lastTs = 0L
        val undated = ArrayList<Pair<ShortArray, ByteArray>>()

        fun flush() {
            if (have == 0) return
            val audio = join(pcm, have)
            val seconds = have / sr.toDouble()
            clips += write(firstTs.toDouble(), firstTs + seconds, audio, timeKnown = true,
                firstMs = firstTs * 1000, lastMs = lastTs * 1000, frameCount = pcm.size, packets = opus.toList())
            pcm.clear(); opus.clear(); have = 0
        }

        for (i in 0 until n) {
            val p = StoredPacket.parse(blob.copyOfRange(i * Offload.STORED_PACKET_BYTES, (i + 1) * Offload.STORED_PACKET_BYTES)) ?: continue
            if (p.frames.isEmpty()) continue
            val decoded = p.frames.mapNotNull { f -> runCatching { decoder.decode(f) }.getOrNull()?.let { it to f }.also { if (it == null) bad++ } }
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
            for ((d, f) in decoded) { pcm += d; opus += f; have += d.size }
            if (have >= clipSeconds * sr) flush()
        }
        flush()

        // Undated audio, cut into ordinary clips that end at arrival and run in order.
        if (undated.isNotEmpty()) {
            // Whole frames per clip, so each clip's compact copy holds exactly its own frames.
            val step = clipSeconds * sr
            val chunks = mutableListOf<List<Pair<ShortArray, ByteArray>>>()
            var cur = mutableListOf<Pair<ShortArray, ByteArray>>(); var size = 0
            for (fr in undated) {
                cur += fr; size += fr.first.size
                if (size >= step) { chunks += cur; cur = mutableListOf(); size = 0 }
            }
            if (cur.isNotEmpty()) chunks += cur
            var end = arrivedEpoch
            for (chunk in chunks.reversed()) {
                val audio = join(chunk.map { it.first }, chunk.sumOf { it.first.size })
                val secs = audio.size / sr.toDouble()
                clips += write(end - secs, end, audio, timeKnown = false, firstMs = null, lastMs = null, frameCount = chunk.size,
                    packets = chunk.map { it.second })
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

    private fun write(started: Double, ended: Double, audio: ShortArray, timeKnown: Boolean, firstMs: Long?, lastMs: Long?, frameCount: Int,
                      packets: List<ByteArray>? = null): File {
        var wav = File(clipsDir, "omi_${ended.toLong()}.wav")
        var k = 1
        while (wav.exists()) wav = File(clipsDir, "omi_${ended.toLong()}-${k++}.wav")
        Wav.write(wav, audio, sr)
        // The compact copy kept after transcription: the device's own frames, not re-encoded.
        packets?.let { runCatching { net.boswell.phone.audio.ClipAudio.writeCompact(clipsDir, wav.name, it) } }
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
