package net.boswell.phone.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import net.boswell.phone.assistant.Secrets
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Everything that is yours, in one file: recordings, transcripts, people and
 * their voices, to-dos, what the assistant remembers, its history, and the
 * settings. For moving to a new phone (or from a test build to the release),
 * and for keeping a copy.
 *
 * Left out on purpose: the models (they download again), the archive index
 * (rebuilt from the transcripts), and anything tied to this phone (the chosen
 * calendar, sync bookkeeping). API keys are encrypted with a key that never
 * leaves this phone's keystore, so they're only included -- in plain text --
 * when asked for.
 */
object Backup {
    const val FORMAT = 1
    private const val MANIFEST = "boswell-backup.json"
    private val DATABASES = listOf("speakers.db", "todo.db", "life.db", "assistant.db")
    private val FOLDERS = listOf("clips", "transcripts")
    private val SKIP_PREFS = setOf("setup_done", "calendar_id", "last_sync", "last_sync_result", "omi_battery_band", "restored")
    private val json = Json { prettyPrint = false }

    data class Summary(val recordings: Int, val bytes: Long, val keys: Boolean, val created: Long)

    fun suggestedName() = "boswell-backup-${java.time.LocalDate.now()}.zip"

    /** Write a backup to [uri]. [progress] gets 0..1. */
    fun export(c: Context, uri: Uri, includeKeys: Boolean, progress: (Float) -> Unit = {}): Summary {
        val files = FOLDERS.flatMap { d -> File(c.filesDir, d).listFiles().orEmpty().filter { it.isFile }.map { "files/$d/${it.name}" to it } }
        val total = files.sumOf { it.second.length() }.coerceAtLeast(1)
        val tmp = File(c.cacheDir, "backup-db").apply { deleteRecursively(); mkdirs() }
        var written = 0L
        val created = System.currentTimeMillis() / 1000
        val recordings = files.count { it.first.startsWith("files/transcripts/") }
        try {
            c.contentResolver.openOutputStream(uri)!!.use { raw ->
                ZipOutputStream(raw.buffered()).use { zip ->
                    fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                    // The manifest goes first, so a restore can refuse a wrong file before reading the rest.
                    put(MANIFEST, json.encodeToString(JsonObject.serializer(), buildJsonObject {
                        put("format", FORMAT); put("app", c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""); put("created", created)
                        put("recordings", recordings); put("keys", includeKeys)
                    }).toByteArray())
                    put("settings.json", json.encodeToString(JsonObject.serializer(), prefsToJson(c)).toByteArray())
                    if (includeKeys) put("keys.json", json.encodeToString(JsonObject.serializer(), buildJsonObject {
                        Secrets.get(c, Secrets.OPENROUTER)?.let { put(Secrets.OPENROUTER, it) }
                        Secrets.get(c, net.boswell.phone.assistant.Email.PASSWORD)?.let { put(net.boswell.phone.assistant.Email.PASSWORD, it) }
                    }).toByteArray())
                    // A consistent copy of each database, even while the app is using it.
                    for (name in DATABASES) {
                        val src = c.getDatabasePath(name).takeIf { it.exists() } ?: continue
                        val out = File(tmp, name)
                        SQLiteDatabase.openDatabase(src.path, null, SQLiteDatabase.OPEN_READONLY).use { it.execSQL("VACUUM INTO ?", arrayOf(out.path)) }
                        zip.putNextEntry(ZipEntry("databases/$name")); out.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    }
                    for ((name, f) in files) {
                        // Audio is already compressed; storing it saves the time of deflating it again.
                        val e = ZipEntry(name)
                        if (f.name.endsWith(".ogg")) {
                            e.method = ZipEntry.STORED; e.size = f.length(); e.compressedSize = f.length()
                            e.crc = java.util.zip.CRC32().also { crc -> f.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; crc.update(b, 0, n) } } }.value
                        }
                        zip.putNextEntry(e); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                        written += f.length(); progress(written.toFloat() / total)
                    }
                }
            }
        } finally { tmp.deleteRecursively() }
        return Summary(recordings, total, includeKeys, created)
    }

    /** Read only the manifest: what a backup holds, or null if it isn't one. */
    fun peek(c: Context, uri: Uri): Summary? = runCatching {
        c.contentResolver.openInputStream(uri)!!.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                val e = zip.nextEntry ?: return null
                if (e.name != MANIFEST) return null
                val m = Json.parseToJsonElement(zip.readBytes().decodeToString()).jsonObject
                if (m["format"]!!.jsonPrimitive.int > FORMAT) return null
                Summary(m["recordings"]!!.jsonPrimitive.int, 0, m["keys"]?.jsonPrimitive?.boolean == true, m["created"]!!.jsonPrimitive.long)
            }
        }
    }.getOrNull()

    /**
     * Replace this install's data with a backup's. Unpacked to a staging
     * folder first, so a bad file leaves everything as it was. The app must
     * restart afterwards (see [restart]): open databases are swapped underneath it.
     */
    fun restore(c: Context, uri: Uri, progress: (Float) -> Unit = {}) {
        val stage = File(c.filesDir, "restore-stage").apply { deleteRecursively(); mkdirs() }
        val size = runCatching { c.contentResolver.openAssetFileDescriptor(uri, "r")!!.use { it.length } }.getOrDefault(-1L).coerceAtLeast(1)
        var settings: JsonObject? = null
        var keys: JsonObject? = null
        var read = 0L
        try {
            c.contentResolver.openInputStream(uri)!!.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    val first = zip.nextEntry
                    require(first?.name == MANIFEST) { "This isn't a Boswell backup." }
                    val m = Json.parseToJsonElement(zip.readBytes().decodeToString()).jsonObject
                    require(m["format"]!!.jsonPrimitive.int <= FORMAT) { "This backup is from a newer Boswell. Update the app first." }
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val name = e.name
                        when {
                            name == "settings.json" -> settings = Json.parseToJsonElement(zip.readBytes().decodeToString()).jsonObject
                            name == "keys.json" -> keys = Json.parseToJsonElement(zip.readBytes().decodeToString()).jsonObject
                            e.isDirectory -> Unit
                            else -> {
                                // Only the folders a backup writes, and no climbing out of them.
                                val ok = (name.startsWith("databases/") && name.removePrefix("databases/") in DATABASES) ||
                                    FOLDERS.any { name.startsWith("files/$it/") && !name.removePrefix("files/$it/").contains('/') }
                                if (!ok || name.contains("..")) continue
                                val out = File(stage, name).apply { parentFile!!.mkdirs() }
                                out.outputStream().use { zip.copyTo(it) }
                                read += out.length(); progress((read.toFloat() / size).coerceAtMost(0.99f))
                            }
                        }
                    }
                }
            }
            // Everything unpacked: now swap it in.
            for (d in FOLDERS) {
                val dest = File(c.filesDir, d)
                val from = File(stage, "files/$d")
                dest.deleteRecursively()
                if (from.exists()) check(from.renameTo(dest)) { "couldn't move $d into place" } else dest.mkdirs()
            }
            for (name in DATABASES + "archive.db") {
                val db = c.getDatabasePath(name)
                for (suffix in listOf("", "-journal", "-wal", "-shm")) File(db.path + suffix).delete()
                val from = File(stage, "databases/$name")
                if (from.exists()) { db.parentFile!!.mkdirs(); check(from.renameTo(db)) { "couldn't move $name into place" } }
            }
            settings?.let { jsonToPrefs(c, it) }
            keys?.let { k ->
                for (name in listOf(Secrets.OPENROUTER, net.boswell.phone.assistant.Email.PASSWORD))
                    k[name]?.jsonPrimitive?.contentOrNull?.let { Secrets.put(c, name, it) }
            }
            // Secrets.put saves in the background, and restart() ends the process at once:
            // write it out now, or the key is lost.
            c.getSharedPreferences("boswell-secrets", Context.MODE_PRIVATE).edit().commit()
            c.getSharedPreferences("boswell", Context.MODE_PRIVATE).edit()
                .putBoolean("setup_done", false).putBoolean("restored", true).commit()
            progress(1f)
        } finally { stage.deleteRecursively() }
    }

    /** Start the app again from scratch, after a restore. */
    fun restart(c: Context) {
        val launch = c.packageManager.getLaunchIntentForPackage(c.packageName)!!
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        c.startActivity(launch)
        Runtime.getRuntime().exit(0)
    }

    /** After a restore's restart: put back what lives outside the files (reminders). */
    fun afterRestore(c: Context) {
        val p = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)
        if (!p.getBoolean("restored", false)) return
        val now = System.currentTimeMillis() / 1000.0
        net.boswell.phone.todo.TodoStore(c).use { s ->
            for (t in s.all(includeDone = false)) t.due?.takeIf { it > now }?.let { net.boswell.phone.todo.TodoReminders.schedule(c, t.id, it) }
        }
        p.edit().remove("restored").apply()
    }

    fun wasRestored(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE).getBoolean("restored", false)

    private fun prefsToJson(c: Context): JsonObject = buildJsonObject {
        for ((k, v) in c.getSharedPreferences("boswell", Context.MODE_PRIVATE).all) {
            if (k in SKIP_PREFS || v == null) continue
            put(k, buildJsonObject {
                when (v) {
                    is Boolean -> { put("t", "b"); put("v", v) }
                    is Int -> { put("t", "i"); put("v", v) }
                    is Long -> { put("t", "l"); put("v", v) }
                    is Float -> { put("t", "f"); put("v", v) }
                    is String -> { put("t", "s"); put("v", v) }
                    is Set<*> -> { put("t", "ss"); put("v", kotlinx.serialization.json.JsonArray(v.map { JsonPrimitive(it.toString()) })) }
                    else -> return@buildJsonObject
                }
            })
        }
    }

    private fun jsonToPrefs(c: Context, o: JsonObject) {
        val e = c.getSharedPreferences("boswell", Context.MODE_PRIVATE).edit()
        for ((k, el) in o) {
            if (k in SKIP_PREFS) continue
            val x = el.jsonObject; val v = x["v"] ?: continue
            when (x["t"]?.jsonPrimitive?.content) {
                "b" -> e.putBoolean(k, v.jsonPrimitive.boolean)
                "i" -> e.putInt(k, v.jsonPrimitive.int)
                "l" -> e.putLong(k, v.jsonPrimitive.long)
                "f" -> e.putFloat(k, v.jsonPrimitive.double.toFloat())
                "s" -> e.putString(k, v.jsonPrimitive.content)
                "ss" -> e.putStringSet(k, v.jsonArray.map { it.jsonPrimitive.content }.toSet())
            }
        }
        e.commit()
    }
}
