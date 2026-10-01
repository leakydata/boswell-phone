package net.boswell.phone

import net.boswell.phone.assistant.QuestionCapture
import net.boswell.phone.audio.OggOpus
import org.junit.Test
import java.io.File

/** Local only: real question audio through QuestionCapture. -Ddiar.qdir=<dir with the clips> */
class QuestionReplayTest {
    @Test fun replay() {
        val dir = System.getProperty("diar.qdir")?.let { File(it) }
        org.junit.Assume.assumeTrue(dir != null)
        // clip, when speech began, when it ended (from the transcript lines)
        val cases = listOf(Triple("omi_1790820570", 17.1, 22.0), Triple("omi_1790820601", 22.6, 25.7),
            Triple("omi_1790820786", 16.0, 20.7), Triple("omi_1790821092", 2.9, 4.6))
        for ((c, a, b) in cases) {
            val pcm = OggOpus.readPcm(File(dir, "$c.ogg"))
            val q = QuestionCapture()
            var i = ((a - 0.3) * 16_000).toInt().coerceAtLeast(0)
            while (!q.done && i + 320 <= pcm.size) { q.add(pcm.copyOfRange(i, i + 320)); i += 320 }
            val end = i / 16_000.0
            println("REPLAY $c: speech %.1f-%.1f s, question ended at %.1f s (%s)".format(a, b, end,
                if (end < b) "CUT %.1f s early".format(b - end) else "got it all"))
        }
    }

    /** The capture as it was until 2026-09-30 22:14: noise averaged over the first frames, a 0.012 floor, 1.2 s pause. */
    private class OldCapture {
        val frames = ArrayList<ShortArray>(); var noise = -1.0; var noiseN = 0; var peak = 0.0; var quietRun = 0
        var started = false; var done = false
        fun add(pcm: ShortArray) {
            if (done) return
            frames += pcm
            var s = 0.0; for (x in pcm) { val v = x / 32768.0; s += v * v }; val r = kotlin.math.sqrt(s / pcm.size)
            if (noiseN < 10) { noise = if (noise < 0) r else (noise * noiseN + r) / (noiseN + 1); noiseN++ }
            val speaking = r > maxOf(noise * 3, 0.012)
            if (speaking) { started = true; quietRun = 0; peak = maxOf(peak, r) }
            else if (started && r < maxOf(noise * 2, peak * 0.15)) quietRun++
            if ((started && quietRun >= 60) || (!started && frames.size >= 300) || frames.size >= 1000) done = true
        }
    }

    @Test fun replayOld() {
        val dir = System.getProperty("diar.qdir")?.let { File(it) }
        org.junit.Assume.assumeTrue(dir != null)
        val cases = listOf(Triple("omi_1790820570", 17.1, 22.0), Triple("omi_1790820601", 22.6, 25.7),
            Triple("omi_1790820786", 16.0, 20.7), Triple("omi_1790821092", 2.9, 4.6))
        for ((c, a, b) in cases) {
            val pcm = OggOpus.readPcm(File(dir, "$c.ogg"))
            val q = OldCapture()
            var i = ((a - 0.3) * 16_000).toInt().coerceAtLeast(0)
            while (!q.done && i + 320 <= pcm.size) { q.add(pcm.copyOfRange(i, i + 320)); i += 320 }
            val end = i / 16_000.0
            println("OLD $c: speech %.1f-%.1f s, ended at %.1f s (%s)".format(a, b, end, if (end < b) "CUT %.1f s early".format(b - end) else "got it all"))
        }
    }
}
