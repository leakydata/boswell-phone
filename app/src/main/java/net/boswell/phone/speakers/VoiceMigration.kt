package net.boswell.phone.speakers

import android.content.ContentValues
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
import net.boswell.phone.capture.logged
import net.boswell.phone.capture.timed
import net.boswell.phone.diarize.VoiceModel
import net.boswell.phone.diarize.VoiceModels
import net.boswell.phone.models.ModelStore
import net.boswell.phone.process.ProcessingWorker
import net.boswell.phone.process.Transcript
import net.boswell.phone.process.TranscriptJson
import java.io.File

/**
 * Moving to the speaker-ID model (ReDimNet2): its voiceprints can't be
 * compared with WeSpeaker's, so everything is made again from the audio.
 *
 *  1. Each transcript's per-speaker voiceprints, from that speaker's lines.
 *  2. Each stored voiceprint, from its recording and speaker, as a new row
 *     beside the old one (old rows stay, unused, so switching back is possible
 *     and desktop-format prints aren't lost).
 *  3. The switch, then every past recording checked again with the new model
 *     (existing names are kept; only new matches are added).
 *
 * Runs while charging. Picks up where it stopped: a transcript already holding
 * new-model voiceprints is skipped.
 */
object VoiceMigration {
    private const val WORK = "voice-migration"

    /** The chosen model differs from the one in use, and it can be run. */
    fun needed(c: Context, store: ModelStore = ModelStore(c)): Boolean {
        val want = VoiceModels.wanted(c)
        return want != VoiceModels.active(c) && (want == VoiceModel.WESPEAKER || VoiceModels.speakerIdInstalled(store))
    }

