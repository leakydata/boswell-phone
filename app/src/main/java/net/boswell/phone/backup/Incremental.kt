package net.boswell.phone.backup

import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.OutputStream
import java.security.MessageDigest

/**
 * The pieces of an incremental home backup (see [HomeBackup]): every file a backup
 * holds is listed with its sha256, the server answers which it doesn't have, and only
 * those go up. The server keeps each file once and can still hand back a whole backup
 * zip for any day it keeps.
 */
internal object Incremental {
    /** One file of a backup: its name in the zip, the file, and what the server is told about it. */
    data class Listed(val path: String, val file: File, val size: Long, val mtime: Long, val sha256: String)

    fun sha256(f: File): String = FileInputStream(f).use { sha256(it) }

    private fun sha256(input: java.io.InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val b = ByteArray(65536)
        while (true) { val n = input.read(b); if (n < 0) break; md.update(b, 0, n) }
        return md.digest().toHex()
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * The backup's file list, in zip order: [parts] (its manifest first, the settings, the
     * databases), hashed every time since they're small and change; then [files], the
     * recordings and transcripts, hashed only when new or changed ([cache]). A file gone
     * since the folder was read is left out. [progress] gets 0..1.
     */
    fun list(parts: List<Pair<String, File>>, files: List<Pair<String, File>>, cache: HashCache,
             progress: (Float) -> Unit = {}): List<Listed> {
        val out = ArrayList<Listed>(parts.size + files.size)
        for ((path, f) in parts) out += Listed(path, f, f.length(), f.lastModified(), sha256(f))
        files.forEachIndexed { i, (path, f) ->
            cache.listed(path, f)?.let { out += it }
            if (i % 200 == 0) progress(i.toFloat() / files.size)
        }
        progress(1f)
        return out
    }

    /** POST /v1/backup/start's body, written straight to [out]: {"files": [{path, size, mtime, sha256}, …]}. */
    fun writeStart(out: OutputStream, list: List<Listed>) {
        val w = out.bufferedWriter()
        w.write("{\"files\":[")
        list.forEachIndexed { i, l ->
            if (i > 0) w.write(",")
            w.write("{\"path\":${JsonPrimitive(l.path)},\"size\":${l.size},\"mtime\":${l.mtime},\"sha256\":\"${l.sha256}\"}")
        }
        w.write("]}")
        w.flush()
    }

    /** What actually went up for a file: its sha256 and size, as read while sending. */
    data class Sent(val sha256: String, val size: Long)

    /**
     * Write [f] to [out] as one blob of POST /v1/backup/blobs: its length (8 bytes,
     * big-endian), the bytes, and their sha256 (32 bytes), hashed as they're read so the
     * server can check what arrived. Returns what was sent, or null (and writes nothing)
     * if the file is gone. All from one open file, so it can't change length halfway:
     * exactly the length announced is sent.
     */
    fun writeBlob(out: OutputStream, f: File, counted: (Int) -> Unit = {}): Sent? {
        val input = try { FileInputStream(f) } catch (e: FileNotFoundException) { return null }
        input.use {
            val size = input.channel.size()
            out.write(java.nio.ByteBuffer.allocate(8).putLong(size).array())
            val md = MessageDigest.getInstance("SHA-256")
            val b = ByteArray(65536)
            var left = size
            while (left > 0) {
                val n = input.read(b, 0, minOf(b.size.toLong(), left).toInt())
                if (n < 0) throw java.io.IOException("${f.name} got shorter while it was sent")
                md.update(b, 0, n); out.write(b, 0, n); left -= n; counted(n)
            }
            val digest = md.digest()
            out.write(digest)
            return Sent(digest.toHex(), size)
        }
    }
}

/**
 * The sha256 of each backed-up file, remembered by its name, size and modification
 * time, so a daily backup doesn't read 12,000 recordings again to find the few new
 * ones. Kept as lines of "size<TAB>mtime<TAB>sha256<TAB>path". Only the files listed
 * this time are kept when it's saved, so deleted ones drop out.
 */
internal class HashCache(private val file: File) {
    private class Known(val size: Long, val mtime: Long, val sha256: String)
    private val known = HashMap<String, Known>()
    private val seen = HashMap<String, Known>()
    /** How many files had to be read this time. */
    var hashed = 0
        private set

    init {
        runCatching {
            if (file.isFile) file.forEachLine { line ->
                val p = line.split('\t', limit = 4)
                if (p.size == 4) known[p[3]] = Known(p[0].toLong(), p[1].toLong(), p[2])
            }
        }
    }

    /** [f], listed as [path]: its sha256 from the cache if it hasn't changed, else read now. Null if it's gone. */
    fun listed(path: String, f: File): Incremental.Listed? {
        val size = f.length()
        val mtime = f.lastModified()
        if (mtime == 0L && !f.exists()) return null
        val k = known[path]?.takeIf { it.size == size && it.mtime == mtime }
            ?: try { Known(size, mtime, Incremental.sha256(f)).also { hashed++ } } catch (e: FileNotFoundException) { return null }
        seen[path] = k
        return Incremental.Listed(path, f, k.size, k.mtime, k.sha256)
    }

    fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            for ((path, k) in seen) if ('\n' !in path && '\t' !in path) w.write("${k.size}\t${k.mtime}\t${k.sha256}\t$path\n")
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
