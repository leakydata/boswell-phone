package net.boswell.phone.sound

import com.k2fsa.sherpa.onnx.AudioTagging
import com.k2fsa.sherpa.onnx.AudioTaggingConfig
import com.k2fsa.sherpa.onnx.AudioTaggingModelConfig
import net.boswell.phone.models.ModelStore

/**
 * What else was audible, on the phone: CED-Mini through sherpa-onnx.
 *
 * Listened to the desktop's way, in 10 s windows every 5 s plus one look at
 * the start, because a two-second bark inside thirty seconds is averaged into
 * nothing by a single verdict (the desktop measured Dog 0.009 whole vs 0.559
 * windowed). CED sees at most 10 s at once -- as does the desktop's AST,
 * which silently truncates -- so "whole" is the first ten seconds there too.
 *
 * Against the desktop's AST on 150 archive clips (tools/sound_compare.py):
 * the keep/empty verdict agrees on 92%, and CED is the less sensitive of the
 * two, so its "empty" is never used to delete anything on its own.
 */
class SoundTagger(store: ModelStore, threads: Int = 2) : AutoCloseable {
    private val tagger = AudioTagging(
        null,
        AudioTaggingConfig(
            model = AudioTaggingModelConfig(ced = store.path(ID, ".int8.onnx"), numThreads = threads, provider = "cpu"),
            labels = store.path(ID, "labels.csv"),
            topK = 20,
        ),
    )

    private fun look(audio: FloatArray): Map<String, Float> {
        val s = tagger.createStream()
        try {
            s.acceptWaveform(if (audio.size > MAX) audio.copyOf(MAX) else audio, 16_000)
            return tagger.compute(s, 20).associate { it.name to it.prob }
        } finally {
            s.release()
        }
    }

    fun tag(audio: FloatArray): List<SoundTag> {
        if (audio.size < 3_200) return emptyList()    // under 0.2 s: nothing to say
        val whole = look(audio)
        val n = (Sounds.WINDOW_S * 16_000).toInt()
        val step = (Sounds.HOP_S * 16_000).toInt()
        val windows = mutableListOf<Pair<Double, Map<String, Float>>>()
        var i = 0
        while (i == 0 || i + n <= audio.size) {
            val seg = audio.copyOfRange(i, minOf(i + n, audio.size))
            if (seg.size < 16_000) break
            windows += i / 16_000.0 to look(seg)
            i += step
            if (i + 16_000 > audio.size) break
        }
        val labels = whole.keys + windows.flatMap { it.second.keys }
        return labels.mapNotNull { label ->
            val hits = windows.mapNotNull { (t, w) -> w[label]?.takeIf { it >= Sounds.WINDOW_MIN }?.let { t to it } }
            val w = whole[label] ?: 0f
            val kept = hits.count { it.second >= Sounds.WINDOW_KEEP } >= 2 || w >= Sounds.WHOLE_KEEP
            if (!kept) return@mapNotNull null
            val best = hits.maxByOrNull { it.second }
            val (at, score) = if (best != null && best.second > w) best else 0.0 to w
            SoundTag(label, score.toDouble(), at)
        }.filter { it.score >= Sounds.FLOOR }.sortedByDescending { it.score }.take(Sounds.KEEP)
    }

    override fun close() = tagger.release()

    companion object {
        const val ID = "sound-tags"
        private const val MAX = 160_000
    }
}
