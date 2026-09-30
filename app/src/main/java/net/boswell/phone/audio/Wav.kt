package net.boswell.phone.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit PCM mono WAV, written atomically: temp file, fsync, rename. */
object Wav {
    fun write(target: File, pcm: ShortArray, sampleRate: Int) {
        val dataBytes = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(dataBytes)
        for (s in pcm) buf.putShort(s)
        writeAtomically(target, buf.array())
    }

    fun readPcm(file: File): Pair<ShortArray, Int> {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val rate = b.getInt(24)
        var pos = 12
        while (pos + 8 <= b.limit()) {
            val id = String(ByteArray(4) { b.get(pos + it) })
            val size = b.getInt(pos + 4)
            if (id == "data") {
                val n = size / 2
                return ShortArray(n) { b.getShort(pos + 8 + it * 2) } to rate
            }
            pos += 8 + size
        }
        error("no data chunk in $file")
    }
}

/** Write, fsync, then rename: a crash leaves the old file or the new one, never half. */
fun writeAtomically(target: File, bytes: ByteArray) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, ".${target.name}.tmp")
    FileOutputStream(tmp).use { out ->
        out.write(bytes)
        out.fd.sync()
    }
    if (!tmp.renameTo(target)) {
        tmp.delete()
        error("could not rename $tmp to $target")
    }
}
