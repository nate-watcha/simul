package dev.nate.uiagent.agent

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatTest {

    @Test
    fun `assistant reply with tool calls parses`() {
        val m = HttpChatClient.parseAssistantMessage(
            """{"choices":[{"message":{"role":"assistant","content":null,
                "tool_calls":[{"id":"call_1","type":"function",
                  "function":{"name":"tap","arguments":"{\"target\":\"웹툰\"}"}}]}}]}"""
        )
        assertEquals(1, m.toolCalls.size)
        assertEquals("tap", m.toolCalls[0].name)
        assertEquals("""{"target":"웹툰"}""", m.toolCalls[0].argumentsJson)
    }

    @Test
    fun `plain text reply parses`() {
        val m = HttpChatClient.parseAssistantMessage(
            """{"choices":[{"message":{"role":"assistant","content":"done"}}]}"""
        )
        assertEquals("done", m.content)
        assertTrue(m.toolCalls.isEmpty())
    }

    @Test
    fun `request encoding carries tool calls and tool results in OpenAI shape`() {
        val body = HttpChatClient.encodeRequest("qwen", listOf(
            ChatMessage("system", "sys"),
            ChatMessage("user", "cmd"),
            ChatMessage("assistant", null, listOf(ToolCall("c1", "tap", """{"target":"a"}"""))),
            ChatMessage("tool", "LAYOUT DIFF: ...", toolCallId = "c1"),
        ), ScenarioSession.TOOLS, 0.0)
        assertTrue(""""tool_calls":[{"id":"c1","type":"function","function":{"name":"tap"""" in body, body.take(400))
        assertTrue(""""role":"tool","content":"LAYOUT DIFF: ...","tool_call_id":"c1"""" in body)
        assertTrue(""""name":"report"""" in body, "tool schemas must be included")
    }

    @Test
    fun `every request carries enable_thinking false — simul never wants thinking tokens`() {
        val body = HttpChatClient.encodeRequest("qwen", listOf(ChatMessage("system", "s")),
            ScenarioSession.TOOLS, 0.0)
        assertTrue(""""chat_template_kwargs":{"enable_thinking":false}""" in body, body.take(200))
    }

    /** Serve one canned reply and capture the Authorization header of the request. */
    private fun withServer(body: (url: String, seenAuth: () -> String?) -> Unit) {
        var auth: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            auth = ex.requestHeaders.getFirst("Authorization")
            val reply = """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""".toByteArray()
            ex.sendResponseHeaders(200, reply.size.toLong())
            ex.responseBody.use { it.write(reply) }
        }
        server.start()
        try { body("http://127.0.0.1:${server.address.port}") { auth } } finally { server.stop(0) }
    }

    @Test
    fun `api key goes out as a bearer token`() = withServer { url, seenAuth ->
        HttpChatClient(url, "m", apiKey = "sk-123").complete(listOf(ChatMessage("user", "hi")), ScenarioSession.TOOLS, 0.0)
        assertEquals("Bearer sk-123", seenAuth())
    }

    @Test
    fun `no api key - no Authorization header (local llama-server)`() = withServer { url, seenAuth ->
        HttpChatClient(url, "m").complete(listOf(ChatMessage("user", "hi")), ScenarioSession.TOOLS, 0.0)
        assertNull(seenAuth())
    }
}
