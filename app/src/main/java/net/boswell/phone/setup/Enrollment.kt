package net.boswell.phone.setup

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.boswell.phone.capture.logged
import net.boswell.phone.capture.timed
import net.boswell.phone.diarize.OrtModels
import net.boswell.phone.models.ModelCatalog
import net.boswell.phone.models.ModelStore
import net.boswell.phone.speakers.Matching
import net.boswell.phone.speakers.SpeakerStore
import kotlin.math.sqrt

/**
 * Learning the owner's voice during setup: while active, the live stream's
 * decoded audio is collected here (the capture service feeds it), and only
 * frames with real sound count towards the target -- the Omi's mic sleeps in
 * silence, and a voiceprint of room noise would match nobody.
 */
object Enrollment {
    data class State(val active: Boolean = false, val heardSeconds: Double = 0.0, val targetSeconds: Double = 15.0,
                     /** 0..1, the level of the latest frame against the room's, for a live meter. */
                     val level: Float = 0f)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val frames = ArrayList<ShortArray>()
    /**
     * The room's level. Learned only from frames that are not speech, so a
     * person reading without pauses can never raise it -- the first version
     * took the quietest 10% of recent frames, and continuous reading filled
     * "recent" with voice until the voice itself became the room.
     */
    private var noise = -1.0

    fun start(target: Double = 15.0) = synchronized(frames) {
        frames.clear(); noise = -1.0; _state.value = State(true, 0.0, target)
    }

    fun stop() = synchronized(frames) { _state.value = _state.value.copy(active = false) }

    /**
     * Called by the capture service for every decoded frame while [State.active].
     *
     * The Omi records quietly -- measured here, a person reading aloud sat at a
     * median frame level of 0.006 -- so a fixed loudness bar (0.01, the first
     * version) heard only 12 s of a minute's reading and never finished. The
     * bar is now relative: 2.5x the room's own level (see [noise]), with a
     * small absolute floor.
     */
    fun feed(pcm: ShortArray) = synchronized(frames) {
        if (!_state.value.active) return
        // Boswell talking (an answer, a reminder) is not the owner's voice.
        if (net.boswell.phone.assistant.AssistantNotify.speakingNow()) return
        var s = 0.0
        for (x in pcm) { val v = x / 32768.0; s += v * v }
        val rms = sqrt(s / pcm.size)
        if (noise < 0) noise = minOf(rms, 0.004)
        val bar = maxOf(0.003, noise * 2.5)
        val level = (rms / (bar * 4)).toFloat().coerceIn(0f, 1f)
        if (rms < bar) {
            noise = noise * 0.95 + rms * 0.05          // only quiet frames teach it the room
            _state.value = _state.value.copy(level = level)
            return
        }
        frames += pcm
        val secs = frames.sumOf { it.size } / 16_000.0
        _state.value = _state.value.copy(heardSeconds = secs, active = secs < _state.value.targetSeconds, level = level)
    }

    /** The stream paused (the mic sleeps when you stop talking): with enough heard, that's the end. */
    fun paused() = synchronized(frames) {
        if (_state.value.active && _state.value.heardSeconds >= MIN_SECONDS) _state.value = _state.value.copy(active = false)
    }

    const val MIN_SECONDS = 8.0

    /**
     * Turn what was heard into the owner: a named person with this voiceprint
     * as a hand-made reference, marked "me". Returns the person id, or an error.
     */
    /** Make [name] a person and "me", with or without a voiceprint yet. */
    fun claimName(context: Context, name: String): Long {
        val store = SpeakerStore(context)
        try {
            val id = store.people().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.id
                ?: store.newPerson(name.trim())
            net.boswell.phone.assistant.AssistantPrefs.setOwner(context, id)
            return id
        } finally { store.close() }
    }

    fun finish(context: Context, name: String): Result<Long> = runCatching {
        val audio = synchronized(frames) {
            val out = FloatArray(frames.sumOf { it.size }); var o = 0
            for (f in frames) for (x in f) out[o++] = x / 32768f
            out
        }
        require(audio.size >= (16_000 * MIN_SECONDS).toInt()) { "need at least 8 seconds of speech" }
        val models = ModelStore(context)
        require(models.isInstalled(ModelCatalog.VOICEPRINT) && models.isInstalled(ModelCatalog.SEGMENTATION)) { "the voiceprint model isn't downloaded yet" }
        // A voiceprint from every installed model: the reading isn't kept, so this is the only
        // chance to make one with the speaker-ID model too (VoiceMigration converts the rest).
        val (vp, idVp) = net.boswell.phone.diarize.VoiceModels.ort(context, models, forceSpeakerId = true).use { m ->
            m.voiceprint(audio) to (if (m.identity == net.boswell.phone.diarize.VoiceModel.SPEAKER_ID) m.identify(audio) else null)
        }
        if (vp == null || !Matching.usable(vp)) error("couldn't make a voiceprint from that audio")
        val id = claimName(context, name)
        val store = SpeakerStore(context)
        try {
            store.addVoiceprint(id, vp, audio.size / 16_000.0, "enrollment", null, "manual")
            idVp?.takeIf { Matching.usable(it) }?.let { store.addVoiceprint(id, it, audio.size / 16_000.0, "enrollment", null, "manual") }
        } finally { store.close() }
        net.boswell.phone.capture.CaptureRepository.log("learned the voice of $name (%.0f s)".format(audio.size / 16_000.0))
        // Past recordings get another look with the new sample.
        runCatching { timed("voice recheck", 10_000) { net.boswell.phone.speakers.VoiceReview(context).recheck() } }
            .logged("voice recheck")
            .onSuccess { if (it.matched > 0) net.boswell.phone.capture.CaptureRepository.log("recognized $name in ${it.matched} more recordings") }
        id
    }
}
