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
                           val waitingForCharger: Int = 0,
                           /** Clips kept for the home server, which couldn't be reached (HomeServer.Fallback.WAIT). */
                           val waitingForHome: Int = 0)

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
        val localReady = needed.all(models::isInstalled)
        // With the home server the phone's own models are only a fallback: not needed, and not loaded unless used.
        val home = net.boswell.phone.home.HomeServer.enabled(applicationContext)
        if (!home && !localReady) {
            ProcessingRepository.state.value = ProcessingState(lastError = "models not installed")
            return@withContext Result.success()
        }
        runCatching { setForeground(foreground("Transcribing…")) }
        val clips = CaptureService.clipsDir(applicationContext)
        val out = transcriptsDir(applicationContext)
        // A person named for Boswell's voice becomes Boswell first, so no new clip is filed under them.
        runCatching { net.boswell.phone.speakers.BoswellPerson.convert(applicationContext) }
        val store = SpeakerStore(applicationContext)
        var done = 0
        val tagger = if (!home && models.isInstalled(net.boswell.phone.sound.SoundTagger.ID)) net.boswell.phone.sound.SoundTagger(models) else null
        // Clips transcribed before tagging was installed get tags without being
        // transcribed again.
        if (tagger != null) backfillSounds(out, clips, tagger)
        var local: Local? = null
        fun local(): Local = local ?: Local(models, applicationContext).also { local = it }
        val forHome = mutableSetOf<String>()
        var homeTrouble: String? = null
        try {
                var deferred = 0
                while (!isStopped) {
                    // A question asked on the Omi goes first: the backlog waits
                    // between clips so the question's own transcription isn't
                    // competing with it for the CPU.
                    while (!isStopped && net.boswell.phone.capture.CaptureRepository.state.value.asking != null) kotlinx.coroutines.delay(250)
                    val all = pending(clips, out)
                    val waitCharger = backlogWaits(applicationContext, all)
                    val todo = (if (waitCharger) all.filter { !isDownload(it) } else all).filter { it.name !in forHome }
                    deferred = all.size - todo.size
                    ProcessingRepository.state.value = ProcessingState(true, todo.size, done, waitingForCharger = deferred)
                    val wav = todo.firstOrNull() ?: break
                    ProcessingRepository.state.value = ProcessingState(true, todo.size, done, wav.name)
                    try {
                        val t = net.boswell.phone.asr.Transcription
                        if (home && !t.requested(applicationContext, wav.name)) {
                            try {
                                processHome(wav, store, out)
                            } catch (e: net.boswell.phone.home.HomeServer.Unavailable) {
                                homeTrouble = e.message
                                if (net.boswell.phone.home.HomeServer.fallback(applicationContext) == net.boswell.phone.home.HomeServer.Fallback.PHONE && localReady)
                                    processLocal(wav, local(), store, out, tagger ?: localTagger(models))
                                else { forHome += wav.name; continue }
                            }
                        } else process(wav, local(), store, out, tagger)
                        // Deleted while it was being worked on: drop the result, leave no trace.
                        if (!net.boswell.phone.audio.ClipAudio.exists(clips, wav.name)) File(out, wav.nameWithoutExtension + ".json").delete()
                        else afterTranscript(clips, wav.name, File(out, wav.nameWithoutExtension + ".json"))
                    } catch (e: Exception) {
                        if (!net.boswell.phone.audio.ClipAudio.exists(clips, wav.name)) { done++; continue }
                        // A clip that cannot be read or decoded is recorded as such
                        // rather than retried forever.
                        writeAtomically(File(out, wav.nameWithoutExtension + ".json"),
                            """{"clip":"${wav.name}","error":${org.json.JSONObject.quote(e.toString())}}""".toByteArray())
                        ProcessingRepository.state.value = ProcessingRepository.state.value.copy(lastError = "${wav.name}: ${e.message}")
                    }
                    done++
                    runCatching { setForeground(foreground("Transcribing… ${todo.size - 1} left")) }
                }
        } finally {
            local?.close()
            homeTagger?.close()
        }
        store.close()
        tagger?.close()
        // Kept for home: try again once there's a network, a little later.
        if (forHome.isNotEmpty()) {
            enqueueLater(applicationContext)
            net.boswell.phone.capture.CaptureRepository.log("home server unavailable (${homeTrouble}); ${forHome.size} recordings wait for it")
        }
        val left = pending(clips, out)
        val waiting = if (backlogWaits(applicationContext, left)) left.count { isDownload(it) } else 0
        ProcessingRepository.state.value = ProcessingState(false, left.size - waiting - forHome.size, done, waitingForCharger = waiting,
            waitingForHome = forHome.size, lastError = homeTrouble?.let { "home server: $it" })
        if (waiting > 0) ChargerKick.schedule(applicationContext)
        if (redoCloud + redoPhone > 0) {
            val n = redoCloud + redoPhone
            net.boswell.phone.assistant.AssistantNotify.post(applicationContext, net.boswell.phone.assistant.AssistantNotify.ANSWERS,
                "Redo finished",
                if (redoPhone == 0) "$n recording${if (n == 1) "" else "s"} transcribed again in the cloud."
                else "$redoCloud in the cloud; $redoPhone on the phone instead, because $redoWhy.")
        }
        Result.success()
    }

    private fun backfillSounds(out: File, clips: File, tagger: net.boswell.phone.sound.SoundTagger) {
        val todo = out.listFiles { f -> f.extension == "json" }.orEmpty()
            .filter { !it.readText().contains("\"sounds\":[") }
        for (f in todo) {
            if (isStopped) return
            val t = runCatching { TranscriptJson.json.decodeFromString(Transcript.serializer(), f.readText()) }.getOrNull() ?: continue
            if (!net.boswell.phone.audio.ClipAudio.exists(clips, t.clip)) continue
            val pcm = net.boswell.phone.audio.ClipAudio.readPcm(clips, t.clip)
            val tags = tagger.tag(FloatArray(pcm.size) { pcm[it] / 32768f })
            val updated = t.copy(sounds = tags, verdict = verdictFor(t.segments.isNotEmpty(), tags))
            writeAtomically(f, TranscriptJson.json.encodeToString(Transcript.serializer(), updated).toByteArray())
        }
    }

    private fun verdictFor(hasSpeech: Boolean, tags: List<net.boswell.phone.sound.SoundTag>): String =
        if (hasSpeech || net.boswell.phone.sound.Sounds.verdict(tags) == net.boswell.phone.sound.Sounds.Verdict.KEEP) "keep" else "empty"

    // "Redo in the cloud" results this run, reported when the run ends.
    private var redoCloud = 0
    private var redoPhone = 0
    private var redoWhy: String? = null

    /** Words Boswell should know, read once per run (names change rarely). */
    private val vocabulary by lazy { runCatching { net.boswell.phone.asr.Vocabulary.all(applicationContext) }.getOrDefault(emptyList()) }

    /** The phone's own models (and the cloud's words, when wanted), loaded only when a clip needs them. */
    private class Local(models: ModelStore, c: Context) : AutoCloseable {
        val asr = LocalAsr(models)
        val ort = net.boswell.phone.diarize.VoiceModels.ort(c, models)
        val diarizer = ort.diarizer()
        override fun close() { ort.close(); asr.close() }
    }

    private var homeTagger: net.boswell.phone.sound.SoundTagger? = null
    private fun localTagger(models: ModelStore) = homeTagger ?: (if (models.isInstalled(net.boswell.phone.sound.SoundTagger.ID))
        net.boswell.phone.sound.SoundTagger(models).also { homeTagger = it } else null)

    private fun process(wav: File, local: Local, store: SpeakerStore, out: File, tagger: net.boswell.phone.sound.SoundTagger?) =
        processLocal(wav, local, store, out, tagger)

    /**
     * On the home server: upload the recording (its compact Ogg, ~30 KB) and put
     * its words, speakers and sounds together here exactly as for the phone's own.
     */
    private fun processHome(wav: File, store: SpeakerStore, out: File) {
        val t0 = System.currentTimeMillis()
        val clips = wav.parentFile!!
        val audio = net.boswell.phone.audio.ClipAudio.ogg(clips, wav.name).takeIf { it.exists() }
            ?: net.boswell.phone.audio.ClipAudio.file(clips, wav.name) ?: error("no audio")
        val r = net.boswell.phone.home.HomeServer.analyze(applicationContext, audio, wav.name,
            net.boswell.phone.diarize.VoiceModels.active(applicationContext).id, vocabulary)
        if (r.speech <= net.boswell.phone.diarize.Diarizer.SPEECH_MIN_S) {
            val t = Transcript(wav.name, System.currentTimeMillis() / 1000.0, emptyList(), emptyMap(), emptyMap(),
                engine = r.engine, processMs = System.currentTimeMillis() - t0, sounds = r.sounds, verdict = verdictFor(false, r.sounds))
            writeAtomically(File(out, wav.nameWithoutExtension + ".json"), TranscriptJson.json.encodeToString(Transcript.serializer(), t).toByteArray())
            return
        }
        finish(wav, t0, r.heard, r.diarization, r.sounds, r.engine, store, out)
    }

    private fun processLocal(wav: File, local: Local, store: SpeakerStore, out: File, tagger: net.boswell.phone.sound.SoundTagger?) {
        val asr = local.asr
        val diarizer = local.diarizer
        val t0 = System.currentTimeMillis()
        val pcm = net.boswell.phone.audio.ClipAudio.readPcm(wav.parentFile!!, wav.name)
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
        val d = diarizer.run(audio)
        val cloud = cloudWords(wav, pcm, diarizer, othersSpeak(d, store))
        val heard = cloud ?: asr.transcribe(audio)
        val tags = tagger?.tag(audio)
        val engine = (if (cloud != null) "${net.boswell.phone.asr.Transcription.ENGINE.id} (cloud)" else "nemotron-3.5-asr-1120ms-int8") +
            " + pyannote-seg-3.0 + ${net.boswell.phone.diarize.VoiceModels.active(applicationContext).id}" + (if (tags != null) " + ced-mini" else "")
        finish(wav, t0, heard, d, tags, engine, store, out)
    }

    /**
     * Words and speakers -> the transcript, wherever they came from: lines by
     * speaker, the vocabulary's fixes, Boswell's own voice, voices matched
     * against this phone's people, and the voice triggers.
     */
    private fun finish(wav: File, t0: Long, heard: List<net.boswell.phone.asr.Word>, d: net.boswell.phone.diarize.Diarization,
                       tags: List<net.boswell.phone.sound.SoundTag>?, engine: String, store: SpeakerStore, out: File) {
        val words = net.boswell.phone.asr.Vocabulary.apply(heard, vocabulary)
        // When the clip started, for the voice triggers and to line words up with what Boswell said.
        val times = runCatching {
            net.boswell.phone.capture.Clipper.json.decodeFromString(net.boswell.phone.capture.ClipTimes.serializer(),
                File(wav.parentFile, wav.nameWithoutExtension + ".json").readText())
        }.getOrNull()
        val own = ownVoice(wav.name, words, d, times, store)
        // A line the vocabulary changed keeps what was heard, as a hand edit does (and can be restored the same way).
        val segments = Lines.build(words, d.turns, boswell = own.words).map { seg ->
            if (words === heard) seg
            else heard.filter { (it.start + it.end) / 2 in seg.start..seg.end }.joinToString(" ") { it.text }
                .let { h -> if (h.isNotBlank() && h != seg.text) seg.copy(original = h) else seg }
        }
        val speakers = LinkedHashMap<String, SpeakerId>()
        val embeddings = LinkedHashMap<String, List<Float>>()
        for (s in d.speakers) {
            val label = Lines.label(s.index)
            val vp = s.voiceprint?.takeIf { Matching.usable(it) }
            if (vp != null) embeddings[label] = Matching.unit(vp).toList()
            // Boswell's voice: never matched to anyone, never filed as an unnamed voice (and
            // out of one it was filed in before, when this clip was transcribed the last time).
            val score = own.voices[s.index]
            if (score != null) store.releaseFromCluster(wav.name, label)
            speakers[label] = score?.let { SpeakerId(BoswellLines.NAME, it, BoswellLines.DECISION, null, emptyList(), null, s.seconds) }
                ?: identify(store, wav.name, label, vp, s.seconds)
        }
        val mine = segments.filter { it.speaker == BoswellLines.LABEL }
        if (mine.isNotEmpty()) speakers[BoswellLines.LABEL] = SpeakerId(BoswellLines.NAME, 1.0, BoswellLines.DECISION, null, emptyList(), null,
            mine.sumOf { it.end - it.start })
        val t = Transcript(wav.name, System.currentTimeMillis() / 1000.0, segments, speakers, embeddings,
            engine = engine,
            processMs = System.currentTimeMillis() - t0,
            sounds = tags, verdict = tags?.let { verdictFor(segments.isNotEmpty(), it) })
        writeAtomically(File(out, wav.nameWithoutExtension + ".json"), TranscriptJson.json.encodeToString(Transcript.serializer(), t).toByteArray())
        // Voice triggers act on new transcripts only; the line's own time goes with it.
        if (times != null) runCatching { net.boswell.phone.assistant.TriggerEngine(applicationContext).run(t, times.started) }
    }

    /** Boswell's own voice in one clip: which words are its, and which diarized voices are wholly its (with how sure). */
    private class Own(val words: BooleanArray, val voices: Map<Int, Double>)

    /**
     * Find Boswell's own spoken answers in a clip. First by time and words:
     * what the phone said while this clip was recorded, lined up against the
     * words heard then (BoswellLines.claim). A diarized voice that is mostly
     * those words is Boswell's voice, and -- long enough to be a reference --
     * teaches the phone what it sounds like, per TTS voice. Then by voice: a
     * diarized voice that sounds like that, clearly more than like anyone
     * named, is Boswell's even when nothing was said then (a recording from
     * the Omi's own storage). Never for a recording someone said wasn't.
     */
    private fun ownVoice(clip: String, words: List<net.boswell.phone.asr.Word>, d: net.boswell.phone.diarize.Diarization,
                         times: net.boswell.phone.capture.ClipTimes?, store: SpeakerStore): Own {
        val mine = BooleanArray(words.size)
        if (store.isNotBoswell(clip)) return Own(mine, emptyMap())
        val diar = Lines.speakers(words, d.turns)
        val (spoken, last) = runCatching {
            net.boswell.phone.assistant.AssistantStore(applicationContext).use { a ->
                val spoken = times?.let { a.spoken(it.started - BoswellLines.AFTER, it.ended + BoswellLines.BEFORE) }.orEmpty()
                spoken to a.lastVoice(times?.ended ?: Double.MAX_VALUE)
            }
        }.getOrDefault(emptyList<BoswellLines.Spoken>() to null)
        if (times != null) {
            val by = BoswellLines.claim(words, times.started, spoken, diar, times.seconds)
            for (i in words.indices) mine[i] = by[i] >= 0
        }
        // The voice it spoke with here, or the one it spoke with last.
        val voiceName = spoken.mapNotNull { it.voice }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: last
        voiceName?.let(store::adoptBoswellPrints)
        val voices = HashMap<Int, Double>()
        for (s in d.speakers) {
            val idx = words.indices.filter { diar[it] == s.index }
            val total = idx.sumOf { words[it].end - words[it].start }
            val ours = idx.filter { mine[it] }.sumOf { words[it].end - words[it].start }
            val vp = s.voiceprint?.takeIf { Matching.usable(it) }
            if (total > 0 && ours >= BoswellLines.MOSTLY * total) {
                voices[s.index] = 1.0
                // Learned from what it said only, never from a voice match, so a mistake can't teach itself.
                if (vp != null && voiceName != null && Matching.printable(ours)) store.addBoswellPrint(voiceName, vp, ours, clip, Lines.label(s.index))
                continue
            }
            if (vp == null || s.seconds < 1.0) continue
            val score = store.boswellScore(vp, voiceName) ?: continue
            // The closest named person is the rival; unnamed voices aren't, since one of them may be Boswell, filed before it knew itself.
            if (BoswellLines.soundsLikeBoswell(score, store.match(vp).score, Matching.MATCH_HIGH, Matching.MARGIN_STRONG)) voices[s.index] = score
        }
        for (i in words.indices) if (diar[i] in voices) mine[i] = true
        return Own(mine, voices)
    }

    /**
     * The words from the cloud, when this clip should go there and can: null
     * means transcribe on the phone (not wanted, no key, over the day's cap,
     * or the call failed -- nothing waits on the network).
     */
    /**
     * Someone besides the owner speaks for a second or more: a voice that
     * isn't theirs (or can't be told), and isn't one marked as a TV. Without
     * a known owner, anyone counts.
     */
    private fun othersSpeak(d: net.boswell.phone.diarize.Diarization, store: SpeakerStore): Boolean {
        val owner = net.boswell.phone.assistant.AssistantPrefs.owner(applicationContext)
        val voice = runCatching { net.boswell.phone.assistant.AssistantStore(applicationContext).use { it.lastVoice(Double.MAX_VALUE) } }.getOrNull()
        return d.speakers.any { s ->
            if (s.seconds < 1.0) return@any false
            val vp = s.voiceprint?.takeIf { Matching.usable(it) } ?: return@any true
            val r = store.match(vp)
            // Boswell's own voice answering isn't someone else.
            if (store.boswellScore(vp, voice)?.let { BoswellLines.soundsLikeBoswell(it, r.score, Matching.MATCH_HIGH, Matching.MARGIN_STRONG) } == true) return@any false
            val pid = r.personId.takeIf { r.decision == Matching.Decision.MATCHED }
            when {
                owner == null -> true
                pid == owner -> false
                pid != null && store.kindOf(pid) == "media" -> false
                else -> true
            }
        }
    }

    private fun cloudWords(wav: File, pcm: ShortArray, diarizer: net.boswell.phone.diarize.Diarizer, othersSpeak: Boolean): List<net.boswell.phone.asr.Word>? {
        val ctx = applicationContext
        val t = net.boswell.phone.asr.Transcription
        if (!t.wantsCloud(ctx, wav.name, othersSpeak)) return null
        val redo = t.requested(ctx, wav.name)
        t.handled(ctx, wav.name)
        fun fellBack(why: String): List<net.boswell.phone.asr.Word>? { if (redo) { redoPhone++; redoWhy = why }; return null }
        val key = net.boswell.phone.assistant.Secrets.get(ctx, net.boswell.phone.assistant.Secrets.OPENROUTER) ?: return fellBack("no OpenRouter key")
        val store = net.boswell.phone.assistant.AssistantStore(ctx)
        try {
            if (store.spentToday(t.PURPOSE) >= t.dailyCap(ctx)) {
                ProcessingRepository.state.value = ProcessingRepository.state.value.copy(lastError = "cloud transcription paused: today's limit reached")
                return fellBack("today's cloud limit was reached")
            }
            val empty = kotlinx.serialization.json.JsonObject(emptyMap())
            return runCatching { net.boswell.phone.asr.SpeechOnly.transcribe(key, t.ENGINE, pcm, diarizer, applicationContext.cacheDir) }.fold(
                { (words, cost) ->
                    store.logCall(t.PURPOSE, t.ENGINE.id, net.boswell.phone.assistant.LlmReply(null, emptyList(), empty, cost, 0, 0))
                    if (redo) redoCloud++
                    words
                },
                { e ->
                    store.logCall(t.PURPOSE, t.ENGINE.id, null, e.message ?: e.toString())
                    fellBack("the cloud didn't answer (${e.message ?: "error"})")
                })
        } finally { store.close() }
    }

    /**
     * A transcribed clip's sound: nothing worth keeping (no speech, and the
     * sound tagger heard only background) goes right away if that's the
     * setting; anything else is kept as its compact copy.
     */
    private fun afterTranscript(clips: File, name: String, transcript: File) {
        val t = runCatching { TranscriptJson.json.decodeFromString(Transcript.serializer(), transcript.readText()) }.getOrNull()
        val quiet = t != null && t.segments.isEmpty() && t.verdict == "empty"
        if (quiet && CleanupWorker.days(applicationContext) == 0) net.boswell.phone.audio.ClipAudio.delete(clips, name)
        else runCatching { net.boswell.phone.audio.ClipAudio.compact(clips, name) }
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

        /**
         * Who one diarized voice is: matched against the people, or -- nobody
         * it can be named as -- filed in (or as) an unnamed voice, so a
         * recurring stranger becomes one entry to name, not a hundred.
         */
        fun identify(store: SpeakerStore, clip: String, label: String, vp: FloatArray?, seconds: Double): SpeakerId {
            if (vp == null || !Matching.usable(vp)) return SpeakerId(null, 0.0, "none", null, emptyList(), null, seconds)
            val r = store.match(vp)
            store.logMatch(clip, label, r)
            val personId = when {
                r.decision == Matching.Decision.MATCHED -> r.personId
                seconds >= Matching.MIN_CLUSTER_SECONDS -> store.ingestUnknown(vp, clip, label, seconds)
                else -> null
            }
            return SpeakerId(
                name = if (r.decision == Matching.Decision.MATCHED) r.personId?.let(store::nameOf) else null,
                score = r.score, decision = r.decision.name.lowercase(), margin = r.margin,
                candidates = r.candidates.map { Candidate(it.personId, store.nameOf(it.personId), it.score, it.voiceprintId) },
                personId = personId, seconds = seconds,
            )
        }

        /**
         * Clips with sound and no transcript. A clip is named by its WAV name
         * whether the sound is still that WAV or already its compact copy
         * (a clip sent back for transcribing again).
         */
        fun pending(clips: File, out: File): List<File> =
            clips.listFiles { f -> f.extension == "json" }.orEmpty()
                .map { File(clips, it.nameWithoutExtension + ".wav") }
                .filter { !File(out, it.nameWithoutExtension + ".json").exists() && net.boswell.phone.audio.ClipAudio.exists(clips, it.name) }
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

        /** Try again later with a network (recordings kept for the home server). */
        fun enqueueLater(context: Context) {
            val req = OneTimeWorkRequestBuilder<ProcessingWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .setInitialDelay(5, java.util.concurrent.TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("$WORK-home", ExistingWorkPolicy.KEEP, req)
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
