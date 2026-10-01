package net.boswell.phone.asr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

/**
 * Cloud transcription through OpenRouter's /audio/transcriptions, used only
 * to compare against the phone's own -- the one place audio leaves the
 * phone, and only when someone asks for a comparison.
 *
 * Measured on real Omi clips (tools/phone_vs_cloud.py, 2026-09-30):
 * Parakeet v3 and Nova-3 read best and agreed with each other most (13%
 * apart, every other pair 20%+), Parakeet at a third of the price;
 * Nemotron cloud, the full-size version of the phone's model, came next;
 * Whisper turbo repeatedly invented "Thank you" over background noise.
 */
object CloudAsr {
    enum class Engine(val id: String, val label: String, val note: String) {
        PARAKEET("nvidia/parakeet-tdt-0.6b-v3", "Parakeet v3", "As accurate as Nova-3 in testing, at a third of the price · about $0.09 per hour"),
        NOVA("deepgram/nova-3", "Nova-3", "Most accurate in testing · about $0.26 per hour of audio"),
        NEMOTRON("nvidia/nemotron-3.5-asr-streaming-multilingual-0.6b", "Nemotron cloud", "The phone's model at full size · about $0.01 per hour"),
        WHISPER("openai/whisper-large-v3-turbo", "Whisper turbo", "Cheap, but invents words over noise · about $0.01 per hour"),
    }

    data class Result(val text: String, val cost: Double, val seconds: Double)

    class CloudException(message: String) : Exception(message)

    private const val URL = "https://openrouter.ai/api/v1/audio/transcriptions"
    private val json = Json { ignoreUnknownKeys = true }

    fun transcribe(apiKey: String, engine: Engine, wav: File): Result {
        val body = buildJsonObject {
            put("model", engine.id)
            put("input_audio", buildJsonObject {
                put("data", Base64.getEncoder().encodeToString(wav.readBytes()))
                put("format", "wav")
            })
            put("language", "en")
        }
        val t0 = System.currentTimeMillis()
        val conn = (URI(URL).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 300_000          // a cloud queue can be slow
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Title", "Boswell Phone")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw CloudException("HTTP $code: ${text.take(200)}")
            val j = json.parseToJsonElement(text).jsonObject
            j["error"]?.let { throw CloudException(it.toString().take(200)) }
            return Result(
                text = j["text"]?.jsonPrimitive?.content?.trim() ?: "",
                cost = j["usage"]?.jsonObject?.get("cost")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                seconds = (System.currentTimeMillis() - t0) / 1000.0,
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Words with their times, for transcription proper: the cloud supplies
     * the words, and the phone still decides who spoke them (the times are
     * what line the two up). Returns the words and what the call cost.
     */
    fun transcribeWords(apiKey: String, engine: Engine, wav: File): Pair<List<Word>, Double> {
        val body = buildJsonObject {
            put("model", engine.id)
            put("input_audio", buildJsonObject {
                put("data", Base64.getEncoder().encodeToString(wav.readBytes()))
                put("format", "wav")
            })
            put("language", "en")
            put("response_format", "verbose_json")
            put("timestamp_granularities", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("word"))))
        }
        val conn = (URI(URL).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 15_000; readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Title", "Boswell Phone")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw CloudException("HTTP $code: ${text.take(200)}")
            val j = json.parseToJsonElement(text).jsonObject
            j["error"]?.let { throw CloudException(it.toString().take(200)) }
            val words = (j["words"] as? kotlinx.serialization.json.JsonArray ?: throw CloudException("no word timings in reply")).mapNotNull { w ->
                val o = w.jsonObject
                val t = o["word"]?.jsonPrimitive?.content?.trim().orEmpty()
                val a = o["start"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val b = o["end"]?.jsonPrimitive?.doubleOrNull ?: a
                if (t.isEmpty()) null else Word(t, a, maxOf(b, a))
            }
            // Parakeet often reports a word as a single instant (start == end),
            // which overlaps no speaker turn. Like Words.fromTokens for the phone's
            // recognizer: a word runs on to the next one's start, at most a second.
            val spread = words.mapIndexed { i, w ->
                val next = words.getOrNull(i + 1)?.start ?: (w.start + 1.0)
                w.copy(end = maxOf(w.end, minOf(next, w.start + 1.0)))
            }
            return spread to (j["usage"]?.jsonObject?.get("cost")?.jsonPrimitive?.doubleOrNull ?: 0.0)
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ comparing

    /** Lowercase letters, digits and apostrophes: punctuation and case are not disagreements. */
    fun norm(w: String): String = w.lowercase().filter { it.isLetterOrDigit() || it == '\'' }

    /**
     * Which words of [a] and [b] the other one also has, in order (longest
     * common subsequence on normalized words). True = shared.
     */
    fun shared(a: List<String>, b: List<String>): Pair<BooleanArray, BooleanArray> {
        val x = a.map(::norm); val y = b.map(::norm)
        val dp = Array(x.size + 1) { IntArray(y.size + 1) }
        for (i in x.indices.reversed()) for (j in y.indices.reversed())
            dp[i][j] = if (x[i].isNotEmpty() && x[i] == y[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val sa = BooleanArray(x.size); val sb = BooleanArray(y.size)
        var i = 0; var j = 0
        while (i < x.size && j < y.size) {
            when {
                x[i].isNotEmpty() && x[i] == y[j] -> { sa[i] = true; sb[j] = true; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        // Punctuation-only tokens ("-") are never a disagreement.
        for (k in x.indices) if (x[k].isEmpty()) sa[k] = true
        for (k in y.indices) if (y[k].isEmpty()) sb[k] = true
        return sa to sb
    }

    /** Word edits turning [ref] into [hyp] (Levenshtein on normalized words), and the size of [ref]. */
    fun edits(ref: List<String>, hyp: List<String>): Pair<Int, Int> {
        val r = ref.map(::norm).filter { it.isNotEmpty() }; val h = hyp.map(::norm).filter { it.isNotEmpty() }
        var prev = IntArray(h.size + 1) { it }
        for (i in 1..r.size) {
            val cur = IntArray(h.size + 1); cur[0] = i
            for (j in 1..h.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[h.size] to r.size
    }

    fun words(s: String): List<String> = s.split(Regex("\\s+")).filter { it.isNotBlank() }
}
