package net.boswell.phone.process

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.boswell.phone.archive.Archive
import net.boswell.phone.speakers.SpeakerStore
import java.util.concurrent.TimeUnit

/**
 * Optional, off by default: once a day, delete the audio of clips that held
 * no speech and in which the sound tagger heard only background, once they
 * are a week old. Their timeline entry, tags and sidecar stay.
 *
 * Off by default because deleting is permanent and the tagger on the phone is
 * less sensitive than the desktop's: on 150 archive clips it called 12 clips
 * empty that the desktop's AST model would have kept (a microwave, a car).
 */
class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val archive = Archive(applicationContext)
        val speakers = SpeakerStore(applicationContext)
        try {
            archive.sync(speakers)
            archive.deleteAudio(archive.quietCandidates(DAYS).map { it.name })
            archive.sync(speakers)
        } finally {
            speakers.close(); archive.close()
        }
        return Result.success()
    }

    companion object {
        const val DAYS = 7
        private const val WORK = "cleanup-quiet-audio"

        fun schedule(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!on) { wm.cancelUniqueWork(WORK); return }
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS).build())
        }
    }
}
