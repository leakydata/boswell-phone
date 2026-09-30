package net.boswell.phone.setup

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    data class State(val active: Boolean = false, val heardSeconds: Double = 0.0, val targetSeconds: Double = 20.0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val frames = ArrayList<ShortArray>()

    fun start(target: Double = 20.0) = synchronized(frames) {
        frames.clear(); _state.value = State(true, 0.0, target)
    }

    fun stop() = synchronized(frames) { _state.value = _state.value.copy(active = false) }

    /** Called by the capture service for every decoded frame while [State.active]. */
    fun feed(pcm: ShortArray) = synchronized(frames) {
        if (!_state.value.active) return
        var s = 0.0
        for (x in pcm) { val v = x / 32768.0; s += v * v }
        if (sqrt(s / pcm.size) < 0.01) return          // near-silence: not voice
        frames += pcm
        val secs = frames.sumOf { it.size } / 16_000.0
        _state.value = _state.value.copy(heardSeconds = secs, active = secs < _state.value.targetSeconds)
    }

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
        require(audio.size >= 16_000 * 8) { "need at least 8 seconds of speech" }
        val models = ModelStore(context)
        require(models.isInstalled(ModelCatalog.VOICEPRINT) && models.isInstalled(ModelCatalog.SEGMENTATION)) { "the voiceprint model isn't downloaded yet" }
        val vp = OrtModels(models.path(ModelCatalog.SEGMENTATION, ".onnx"), models.path(ModelCatalog.VOICEPRINT, "voiceprint.onnx")).use { it.voiceprint(audio) }
            ?: error("couldn't make a voiceprint from that audio")
        require(Matching.usable(vp)) { "couldn't make a voiceprint from that audio" }
        val id = claimName(context, name)
        val store = SpeakerStore(context)
        try { store.addVoiceprint(id, vp, audio.size / 16_000.0, "enrollment", null, "manual") } finally { store.close() }
        net.boswell.phone.capture.CaptureRepository.log("learned the voice of $name (%.0f s)".format(audio.size / 16_000.0))
        id
    }
}
