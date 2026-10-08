package net.boswell.phone.speakers

import net.boswell.phone.capture.logged
import net.boswell.phone.process.Transcript
import net.boswell.phone.process.TranscriptJson
import java.io.File

/**
 * A clean voice heard again: a voiceprint from a second or two of speech
 * is noisy, and the same near voice was very likely speaking in the
 * recordings just before. A voice at least [MIN_SNR] above its room is
 * matched with its print averaged with every voice of the [WINDOW] seconds
 * before it that is as clean and at least [MIN_COS] like it, each weighted
 * by its speech (up to [MAX_SECONDS]). Only for matching: the voice's own
 * print is what is kept. Measured with ReDimNet2 only, so only with it.
 */
object Pooling {
    const val MIN_SNR = 12.0
    const val WINDOW = 30.0
    const val MIN_COS = 0.40
    const val MAX_SECONDS = 8.0

    /** A voice of an earlier recording: its print (unit length), speech, and dB above its room. */
    data class Voice(val clip: String, val time: Double, val vec: FloatArray, val seconds: Double, val snr: Double)

    private val STAMP = Regex("_(\\d{9,})")

    /** When a recording was made, from its name (omi_<ended>.wav); null if the name doesn't say. */
    fun clipTime(clip: String): Double? = STAMP.find(clip)?.groupValues?.get(1)?.toDoubleOrNull()

    /**
     * The print to match [vec] by: pooled with [earlier] voices as above, or
     * [vec] itself (a voice that isn't clean, of unknown loudness, with nobody
     * like it just before, or another model).
     */
    fun pooled(vec: FloatArray, seconds: Double, snr: Double?, clip: String, earlier: List<Voice>): FloatArray {
        val time = clipTime(clip)
        if (Matching.model != net.boswell.phone.diarize.VoiceModel.SPEAKER_ID || snr == null || snr < MIN_SNR || time == null) return vec
        val u = Matching.unit(vec)
        val sum = DoubleArray(u.size)
        val w0 = minOf(seconds, MAX_SECONDS)
        for (i in u.indices) sum[i] = w0 * u[i]
        var n = 0
        for (o in earlier) {
            val dt = o.time - time
            if (o.clip == clip || dt >= 0 || dt < -WINDOW || o.snr < MIN_SNR || o.vec.size != u.size) continue
            if (Matching.dot(o.vec, u) < MIN_COS) continue
            val w = minOf(o.seconds, MAX_SECONDS)
            for (i in u.indices) sum[i] += w * o.vec[i]
            n++
        }
        if (n == 0) return u
        var norm = 0.0
        for (x in sum) norm += x * x
        norm = kotlin.math.sqrt(norm)
        return if (norm > 0) FloatArray(u.size) { (sum[it] / norm).toFloat() } else u
    }

    /** A transcript's voices that can be pooled with: known loudness and a print, not Boswell's own. */
    fun voicesOf(t: Transcript): List<Voice> {
        val time = clipTime(t.clip) ?: return emptyList()
        return t.speakers.mapNotNull { (label, sp) ->
            val snr = sp.snrDb ?: return@mapNotNull null
            if (net.boswell.phone.process.BoswellLines.isBoswell(sp)) return@mapNotNull null
            val emb = t.embeddings[label]?.toFloatArray()?.takeIf { Matching.usable(it) } ?: return@mapNotNull null
            Voice(t.clip, time, Matching.unit(emb), sp.seconds, snr)
        }
    }

    /** The voices of the recordings made in the [WINDOW] seconds before one, for many recordings in turn. */
    interface Earlier {
        fun before(clip: String): List<Voice>
    }

    /**
     * [Earlier] from voices already at hand (the archive's index has every
     * voice's print, speech and loudness): no transcript read at all. Only
     * clean voices are kept, as [pooled] uses no other.
     */
    class Known(voices: List<Voice>) : Earlier {
        // Stable: voices of one time stay in the order given (a clip's, in its transcript's order).
        private val voices = voices.filter { it.snr >= MIN_SNR }.sortedBy { it.time }

        override fun before(clip: String): List<Voice> {
            val time = clipTime(clip) ?: return emptyList()
            var i = voices.binarySearchBy(time - WINDOW) { it.time }.let { if (it < 0) -it - 1 else it }
            while (i > 0 && voices[i - 1].time >= time - WINDOW) i--
            val out = mutableListOf<Voice>()
            while (i < voices.size && voices[i].time < time) out += voices[i++]
            return out
        }
    }

    /** [before] for many recordings: the folder listed once, and each transcript read at most once. */
    class Index(dir: File) : Earlier {
        private val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f -> clipTime(f.name)?.let { it to f } }.sortedBy { it.first }
        private val read = HashMap<File, List<Voice>>()

        override fun before(clip: String): List<Voice> {
            val time = clipTime(clip) ?: return emptyList()
            var i = files.binarySearchBy(time - WINDOW) { it.first }.let { if (it < 0) -it - 1 else it }
            while (i > 0 && files[i - 1].first >= time - WINDOW) i--
            val out = mutableListOf<Voice>()
            while (i < files.size && files[i].first < time) {
                val f = files[i++].second
                out += read.getOrPut(f) { runCatching { voicesOf(TranscriptJson.json.decodeFromString(Transcript.serializer(), f.readText())) }.logged("pooling: reading ${f.name}").getOrDefault(emptyList()) }
            }
            return out
        }
    }

    /** The voices of the recordings transcribed in [dir] during the [WINDOW] seconds before [clip]. */
    fun before(dir: File, clip: String): List<Voice> {
        val time = clipTime(clip) ?: return emptyList()
        return dir.listFiles { f -> f.extension == "json" && clipTime(f.name)?.let { it < time && it >= time - WINDOW } == true }.orEmpty()
            .flatMap { f -> runCatching { voicesOf(TranscriptJson.json.decodeFromString(Transcript.serializer(), f.readText())) }.logged("pooling: reading ${f.name}").getOrDefault(emptyList()) }
    }
}
