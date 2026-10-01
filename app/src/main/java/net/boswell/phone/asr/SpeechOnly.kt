package net.boswell.phone.asr

import net.boswell.phone.audio.Wav
import net.boswell.phone.diarize.Diarizer
import java.io.File

/**
 * Cloud transcription of only the parts of a clip where someone speaks.
 *
 * The cloud charges by the second, and on real Omi clips 59% of the audio
 * had no speech in it. Each stretch of speech is sent with [CONTEXT] seconds
 * either side, so a long silence shrinks to about twice that, and the word
 * times that come back are mapped onto the clip's own timeline.
 */
object SpeechOnly {
    const val CONTEXT = 0.5
    /** Below this saving the whole clip is sent: cutting it isn't worth the seams. */
    private const val MIN_SAVING = 0.15

    /** A kept piece: [len] seconds that sit at [sent] in what was sent and at [clip] in the clip. */
    data class Piece(val sent: Double, val clip: Double, val len: Double)

    /** The pieces to keep, from speech spans: padded, clamped to the clip, joined where they touch. */
    fun plan(spans: List<Pair<Double, Double>>, clipSeconds: Double, context: Double = CONTEXT): List<Piece> {
        val merged = mutableListOf<Pair<Double, Double>>()
        for ((a, b) in spans.sortedBy { it.first }) {
            val s = (a - context).coerceAtLeast(0.0); val e = (b + context).coerceAtMost(clipSeconds)
            val last = merged.lastOrNull()
            if (last != null && s <= last.second) merged[merged.lastIndex] = last.first to maxOf(last.second, e) else merged += s to e
        }
        var at = 0.0
        return merged.map { (s, e) -> Piece(at, s, e - s).also { at += e - s } }
    }

    /** A time in what was sent -> the same moment in the clip. */
    fun toClip(t: Double, pieces: List<Piece>): Double {
        val p = pieces.lastOrNull { t >= it.sent } ?: return t
        return p.clip + (t - p.sent).coerceAtMost(p.len)
    }

    fun transcribe(apiKey: String, engine: CloudAsr.Engine, pcm: ShortArray, diarizer: Diarizer, tmpDir: File): Pair<List<Word>, Double> {
        val sr = 16_000
        val seconds = pcm.size / sr.toDouble()
        val pieces = plan(diarizer.speechSpans(FloatArray(pcm.size) { pcm[it] / 32768f }), seconds)
        val kept = pieces.sumOf { it.len }
        val whole = pieces.isEmpty() || kept > seconds * (1 - MIN_SAVING)
        val audio = if (whole) pcm else {
            val out = ShortArray(pieces.sumOf { (it.len * sr).toInt() })
            var o = 0
            for (p in pieces) {
                val a = (p.clip * sr).toInt().coerceIn(0, pcm.size); val n = (p.len * sr).toInt().coerceAtMost(pcm.size - a)
                pcm.copyInto(out, o, a, a + n); o += n
            }
            out.copyOf(o)
        }
        val f = File.createTempFile("cloud", ".wav", tmpDir)
        try {
            Wav.write(f, audio, sr)
            val (words, cost) = CloudAsr.transcribeWords(apiKey, engine, f)
            if (whole) return words to cost
            return words.map { w -> w.copy(start = toClip(w.start, pieces), end = toClip(w.end, pieces)) } to cost
        } finally { f.delete() }
    }
}
