package net.boswell.phone.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import net.boswell.phone.assistant.Secrets
import net.boswell.phone.capture.CaptureRepository
import net.boswell.phone.capture.logged
import net.boswell.phone.capture.timed
import net.boswell.phone.home.HomeServer
import java.io.File
import java.io.FilterOutputStream
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A backup a day to Boswell Server, which keeps the newest 7 for this phone. API keys
 * are never included.
 *
 * Incremental: the phone lists every file a backup holds with its sha256 (remembered in
 * a [HashCache], so only new and changed files are read), the server says which it
 * doesn't have yet, and only those go up, with the databases and settings, which change
 * every day. The server keeps each file once and hands back a whole backup zip for any
 * of its days, so a restore is the same as from any backup. A server too old for that
 * gets the whole zip, streamed straight from [Backup.export] (no copy on the phone).
 *
 * The computer is off at night, so instead of one nightly try this looks every three
 * hours (on Wi-Fi, battery not low) and backs up once the last one is [EVERY_HOURS] old
 * and the server answers.
 */
object HomeBackup {
    private const val EVERY_HOURS = 20
    private const val WORK = "home-backup"
    private const val WORK_NOW = "home-backup-now"
    internal const val NOW = "now"
    private val json = Json { ignoreUnknownKeys = true }
    private fun p(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)

    /** Progress 0..1 while a backup is going up, otherwise null. */
    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress
    private val busy = AtomicBoolean(false)

