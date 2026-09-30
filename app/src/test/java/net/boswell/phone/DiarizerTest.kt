package net.boswell.phone

import net.boswell.phone.audio.Wav
import net.boswell.phone.diarize.Diarizer
import net.boswell.phone.diarize.OrtModels
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DiarizerTest {
    @Test fun `clustering merges similar vectors and keeps different ones apart`() {
        val d = Diarizer(segment = { error("unused") }, embed = { null })
        val a = floatArrayOf(1f, 0f, 0f); val a2 = Diarizer.unit(floatArrayOf(0.95f, 0.1f, 0f))
        val b = floatArrayOf(0f, 1f, 0f); val b2 = Diarizer.unit(floatArrayOf(0.1f, 0.95f, 0f))
        val labels = d.cluster(listOf(a, b, a2, b2))
        assertEquals(labels[0], labels[2])
        assertEquals(labels[1], labels[3])
        assert(labels[0] != labels[1])
    }

    @Test fun `powerset has the pyannote class order`() {
        assertEquals(7, Diarizer.POWERSET.size)
        assertEquals(listOf(true, true, false), Diarizer.POWERSET[4].toList())
    }

    /**
     * Runs the real models on real clips and writes JSON for
     * tools/diarize_compare.py to score against desktop pyannote. Local only:
     *   -Ddiar.models=tools/models/release-models-v1 -Ddiar.clips=a.wav,b.wav -Ddiar.out=out.json
     */
    @Test fun diarizeForComparison() {
        val models = System.getProperty("diar.models")?.let(::File)
        val clips = System.getProperty("diar.clips")?.split(",")?.map(::File)
        val out = System.getProperty("diar.out")?.let(::File)
        assumeTrue(models != null && clips != null && out != null)
        OrtModels(File(models, "pyannote-segmentation-3.0.onnx").path, File(models, "voiceprint.onnx").path).use { m ->
            val d = Diarizer(segment = m::segment, embed = m::voiceprint,
                stepSeconds = System.getProperty("diar.step")?.toDouble() ?: 2.0,
                mergeAt = System.getProperty("diar.merge")?.toDouble() ?: 0.60)
            val json = clips!!.joinToString(",\n", "[\n", "\n]") { f ->
                val (pcm, _) = Wav.readPcm(f)
                val audio = FloatArray(pcm.size) { pcm[it] / 32768f }
                val t0 = System.nanoTime()
                val r = d.run(audio)
                val ms = (System.nanoTime() - t0) / 1e6
                val spk = r.speakers.joinToString(",") { s ->
                    val turns = s.turns.joinToString(",") { "[%.3f,%.3f]".format(it.start, it.end) }
                    val vp = s.voiceprint?.joinToString(",") { "%.6f".format(it) } ?: ""
                    """{"turns":[$turns],"seconds":${"%.2f".format(s.seconds)},"voiceprint":[$vp]}"""
                }
                """{"clip":"${f.path}","ms":${"%.0f".format(ms)},"speakers":[$spk]}"""
            }
            out!!.writeText(json)
        }
    }

    /**
     * The speech check on real clips, for tools/speech_check.py to compare.
     * Local only: -Ddiar.models=tools/models/release-models-v1 -Ddiar.speechdir=<wavs> -Ddiar.out=out.json
     */
    @Test fun speechSecondsForComparison() {
        val models = System.getProperty("diar.models")?.let(::File)
        val dir = System.getProperty("diar.speechdir")?.let(::File)
        val out = System.getProperty("diar.out")?.let(::File)
        assumeTrue(models != null && dir != null && out != null)
        OrtModels(File(models, "pyannote-segmentation-3.0.onnx").path, File(models, "voiceprint.onnx").path).use { m ->
            val d = Diarizer(segment = m::segment, embed = m::voiceprint)
            val rows = dir!!.listFiles { f -> f.extension == "wav" && f.length() > 44 }.orEmpty().sortedBy { it.name }.map { f ->
                val (pcm, _) = Wav.readPcm(f)
                "\"${f.name}\":${"%.4f".format(d.speechSeconds(FloatArray(pcm.size) { pcm[it] / 32768f }))}"
            }
            out!!.writeText(rows.joinToString(",", "{", "}"))
        }
    }
}
