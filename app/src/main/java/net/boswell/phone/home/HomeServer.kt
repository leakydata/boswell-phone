package net.boswell.phone.home

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    /**
     * Paired over plain HTTP (http://computer.tailnet.ts.net:8765) but the server now has
     * HTTPS too (`tailscale serve`, a real certificate): move to https://computer.tailnet.ts.net
     * by ourselves, no pairing again. Tried at most every half hour; the key stays the same.
     */
    private fun preferHttps(c: Context) {
        val now = url(c) ?: return
        val uri = runCatching { URI(now) }.getOrNull() ?: return
        if (uri.scheme != "http" || uri.host?.endsWith(".ts.net") != true) return
        val last = p(c).getLong("home_https_tried", 0L)
        if (System.currentTimeMillis() - last < 30 * 60_000L) return
        p(c).edit().putLong("home_https_tried", System.currentTimeMillis()).apply()
        val https = "https://${uri.host}"
        runCatching {
            val conn = open("$https/v1/health", "GET", 5_000)
            val ok = conn.responseCode == 200 &&
                json.parseToJsonElement(conn.inputStream.readBytes().decodeToString()).jsonObject["name"]?.jsonPrimitive?.contentOrNull == "Boswell Server"
            if (ok) {
                p(c).edit().putString("home_url", https).commit()
                net.boswell.phone.capture.CaptureRepository.log("home server: moved to $https")
            }
        }
    }
    fun paired(c: Context) = url(c) != null && Secrets.get(c, KEY) != null
    fun enabled(c: Context) = paired(c) && p(c).getBoolean("home_enabled", false)
    fun setEnabled(c: Context, on: Boolean) {
        p(c).edit().putBoolean("home_enabled", on).apply()
        if (on) net.boswell.phone.process.CatchUp.markSince(c)
    }
    fun fallback(c: Context): Fallback = runCatching { Fallback.valueOf(p(c).getString("home_fallback", null)!!) }.getOrDefault(Fallback.PHONE)
    fun setFallback(c: Context, f: Fallback) = p(c).edit().putString("home_fallback", f.name).apply()

    fun forget(c: Context) {
        p(c).edit().remove("home_url").remove("home_enabled").apply()
        Secrets.put(c, KEY, null)
    }

    /**
     * Home couldn't be reached while recordings were waiting: since when (ms) and why, until
     * the next recording gets through. Otherwise the phone quietly does the work itself and
     * nobody notices that, say, Tailscale was off all day. After [TROUBLE_NOTICE_MS] it's
     * also a (silent) notification.
     */
    fun trouble(c: Context): Pair<Long, String>? =
        p(c).getLong("home_trouble_since", 0L).takeIf { it > 0 }?.let { it to (p(c).getString("home_trouble_why", null) ?: "") }

    fun noteTrouble(c: Context, message: String?) {
        val since = p(c).getLong("home_trouble_since", 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
        p(c).edit().putLong("home_trouble_since", since).putString("home_trouble_why", explain(message)).apply()
        if (System.currentTimeMillis() - since >= TROUBLE_NOTICE_MS && !p(c).getBoolean("home_trouble_told", false)) {
            p(c).edit().putBoolean("home_trouble_told", true).apply()
            val at = java.time.Instant.ofEpochMilli(since).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
            val open = android.app.PendingIntent.getActivity(c, TROUBLE_ID,
                android.content.Intent(c, net.boswell.phone.ui.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = androidx.core.app.NotificationCompat.Builder(c, net.boswell.phone.process.Notifications.WORK)
                .setSmallIcon(net.boswell.phone.R.drawable.ic_stat_mic).setSilent(true).setAutoCancel(true).setContentIntent(open)
                .setContentTitle("Can't reach your computer since $at")
                .setContentText(explain(message) + if (fallback(c) == Fallback.PHONE) " The phone is transcribing meanwhile." else " Recordings wait for it.")
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle())
                .build()
            runCatching { c.getSystemService(android.app.NotificationManager::class.java).notify(TROUBLE_ID, n) }
        }
    }

    fun clearTrouble(c: Context) {
        if (p(c).getLong("home_trouble_since", 0L) == 0L) return
        p(c).edit().remove("home_trouble_since").remove("home_trouble_why").remove("home_trouble_told").apply()
        runCatching { c.getSystemService(android.app.NotificationManager::class.java).cancel(TROUBLE_ID) }
    }

    /** What went wrong, in words someone can act on. */
    internal fun explain(message: String?): String {
        val m = message.orEmpty()
        return when {
            "resolve host" in m || "No address associated" in m -> "It can't be found on the network. Is Tailscale on, on this phone?"
            "pair again" in m || "not paired" in m -> "It doesn't know this phone any more: pair again."
            "timed out" in m || "timeout" in m.lowercase() || "ECONNREFUSED" in m || "Failed to connect" in m || "refused" in m ->
                "It didn't answer. Is the computer on, and Boswell Server running?"
            m.isBlank() -> "It didn't answer."
            else -> "It didn't answer ($m)."
        }
    }

    private const val TROUBLE_ID = 6
    private const val TROUBLE_NOTICE_MS = 10 * 60_000L

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
                // Recordings from here on that the phone does itself, home away, are caught up later (CatchUp).
                net.boswell.phone.process.CatchUp.markSince(c)
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
        preferHttps(c)
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

    /**
     * The home server's own AI (a model in Ollama, for the assistant): a one-line description
     * like "gemma4:e4b · ready", or throws [Unavailable] saying why it can't answer.
     */
    fun llm(c: Context): String {
        preferHttps(c)
        val base = url(c) ?: throw Unavailable("not paired")
        val o = try {
            val conn = open("$base/v1/llm", "GET", 8_000)
            if (conn.responseCode == 404) throw Unavailable("this server is too old for that: update Boswell Server")
            json.parseToJsonElement(conn.inputStream.readBytes().decodeToString()).jsonObject
        } catch (e: Unavailable) { throw e } catch (e: Exception) { throw Unavailable(e.message ?: "unreachable") }
        val model = o["model"]?.jsonPrimitive?.contentOrNull ?: "?"
        if (o["available"]?.jsonPrimitive?.contentOrNull != "true") throw Unavailable(o["reason"]?.jsonPrimitive?.contentOrNull ?: "$model isn't available")
        return model + if (o["loaded"]?.jsonPrimitive?.contentOrNull == "true") " · loaded and ready" else " · ready (the first answer loads it, which takes a while)"
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
     * The vocabulary as the X-Boswell-Hotwords header: a JSON array of strings, so the
     * server's recognizer can favor names it would otherwise mishear. Headers must be
     * ASCII, so anything else is escaped as \uXXXX (still valid JSON); capped so a long
     * list of people can't push the request past a server's header limit.
     */
    internal fun hotwordsHeader(terms: List<String>): String {
        val kept = mutableListOf<JsonPrimitive>()
        var length = 2
        for (t in terms.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(300)) {
            val item = asciiJson(JsonPrimitive(t).toString())
            if (length + item.length + 1 > 6000) break
            kept += JsonPrimitive(t); length += item.length + 1
        }
        return asciiJson(JsonArray(kept).toString())
    }

    private fun asciiJson(s: String) = buildString {
        for (ch in s) if (ch.code < 0x80) append(ch) else append("\\u").append(ch.code.toString(16).padStart(4, '0'))
    }

    /**
     * Send one recording (its compact Ogg if there is one: ~30 KB per 30 s) and get
     * back its words, speakers with voiceprints in [voiceModel], and sounds.
     * [hotwords] are names and words the recognizer should listen for.
     */
    fun analyze(c: Context, audio: File, clip: String, voiceModel: String, hotwords: List<String>): Result {
        preferHttps(c)
        val base = url(c) ?: throw Unavailable("not paired")
        val key = Secrets.get(c, KEY) ?: throw Unavailable("not paired", notPaired = true)
        val conn = try {
            open("$base/v1/analyze?voice_model=$voiceModel&clip=${java.net.URLEncoder.encode(clip, "UTF-8")}", "POST", 120_000).apply {
                doOutput = true
                setFixedLengthStreamingMode(audio.length())
                setRequestProperty("Authorization", "Bearer $key")
                if (hotwords.isNotEmpty()) setRequestProperty("X-Boswell-Hotwords", hotwordsHeader(hotwords))
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
