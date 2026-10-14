package dev.nate.uiagent.agent

import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmStatsTest {

    @Test
    fun `sum adds calls, time and tokens and keeps the peak prompt`() {
        val a = LlmStats(calls = 3, durationMs = 1200, promptTokens = 20_000, completionTokens = 300, peakPromptTokens = 9_000)
        val b = LlmStats(calls = 2, durationMs = 800, promptTokens = 25_000, completionTokens = 100, peakPromptTokens = 13_000)
        assertEquals(LlmStats(5, 2000, 45_000, 400, 13_000), a + b)
        assertEquals(a + b, LlmStats.sum(listOf(a, null, b)))
        assertNull(LlmStats.sum(listOf(null, null)), "nothing consulted the model → no stats, not zeros")
    }

    @Test
    fun `summary reads as one line with the model's share of wall time`() {
        val s = LlmStats(8, 41_200, 52_300, 1_200, 9_100).summary(totalMs = 65_000)
        assertEquals("llm 8 calls · 41.2s (63% of 65.0s) · prompt 52.3k tok (peak 9.1k) · completion 1.2k tok", s)
        assertEquals("llm 1 call · 2.0s · tokens not reported by the endpoint", LlmStats(1, 2000, 0, 0, 0).summary())
        assertEquals("1.2M", LlmStats.tokens(1_234_567))
    }

    @Test
    fun `turn log accumulates per-step stats from the response usage and resets between steps`() {
        val lines = mutableListOf<String>()
        val log = TurnLog(lines::add)
        log.llmStarted()
        log.llmCompleted(1, ResponseMetaInfo.Empty.copy(inputTokensCount = 9_100, outputTokensCount = 45))
        log.tool("tap(웹툰) 0.3s")
        log.llmStarted()
        log.llmCompleted(1, ResponseMetaInfo.Empty.copy(inputTokensCount = 9_400, outputTokensCount = 20))
        log.tool("report(PASSED) 0.0s")
        log.flush()

        val s = log.stats
        assertEquals(2, s.calls)
        assertEquals(18_500, s.promptTokens)
        assertEquals(65, s.completionTokens)
        assertEquals(9_400, s.peakPromptTokens)
        assertTrue(lines[0].startsWith("turn 1: llm ") && "[9.1k→45 tok]" in lines[0] && "tap(웹툰)" in lines[0], lines[0])

        log.reset()
        assertEquals(LlmStats.ZERO, log.stats)
        // an endpoint without usage: calls and time still count, tokens stay zero
        log.llmStarted(); log.llmCompleted(0, null); log.flush()
        assertEquals(1, log.stats.calls)
        assertEquals(0, log.stats.promptTokens)
        assertTrue("[" !in lines.last(), lines.last())
    }
}
