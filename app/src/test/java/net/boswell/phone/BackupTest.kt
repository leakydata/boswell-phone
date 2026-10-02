package net.boswell.phone

import net.boswell.phone.backup.Backup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupTest {
    @Test fun `a file gone since the listing is skipped without an entry`() {
        val dir = kotlin.io.path.createTempDirectory("backup").toFile()
        try {
            val ogg = File(dir, "clip.ogg").apply { writeBytes(ByteArray(100_000) { (it % 251).toByte() }) }
            val wav = File(dir, "clip.wav").apply { writeBytes(ByteArray(10)) }
            val json = File(dir, "clip.json").apply { writeText("{}") }
            wav.delete()   // compacted while the backup ran
            val buf = ByteArrayOutputStream()
            ZipOutputStream(buf).use { zip ->
                assertEquals(100_000L, Backup.putFile(zip, "files/clips/clip.ogg", ogg))
                assertNull(Backup.putFile(zip, "files/clips/clip.wav", wav))
                assertEquals(2L, Backup.putFile(zip, "files/transcripts/clip.json", json))
            }
            val read = mutableMapOf<String, ByteArray>()
            ZipInputStream(buf.toByteArray().inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; read[e.name] = z.readBytes() } }
            assertEquals(setOf("files/clips/clip.ogg", "files/transcripts/clip.json"), read.keys)
            assertEquals(ogg.readBytes().toList(), read["files/clips/clip.ogg"]!!.toList())
        } finally { dir.deleteRecursively() }
    }
}
