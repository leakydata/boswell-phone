package net.boswell.phone.speakers

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.boswell.phone.capture.CaptureRepository
import net.boswell.phone.capture.CaptureService
import net.boswell.phone.diarize.Turn
import net.boswell.phone.process.BoswellLines
import net.boswell.phone.process.ProcessingWorker
import net.boswell.phone.process.Transcript
import net.boswell.phone.process.TranscriptJson

/**
 * Transcripts made before the SNR was kept have none, so the owner rule
 * (Matching) and pooling (Pooling) can't use it on them. Once, it is worked
 * out from the audio for every voice, then every past recording is checked
 * again: in a replay, 120 of 136 owner voices were recognized with it and
 * 108 without.
 *
 * Runs while charging, after VoiceMigration. Picks up where it stopped: a
 * transcript whose every voice has its SNR is skipped. Only a missing SNR is
 * filled; nothing else in a transcript changes.
 */
object SnrBackfill {
    private const val WORK = "snr-backfill"
    private const val DONE = "snr_backfill_done"

    private fun prefs(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)

    fun schedule(c: Context) {
        if (prefs(c).getBoolean(DONE, false)) return
        // Its recheck must use the voiceprints the migration makes: wait for it.
        if (VoiceMigration.needed(c)) return
        WorkManager.getInstance(c).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SnrBackfillWorker>()
                .setConstraints(Constraints.Builder().setRequiresCharging(
                    ProcessingWorker.transcriptsDir(c).list().orEmpty().size > 50).setRequiresBatteryNotLow(true).build()).build())
    }

    /** One voice's lines as turns: theirs, and Boswell's lines heard in their voice (VoiceMigration's spans). */
    internal fun turns(t: Transcript, label: String): List<Turn> =
        t.segments.filter { it.speaker == label || (it.speaker == BoswellLines.LABEL && it.diarized == label) }.map { Turn(0, it.start, it.end) }

    /** A voice with lines but no SNR. */
    internal fun pending(t: Transcript): Boolean = t.speakers.any { (label, sp) -> sp.snrDb == null && turns(t, label).isNotEmpty() }

    /** [t] with each missing SNR worked out from [pcm] (16 kHz), or null if there was none to fill. */
    internal fun fill(t: Transcript, pcm: ShortArray): Transcript? {
        if (!pending(t)) return null
        val audio = FloatArray(pcm.size) { pcm[it] / 32768f }
        var changed = false
        val speakers = t.speakers.mapValues { (label, sp) ->
            if (sp.snrDb != null) return@mapValues sp
            val db = Snr.db(audio, turns(t, label).takeIf { it.isNotEmpty() } ?: return@mapValues sp)?.takeIf { it.isFinite() } ?: return@mapValues sp
            changed = true
            sp.copy(snrDb = db)
        }
        return if (changed) t.copy(speakers = speakers) else null
    }

    private fun read(f: java.io.File): Transcript? =
        runCatching { TranscriptJson.json.decodeFromString(Transcript.serializer(), f.readText()) }.getOrNull()

    /** What [run] did; [matched] is how many more recordings the recheck recognized. */
    data class Outcome(val message: String, val matched: Int = 0)

    fun run(c: Context, isStopped: () -> Boolean = { false }, progress: (Int, Int) -> Unit = { _, _ -> }): Outcome {
        if (prefs(c).getBoolean(DONE, false)) return Outcome("nothing to do")
        if (VoiceMigration.needed(c)) return Outcome("waiting")
        val clips = CaptureService.clipsDir(c)
        var filled = 0; var skipped = 0
        val files = ProcessingWorker.transcriptsDir(c).listFiles { x -> x.extension == "json" }.orEmpty().sortedDescending()
        for ((n, f) in files.withIndex()) {
            if (isStopped()) return Outcome("paused")
            if (n % 25 == 0) progress(n, files.size)
            val t = read(f) ?: continue
            if (!pending(t)) continue
            if (!net.boswell.phone.audio.ClipAudio.exists(clips, t.clip)) { skipped++; continue }
            val pcm = runCatching { net.boswell.phone.audio.ClipAudio.readPcm(clips, t.clip) }.getOrNull() ?: run { skipped++; continue }
            // Processing may have written it again meanwhile: fill what's missing now, not in the copy read before the audio.
            val u = fill(read(f) ?: continue, pcm) ?: continue
            net.boswell.phone.audio.writeAtomically(f, TranscriptJson.json.encodeToString(Transcript.serializer(), u).toByteArray())
            filled++
        }
        val recheck = runCatching { VoiceReview(c).recheck() }.getOrNull()
        // A recheck that failed is tried again at the next start (every SNR is in by then, so that's all it does).
        if (recheck != null) prefs(c).edit().putBoolean(DONE, true).apply()
        val msg = "voice levels worked out for $filled recordings" +
            (if (skipped > 0) "; $skipped recordings had no sound left" else "") +
            (recheck?.let { "; ${it.matched} more recordings recognized" } ?: "")
        CaptureRepository.log(msg)
        return Outcome(msg, recheck?.matched ?: 0)
    }
}

class SnrBackfillWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): androidx.work.ListenableWorker.Result = withContext(Dispatchers.IO) {
        // In the foreground, as transcription is: a background app doing minutes of work gets frozen.
        runCatching { setForeground(foreground("Measuring voice levels…")) }
        val r = SnrBackfill.run(applicationContext, { isStopped }) { done, total ->
            runCatching { kotlinx.coroutines.runBlocking { setForeground(foreground("Measuring voice levels… $done of $total recordings")) } }
        }
        if (r.message == "paused") return@withContext androidx.work.ListenableWorker.Result.retry()
        if (r.message == "waiting" || r.message == "nothing to do") return@withContext androidx.work.ListenableWorker.Result.success()
        // The archive index holds the voices' names: rebuild it from the rewritten transcripts.
        runCatching {
            val speakers = SpeakerStore(applicationContext)
            val archive = net.boswell.phone.archive.Archive(applicationContext)
            try { archive.sync(speakers, force = true) } finally { archive.close(); speakers.close() }
        }
        // Quiet unless it found someone.
        val n = r.matched
        if (n > 0) net.boswell.phone.assistant.AssistantNotify.post(applicationContext, net.boswell.phone.assistant.AssistantNotify.ANSWERS,
            "Voice recognition updated", "$n more recording${if (n == 1) "" else "s"} recognized.")
        androidx.work.ListenableWorker.Result.success()
    }

    private fun foreground(text: String): androidx.work.ForegroundInfo {
        net.boswell.phone.process.Notifications.ensureChannels(applicationContext)
        val n = androidx.core.app.NotificationCompat.Builder(applicationContext, net.boswell.phone.process.Notifications.WORK)
            .setSmallIcon(net.boswell.phone.R.drawable.ic_stat_mic).setContentTitle("Boswell").setContentText(text).setOngoing(true).setSilent(true).build()
        return androidx.work.ForegroundInfo(5, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
