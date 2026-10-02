package net.boswell.phone.capture

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.boswell.phone.audio.Wav
import net.boswell.phone.audio.writeAtomically
import java.io.File

/**
 * What the desktop's clipwriter.write_times records, so a clip made here can
 * be imported into Boswell later without translation.
 */
@Serializable
data class ClipTimes(
    val started: Double,
    val ended: Double,
    val seconds: Double,
    val source: String,
    @SerialName("first_ms") val firstMs: Long?,
    @SerialName("last_ms") val lastMs: Long?,
    @SerialName("boot_id") val bootId: Int,
    @SerialName("device_id") val deviceId: String?,
    /** Live audio carries no timestamp; its placement is arrival time. */
    @SerialName("time_known") val timeKnown: Boolean,
    @SerialName("sample_rate") val sampleRate: Int,
    val frames: Int,
)

/**
 * Accumulates decoded audio and files it in clip-sized pieces.
 *
 * The WAV and its sidecar are written before the buffer is cleared, so a
 * failure while writing costs nothing: the audio is still held and the next
 * flush tries again.
 */
class Clipper(
    private val dir: File,
    private val deviceId: String?,
    private val sampleRate: Int = 16_000,
    clipSeconds: Int = 30,
    private val frameMs: Int = 20,
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    /** The longest a clip runs before it's closed; shorter with the home server, for near-live transcripts. */
    @Volatile var clipSeconds: Int = clipSeconds
    private val chunks = ArrayList<ShortArray>()
    /** The Opus frames behind [chunks], for the compact copy; null once any frame came without one. */
    private var opus: ArrayList<ByteArray>? = ArrayList()
    private var have = 0
    private var firstMs: Long? = null
    private var lastMs: Long? = null
    private var frames = 0

    var bootId: Int = 0
    var clipsWritten = 0
        private set

    val heldSeconds: Double get() = have / sampleRate.toDouble()

    /** Add one decoded frame. Returns the clip written if this filled one. */
    fun add(extendedCounter: Long, pcm: ShortArray, frame: ByteArray? = null): File? {
        if (frame == null) opus = null else opus?.add(frame)
        val ms = extendedCounter * frameMs
        if (firstMs == null) firstMs = ms
        lastMs = ms
        chunks += pcm
        have += pcm.size
        frames++
        return if (have >= clipSeconds * sampleRate) flush() else null
    }

    fun flush(): File? {
        if (have == 0) return null
        val audio = ShortArray(have)
        var off = 0
        for (c in chunks) {
            c.copyInto(audio, off)
            off += c.size
        }
        val seconds = have / sampleRate.toDouble()
        val ended = now()
        val wav = uniqueName(ended.toLong())
        Wav.write(wav, audio, sampleRate)
        // The compact copy kept after transcription: the Omi's own frames, not re-encoded.
        opus?.let { frames -> runCatching { net.boswell.phone.audio.ClipAudio.writeCompact(dir, wav.name, frames) } }
        val times = ClipTimes(
            started = ended - seconds, ended = ended, seconds = seconds, source = "omi",
            firstMs = firstMs, lastMs = lastMs, bootId = bootId, deviceId = deviceId,
            timeKnown = false, sampleRate = sampleRate, frames = frames,
        )
        writeAtomically(File(dir, wav.nameWithoutExtension + ".json"), json.encodeToString(ClipTimes.serializer(), times).toByteArray())

        chunks.clear()
        opus = ArrayList()
        have = 0
        frames = 0
        firstMs = null
        lastMs = null
        clipsWritten++
        return wav
    }

    private fun uniqueName(ended: Long): File {
        var f = File(dir, "omi_$ended.wav")
        var n = 1
        while (f.exists()) f = File(dir, "omi_$ended-${n++}.wav")
        return f
    }

    companion object {
        val json = Json { prettyPrint = true; encodeDefaults = true }
    }
}
