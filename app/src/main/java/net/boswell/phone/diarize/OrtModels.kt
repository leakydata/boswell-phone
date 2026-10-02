package net.boswell.phone.diarize

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * segmentation-3.0 and voiceprint.onnx through ONNX Runtime. The same API
 * exists in onnxruntime-android and the desktop jar, so the unit tests run
 * these exact calls against the real models.
 */
class OrtModels(segmentationPath: String, voiceprintPath: String, threads: Int = 4,
                /** The "who is this" model (VoiceModel.SPEAKER_ID), when installed and in use. */
                speakerIdPath: String? = null) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val seg = env.createSession(segmentationPath, opts)
    private val vp = env.createSession(voiceprintPath, opts)
    private val sid = speakerIdPath?.let { env.createSession(it, opts) }

    /** Which voiceprints this produces: the model's id and its vectors' size. */
    val identity: VoiceModel get() = if (sid != null) VoiceModel.SPEAKER_ID else VoiceModel.WESPEAKER

    /** One 10 s window (160,000 samples) -> 589 frames x 7 powerset log-probabilities. */
    fun segment(window: FloatArray): Array<FloatArray> =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(window), longArrayOf(1, 1, window.size.toLong())).use { x ->
            seg.run(mapOf("x" to x)).use { r ->
                @Suppress("UNCHECKED_CAST")
                (r[0].value as Array<Array<FloatArray>>)[0]
            }
        }

    /**
     * Raw 16 kHz audio -> the desktop-compatible 256-d voiceprint, or null if
     * there is too little audio for the model's front end (under 25 ms) or the
     * result is not finite.
     */
    fun voiceprint(audio: FloatArray): FloatArray? {
        if (audio.size < 400) return null
        val v = OnnxTensor.createTensor(env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong())).use { x ->
            vp.run(mapOf("audio" to x)).use { r ->
                @Suppress("UNCHECKED_CAST")
                (r[0].value as Array<FloatArray>)[0]
            }
        }
        return if (v.all { it.isFinite() }) v else null
    }

    /**
     * The voiceprint that says who a speaker is: the speaker-ID model when in
     * use (raw 16 kHz "waveform" in, a unit vector out), else the same model
     * that splits speakers. At most [VoiceModel.MAX_ID_SECONDS] of speech is
     * used -- more barely helps and costs time on a phone.
     */
    fun identify(audio: FloatArray): FloatArray? {
        val s = sid ?: return voiceprint(audio)
        if (audio.size < 16_000 / 2) return null
        val x = if (audio.size > VoiceModel.MAX_ID_SAMPLES) audio.copyOfRange(0, VoiceModel.MAX_ID_SAMPLES) else audio
        val v = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, x.size.toLong())).use { t ->
            s.run(mapOf("waveform" to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                (r[0].value as Array<FloatArray>)[0]
            }
        }
        return if (v.all { it.isFinite() }) v else null
    }

    fun diarizer() = Diarizer(segment = ::segment, embed = ::voiceprint, identify = ::identify)

    override fun close() {
        seg.close()
        vp.close()
        sid?.close()
        opts.close()
    }
}
