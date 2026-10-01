package net.boswell.phone

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import net.boswell.phone.audio.OggOpus
import net.boswell.phone.audio.OpusFrameDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.math.sin

class OggOpusTest {
    private fun frames(n: Int): List<ByteArray> {
        val enc = OpusEncoder(16_000, 1, OpusApplication.OPUS_APPLICATION_VOIP)
        val buf = ByteArray(1500)
        return (0 until n).map { k ->
            val pcm = ShortArray(320) { (sin((k * 320 + it) * 0.05) * 8000 * (1 + (k % 7))).toInt().toShort() }
            val len = enc.encode(pcm, 0, 320, buf, 0, buf.size)
            buf.copyOf(len)
        }
    }

    @Test fun `round trip gives the same samples as decoding the frames`() {
        val fs = frames(1500)                       // 30 s, several pages
        val f = File.createTempFile("clip", ".ogg")
        OggOpus.write(f, fs)
        val dec = OpusFrameDecoder()
        val direct = fs.flatMap { dec.decode(it).toList() }.toShortArray()
        val back = OggOpus.readPcm(f)
        assertEquals(direct.size, back.size)
        assertArrayEquals(direct, back)
        assertEquals(1500, OggOpus.readPackets(f).packets.size)
        System.getProperty("diar.oggout")?.let { f.copyTo(File(it), overwrite = true) }
    }

    @Test fun `packet durations from the TOC`() {
        assertEquals(960, OggOpus.samples48k(frames(1)[0]))
    }
}

/** Local only: what re-encoding old WAV clips costs. -Ddiar.models=<release dir> -Ddiar.speechdir=<wavs> */
class ReencodeQualityTest {
    @Test fun measure() {
        val models = System.getProperty("diar.models")?.let { File(it) }
        val dir = System.getProperty("diar.speechdir")?.let { File(it) }
        org.junit.Assume.assumeTrue(models != null && dir != null)
        net.boswell.phone.diarize.OrtModels(File(models, "pyannote-segmentation-3.0.onnx").path, File(models, "voiceprint.onnx").path).use { m ->
            val d = net.boswell.phone.diarize.Diarizer(segment = m::segment, embed = m::voiceprint)
            var wavBytes = 0L; var oggBytes = 0L; val cos = mutableListOf<Double>(); var flips = 0; var n = 0
            for (w in dir!!.listFiles()!!.filter { it.name.endsWith(".wav") && it.length() > 100_000 }.sortedBy { it.name }.take(60)) {
                val pcm = net.boswell.phone.audio.Wav.readPcm(w).first
                val o = File.createTempFile("reenc", ".ogg"); net.boswell.phone.audio.OpusEncode.toOgg(pcm, o)
                val back = net.boswell.phone.audio.OggOpus.readPcm(o)
                wavBytes += w.length(); oggBytes += o.length(); n++
                val a = FloatArray(pcm.size) { pcm[it] / 32768f }; val b = FloatArray(back.size) { back[it] / 32768f }
                val sa = d.speechSeconds(a); val sb = d.speechSeconds(b)
                if ((sa <= 0.3) != (sb <= 0.3)) flips++
                if (sa > 3) {
                    val va = m.voiceprint(a); val vb = m.voiceprint(b)
                    if (va != null && vb != null) cos += net.boswell.phone.speakers.Matching.dot(net.boswell.phone.speakers.Matching.unit(va), net.boswell.phone.speakers.Matching.unit(vb))
                }
                o.delete()
            }
            println("REENCODE $n clips: ${wavBytes / 1_000_000} MB wav -> ${oggBytes / 1_000_000} MB ogg (${"%.1f".format(wavBytes.toDouble() / oggBytes)}x smaller); " +
                "speech-check decisions changed: $flips; voiceprint cosine original vs re-encoded: min ${"%.3f".format(cos.minOrNull())}, median ${"%.3f".format(cos.sorted()[cos.size / 2])} over ${cos.size}")
        }
    }
}
