package net.boswell.phone.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import net.boswell.phone.models.ModelCatalog
import net.boswell.phone.models.ModelStore

/**
 * Nemotron 3.5 ASR, on the phone, through sherpa-onnx.
 *
 * Measured on this model (1120 ms chunk, int8) on a Pixel 10 Pro XL: RTF 0.12
 * with 4 threads, 0.26 with 8 -- the efficiency cores slow it down -- so 4.
 * Loading takes a couple of seconds and ~700 MB, so one instance is kept for
 * a batch of clips and released afterwards.
 */
class LocalAsr(store: ModelStore, threads: Int = 4) : AutoCloseable {
    private val recognizer = OnlineRecognizer(
        null,
        OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 128, dither = 0f),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = store.path(ModelCatalog.ASR, "encoder.int8.onnx"),
                    decoder = store.path(ModelCatalog.ASR, "decoder.int8.onnx"),
                    joiner = store.path(ModelCatalog.ASR, "joiner.int8.onnx"),
                ),
                tokens = store.path(ModelCatalog.ASR, "tokens.txt"),
                numThreads = threads,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
            enableEndpoint = false,
        ),
    )

    /** Transcribe mono 16 kHz audio in -1..1. */
    fun transcribe(audio: FloatArray, language: String = "en"): List<Word> {
        val s = recognizer.createStream("")
        try {
            runCatching { s.setOption("language", language) }
            val step = 16_000
            var i = 0
            while (i < audio.size) {
                s.acceptWaveform(audio.copyOfRange(i, minOf(i + step, audio.size)), 16_000)
                while (recognizer.isReady(s)) recognizer.decode(s)
                i += step
            }
            // Trailing silence flushes the last chunk through the encoder.
            s.acceptWaveform(FloatArray(24_000), 16_000)
            s.inputFinished()
            while (recognizer.isReady(s)) recognizer.decode(s)
            val r = recognizer.getResult(s)
            return Words.fromTokens(r.tokens, r.timestamps, audio.size / 16_000.0)
        } finally {
            s.release()
        }
    }

    override fun close() = recognizer.release()
}
