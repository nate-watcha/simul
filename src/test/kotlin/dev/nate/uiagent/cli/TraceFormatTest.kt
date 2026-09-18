package dev.nate.uiagent.cli

import dev.nate.uiagent.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraceFormatTest {

    private val sample = TraceFile(
        scenario = "browse-greenbook",
        agentVersion = "0.2.0",
        appVersionName = "5.4.0",
        steps = listOf(
            TraceStep(
                text = "구독 탭에서 \"그린북\" 콘텐츠를 찾아 클릭해",
                actions = listOf(
                    TraceAction(
                        "scrollToFind",
                        args = linkedMapOf("scrollable" to "recycler_view", "target" to "그린북", "direction" to "down"),
                    ),
                    TraceAction("tap", target = TraceTarget(null, "그린북", Point(360, 815))),
                ),
                evidence = listOf("재생하기"),
            ),
            TraceStep("\"재생하기\"를 눌러", listOf(TraceAction("back")), emptyList()),
        ),
    )

    @Test
    fun `round-trips through json`() {
        assertEquals(sample, TraceJson.parse(TraceJson.render(sample)))
    }

    @Test
    fun `rendering is deterministic and diff-friendly`() {
        val a = TraceJson.render(sample)
        val b = TraceJson.render(sample)
        assertEquals(a, b)
        assertTrue(a.endsWith("\n"))
        assertTrue("  \"scenario\"" in a, "2-space indent expected")
        // fixed key order: scenario < recordedWith < steps; text < actions < evidence
        assertTrue(a.indexOf("\"scenario\"") < a.indexOf("\"recordedWith\""))
        assertTrue(a.indexOf("\"recordedWith\"") < a.indexOf("\"steps\""))
        assertTrue(a.indexOf("\"text\"") < a.indexOf("\"actions\""))
    }

    @Test
    fun `no volatile values in the trace`() {
        val a = TraceJson.render(sample)
        for (banned in listOf("durationMs", "\"ts\"", "timestamp", "startedAt")) {
            assertFalse(banned in a, "volatile field '$banned' must not be in the trace")
        }
    }

    @Test
    fun `null resourceId and lastCoords are serialized explicitly`() {
        val a = TraceJson.render(sample)
        assertTrue("\"resourceId\": null" in a)
        assertTrue("\"lastCoords\": [" in a)
    }
}
