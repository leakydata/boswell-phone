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

data class ProcessingState(val running: Boolean = false, val pending: Int = 0, val done: Int = 0, val current: String? = null, val lastError: String? = null,
                           /** Downloaded clips held back until the phone is charging. */
                           val waitingForCharger: Int = 0)

object ProcessingRepository {
    val state = MutableStateFlow(ProcessingState())
}

/**
 * Transcribes, diarizes and identifies every clip that has no transcript yet,
 * entirely on the phone: live clips first and newest first, so the day stays
 * current while a download fills in behind it; a big download waits for the
 * phone's charger (see [backlogWaits]); a clip nobody speaks in is recorded as
 * such after a quick speech check, without running the recognizer. Runs as background work so it
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
                var deferred = 0
                while (!isStopped) {
                    // A question asked on the Omi goes first: the backlog waits
                    // between clips so the question's own transcription isn't
                    // competing with it for the CPU.
                    while (!isStopped && net.boswell.phone.capture.CaptureRepository.state.value.asking != null) kotlinx.coroutines.delay(250)
                    val all = pending(clips, out)
                    val waitCharger = backlogWaits(applicationContext, all)
                    val todo = if (waitCharger) all.filter { !isDownload(it) } else all
                    deferred = all.size - todo.size
                    ProcessingRepository.state.value = ProcessingState(true, todo.size, done, waitingForCharger = deferred)
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
        val left = pending(clips, out)
        val waiting = if (backlogWaits(applicationContext, left)) left.count { isDownload(it) } else 0
        ProcessingRepository.state.value = ProcessingState(false, left.size - waiting, done, waitingForCharger = waiting)
        if (waiting > 0) ChargerKick.schedule(applicationContext)
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

    /** Words Boswell should know, read once per run (names change rarely). */
    private val vocabulary by lazy { runCatching { net.boswell.phone.asr.Vocabulary.all(applicationContext) }.getOrDefault(emptyList()) }

    private fun process(wav: File, asr: LocalAsr, diarizer: net.boswell.phone.diarize.Diarizer, store: SpeakerStore, out: File,
                        tagger: net.boswell.phone.sound.SoundTagger?) {
        val t0 = System.currentTimeMillis()
        val (pcm, _) = Wav.readPcm(wav)
        val audio = FloatArray(pcm.size) { pcm[it] / 32768f }
        if (diarizer.speechSeconds(audio) <= net.boswell.phone.diarize.Diarizer.SPEECH_MIN_S) {
            // Nobody speaking: record that without running the recognizer.
            val tags = tagger?.tag(audio)
            val t = Transcript(wav.name, System.currentTimeMillis() / 1000.0, emptyList(), emptyMap(), emptyMap(),
                engine = "pyannote-seg-3.0 speech check" + (if (tags != null) " + ced-mini" else ""),
                processMs = System.currentTimeMillis() - t0, sounds = tags, verdict = tags?.let { verdictFor(false, it) })
            writeAtomically(File(out, wav.nameWithoutExtension + ".json"), TranscriptJson.json.encodeToString(Transcript.serializer(), t).toByteArray())
            return
        }
        val cloud = cloudWords(wav)
        val heard = cloud ?: asr.transcribe(audio)
        val words = net.boswell.phone.asr.Vocabulary.apply(heard, vocabulary)
        val d = diarizer.run(audio)
        // A line the vocabulary changed keeps what was heard, as a hand edit does (and can be restored the same way).
        val segments = Lines.build(words, d.turns).map { seg ->
            if (words === heard) seg
            else heard.filter { (it.start + it.end) / 2 in seg.start..seg.end }.joinToString(" ") { it.text }
                .let { h -> if (h.isNotBlank() && h != seg.text) seg.copy(original = h) else seg }
        }
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
            engine = (if (cloud != null) "${net.boswell.phone.asr.Transcription.ENGINE.id} (cloud)" else "nemotron-3.5-asr-1120ms-int8") +
                " + pyannote-seg-3.0 + wespeaker-r34" + (if (tags != null) " + ced-mini" else ""),
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

    /**
     * The words from the cloud, when this clip should go there and can: null
     * means transcribe on the phone (not wanted, no key, over the day's cap,
     * or the call failed -- nothing waits on the network).
     */
    private fun cloudWords(wav: File): List<net.boswell.phone.asr.Word>? {
        val ctx = applicationContext
        val t = net.boswell.phone.asr.Transcription
        if (!t.wantsCloud(ctx, wav.name)) return null
        t.handled(ctx, wav.name)
        val key = net.boswell.phone.assistant.Secrets.get(ctx, net.boswell.phone.assistant.Secrets.OPENROUTER) ?: return null
        val store = net.boswell.phone.assistant.AssistantStore(ctx)
        try {
            if (store.spentToday(t.PURPOSE) >= t.dailyCap(ctx)) {
                ProcessingRepository.state.value = ProcessingRepository.state.value.copy(lastError = "cloud transcription paused: today's limit reached")
                return null
            }
            val empty = kotlinx.serialization.json.JsonObject(emptyMap())
            return runCatching { net.boswell.phone.asr.CloudAsr.transcribeWords(key, t.ENGINE, wav) }.fold(
                { (words, cost) ->
                    store.logCall(t.PURPOSE, t.ENGINE.id, net.boswell.phone.assistant.LlmReply(null, emptyList(), empty, cost, 0, 0))
                    words
                },
                { e ->
                    store.logCall(t.PURPOSE, t.ENGINE.id, null, e.message ?: e.toString())
                    null
                })
        } finally { store.close() }
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
                .map { it to isDownload(it) }
                .sortedWith(compareBy<Pair<File, Boolean>> { it.second }.thenByDescending { it.first.name })
                .map { it.first }

        /** Audio the Omi stored and the phone downloaded later, as opposed to heard live. */
        fun isDownload(wav: File): Boolean =
            runCatching { File(wav.parentFile, wav.nameWithoutExtension + ".json").readText().contains("\"omi-card\"") }.getOrDefault(false)

        /** More downloaded clips than this (about half an hour of audio) is a big download. */
        const val BIG_DOWNLOAD = 60

        /**
         * A big download waits for the phone's charger: transcribing hours of
         * audio keeps several cores busy for a long time. Clips heard live never wait.
         */
        fun backlogWaits(context: Context, pending: List<File>): Boolean {
            if (!net.boswell.phone.sync.Modes.backlogOnCharger(context)) return false
            val bm = context.getSystemService(android.os.BatteryManager::class.java)
            if (bm?.isCharging == true) return false
            return pending.count { isDownload(it) } > BIG_DOWNLOAD
        }

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<ProcessingWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            // Appended, so a clip that lands while a run is finishing still gets a run of its own.
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}

/**
 * Wakes the processing worker when the phone goes on charge, for a download
 * that was waiting. Its own work, so the charging constraint never holds up
 * the clips heard live, which queue behind nothing.
 */
class ChargerKick(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ProcessingWorker.enqueue(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val req = OneTimeWorkRequestBuilder<ChargerKick>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("process-on-charger", ExistingWorkPolicy.KEEP, req)
        }
    }
}
