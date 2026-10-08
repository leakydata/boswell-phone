package net.boswell.phone

import net.boswell.phone.backup.Backup
import net.boswell.phone.backup.HashCache
import net.boswell.phone.backup.Incremental
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest

class IncrementalBackupTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun <T> inTemp(block: (File) -> T): T {
        val dir = kotlin.io.path.createTempDirectory("incremental").toFile()
        try { return block(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun `the hash cache reads a file only when it is new or changed`() = inTemp { dir ->
        val a = File(dir, "a.ogg").apply { writeBytes(ByteArray(70_000) { (it % 13).toByte() }) }
        val b = File(dir, "b.json").apply { writeText("{\"words\":[]}") }
        val store = File(dir, "cache/hashes.tsv")
        HashCache(store).run {
            assertEquals(sha(a.readBytes()), listed("files/clips/a.ogg", a)!!.sha256)
            assertEquals(sha(b.readBytes()), listed("files/transcripts/b.json", b)!!.sha256)
            assertEquals(2, hashed)
            save()
        }
        // The next day: nothing changed, nothing read.
        HashCache(store).run {
            val l = listed("files/clips/a.ogg", a)!!
            assertEquals(sha(a.readBytes()), l.sha256); assertEquals(70_000L, l.size); assertEquals(a.lastModified(), l.mtime)
            listed("files/transcripts/b.json", b)
            assertEquals(0, hashed)
            save()
        }
        // A transcript rewritten (a recheck): read again, even at the same size.
        b.writeText("{\"words\":[1]}".take(12)); b.setLastModified(b.lastModified() + 5_000)
        HashCache(store).run {
            assertEquals(sha(b.readBytes()), listed("files/transcripts/b.json", b)!!.sha256)
            assertEquals(1, hashed)
            assertNull(listed("files/clips/gone.wav", File(dir, "gone.wav")))
            save()                    // a.ogg wasn't listed this time: it drops out
        }
        assertEquals(listOf("files/transcripts/b.json"), store.readLines().map { it.split('\t')[3] })
        // A damaged cache is only a slower day.
        store.writeText("garbage\n1\tx\ty\tz\n")
        HashCache(store).run { assertEquals(sha(a.readBytes()), listed("files/clips/a.ogg", a)!!.sha256); assertEquals(1, hashed) }
    }

    @Test fun `the file list is the zip's order, manifest first, without files that went`() = inTemp { dir ->
        val files = File(dir, "files")
        File(files, "clips").mkdirs(); File(files, "transcripts").mkdirs()
        File(files, "clips/omi_1.ogg").writeBytes(ByteArray(1000) { 1 })
        File(files, "clips/omi_1.json").writeText("{}")
        File(files, "transcripts/omi_1.json").writeText("{\"t\":1}")
        File(files, "clips/sub").mkdirs()                                  // not a file: not listed
        val manifest = File(dir, "boswell-backup.json").apply { writeText("{\"format\":1}") }
        val settings = File(dir, "settings.json").apply { writeText("{}") }
        val db = File(dir, "speakers.db").apply { writeBytes(ByteArray(4096)) }
        val folder = Backup.listFiles(files)
        assertEquals(setOf("files/clips/omi_1.ogg", "files/clips/omi_1.json"), folder.take(2).map { it.first }.toSet())
        assertEquals("files/transcripts/omi_1.json", folder.last().first)
        val gone = folder + ("files/clips/omi_2.wav" to File(files, "clips/omi_2.wav"))   // compacted since the folder was read
        val seen = mutableListOf<Float>()
        val list = Incremental.list(listOf(Backup.MANIFEST to manifest, "settings.json" to settings, "databases/speakers.db" to db),
            gone, HashCache(File(dir, "h.tsv"))) { seen += it }
        assertEquals(listOf("boswell-backup.json", "settings.json", "databases/speakers.db") + folder.map { it.first }, list.map { it.path })
        assertEquals(sha(db.readBytes()), list[2].sha256)
        assertEquals(1000L, list.first { it.path == "files/clips/omi_1.ogg" }.size)
        assertEquals(1f, seen.last())
        // What /v1/backup/start is sent.
        val body = ByteArrayOutputStream().also { Incremental.writeStart(it, list) }.toString()
        val o = kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
        val first = (o["files"] as kotlinx.serialization.json.JsonArray)[0] as kotlinx.serialization.json.JsonObject
        assertEquals("\"boswell-backup.json\"", first["path"].toString()); assertEquals(list[0].sha256, (first["sha256"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(6, (o["files"] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test fun `a blob goes up as length, bytes, sha256`() = inTemp { dir ->
        val data = ByteArray(200_000) { (it * 7).toByte() }
        val f = File(dir, "omi_1.ogg").apply { writeBytes(data) }
        val buf = ByteArrayOutputStream()
        var counted = 0L
        val sent = Incremental.writeBlob(buf, f) { counted += it }!!
        assertEquals(sha(data), sent.sha256); assertEquals(200_000L, sent.size); assertEquals(200_000L, counted)
        assertNull(Incremental.writeBlob(buf, File(dir, "gone.wav")))      // nothing written for it
        val input = DataInputStream(buf.toByteArray().inputStream())
        assertEquals(200_000L, input.readLong())
        val got = ByteArray(200_000).also { input.readFully(it) }
        assertTrue(got.contentEquals(data))
        assertEquals(sha(data), ByteArray(32).also { input.readFully(it) }.joinToString("") { "%02x".format(it) })
        assertEquals(-1, input.read())
    }
}
