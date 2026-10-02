package net.boswell.phone.home

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.boswell.phone.asr.Word
import net.boswell.phone.assistant.Secrets
import net.boswell.phone.diarize.DiarizedSpeaker
import net.boswell.phone.diarize.Diarization
import net.boswell.phone.diarize.Turn
import net.boswell.phone.sound.SoundTag
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Boswell Server on the person's own computer (github.com/leakydata/boswell-server):
 * it does the heavy work on a GPU -- who spoke when, the words, voiceprints, sounds --
 * and the phone only uploads the recording and puts the result together itself,
 * matching voiceprints against its own people.
 *
 * Paired once with a code from the server's screen (usually as a QR code), traded
 * for a key that's kept with [Secrets]. Reached over Tailscale, so the address is
 * the computer's private tailnet name.
 */
object HomeServer {
    private fun p(c: Context) = c.getSharedPreferences("boswell", Context.MODE_PRIVATE)
    const val KEY = "home_server_key"
    private val json = Json { ignoreUnknownKeys = true }

    /** When home can't be reached: process on the phone, or keep the recording for home. */
    enum class Fallback { PHONE, WAIT }

    fun url(c: Context): String? = p(c).getString("home_url", null)
    fun paired(c: Context) = url(c) != null && Secrets.get(c, KEY) != null
    fun enabled(c: Context) = paired(c) && p(c).getBoolean("home_enabled", false)
    fun setEnabled(c: Context, on: Boolean) = p(c).edit().putBoolean("home_enabled", on).apply()
    fun fallback(c: Context): Fallback = runCatching { Fallback.valueOf(p(c).getString("home_fallback", null)!!) }.getOrDefault(Fallback.PHONE)
    fun setFallback(c: Context, f: Fallback) = p(c).edit().putString("home_fallback", f.name).apply()

    fun forget(c: Context) {
        p(c).edit().remove("home_url").remove("home_enabled").apply()
        Secrets.put(c, KEY, null)
    }

    /** Couldn't reach the server, or it refused; [notPaired] when the key no longer works. */
    class Unavailable(message: String, val notPaired: Boolean = false) : IOException(message)

    private fun open(url: String, method: String, timeoutMs: Int): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 5_000; readTimeout = timeoutMs
        }

    /**
     * What the pairing QR code holds: {"boswell": 1, "server": "http://…:8765", "code": "123456"}.
     * Returns (server, code), or null if it isn't one of ours.
     */
    fun parseQr(text: String): Pair<String, String>? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        if (o["boswell"] == null) return null
        o["server"]!!.jsonPrimitive.content.trimEnd('/') to o["code"]!!.jsonPrimitive.content
    }.getOrNull()

    /** Trade a pairing code for this phone's key. Returns null on success, or what went wrong. */
    fun pair(c: Context, server: String, code: String): String? = runCatching {
        val base = server.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
        val conn = open("$base/v1/pair", "POST", 15_000)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val body = buildJsonObject { put("code", code.trim()); put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}") }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        when (conn.responseCode) {
            200 -> {
                val token = json.parseToJsonElement(conn.inputStream.readBytes().decodeToString()).jsonObject["token"]!!.jsonPrimitive.content
                Secrets.put(c, KEY, token)
                p(c).edit().putString("home_url", base).putBoolean("home_enabled", true).commit()
                null
            }
            403 -> "The code was wrong or has expired. Show a new one on the server (press p) and try again."
            else -> "The server answered ${conn.responseCode}."
        }
    }.getOrElse { e ->
        if (e is java.net.UnknownHostException) "Couldn't find $server. Is Tailscale on, on this phone and on the computer?"
        else "Couldn't reach the server: ${e.message}"
    }

    /** Is it up? Returns a one-line description, or throws [Unavailable]. */
    fun health(c: Context): String {
        val base = url(c) ?: throw Unavailable("not paired")
        val t0 = System.currentTimeMillis()
        return try {
            val conn = open("$base/v1/health", "GET", 8_000)
            val o = json.parseToJsonElement(conn.inputStream.readBytes().decodeToString()).jsonObject
            val gpu = o["gpu"]?.jsonPrimitive?.contentOrNull
            "Connected in ${System.currentTimeMillis() - t0} ms" + (gpu?.let { " · $it" } ?: "")
        } catch (e: Exception) {
            throw Unavailable(e.message ?: "unreachable")
        }
    }

    @Serializable data class HWord(val text: String, val start: Double, val end: Double)
    @Serializable data class HTurn(val start: Double, val end: Double)
    @Serializable data class HSpeaker(val index: Int, val turns: List<HTurn>, val seconds: Double, val voiceprint: List<Float>? = null)
    @Serializable data class Result(
        val speech: Double,
        val words: List<HWord> = emptyList(),
        val speakers: List<HSpeaker> = emptyList(),
        val sounds: List<SoundTag> = emptyList(),
        val engine: String = "home",
        val ms: Long = 0,
    ) {
        val heard: List<Word> get() = words.map { Word(it.text, it.start, it.end) }
        val diarization: Diarization get() = Diarization(speakers.map { s ->
            DiarizedSpeaker(s.index, s.turns.map { Turn(s.index, it.start, it.end) }, s.seconds, s.voiceprint?.toFloatArray())
        })
    }

    /**
     * Send one recording (its compact Ogg if there is one: ~30 KB per 30 s) and get
     * back its words, speakers with voiceprints in [voiceModel], and sounds.
     */
    fun analyze(c: Context, audio: File, clip: String, voiceModel: String): Result {
        val base = url(c) ?: throw Unavailable("not paired")
        val key = Secrets.get(c, KEY) ?: throw Unavailable("not paired", notPaired = true)
        val conn = try {
            open("$base/v1/analyze?voice_model=$voiceModel&clip=${java.net.URLEncoder.encode(clip, "UTF-8")}", "POST", 120_000).apply {
                doOutput = true
                setFixedLengthStreamingMode(audio.length())
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", if (audio.name.endsWith(".ogg")) "audio/ogg" else "audio/wav")
                outputStream.use { o -> audio.inputStream().use { it.copyTo(o) } }
            }
        } catch (e: IOException) { throw Unavailable(e.message ?: "unreachable") }
        val code = try { conn.responseCode } catch (e: IOException) { throw Unavailable(e.message ?: "unreachable") }
        when (code) {
            200 -> return json.decodeFromString(Result.serializer(), conn.inputStream.readBytes().decodeToString())
            401 -> throw Unavailable("the server doesn't know this phone any more: pair again", notPaired = true)
            else -> throw Unavailable("the server answered $code: ${runCatching { conn.errorStream?.readBytes()?.decodeToString() }.getOrNull()?.take(200)}")
        }
    }
}
