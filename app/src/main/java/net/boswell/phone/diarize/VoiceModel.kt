package net.boswell.phone.diarize

/**
 * Which model made a voiceprint. Vectors from different models can't be
 * compared, so every voiceprint is filed with its model (by size: the two
 * differ) and matching only ever looks at the model in use. Thresholds belong
 * to the model too: the same voice scores higher under ReDimNet2 than under
 * WeSpeaker, so 0.75 means a different thing in each.
 */
enum class VoiceModel(
    val id: String,
    val dim: Int,
    /** A confident match on its own. */
    val matchHigh: Double,
    /** Worth considering at all. */
    val matchLow: Double,
    /** Lead over the runner-up needed with a confident score. */
    val marginMin: Double,
    /** Lead that makes a lower score a match ("clear of the field"). */
    val marginStrong: Double,
    /** An unnamed voice joins an unnamed cluster at or above this. */
    val clusterMin: Double,
    /** Two voices in one conversation are the same person (Archive conversation keys). */
    val sameVoice: Double,
    /** "Is this you?": close enough to ask. */
    val likely: Double,
) {
    /** WeSpeaker ResNet34-LM, desktop Boswell's model: the original thresholds. */
    WESPEAKER("wespeaker-resnet34-lm", 256, 0.75, 0.55, 0.15, 0.25, 0.75, 0.60, 0.65),

    /**
     * ReDimNet2-B6 (vb2+vox2, large-margin fine-tuned; MIT). Each threshold
     * wrongly accepts different people exactly as often as WeSpeaker's does,
     * over 63,247 different-person pairs from desktop Boswell's archive
     * (tools/calibrate_voice.py, 8 s cap); at that caution it accepts about
     * twice the true pairs at the confident bar (31% vs 15%) and picked the
     * right person for 92% of hand-checked voices (vs 82%). Margins scale
     * with the score spread (x0.75).
     */
    SPEAKER_ID("redimnet2-b6-vb2vox2-lm", 192, 0.78, 0.64, 0.11, 0.19, 0.78, 0.69, 0.73);

    companion object {
        const val MAX_ID_SECONDS = 8
        const val MAX_ID_SAMPLES = MAX_ID_SECONDS * 16_000
        fun byDim(dim: Int) = entries.firstOrNull { it.dim == dim }
    }
}

/** Which voiceprint model is in use, and the models to run it with. */
object VoiceModels {
    private fun p(c: android.content.Context) = c.getSharedPreferences("boswell", android.content.Context.MODE_PRIVATE)

    /** Switched to [VoiceModel.SPEAKER_ID] only once every voiceprint has been made again with it (VoiceMigration). */
    fun active(c: android.content.Context): VoiceModel =
        p(c).getString("voice_model", null)?.let { id -> VoiceModel.entries.firstOrNull { it.id == id } } ?: VoiceModel.WESPEAKER

    fun setActive(c: android.content.Context, m: VoiceModel) = p(c).edit().putString("voice_model", m.id).commit()

    /**
     * The model the person chose: Device -> "Better voice recognition" is
     * SPEAKER_ID (opt-in: about twice the processing per recording). The
     * switch happens once VoiceMigration has converted everything to it.
     */
    fun wanted(c: android.content.Context): VoiceModel =
        p(c).getString("voice_model_wanted", null)?.let { id -> VoiceModel.entries.firstOrNull { it.id == id } } ?: VoiceModel.WESPEAKER

    fun setWanted(c: android.content.Context, m: VoiceModel) = p(c).edit().putString("voice_model_wanted", m.id).commit()

    fun speakerIdInstalled(store: net.boswell.phone.models.ModelStore) =
        runCatching { store.isInstalled(net.boswell.phone.models.ModelCatalog.SPEAKER_ID) }.getOrDefault(false)

    /** Segmentation, the splitting model, and -- when it's the one in use -- the speaker-ID model. */
    fun ort(c: android.content.Context, store: net.boswell.phone.models.ModelStore, forceSpeakerId: Boolean = false): OrtModels {
        val useId = (forceSpeakerId || active(c) == VoiceModel.SPEAKER_ID) && speakerIdInstalled(store)
        return OrtModels(store.path(net.boswell.phone.models.ModelCatalog.SEGMENTATION, ".onnx"),
            store.path(net.boswell.phone.models.ModelCatalog.VOICEPRINT, "voiceprint.onnx"),
            speakerIdPath = if (useId) store.path(net.boswell.phone.models.ModelCatalog.SPEAKER_ID, ".onnx") else null)
    }
}