    /** On by default once paired. */
    fun on(c: Context) = HomeServer.paired(c) && p(c).getBoolean("home_backup", true)
    fun setOn(c: Context, on: Boolean) { p(c).edit().putBoolean("home_backup", on).apply(); schedule(c) }
    /** When the last good one went up (ms), and how big it was. */
    fun last(c: Context): Long = p(c).getLong("home_backup_last", 0)
    fun lastBytes(c: Context): Long = p(c).getLong("home_backup_bytes", 0)
    /** How big the last backup is on the server, all of it (only what changed was sent). */
    fun lastTotal(c: Context): Long = p(c).getLong("home_backup_total", 0)
    fun lastResult(c: Context): String? = p(c).getString("home_backup_last_result", null)

    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c)
        if (!on(c)) { wm.cancelUniqueWork(WORK); return }
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HomeBackupWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build()).build())
    }

    /** "Back up now": a backup whether or not one is due, on any network. */
    fun now(c: Context) {
        WorkManager.getInstance(c).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<HomeBackupWorker>().setInputData(workDataOf(NOW to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    /** Whether a "Back up now" is waiting (for a network) or going. */
    fun asked(c: Context): kotlinx.coroutines.flow.Flow<Boolean> =
        WorkManager.getInstance(c).getWorkInfosForUniqueWorkFlow(WORK_NOW).map { l -> l.any { !it.state.isFinished } }

    fun due(c: Context) = on(c) && System.currentTimeMillis() - last(c) > EVERY_HOURS * 3_600_000L

    /** Send one backup now. Returns what happened, in words. */
    fun runNow(c: Context, progress: (Float) -> Unit = {}): String {
        if (!busy.compareAndSet(false, true)) return "A backup is already going"
        _progress.value = 0f
        try {
            val result = runCatching {
                val (s, sent) = upload(c) { _progress.value = it; progress(it) }
                p(c).edit().putLong("home_backup_last", System.currentTimeMillis()).putLong("home_backup_bytes", sent)
                    .putLong("home_backup_total", s.bytes).apply()
                "Backed up ${s.recordings} recordings"
            }.getOrElse { "Backup failed: " + HomeServer.explain(it.message) }
            p(c).edit().putString("home_backup_last_result", result).apply()
            CaptureRepository.log("home backup: $result")
            return result
        } finally { _progress.value = null; busy.set(false) }
    }

    private fun authorized(c: Context, path: String, method: String, timeoutMs: Int): HttpURLConnection {
        val base = HomeServer.url(c) ?: throw HomeServer.Unavailable("not paired")
        val key = Secrets.get(c, HomeServer.KEY) ?: throw HomeServer.Unavailable("not paired", notPaired = true)
        return HomeServer.open("$base$path", method, timeoutMs).apply { setRequestProperty("Authorization", "Bearer $key") }
    }

    /** Throw what went wrong unless it answered 200; [missing] is what a 404 means here. */
    private fun check(conn: HttpURLConnection, missing: String = "this server is too old for backups: update Boswell Server") {
        when (val code = conn.responseCode) {
            200 -> Unit
            401 -> throw HomeServer.Unavailable("the server doesn't know this phone any more: pair again", notPaired = true)
            404 -> throw HomeServer.Unavailable(missing)
            else -> throw HomeServer.Unavailable("the server answered $code: ${runCatching { conn.errorStream?.readBytes()?.decodeToString() }.getOrNull()?.take(200)}")
        }
    }

    /** A backup: only what changed if the server can, else all of it. Returns it (bytes: its whole size) and the bytes sent. */
    private fun upload(c: Context, progress: (Float) -> Unit): Pair<Backup.Summary, Long> =
        incremental(c, progress) ?: full(c, progress)

    private const val HASHING = 0.1f            // of the progress bar: making the list
    private const val BATCH_BYTES = 64L shl 20  // files sent per request, at most (so a cut-off loses little)
    private const val BATCH_FILES = 4000

    /**
     * The incremental backup; null if the server doesn't do them (it's older), and nothing was sent.
     * Files sent before a failure stay on the server, so the next try picks up where this stopped.
     */
    private fun incremental(c: Context, progress: (Float) -> Unit): Pair<Backup.Summary, Long>? {
        val tmp = File(c.cacheDir, "home-backup").apply { deleteRecursively(); mkdirs() }
        try {
            val created = System.currentTimeMillis() / 1000
            val files = Backup.listFiles(c.filesDir)
            val manifest = File(tmp, Backup.MANIFEST).apply { writeBytes(Backup.manifest(c, files, created, includeKeys = false)) }
            val settings = File(tmp, "settings.json").apply { writeBytes(Backup.settings(c)) }
            val parts = listOf(Backup.MANIFEST to manifest, "settings.json" to settings) + Backup.copyDatabases(c, tmp)
            val cache = HashCache(File(c.noBackupFilesDir, "home-backup-hashes.tsv"))
            val list = Incremental.list(parts, files, cache) { progress(it * HASHING) }
            cache.save()
            CaptureRepository.log("home backup: ${list.size} files, ${cache.hashed} read to hash")

            val start = authorized(c, "/v1/backup/start", "POST", 5 * 60_000).apply {
                doOutput = true; setChunkedStreamingMode(256 * 1024); setRequestProperty("Content-Type", "application/json")
            }
            start.outputStream.use { Incremental.writeStart(it, list) }
            if (start.responseCode == 404) return null
            check(start)
            val answer = json.parseToJsonElement(start.inputStream.readBytes().decodeToString()).jsonObject
            val session = answer["session"]!!.jsonPrimitive.content
            val missing = answer["missing"]!!.jsonArray.map { it.jsonPrimitive.content }.toHashSet()
            val toSend = answer["missing_bytes"]!!.jsonPrimitive.long.coerceAtLeast(1)

            var sent = 0L
            var data = 0L
            val done = HashSet<String>()
            val changed = mutableListOf<Pair<Incremental.Listed, Incremental.Sent>>()
            val drop = mutableListOf<String>()
            val queue = list.filter { it.sha256 in missing }.iterator()
            var next: Incremental.Listed? = null
            fun take(): Incremental.Listed? {
                while (next == null && queue.hasNext()) next = queue.next().takeIf { it.sha256 !in done }
                return next.also { next = null }
            }
            var l = take()
            while (l != null) {
                val conn = authorized(c, "/v1/backup/blobs", "POST", 10 * 60_000).apply {
                    doOutput = true; setChunkedStreamingMode(256 * 1024); setRequestProperty("Content-Type", "application/octet-stream")
                }
                var batch = 0L
                var count = 0
                val out = object : FilterOutputStream(conn.outputStream) {
                    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); sent += len }
                    override fun write(b: Int) { out.write(b); sent++ }
                }.buffered(256 * 1024)
                out.use {
                    while (l != null && batch < BATCH_BYTES && count < BATCH_FILES) {
                        val f = l!!
                        val r = Incremental.writeBlob(out, f.file) { n ->
                            data += n; progress(HASHING + (1 - HASHING) * (data.toFloat() / toSend).coerceAtMost(1f))
                        }
                        when {
                            r == null -> drop += f.path      // gone since it was listed (a .wav compacted to .ogg)
                            r.sha256 != f.sha256 -> changed += f to r
                            else -> done += f.sha256
                        }
                        batch += r?.size ?: 0; count++
                        l = take()
                    }
                }
                check(conn)
                conn.inputStream.use { it.readBytes() }
            }

            val finish = authorized(c, "/v1/backup/finish", "POST", 10 * 60_000).apply {
                doOutput = true; setRequestProperty("Content-Type", "application/json")
            }
            val body = buildJsonObject {
                put("session", session)
                put("drop", JsonArray(drop.map { JsonPrimitive(it) }))
                put("changed", JsonArray(changed.map { (f, r) ->
                    buildJsonObject { put("path", f.path); put("size", r.size); put("sha256", r.sha256) } }))
            }
            finish.outputStream.use { it.write(body.toString().toByteArray()) }
            check(finish)
            val made = json.parseToJsonElement(finish.inputStream.readBytes().decodeToString()).jsonObject
            progress(1f)
            val recordings = list.count { it.path.startsWith("files/transcripts/") && it.path !in drop }
            return Backup.Summary(recordings, made["bytes"]!!.jsonPrimitive.long, false, created) to sent
        } finally { tmp.deleteRecursively() }
    }

    /** The whole backup, written into the request as it's made (chunked, so nothing is held). Returns it and the bytes sent. */
    private fun full(c: Context, progress: (Float) -> Unit): Pair<Backup.Summary, Long> {
        val conn = authorized(c, "/v1/backup", "POST", 10 * 60_000).apply {
            doOutput = true
            setChunkedStreamingMode(256 * 1024)
            setRequestProperty("Content-Type", "application/zip")
        }
        var sent = 0L
        val counted = object : FilterOutputStream(conn.outputStream) {
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); sent += len }
            override fun write(b: Int) { out.write(b); sent++ }
        }
        val s = Backup.export(c, counted, includeKeys = false, progress)
        check(conn)
        return s.copy(bytes = sent) to sent
    }

    /** One backup on the server: its name, size, and when it was made (epoch seconds). */
    data class Remote(val name: String, val bytes: Long, val created: Double)

    /** This phone's backups on the server, newest first. */
    fun list(c: Context): List<Remote> {
        val conn = authorized(c, "/v1/backups", "GET", 15_000)
        check(conn)
        return json.parseToJsonElement(conn.inputStream.readBytes().decodeToString()).jsonObject["backups"]!!.jsonArray.map {
            val o = it.jsonObject
            Remote(o["name"]!!.jsonPrimitive.content, o["bytes"]!!.jsonPrimitive.long, o["created"]!!.jsonPrimitive.double)
        }
    }

    /** Fetch [name] into the cache, for a restore; the caller deletes it afterward. [progress] gets 0..1. */
    fun download(c: Context, name: String, progress: (Float) -> Unit = {}): File {
        val f = File(c.cacheDir, "home-restore.zip")
        try {
            val conn = authorized(c, "/v1/backups/${java.net.URLEncoder.encode(name, "UTF-8")}", "GET", 120_000)
            check(conn, missing = "the server no longer has that backup")
            val total = conn.contentLengthLong.coerceAtLeast(1)
            var got = 0L
            conn.inputStream.use { i ->
                f.outputStream().use { o ->
                    val b = ByteArray(256 * 1024)
                    while (true) { val n = i.read(b); if (n < 0) break; o.write(b, 0, n); got += n; progress((got.toFloat() / total).coerceAtMost(1f)) }
                }
            }
            return f
        } catch (e: Exception) { f.delete(); throw e }
    }
}

class HomeBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        if (!inputData.getBoolean(HomeBackup.NOW, false)) {
            if (!HomeBackup.due(c)) return@withContext Result.success()
            // The computer is off (at night, say): nothing to say, and another look in three hours.
            if (runCatching { HomeServer.health(c) }.isFailure) return@withContext Result.success()
        }
        // In the foreground, as transcription is: hundreds of MB going up in the background gets frozen.
        runCatching { setForeground(foreground(null)) }.logged("home backup: foreground")
        var shown = 0
        timed("home backup", 10 * 60_000L) {
            HomeBackup.runNow(c) { f ->
                val pct = (f * 100).toInt()
                if (pct >= shown + 5) { shown = pct; runCatching { kotlinx.coroutines.runBlocking { setForeground(foreground(pct)) } }.logged("home backup: foreground") }
            }
        }
        Result.success()
    }

    private fun foreground(pct: Int?): androidx.work.ForegroundInfo {
        net.boswell.phone.process.Notifications.ensureChannels(applicationContext)
        val n = androidx.core.app.NotificationCompat.Builder(applicationContext, net.boswell.phone.process.Notifications.WORK)
            .setSmallIcon(net.boswell.phone.R.drawable.ic_stat_mic).setContentTitle("Boswell")
            .setContentText("Backing up to your computer…").setProgress(100, pct ?: 0, pct == null).setOngoing(true).build()
        return androidx.work.ForegroundInfo(7, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
