package dev.nate.uiagent.agent

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
