package net.boswell.phone.process

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.boswell.phone.R
import net.boswell.phone.asr.LocalAsr
import net.boswell.phone.audio.Wav
import net.boswell.phone.audio.writeAtomically
import net.boswell.phone.capture.CaptureService
import net.boswell.phone.diarize.OrtModels
import net.boswell.phone.models.ModelCatalog
import net.boswell.phone.models.ModelStore
import net.boswell.phone.speakers.Matching
import net.boswell.phone.speakers.SpeakerStore
import java.io.File

data class ProcessingState(val running: Boolean = false, val pending: Int = 0, val done: Int = 0, val current: String? = null, val lastError: String? = null)

object ProcessingRepository {
    val state = MutableStateFlow(ProcessingState())
}

/**
 * Transcribes, diarizes and identifies every clip that has no transcript yet,
 * oldest first, entirely on the phone. Runs as background work so it
 * survives the app being closed; a clip interrupted half way is simply
 * redone, since nothing is written until its transcript is complete.
 */
class ProcessingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val models = ModelStore(applicationContext)
        val needed = listOf(ModelCatalog.ASR, ModelCatalog.SEGMENTATION, ModelCatalog.VOICEPRINT)
        if (!needed.all(models::isInstalled)) {
            ProcessingRepository.state.value = ProcessingState(lastError = "models not installed")
            return@withContext Result.success()
        }
        runCatching { setForeground(foreground("Transcribing…")) }
        val clips = CaptureService.clipsDir(applicationContext)
        val out = transcriptsDir(applicationContext)
        val store = SpeakerStore(applicationContext)
        var done = 0
        val tagger = if (models.isInstalled(net.boswell.phone.sound.SoundTagger.ID)) net.boswell.phone.sound.SoundTagger(models) else null
        // Clips transcribed before tagging was installed get tags without being
        // transcribed again.
        if (tagger != null) backfillSounds(out, clips, tagger)
        LocalAsr(models).use { asr ->
            OrtModels(models.path(ModelCatalog.SEGMENTATION, ".onnx"), models.path(ModelCatalog.VOICEPRINT, "voiceprint.onnx")).use { ort ->
                val diarizer = ort.diarizer()
                while (!isStopped) {
                    val todo = pending(clips, out)
                    ProcessingRepository.state.value = ProcessingState(true, todo.size, done)
                    val wav = todo.firstOrNull() ?: break
                    ProcessingRepository.state.value = ProcessingState(true, todo.size, done, wav.name)
                    try {
                        process(wav, asr, diarizer, store, out, tagger)
                        // Deleted while it was being worked on: drop the result, leave no trace.
                        if (!wav.exists()) File(out, wav.nameWithoutExtension + ".json").delete()
                    } catch (e: Exception) {
                        if (!wav.exists()) { done++; continue }
                        // A clip that cannot be read or decoded is recorded as such
                        // rather than retried forever.
                        writeAtomically(File(out, wav.nameWithoutExtension + ".json"),
                            """{"clip":"${wav.name}","error":${org.json.JSONObject.quote(e.toString())}}""".toByteArray())
                        ProcessingRepository.state.value = ProcessingRepository.state.value.copy(lastError = "${wav.name}: ${e.message}")
                    }
                    done++
                    runCatching { setForeground(foreground("Transcribing… ${todo.size - 1} left")) }
                }
            }
        }
        store.close()
        tagger?.close()
        ProcessingRepository.state.value = ProcessingState(false, pending(clips, out).size, done)
        Result.success()
    }

    private fun backfillSounds(out: File, clips: File, tagger: net.boswell.phone.sound.SoundTagger) {
        val todo = out.listFiles { f -> f.extension == "json" }.orEmpty()
            .filter { !it.readText().contains("\"sounds\":[") }
        for (f in todo) {
            if (isStopped) return
            val t = runCatching { TranscriptJson.json.decodeFromString(Transcript.serializer(), f.readText()) }.getOrNull() ?: continue
            val wav = File(clips, t.clip)
            if (!wav.exists()) continue
            val (pcm, _) = Wav.readPcm(wav)
            val tags = tagger.tag(FloatArray(pcm.size) { pcm[it] / 32768f })
            val updated = t.copy(sounds = tags, verdict = verdictFor(t.segments.isNotEmpty(), tags))
            writeAtomically(f, TranscriptJson.json.encodeToString(Transcript.serializer(), updated).toByteArray())
        }
    }

    private fun verdictFor(hasSpeech: Boolean, tags: List<net.boswell.phone.sound.SoundTag>): String =
        if (hasSpeech || net.boswell.phone.sound.Sounds.verdict(tags) == net.boswell.phone.sound.Sounds.Verdict.KEEP) "keep" else "empty"

    private fun process(wav: File, asr: LocalAsr, diarizer: net.boswell.phone.diarize.Diarizer, store: SpeakerStore, out: File,
                        tagger: net.boswell.phone.sound.SoundTagger?) {
        val t0 = System.currentTimeMillis()
        val (pcm, _) = Wav.readPcm(wav)
        val audio = FloatArray(pcm.size) { pcm[it] / 32768f }
        val words = asr.transcribe(audio)
        val d = diarizer.run(audio)
        val segments = Lines.build(words, d.turns)
        val tags = tagger?.tag(audio)

        val speakers = LinkedHashMap<String, SpeakerId>()
        val embeddings = LinkedHashMap<String, List<Float>>()
        for (s in d.speakers) {
            val label = Lines.label(s.index)
            val vp = s.voiceprint
            if (vp == null || !Matching.usable(vp)) {
                speakers[label] = SpeakerId(null, 0.0, "none", null, emptyList(), null, s.seconds)
                continue
            }
            embeddings[label] = Matching.unit(vp).toList()
            val r = store.match(vp)
            store.logMatch(wav.name, label, r)
            // A voice nobody can name joins (or starts) an unnamed cluster, so a
            // recurring stranger becomes one entry to name, not a hundred.
            val personId = when {
                r.decision == Matching.Decision.MATCHED -> r.personId
                s.seconds >= Matching.MIN_CLUSTER_SECONDS -> store.ingestUnknown(vp, wav.name, label, s.seconds)
                else -> null
            }
            speakers[label] = SpeakerId(
                name = if (r.decision == Matching.Decision.MATCHED) r.personId?.let(store::nameOf) else null,
                score = r.score, decision = r.decision.name.lowercase(), margin = r.margin,
                candidates = r.candidates.map { Candidate(it.personId, store.nameOf(it.personId), it.score, it.voiceprintId) },
                personId = personId, seconds = s.seconds,
            )
        }
        val t = Transcript(wav.name, System.currentTimeMillis() / 1000.0, segments, speakers, embeddings,
            engine = "nemotron-3.5-asr-1120ms-int8 + pyannote-seg-3.0 + wespeaker-r34" + (if (tags != null) " + ced-mini" else ""),
            processMs = System.currentTimeMillis() - t0,
            sounds = tags, verdict = tags?.let { verdictFor(segments.isNotEmpty(), it) })
        writeAtomically(File(out, wav.nameWithoutExtension + ".json"), TranscriptJson.json.encodeToString(Transcript.serializer(), t).toByteArray())
        // Voice triggers act on new transcripts only; the line's own time goes with it.
        val started = runCatching {
            net.boswell.phone.capture.Clipper.json.decodeFromString(net.boswell.phone.capture.ClipTimes.serializer(),
                File(wav.parentFile, wav.nameWithoutExtension + ".json").readText()).started
        }.getOrNull()
        if (started != null) runCatching { net.boswell.phone.assistant.TriggerEngine(applicationContext).run(t, started) }
    }

    private fun foreground(text: String): ForegroundInfo {
        Notifications.ensureChannels(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, Notifications.WORK)
            .setSmallIcon(R.drawable.ic_stat_mic).setContentTitle("Boswell").setContentText(text).setOngoing(true).build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val NOTIFICATION_ID = 3
        private const val WORK = "process-clips"

        fun transcriptsDir(context: Context) = File(context.filesDir, "transcripts").apply { mkdirs() }

        fun pending(clips: File, out: File): List<File> =
            clips.listFiles { f -> f.extension == "wav" }.orEmpty()
                .filter { !File(out, it.nameWithoutExtension + ".json").exists() }
                .sortedBy { it.name }

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<ProcessingWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            // Appended, so a clip that lands while a run is finishing still gets a run of its own.
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
