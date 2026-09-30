package net.boswell.phone.models

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import net.boswell.phone.R
import net.boswell.phone.process.Notifications

data class ModelProgress(val bytes: Long, val total: Long, val error: String? = null, val running: Boolean = false)

/** Live download progress for the UI, keyed by model id. */
object ModelProgressRepository {
    private val _state = MutableStateFlow<Map<String, ModelProgress>>(emptyMap())
    val state: StateFlow<Map<String, ModelProgress>> = _state.asStateFlow()
    fun set(id: String, p: ModelProgress) = _state.update { it + (id to p) }
}

/**
 * Downloads one model's files. WorkManager keeps it going when the app is in
 * the background and retries it later if the network drops; the downloader
 * resumes from what already arrived.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_ID) ?: return@withContext Result.failure()
        val store = ModelStore(applicationContext)
        val spec = store.catalog.byId(id)
        runCatching { setForeground(foreground(spec.name, 0)) }
        var done = 0L
        try {
            for (f in spec.files) {
                ModelDownloader.download(store.catalog.baseUrl, f, store.dir,
                    onBytes = { n ->
                        val now = done + n
                        ModelProgressRepository.set(id, ModelProgress(now, spec.totalBytes, running = true))
                    },
                    isCancelled = { isStopped })
                done += f.size
                runCatching { setForeground(foreground(spec.name, (done * 100 / spec.totalBytes).toInt())) }
            }
            ModelProgressRepository.set(id, ModelProgress(spec.totalBytes, spec.totalBytes))
            Result.success()
        } catch (e: Exception) {
            ModelProgressRepository.set(id, ModelProgress(store.installedBytes(id), spec.totalBytes, e.message))
            if (isStopped) Result.failure() else Result.retry()
        }
    }

    private fun foreground(name: String, percent: Int): ForegroundInfo {
        Notifications.ensureChannels(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, Notifications.WORK)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Downloading $name")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val KEY_ID = "id"
        private const val NOTIFICATION_ID = 2

        fun workName(id: String) = "model-$id"

        fun enqueue(context: Context, id: String, wifiOnly: Boolean) {
            val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(KEY_ID to id))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, id: String) =
            WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }
}
