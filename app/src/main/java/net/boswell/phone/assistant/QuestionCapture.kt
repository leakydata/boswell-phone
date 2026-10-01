package net.boswell.phone.assistant

import kotlin.math.sqrt

/**
 * Collects what is said after a button tap, and decides when the question is
 * over.
 *
 * No VAD model: the room's own level is learned from the quietest of the
 * first few frames (someone may start talking the instant they tap), speech
 * is anything well above it, and the question ends after a pause of
 * [pauseFrames] once speech has started -- 2 s, because people pause
 * mid-question and 1.2 s cut questions off ("I don't have any"). The Omi's mic sleeps in silence, so
 * the stream simply stopping (see [idle]) also ends it. A second tap ends it
 * at once. Nothing said within [maxWaitFrames] means no question.
 */
class QuestionCapture(
    private val pauseFrames: Int = 100,       // 2 s of 20 ms frames
    private val maxWaitFrames: Int = 300,     // 6 s to start talking
    private val maxFrames: Int = 1000,        // 20 s at most
) {
    private val frames = ArrayList<ShortArray>()
    private var noise = -1.0
    private var noiseN = 0
    private var peak = 0.0
    private var quietRun = 0
    var speechStarted = false
        private set
    var done = false
        private set

    fun add(pcm: ShortArray) {
        if (done) return
        frames += pcm
        val r = rms(pcm)
        if (noiseN < 10) {
            noise = if (noise < 0) r else minOf(noise, r); noiseN++
        }
        // The Omi records quietly (a voice reading aloud sat near 0.006 rms), so
        // the bar is relative to the room with only a small floor; 0.012 missed
        // soft speech.
        val speaking = r > maxOf(noise * 3, 0.004)
        if (speaking) { speechStarted = true; quietRun = 0; peak = maxOf(peak, r) }
        else if (speechStarted && r < maxOf(noise * 2, peak * 0.15)) quietRun++
        when {
            speechStarted && quietRun >= pauseFrames -> done = true
            !speechStarted && frames.size >= maxWaitFrames -> done = true
            frames.size >= maxFrames -> done = true
        }
    }

    /** The stream went quiet (the mic sleeps in silence): after speech, that is the end. */
    fun idle() { if (speechStarted) done = true }

    fun finish() { done = true }

    fun audio(): FloatArray {
        val n = frames.sumOf { it.size }
        val out = FloatArray(n)
        var o = 0
        for (f in frames) for (s in f) out[o++] = s / 32768f
        return out
    }

    private fun rms(p: ShortArray): Double {
        var s = 0.0
        for (x in p) { val v = x / 32768.0; s += v * v }
        return sqrt(s / p.size.coerceAtLeast(1))
    }
}
