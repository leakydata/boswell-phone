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
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import net.boswell.phone.assistant.Secrets
import net.boswell.phone.capture.CaptureRepository
import net.boswell.phone.home.HomeServer
import java.io.File
import java.io.FilterOutputStream
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A backup a day to Boswell Server, which keeps the newest 7 for this phone. Streamed
 * straight from [Backup.export] to the server, so there's no copy of it on the phone.
 * API keys are never included.
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
                p(c).edit().putLong("home_backup_last", System.currentTimeMillis()).putLong("home_backup_bytes", sent).apply()
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

    /** The backup, written into the request as it's made (chunked, so nothing is held). Returns it and the bytes sent. */
    private fun upload(c: Context, progress: (Float) -> Unit): Pair<Backup.Summary, Long> {
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
        return s to sent
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
        runCatching { setForeground(foreground(null)) }
        var shown = 0
        HomeBackup.runNow(c) { f ->
            val pct = (f * 100).toInt()
            if (pct >= shown + 5) { shown = pct; runCatching { kotlinx.coroutines.runBlocking { setForeground(foreground(pct)) } } }
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
