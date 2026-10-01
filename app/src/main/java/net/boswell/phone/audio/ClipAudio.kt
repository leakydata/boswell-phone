package net.boswell.phone.audio

import java.io.File

/**
 * A clip's audio, wherever it is kept. A clip is known everywhere by its
 * WAV name ("omi_123.wav" -- transcripts, the index, links); the sound
 * itself is that WAV while the clip is new, and an Ogg Opus copy beside it
 * ("omi_123.ogg", about a tenth of the size) once it has been transcribed.
 * Everything that reads or removes clip audio goes through here.
 */
object ClipAudio {
    fun wav(dir: File, name: String) = File(dir, name)
    fun ogg(dir: File, name: String) = File(dir, name.removeSuffix(".wav") + ".ogg")

    /** The file holding the clip's sound, or null if it has none (deleted, or cleaned up). */
    fun file(dir: File, name: String): File? = wav(dir, name).takeIf { it.exists() } ?: ogg(dir, name).takeIf { it.exists() }

    fun exists(dir: File, name: String) = file(dir, name) != null

    fun bytes(dir: File, name: String): Long = file(dir, name)?.length() ?: 0L

    /** 16 kHz mono samples. */
    fun readPcm(dir: File, name: String): ShortArray {
        val w = wav(dir, name)
        if (w.exists()) return Wav.readPcm(w).first
        val o = ogg(dir, name)
        if (o.exists()) return OggOpus.readPcm(o)
        throw java.io.FileNotFoundException("no audio for $name")
    }

    /** Remove the sound (both forms); the clip's times and transcript stay. */
    fun delete(dir: File, name: String) {
        wav(dir, name).delete()
        ogg(dir, name).delete()
    }

    /** Write the compact copy from the Opus frames the clip was decoded from. */
    fun writeCompact(dir: File, name: String, frames: List<ByteArray>) = OggOpus.write(ogg(dir, name), frames)

    /**
     * Once a clip is transcribed: keep only the compact copy, making one by
     * encoding the WAV if the frames weren't kept (clips from before this).
     * The WAV goes only if the copy decodes to the same length. Returns the
     * bytes saved.
     */
    fun compact(dir: File, name: String): Long {
        val w = wav(dir, name)
        if (!w.exists()) return 0
        val o = ogg(dir, name)
        val pcm = Wav.readPcm(w).first
        // Clips made before the Omi's own frames were kept are encoded once
        // (32 kbps; measured on 60 real clips: 8x smaller, voiceprints of the
        // copy 0.97 alike the original's -- and those already in transcripts
        // were made from the original). A failure keeps the WAV.
        if (!o.exists() && runCatching { OpusEncode.toOgg(pcm, o) }.isFailure) { o.delete(); return 0 }
        val back = runCatching { OggOpus.readPcm(o) }.getOrNull()
        // A frame or two of encoder delay is fine; anything else is a bad copy.
        if (back == null || kotlin.math.abs(back.size - pcm.size) > 2 * 320) {
            o.delete()
            return 0
        }
        val saved = w.length() - o.length()
        w.delete()
        return saved
    }
}
