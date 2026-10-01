package net.boswell.phone.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URI

/** One tool call the model asked for. */
data class ToolCall(val id: String, val name: String, val arguments: String)

data class LlmReply(val text: String?, val toolCalls: List<ToolCall>, val message: JsonObject, val cost: Double, val promptTokens: Int, val completionTokens: Int)

class LlmException(message: String) : Exception(message)

/**
 * The OpenAI-compatible chat-completions API, which OpenRouter speaks -- and
 * which OpenAI itself speaks, so a second provider is a base URL and a key.
 * Text only: the one place audio can leave the phone is a transcription
 * comparison someone asks for (asr/CloudAsr).
 */
class Llm(private val apiKey: String, private val model: String, private val baseUrl: String = OPENROUTER_URL) {

    fun chat(messages: List<JsonObject>, tools: JsonArray? = null, maxTokens: Int = 800, temperature: Double = 0.3): LlmReply {
        val body = buildJsonObject {
            put("model", model)
            put("messages", JsonArray(messages))
            if (tools != null && tools.isNotEmpty()) put("tools", tools)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            // OpenRouter reports what each call cost, which is what the daily budget counts.
            put("usage", buildJsonObject { put("include", true) })
        }
        val conn = (URI(baseUrl).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Title", "Boswell Phone")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw LlmException("HTTP $code: ${text.take(300)}")
            val j = json.parseToJsonElement(text).jsonObject
            j["error"]?.let { throw LlmException(it.toString().take(300)) }
            val msg = j["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                ?: throw LlmException("no message in reply")
            val calls = msg["tool_calls"]?.jsonArray.orEmpty().map { c ->
                val o = c.jsonObject
                val f = o["function"]!!.jsonObject
                ToolCall(o["id"]!!.jsonPrimitive.content, f["name"]!!.jsonPrimitive.content, f["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}")
            }
            val usage = j["usage"]?.jsonObject
            return LlmReply(
                text = msg["content"]?.let { if (it is JsonPrimitive) it.contentOrNull else null },
                toolCalls = calls, message = msg,
                cost = usage?.get("cost")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0,
                completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            )
        } finally {
            conn.disconnect()
        }
    }

    /** What OpenRouter has recorded for this key (all apps using it), or null if it can't be reached. */
    data class KeyInfo(val daily: Double, val weekly: Double, val monthly: Double, val total: Double, val limit: Double?, val remaining: Double?)

    fun keyInfo(): KeyInfo? = runCatching {
        val conn = (URI("https://openrouter.ai/api/v1/key").toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            if (conn.responseCode != 200) return null
            val d = json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject["data"]!!.jsonObject
            fun num(k: String) = d[k]?.let { (it as? JsonPrimitive)?.doubleOrNull }
            KeyInfo(num("usage_daily") ?: 0.0, num("usage_weekly") ?: 0.0, num("usage_monthly") ?: 0.0, num("usage") ?: 0.0, num("limit"), num("limit_remaining"))
        } finally { conn.disconnect() }
    }.getOrNull()

    companion object {
        const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val DEFAULT_MODEL = "z-ai/glm-5.3-flash"
        val json = Json { ignoreUnknownKeys = true }

        fun system(text: String) = buildJsonObject { put("role", "system"); put("content", text) }
        fun user(text: String) = buildJsonObject { put("role", "user"); put("content", text) }
        fun toolResult(id: String, content: String) = buildJsonObject { put("role", "tool"); put("tool_call_id", id); put("content", content) }

        /** A tool definition in the OpenAI function-calling format. */
        fun tool(name: String, description: String, params: Map<String, Pair<String, String>>, required: List<String> = emptyList()) = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("description", description)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        for ((p, td) in params) put(p, buildJsonObject { put("type", td.first); put("description", td.second) })
                    })
                    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
                })
            })
        }
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
