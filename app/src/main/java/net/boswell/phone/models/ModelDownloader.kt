package net.boswell.phone.models

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

class DownloadException(message: String) : Exception(message)

/**
 * Downloads one file with resume and verification.
 *
 * Bytes go to `<name>.part`. An interrupted download resumes from the part
 * file's length with an HTTP Range request; the existing bytes are re-hashed
 * first so the final SHA-256 covers the whole file. Only a file whose size and
 * hash both match the catalog is renamed into place -- a model that is
 * silently truncated or corrupted would otherwise load and produce nonsense.
 */
object ModelDownloader {
    private const val BUFFER = 1 shl 16

    fun download(
        baseUrl: String,
        spec: ModelFile,
        dir: File,
        onBytes: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val target = File(dir, spec.name)
        if (target.exists() && target.length() == spec.size) return
        val part = File(dir, spec.name + ".part")
        if (part.exists() && part.length() > spec.size) part.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        var have = if (part.exists()) part.length() else 0L
        if (have > 0) {
            RandomAccessFile(part, "r").use { raf ->
                val buf = ByteArray(BUFFER)
                var left = have
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    left -= n
                }
            }
        }
        onBytes(have)

        if (have < spec.size) {
            val conn = (URI(baseUrl + spec.name).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 60_000
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            try {
                val code = conn.responseCode
                if (have > 0 && code == 200) {
                    // Server ignored the range: start over rather than append a second copy.
                    part.delete()
                    return download(baseUrl, spec, dir, onBytes, isCancelled)
                }
                if (code != 200 && code != 206) throw DownloadException("HTTP $code for ${spec.name}")
                conn.inputStream.use { input ->
                    FileOutputStream(part, true).use { out ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            if (isCancelled()) throw DownloadException("cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            have += n
                            onBytes(have)
                        }
                        out.fd.sync()
                    }
                }
            } finally {
                conn.disconnect()
            }
        }

        if (have != spec.size) throw DownloadException("${spec.name}: got $have of ${spec.size} bytes")
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (got != spec.sha256) {
            part.delete()
            throw DownloadException("${spec.name}: checksum mismatch, discarded")
        }
        if (!part.renameTo(target)) throw DownloadException("could not install ${spec.name}")
    }
}
