package net.boswell.phone

import kotlinx.serialization.json.buildJsonArray
import net.boswell.phone.assistant.Llm
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Live check of tool calling against OpenRouter (costs a fraction of a cent).
 * Runs only with -Dllm.live=1 and a key (-Dllm.keyfile=tools/.env, or OPENROUTER_API_KEY).
 */
class LlmToolTest {
    @Test fun modelCallsToolsAndAnswersFromThem() {
        // From -Dllm.keyfile (a KEY=value file such as tools/.env), else the environment.
        val key = System.getProperty("llm.keyfile")?.let(::File)?.takeIf { it.exists() }?.readLines()
            ?.firstOrNull { it.trim().startsWith("OPENROUTER_API_KEY=") }?.substringAfter("=")?.trim()?.trim('"', '\'')
            ?: System.getenv("OPENROUTER_API_KEY")
        assumeTrue(System.getProperty("llm.live") == "1" && !key.isNullOrBlank())
        val llm = Llm(key!!, System.getProperty("llm.model") ?: Llm.DEFAULT_MODEL)
        val tools = buildJsonArray {
            add(Llm.tool("search_transcripts", "Full-text search over everything recorded.", mapOf("query" to ("string" to "words")), listOf("query")))
        }
        val messages = mutableListOf(
            Llm.system("You are Boswell, a personal assistant with a searchable record of the user's conversations. Be brief."),
            Llm.user("What did Sam say about the dentist appointment?"),
        )
        val t0 = System.currentTimeMillis()
        val first = llm.chat(messages, tools)
        val log = StringBuilder("round 1: ${System.currentTimeMillis() - t0} ms, tool calls ${first.toolCalls.map { it.name + it.arguments }}, cost ${first.cost}\n")
        assertTrue("expected a tool call", first.toolCalls.isNotEmpty())
        messages += first.message
        for (c in first.toolCalls) messages += Llm.toolResult(c.id,
            "2026-09-29 3:14 PM (conversation 42) Sam: the dentist moved my appointment to Thursday at nine, so I can't do the school run")
        val t1 = System.currentTimeMillis()
        val second = llm.chat(messages, tools)
        log.append("round 2: ${System.currentTimeMillis() - t1} ms, answer: ${second.text}, cost ${second.cost}\n")
        File(System.getProperty("llm.out") ?: "/tmp/llm_test.txt").writeText(log.toString())
        assertTrue(second.text.orEmpty().contains("Thursday", ignoreCase = true))
    }
}
