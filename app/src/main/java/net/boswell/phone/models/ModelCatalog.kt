package net.boswell.phone.models

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A gzip-compressed copy of a file on the release, to download in its place. */
@Serializable
data class PackedFile(val name: String, val size: Long, val sha256: String)

@Serializable
data class ModelFile(val name: String, val size: Long, val sha256: String, val gz: PackedFile? = null) {
    /** What actually crosses the network. */
    val downloadSize: Long get() = gz?.size ?: size
}

@Serializable
data class ModelSpec(
    val id: String,
    val name: String,
    val purpose: String,
    val license: String,
    val source: String,
    val files: List<ModelFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.size }
    val downloadBytes: Long get() = files.sumOf { it.downloadSize }
}

@Serializable
data class ModelCatalog(
    val version: Int,
    val release: String,
    @SerialName("base_url") val baseUrl: String,
    val models: List<ModelSpec>,
) {
    fun byId(id: String) = models.first { it.id == id }

    companion object {
        const val ASR = "asr-nemotron-1120"
        const val SEGMENTATION = "segmentation"
        const val VOICEPRINT = "voiceprint"
        const val SPEAKER_ID = "speaker-id"

        private val json = Json { ignoreUnknownKeys = true }

        /** The catalog ships in the APK (assets/models.json); the models do not. */
        fun load(context: Context): ModelCatalog =
            context.assets.open("models.json").use { json.decodeFromString(serializer(), it.readBytes().decodeToString()) }
    }
}

/**
 * Where installed models live: filesDir/models/<release>/. A file counts as
 * installed only once it has been downloaded in full and its SHA-256 checked;
 * until then it is a `.part` file and nothing reads it.
 */
class ModelStore(context: Context, val catalog: ModelCatalog = ModelCatalog.load(context)) {
    val dir = File(context.filesDir, "models/${catalog.release}").apply { mkdirs() }

    fun file(name: String) = File(dir, name)

    fun path(modelId: String, suffix: String): String =
        file(catalog.byId(modelId).files.first { it.name.endsWith(suffix) }.name).path

    fun isInstalled(id: String): Boolean =
        catalog.byId(id).files.all { f -> file(f.name).let { it.exists() && it.length() == f.size } }

    fun installedBytes(id: String): Long =
        catalog.byId(id).files.sumOf { f ->
            val done = file(f.name)
            val part = File(dir, f.name + ".part")
            val gz = f.gz?.let { File(dir, it.name) }
            val gzPart = f.gz?.let { File(dir, it.name + ".part") }
            // Compressed bytes count in proportion, so progress stays in installed size.
            fun scaled(n: Long) = f.gz?.let { n * f.size / it.size } ?: n
            when {
                done.exists() -> done.length()
                gz?.exists() == true -> f.size
                gzPart?.exists() == true -> scaled(gzPart.length())
                part.exists() -> part.length()
                else -> 0L
            }
        }

    fun delete(id: String) {
        for (f in catalog.byId(id).files) {
            file(f.name).delete()
            File(dir, f.name + ".part").delete()
        }
    }
}
