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
/** With the speech model listening: this much without speech after speaking ends a question. */
const val PAUSE_S = 1.5

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
    /** Why it ended: pause, stream stopped, tap, no speech, or too long -- logged to tell them apart. */
    var endedBy: String? = null
        private set

    /**
     * Set once the speech model is listening ([heard]): it decides when the
     * question is over, and loudness no longer does. Loudness can't tell a
     * finished question from a noisy room -- a question once ran to the 20 s
     * limit after 6 s of speech -- and the model can.
     */
    @Volatile var modelListening = false

    /** Seconds of audio collected so far. */
    val seconds: Double get() = synchronized(frames) { frames.sumOf { it.size } } / 16_000.0

    /** The last [maxSeconds] of what's been collected, for the speech model. */
    fun tail(maxSeconds: Double): FloatArray = synchronized(frames) {
        val n = frames.sumOf { it.size }
        val keep = minOf(n, (maxSeconds * 16_000).toInt())
        val out = FloatArray(keep)
        var skip = n - keep; var o = 0
        for (f in frames) for (s in f) { if (skip > 0) { skip--; continue }; out[o++] = s / 32768f }
        out
    }

    /**
     * What the speech model heard in the last stretch: [spans] in seconds into
     * that tail of [tailSeconds], which ends now. Over once someone has spoken
     * and the last [PAUSE_S] has none.
     */
    fun heard(spans: List<Pair<Double, Double>>, tailSeconds: Double) {
        if (done) return
        if (spans.isNotEmpty()) speechStarted = true
        val lastEnd = spans.maxOfOrNull { it.second } ?: if (speechStarted) 0.0 else return
        if (speechStarted && tailSeconds - lastEnd >= PAUSE_S && seconds >= 1.0) end("pause")
    }

    fun add(pcm: ShortArray) {
        if (done) return
        synchronized(frames) { frames += pcm }
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
            !modelListening && speechStarted && quietRun >= pauseFrames -> end("pause")
            !speechStarted && frames.size >= maxWaitFrames -> end("no speech")
            frames.size >= maxFrames -> end("too long")
        }
    }

    /**
     * The stream stopped for a while (the mic sleeps in silence). That ends
     * the question only if what came just before was already quiet: a stall
     * mid-word (a Bluetooth hiccup, the buzz as you start) once ended
     * questions while people were still talking.
     */
    fun idle() { if (speechStarted && quietRun >= 10) end("stream stopped") }

    fun finish() = end("tap")
    fun limit() = end("25 s limit")

    /** A second tap: finish once the audio still on its way over Bluetooth has arrived. */
    @Volatile var finishAt: Long? = null

    private fun end(why: String) { if (!done) { done = true; endedBy = why } }


    fun audio(): FloatArray = synchronized(frames) {
        val n = frames.sumOf { it.size }
        val out = FloatArray(n)
        var o = 0
        for (f in frames) for (s in f) out[o++] = s / 32768f
        out
    }

    private fun rms(p: ShortArray): Double {
        var s = 0.0
        for (x in p) { val v = x / 32768.0; s += v * v }
        return sqrt(s / p.size.coerceAtLeast(1))
    }
}
