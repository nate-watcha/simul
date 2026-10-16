package dev.nate.uiagent.agent

import ai.koog.prompt.message.ResponseMetaInfo
import dev.nate.uiagent.secs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * What one step (or, summed, one scenario) cost in model terms: wall time spent inside LLM
 * calls and the token counts the endpoint reported in `usage`. Prompt tokens are the FULL
 * context of each call (the conversation is append-only), so their sum grows quadratically
 * with turns and mostly hits llama.cpp's prefix cache; [peakPromptTokens] — the largest single
 * prompt — is the number to watch against the model's context window. Zero tokens with calls
 * means the endpoint did not report usage.
 */
data class LlmStats(
    val calls: Int,
    val durationMs: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val peakPromptTokens: Long,
) {
    operator fun plus(o: LlmStats) = LlmStats(
        calls + o.calls, durationMs + o.durationMs, promptTokens + o.promptTokens,
        completionTokens + o.completionTokens, maxOf(peakPromptTokens, o.peakPromptTokens),
    )

    val tokensReported get() = promptTokens > 0 || completionTokens > 0

    /**
     * One-line human summary, e.g. `llm 8 calls · 41.2s (63% of 65.0s) · prompt 52.3k tok
     * (peak 9.1k) · completion 1.2k tok`; [totalMs] (the step/scenario wall time) adds the share.
     */
    fun summary(totalMs: Long? = null): String = buildString {
        append("llm $calls call").append(if (calls == 1) "" else "s")
        append(" · ").append(secs(durationMs))
        if (totalMs != null && totalMs > 0) append(" (${durationMs * 100 / totalMs}% of ${secs(totalMs)})")
        if (tokensReported) {
            append(" · prompt ${tokens(promptTokens)} tok (peak ${tokens(peakPromptTokens)})")
            append(" · completion ${tokens(completionTokens)} tok")
        } else append(" · tokens not reported by the endpoint")
    }

    companion object {
        val ZERO = LlmStats(0, 0, 0, 0, 0)

        /** Sum of the non-null stats, or null when nothing used the model. */
        fun sum(parts: Iterable<LlmStats?>): LlmStats? = parts.filterNotNull().takeIf { it.isNotEmpty() }?.reduce { a, b -> a + b }

        /** `845` / `9.1k` / `1.2M` */
        fun tokens(n: Long): String = when {
            n < 1_000 -> n.toString()
            n < 1_000_000 -> "%.1fk".format(n / 1000.0)
            else -> "%.1fM".format(n / 1_000_000.0)
        }
    }
}

/**
 * Live per-turn log line: `turn N: llm 1.2s [9.1k→45 tok] → tap(보관함) 0.3s, report(PASSED) 0.1s`.
 * LLM timing comes from Koog's EventHandler, token counts from the response's usage metadata,
 * tool parts from the tools themselves; a line is flushed when the next LLM call starts, or at
 * the end of the step. The same hooks accumulate the step's [LlmStats].
 */
internal class TurnLog(private val sink: (String) -> Unit) {
    var turns: Int = 0
        private set
    private var llmStartedAt = 0L
    private var llmMs = 0L
    private var open = false
    private val parts = mutableListOf<String>()
    private var lastTokens: String? = null
    private var llmMsTotal = 0L
    private var promptTokens = 0L
    private var completionTokens = 0L
    private var peakPromptTokens = 0L

    /** Model usage accumulated since [reset] — i.e. of the current step. */
    val stats: LlmStats get() = LlmStats(turns, llmMsTotal, promptTokens, completionTokens, peakPromptTokens)

    fun reset() {
        turns = 0; open = false; parts.clear()
        llmMsTotal = 0; promptTokens = 0; completionTokens = 0; peakPromptTokens = 0; lastTokens = null
    }

    fun llmStarted() {
        flush()
        llmStartedAt = System.currentTimeMillis()
    }

    fun llmCompleted(toolCalls: Int, meta: ResponseMetaInfo? = null) {
        turns++
        llmMs = System.currentTimeMillis() - llmStartedAt
        llmMsTotal += llmMs
        open = true
        val inTok = meta?.inputTokensCount?.toLong()
        val outTok = meta?.outputTokensCount?.toLong()
        if (inTok != null) { promptTokens += inTok; peakPromptTokens = maxOf(peakPromptTokens, inTok) }
        if (outTok != null) completionTokens += outTok
        lastTokens = if (inTok != null || outTok != null) "${LlmStats.tokens(inTok ?: 0)}→${LlmStats.tokens(outTok ?: 0)} tok" else null
        if (toolCalls == 0) parts += "text reply, no tool call"
    }

    fun tool(part: String) { parts += part }

    fun flush() {
        if (!open) return
        val tok = lastTokens?.let { " [$it]" } ?: ""
        sink("turn $turns: llm ${secs(llmMs)}$tok → ${parts.joinToString(", ")}")
        parts.clear()
        open = false
    }
}

/** `llm` object of report.json / summary.json — fixed key order, omitted entirely when null. */
fun llmJson(l: LlmStats): JsonObject = buildJsonObject {
    put("calls", l.calls)
    put("durationMs", l.durationMs)
    put("promptTokens", l.promptTokens)
    put("completionTokens", l.completionTokens)
    put("peakPromptTokens", l.peakPromptTokens)
}

fun llmFromJson(o: JsonObject?): LlmStats? {
    o ?: return null
    fun n(k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: 0L
    return LlmStats(n("calls").toInt(), n("durationMs"), n("promptTokens"), n("completionTokens"), n("peakPromptTokens"))
}
