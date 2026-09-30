package net.boswell.phone.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.boswell.phone.capture.CaptureRepository
import net.boswell.phone.capture.CaptureService
import java.util.concurrent.TimeUnit

/**
 * The clock that drives sync visits. It only asks the capture service to
 * visit; the service holds the radio. Starting a foreground service from the
 * background is allowed because the app is paired as a companion of the Omi,
 * or exempt from battery optimization -- without either, Android refuses, and
 * that is reported rather than swallowed.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (Modes.mode(ctx) != Mode.SYNC) return Result.success()
        val address = Modes.address(ctx) ?: return Result.success()
        runCatching { CaptureService.sync(ctx, address) }.onFailure {
            CaptureRepository.log("background sync not allowed: ${it.javaClass.simpleName}")
            Modes.recordSync(ctx, "blocked by Android: allow unrestricted battery or pair the Omi")
        }
        return Result.success()
    }

    companion object {
        private const val WORK = "omi-sync"

        fun schedule(context: Context) {
            val minutes = Modes.syncMinutes(context).toLong().coerceAtLeast(15)
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES).build(),
            )
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
}
