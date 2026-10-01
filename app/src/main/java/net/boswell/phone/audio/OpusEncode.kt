package net.boswell.phone.audio

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal
import java.io.File

/**
 * Opus-encode PCM for clips that predate keeping the Omi's own frames. The
 * Omi sends about 28 kbps; 32 kbps here keeps the second generation close
 * to the first. Pure Java (Concentus), like the decoder.
 */
object OpusEncode {
    const val BITRATE = 32_000

    fun toOgg(pcm: ShortArray, out: File, rate: Int = 16_000) {
        val enc = OpusEncoder(rate, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
            bitrate = BITRATE
            signalType = OpusSignal.OPUS_SIGNAL_VOICE
            complexity = 8
        }
        val frame = rate / 50                        // 20 ms
        val buf = ByteArray(1500)
        val packets = ArrayList<ByteArray>(pcm.size / frame + 2)
        var i = 0
        while (i < pcm.size) {
            val chunk = if (i + frame <= pcm.size) pcm.copyOfRange(i, i + frame)
                else ShortArray(frame).also { pcm.copyInto(it, 0, i, pcm.size) }     // last frame padded with silence
            val n = enc.encode(chunk, 0, frame, buf, 0, buf.size)
            packets += buf.copyOf(n)
            i += frame
        }
        OggOpus.write(out, packets, rate, preSkip = 0)
    }
}
