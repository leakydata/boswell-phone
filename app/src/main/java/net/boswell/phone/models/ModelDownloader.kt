package net.boswell.phone.models

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

class DownloadException(message: String) : Exception(message)

/**
 * Downloads one file with resume and verification -- or, when the catalog
 * lists a gzip copy, that smaller copy, unpacked and checked against the
 * original's size and SHA-256 before it is put in place.
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
        val packed = spec.gz
        if (packed == null) {
            fetch(baseUrl, spec.name, spec.size, spec.sha256, dir, onBytes, isCancelled)
            return
        }
        // The compressed copy: fetched and verified exactly like a plain file,
        // then unpacked and the result verified against the original's hash.
        val gz = File(dir, packed.name)
        if (!(gz.exists() && gz.length() == packed.size)) {
            fetch(baseUrl, packed.name, packed.size, packed.sha256, dir, { n -> onBytes(n * spec.size / packed.size) }, isCancelled)
        }
        onBytes(spec.size)
        unpack(gz, spec, dir, isCancelled)
        gz.delete()
    }

    private fun unpack(gz: File, spec: ModelFile, dir: File, isCancelled: () -> Boolean) {
        val tmp = File(dir, spec.name + ".unpack")
        val digest = MessageDigest.getInstance("SHA-256")
        var n = 0L
        try {
            GZIPInputStream(BufferedInputStream(FileInputStream(gz), BUFFER), BUFFER).use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        if (isCancelled()) throw DownloadException("canceled")
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r); digest.update(buf, 0, r); n += r
                    }
                    out.fd.sync()
                }
            }
        } catch (e: java.io.IOException) {
            tmp.delete(); gz.delete()
            throw DownloadException("${spec.name}: could not unpack (${e.message}), discarded")
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (n != spec.size || got != spec.sha256) {
            tmp.delete(); gz.delete()
            throw DownloadException("${spec.name}: unpacked file doesn't match, discarded")
        }
        if (!tmp.renameTo(File(dir, spec.name))) throw DownloadException("could not install ${spec.name}")
    }

    /** One file from the release, resumable and verified, renamed into place as [name]. */
    private fun fetch(
        baseUrl: String, name: String, size: Long, sha256: String, dir: File,
        onBytes: (Long) -> Unit, isCancelled: () -> Boolean,
    ) {
        val target = File(dir, name)
        val part = File(dir, "$name.part")
        if (part.exists() && part.length() > size) part.delete()

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

        if (have < size) {
            val conn = (URI(baseUrl + name).toURL().openConnection() as HttpURLConnection).apply {
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
                    return fetch(baseUrl, name, size, sha256, dir, onBytes, isCancelled)
                }
                if (code != 200 && code != 206) throw DownloadException("HTTP $code for $name")
                conn.inputStream.use { input ->
                    FileOutputStream(part, true).use { out ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            if (isCancelled()) throw DownloadException("canceled")
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

        if (have != size) throw DownloadException("$name: got $have of $size bytes")
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (got != sha256) {
            part.delete()
            throw DownloadException("$name: checksum mismatch, discarded")
        }
        if (!part.renameTo(target)) throw DownloadException("could not install $name")
    }
}
