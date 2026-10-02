package dev.nate.uiagent.agent

import com.sun.net.httpserver.HttpServer
import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.LogicalLayout
import dev.nate.uiagent.Point
import dev.nate.uiagent.device.Device
import dev.nate.uiagent.device.DeviceController
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end over the wire: a real Koog executor against a canned OpenAI-compatible server.
 * Pins what simul needs from every request regardless of how Koog assembles it.
 */
class LlmWiringTest {

    private class NoopDevice : Device {
        override fun observe() = LogicalLayout(listOf(
            LogicalElement(0, "웹툰", null, listOf("clickable"), emptyList(), null, Point(1, 1), null)))
        override fun tap(x: Int, y: Int) {}
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
        override fun inputText(text: String) {}
        override fun keyBack() {}
        override fun sleep(ms: Long) {}
    }

    private class Seen(var auth: String? = null, val bodies: MutableList<String> = mutableListOf())

    /** Serves one reply per request: first a tap() call, then report(PASSED). */
    private fun withServer(body: (url: String, seen: Seen) -> Unit) {
        val seen = Seen()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            seen.auth = ex.requestHeaders.getFirst("Authorization")
            seen.bodies += ex.requestBody.readBytes().decodeToString()
            val call = if (seen.bodies.size == 1) """{"id":"c1","type":"function","function":{"name":"tap","arguments":"{\"target\":\"웹툰\"}"}}"""
                       else """{"id":"c2","type":"function","function":{"name":"report","arguments":"{\"status\":\"PASSED\",\"reason\":\"ok\"}"}}"""
            val reply = """{"id":"r","object":"chat.completion","created":1,"model":"qwen",
                "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[$call]}}],
                "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, reply.size.toLong())
            ex.responseBody.use { it.write(reply) }
        }
        server.start()
        try { body("http://127.0.0.1:${server.address.port}", seen) } finally { server.stop(0) }
    }

    private fun run(url: String, apiKey: String?): ScenarioSession.Verdict {
        val controller = DeviceController(NoopDevice(), settleIntervalMs = 0)
        val session = ScenarioSession(SimulLlm.executor(url, apiKey), controller, model = SimulLlm.model("qwen"))
        return session.runStep("Tap \"웹툰\"", controller.fullLayout())
    }

    @Test
    fun `requests carry simul's pinned params, the eval-validated tools and the full conversation`() = withServer { url, seen ->
        val v = run(url, apiKey = "sk-123")
        assertTrue(v.passed, v.reason)
        assertEquals("Bearer sk-123", seen.auth)
        assertEquals(2, seen.bodies.size)

        val first = seen.bodies[0]
        assertTrue(""""model":"qwen"""" in first, first.take(300))
        assertTrue(""""temperature":0.0""" in first, first.take(300))
        assertTrue(""""chat_template_kwargs":{"enable_thinking":false}""" in first,
            "every request must disable thinking — simul never wants thinking tokens: ${first.take(300)}")
        assertTrue(""""name":"report"""" in first && """"name":"scrollToFind"""" in first, "tool schemas must be included")
        assertTrue(""""role":"system"""" in first && SYSTEM_PROMPT_V1.lineSequence().first() in first)
        assertTrue("COMMAND: Tap" in first)

        // second request = first request's messages + the assistant's tool call + its result (append-only)
        val second = seen.bodies[1]
        assertTrue(""""tool_calls":[{"id":"c1"""" in second, second.take(600))
        assertTrue(""""role":"tool"""" in second && """"tool_call_id":"c1"""" in second, second.take(600))
        assertTrue("LAYOUT DIFF" in second || "NO CHANGE" in second, "tool result text must reach the model verbatim")
        assertTrue(second.startsWith(first.substringBefore("\"messages\"")), "request prefix must stay byte-stable")
    }

    @Test
    fun `no api key - nothing secret goes out (local llama-server)`() = withServer { url, seen ->
        run(url, apiKey = null)
        assertTrue(seen.auth == null || seen.auth == "Bearer " || seen.auth == "Bearer", "got ${seen.auth}")
    }
}
