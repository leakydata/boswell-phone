package net.boswell.phone.audio

import io.github.jaredmdobson.concentus.OpusDecoder

/**
 * Stateful Opus decoder for one stream, 16 kHz mono -- the Omi's encoder
 * settings, so no resampling. Concentus is a pure-Java port of libopus, which
 * keeps the build free of native code.
 *
 * Measured against libopus (what desktop Boswell decodes with) on three real
 * Omi spool files, 2026-09-30 (tools/opus_parity.py + OpusParityTest): the two
 * refuse exactly the same frames, 99.85% of samples agree within +/-1, and
 * voiceprints of the decoded audio agree at cosine 0.9999. The remainder is
 * about 7 ms in 290 s where Concentus outputs zeros around damaged stretches.
 * Swap in libopus through the NDK if transcription ever shows it matters.
 *
 * Opus frames depend on the frames before them, so one decoder per stream, and
 * a fresh one when the stream restarts (a new run or a reconnect).
 */
class OpusFrameDecoder(
    private val sampleRate: Int = 16_000,
    private val frameSamples: Int = 320,
) {
    private var decoder = OpusDecoder(sampleRate, 1)
    private val out = ShortArray(frameSamples * 6)   // room for up to 120 ms

    fun decode(frame: ByteArray): ShortArray {
        val n = decoder.decode(frame, 0, frame.size, out, 0, out.size, false)
        require(n > 0) { "opus decode returned $n" }
        return out.copyOf(n)
    }

    fun reset() {
        decoder = OpusDecoder(sampleRate, 1)
    }
}
