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
 * Deletes the audio of clips that held no speech and in which the sound
 * tagger heard only background, once they are [days] old (0: as soon as
 * they're transcribed, done by the processing worker; this daily pass
 * catches anything older). Their timeline entry, tags and sidecar stay.
 *
 * The phone's tagger is less sensitive than the desktop's (on 150 archive
 * clips it called 12 empty that the desktop's model would have kept: a
 * microwave, a car), which is why this is a choice and "never" is one.
 */
class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val archive = Archive(applicationContext)
        val speakers = SpeakerStore(applicationContext)
        try {
            archive.sync(speakers)
            val d = days(applicationContext)
            if (d >= 0) archive.deleteAudio(archive.quietCandidates(d).map { it.name })
            compactAll()
            archive.sync(speakers)
        } finally {
            speakers.close(); archive.close()
        }
        return Result.success()
    }

    /**
     * Transcribed clips still kept as WAV (made before compact copies, or whose
     * copy couldn't be made at the time) become their compact copy.
     */
    private fun compactAll() {
        val clips = net.boswell.phone.capture.CaptureService.clipsDir(applicationContext)
        val tdir = ProcessingWorker.transcriptsDir(applicationContext)
        var saved = 0L; var n = 0
        for (w in clips.listFiles { f -> f.extension == "wav" }.orEmpty().sortedBy { it.name }) {
            if (isStopped) break
            if (!java.io.File(tdir, w.nameWithoutExtension + ".json").exists()) continue      // not transcribed yet
            val s = runCatching { net.boswell.phone.audio.ClipAudio.compact(clips, w.name) }.getOrDefault(0L)
            if (s > 0) { saved += s; n++ }
        }
        if (n > 0) net.boswell.phone.capture.CaptureRepository.log("compacted $n clips, freed ${saved / 1_000_000} MB")
    }

    companion object {
        const val DAYS = 7

        /** One pass now (compacting older clips, clearing quiet ones), on top of the daily one. */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("cleanup-now", androidx.work.ExistingWorkPolicy.KEEP,
                androidx.work.OneTimeWorkRequestBuilder<CleanupWorker>()
                    .setConstraints(androidx.work.Constraints.Builder().setRequiresBatteryNotLow(true).build()).build())
        }
        private const val WORK = "cleanup-quiet-audio"

        private fun prefs(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)

        /** After how many days quiet clips lose their audio: 0 right away, -1 never. Default right away. */
        fun days(c: Context): Int = prefs(c).let { p ->
            if (p.contains("quiet_audio_days")) p.getInt("quiet_audio_days", 0)
            else if (p.contains("auto_clean") && !p.getBoolean("auto_clean", false)) -1 else 0
        }

        fun setDays(c: Context, d: Int) {
            prefs(c).edit().putInt("quiet_audio_days", d).apply()
            schedule(c, d >= 0)
        }

        fun schedule(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!on) { wm.cancelUniqueWork(WORK); return }
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS).build())
        }
    }
}
