package net.boswell.phone.speakers

import net.boswell.phone.diarize.Turn
import kotlin.math.log10

/**
 * How far a voice stands above its recording's noise floor, in dB: the
 * level of the loudest 70% of its turns' frames over the 10th percentile of
 * every frame in the recording. The one who wears the Omi is the near voice,
 * so this tells the owner from a TV or someone across the room (Matching's
 * owner rule) and says which voices are clean enough to pool (Pooling).
 * The home server works it out the same way (pipeline.snr_db).
 */
object Snr {
    private const val SR = 16_000
    private const val FRAME = 512
    private const val HOP = 160

    /** [audio] at 16 kHz; null without any turns. */
    fun db(audio: FloatArray, turns: List<Turn>): Double? {
        if (turns.isEmpty()) return null
        val parts = turns.map { t ->
            val a = (t.start * SR).toInt().coerceIn(0, audio.size)
            val b = (t.end * SR).toInt().coerceIn(a, audio.size)
            a to b
        }
        val voice = FloatArray(parts.sumOf { it.second - it.first })
        var at = 0
        for ((a, b) in parts) { audio.copyInto(voice, at, a, b); at += b - a }
        val e = energies(voice)
        val floor = percentile(e, 30.0)
        var sum = 0.0
        var n = 0
        for (x in e) if (x >= floor) { sum += x; n++ }
        return 10 * log10(sum / n + 1e-12) - 10 * log10(percentile(energies(audio), 10.0) + 1e-12)
    }

    /** Mean square of each 512-sample frame, every 160 samples; one frame (zero-padded) for anything shorter. */
    internal fun energies(x: FloatArray): DoubleArray {
        val n = if (x.size < FRAME) 1 else 1 + (x.size - FRAME) / HOP
        return DoubleArray(n) { k ->
            var s = 0.0
            val from = k * HOP
            for (i in from until minOf(from + FRAME, x.size)) s += x[i].toDouble() * x[i]
            s / FRAME
        }
    }

    /** numpy's percentile (linear between the two nearest ranks). */
    internal fun percentile(v: DoubleArray, p: Double): Double {
        val s = v.sorted()
        val pos = p / 100 * (s.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }
}