    fun schedule(c: Context) {
        if (!needed(c)) return
        WorkManager.getInstance(c).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<VoiceMigrationWorker>()
                // A big archive is real work for the phone: wait for the charger. A new one has nothing to convert.
                .setConstraints(Constraints.Builder().setRequiresCharging(
                    ProcessingWorker.transcriptsDir(c).list().orEmpty().size > 50).setRequiresBatteryNotLow(true).build()).build())
    }

    /** The speech of one speaker in one clip, from their lines' times. */
    private fun speech(pcm: ShortArray, t: Transcript, label: String): FloatArray {
        // Boswell's lines remember the voice they came from: still that voice's speech.
        val spans = t.segments.filter { it.speaker == label || (it.speaker == net.boswell.phone.process.BoswellLines.LABEL && it.diarized == label) }.map { (it.start * 16_000).toInt().coerceIn(0, pcm.size) to (it.end * 16_000).toInt().coerceIn(0, pcm.size) }
        val out = FloatArray(spans.sumOf { (a, b) -> (b - a).coerceAtLeast(0) })
        var o = 0
        for ((a, b) in spans) for (i in a until b) out[o++] = pcm[i] / 32768f
        return out
    }

    fun run(c: Context, isStopped: () -> Boolean = { false }, progress: (Int, Int) -> Unit = { _, _ -> }): String {
        val models = ModelStore(c)
        if (!needed(c, models)) return "nothing to do"
        val target = VoiceModels.wanted(c)
        val dim = target.dim
        val clips = CaptureService.clipsDir(c)
        var transcripts = 0; var prints = 0; var skipped = 0
        VoiceModels.ort(c, models, forceSpeakerId = target == VoiceModel.SPEAKER_ID).use { ort ->
            check(target == VoiceModel.WESPEAKER || ort.identity == VoiceModel.SPEAKER_ID) { "the speaker-ID model isn't installed" }
            val embed: (FloatArray) -> FloatArray? = if (target == VoiceModel.SPEAKER_ID) ort::identify else ort::voiceprint
            // 1. Transcripts.
            val newEmb = HashMap<Pair<String, String>, FloatArray>()
            val files = ProcessingWorker.transcriptsDir(c).listFiles { x -> x.extension == "json" }.orEmpty().sortedDescending()
            for ((n, f) in files.withIndex()) {
                if (isStopped()) return "paused"
                if (n % 25 == 0) progress(n, files.size)
                val t = runCatching { TranscriptJson.json.decodeFromString(Transcript.serializer(), f.readText()) }.let { if (f.exists()) it.logged("voice update: reading ${f.name}") else it }.getOrNull() ?: continue
                if (t.embeddings.isEmpty()) continue
                if (t.embeddings.values.all { it.size == dim }) { t.embeddings.forEach { (l, v) -> newEmb[t.clip to l] = v.toFloatArray() }; continue }
                if (!net.boswell.phone.audio.ClipAudio.exists(clips, t.clip)) { skipped++; continue }
                val pcm = runCatching { net.boswell.phone.audio.ClipAudio.readPcm(clips, t.clip) }.logged("voice update: reading audio").getOrNull() ?: run { skipped++; continue }
                val emb = LinkedHashMap<String, List<Float>>()
                for (label in t.embeddings.keys) {
                    val v = embed(speech(pcm, t, label))?.takeIf { Matching.usable(it) } ?: continue
                    val u = Matching.unit(v)
                    emb[label] = u.toList(); newEmb[t.clip to label] = u
                }
                net.boswell.phone.audio.writeAtomically(f, TranscriptJson.json.encodeToString(Transcript.serializer(), t.copy(embeddings = emb)).toByteArray())
                transcripts++
            }
            // 2. Stored voiceprints, as new rows beside the old.
            val store = SpeakerStore(c)
            try {
                val db = store.writableDatabase
                val old = db.rawQuery("""SELECT person_id, clip, speaker, seconds, origin, source_cluster, created, impure FROM voiceprints v
                    WHERE dim != $dim AND clip IS NOT NULL AND speaker IS NOT NULL
                      AND NOT EXISTS (SELECT 1 FROM voiceprints w WHERE w.dim = $dim AND w.clip = v.clip AND w.speaker = v.speaker AND w.person_id = v.person_id)""", null).use { cur ->
                    buildList { while (cur.moveToNext()) add(cur.run {
                        listOf(getLong(0), getString(1), getString(2), if (isNull(3)) null else getDouble(3), getString(4),
                            if (isNull(5)) null else getLong(5), if (isNull(6)) null else getDouble(6), getInt(7)) }) }
                }
                db.beginTransaction()
                try {
                    for (r in old) {
                        val v = newEmb[(r[1] as String) to (r[2] as String)] ?: continue
                        db.insert("voiceprints", null, ContentValues().apply {
                            put("person_id", r[0] as Long); put("vec", SpeakerStore.pack(v)); put("dim", v.size)
                            put("seconds", r[3] as Double?); put("clip", r[1] as String); put("speaker", r[2] as String)
                            put("origin", r[4] as String); put("source_cluster", r[5] as Long?); put("created", r[6] as Double?); put("impure", r[7] as Int)
                        })
                        prints++
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            } finally { store.close() }
        }
        // 3. The switch, and every past recording looked at again with the new model.
        VoiceModels.setActive(c, target)
        val recheck = runCatching { timed("voice recheck", 10_000) { VoiceReview(c).recheck() } }.logged("voice recheck").getOrNull()
        val msg = "voices moved to ${if (target == VoiceModel.SPEAKER_ID) "better voice recognition" else "standard voice recognition"}: " +
            "$transcripts recordings and $prints voiceprints made again" +
            (if (skipped > 0) "; $skipped recordings had no sound left" else "") +
            (recheck?.let { "; ${it.matched} more recordings recognized" } ?: "")
        CaptureRepository.log(msg)
        return msg
    }
}

class VoiceMigrationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): androidx.work.ListenableWorker.Result = withContext(Dispatchers.IO) {
        // In the foreground, as transcription is: a background app doing minutes of work gets frozen.
        runCatching { setForeground(foreground("Updating voice recognition…")) }.logged("voice update: foreground")
        val r = VoiceMigration.run(applicationContext, { isStopped }) { done, total ->
            runCatching { kotlinx.coroutines.runBlocking { setForeground(foreground("Updating voice recognition… $done of $total recordings")) } }.logged("voice update: foreground")
        }
        if (r == "paused") return@withContext androidx.work.ListenableWorker.Result.retry()
        // The archive index holds voiceprints too: rebuild it from the rewritten transcripts.
        runCatching {
            val speakers = SpeakerStore(applicationContext)
            val archive = net.boswell.phone.archive.Archive(applicationContext)
            try { archive.sync(speakers, force = true) } finally { archive.close(); speakers.close() }
        }.logged("archive rebuild")
        net.boswell.phone.assistant.AssistantNotify.post(applicationContext, net.boswell.phone.assistant.AssistantNotify.ANSWERS,
            "Voice recognition updated", r.replaceFirstChar { it.uppercase() } + ".")
        androidx.work.ListenableWorker.Result.success()
    }

    private fun foreground(text: String): androidx.work.ForegroundInfo {
        net.boswell.phone.process.Notifications.ensureChannels(applicationContext)
        val n = androidx.core.app.NotificationCompat.Builder(applicationContext, net.boswell.phone.process.Notifications.WORK)
            .setSmallIcon(net.boswell.phone.R.drawable.ic_stat_mic).setContentTitle("Boswell").setContentText(text).setOngoing(true).build()
        return androidx.work.ForegroundInfo(4, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
