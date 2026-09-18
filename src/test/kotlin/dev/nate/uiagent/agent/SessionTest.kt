package dev.nate.uiagent.agent

import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.LogicalLayout
import dev.nate.uiagent.Point
import dev.nate.uiagent.device.Device
import dev.nate.uiagent.device.DeviceController
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionTest {

    private class FakeDevice(private val layout: LogicalLayout) : Device {
        val gestures = mutableListOf<String>()
        override fun observe(): LogicalLayout = layout
        override fun tap(x: Int, y: Int) { gestures += "tap $x $y" }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) { gestures += "swipe" }
        override fun inputText(text: String) { gestures += "text $text" }
        override fun keyBack() { gestures += "back" }
        override fun sleep(ms: Long) {}
    }

    /** Scripted client: pops one reply per call, records every request's message list. */
    private class FakeChat(vararg replies: ChatMessage) : ChatClient {
        val queue = ArrayDeque(replies.toList())
        val requests = mutableListOf<List<ChatMessage>>()
        override fun complete(messages: List<ChatMessage>, tools: JsonArray, temperature: Double): ChatResponse {
            requests += messages.toList()
            return ChatResponse(queue.removeFirst())
        }
    }

    private fun tapCall(target: String, id: String = "c1") =
        ChatMessage("assistant", null, listOf(ToolCall(id, "tap", """{"target":"$target"}""")))

    private fun reportCall(status: String = "PASSED", id: String = "c9") =
        ChatMessage("assistant", null, listOf(ToolCall(id, "report", """{"status":"$status","reason":"ok"}""")))

    private fun screen() = LogicalLayout(listOf(
        LogicalElement(0, "웹툰", null, listOf("clickable"), emptyList(), null, Point(360, 1176), null),
        LogicalElement(1, "찾기", null, listOf("clickable"), emptyList(), null, Point(500, 1176), null),
    ))

    private fun controller(dev: FakeDevice) = DeviceController(dev, settleIntervalMs = 0).also { it.fullLayout() }

    @Test
    fun `steps share one growing conversation - later steps send the bare command`() {
        val dev = FakeDevice(screen())
        val chat = FakeChat(tapCall("웹툰"), reportCall(), tapCall("찾기", "c2"), reportCall(id = "c10"))
        val session = ScenarioSession(chat, controller(dev))

        val v1 = session.runStep("Tap the \"웹툰\" tab", "[LAYOUT]")
        val v2 = session.runStep("Tap the \"찾기\" tab", null)

        assertTrue(v1.passed && v2.passed)
        assertEquals(listOf("tap 360 1176", "tap 500 1176"), dev.gestures)

        // request 1: system + first user (command + layout)
        val r1 = chat.requests[0]
        assertEquals(listOf("system", "user"), r1.map { it.role })
        assertTrue(r1[1].content!!.startsWith("COMMAND: ") && "INITIAL LAYOUT:\n[LAYOUT]" in r1[1].content!!)
        // request 3 (step 2 opening): FULL history + new bare command — append-only growth
        val r3 = chat.requests[2]
        assertEquals(listOf("system", "user", "assistant", "tool", "assistant", "tool", "user"), r3.map { it.role })
        assertEquals(r1[1].content, r3[1].content, "earlier messages must be byte-identical (prefix cache)")
        assertEquals("COMMAND: Tap the \"찾기\" tab", r3.last().content)
        // tool result answers the right call id
        assertEquals("c1", r3[3].toolCallId)
    }

    @Test
    fun `text reply without tools fails the step but keeps the conversation intact`() {
        val dev = FakeDevice(screen())
        val chat = FakeChat(ChatMessage("assistant", "I think I am done"))
        val session = ScenarioSession(chat, controller(dev))
        val v = session.runStep("Tap \"웹툰\"", "[L]")
        assertTrue(!v.passed && "no report()" in v.reason)
    }

    @Test
    fun `turn cap per step`() {
        val dev = FakeDevice(screen())
        val chat = FakeChat(tapCall("웹툰", "a"), tapCall("찾기", "b"), tapCall("웹툰", "c"))
        val session = ScenarioSession(chat, controller(dev), maxTurnsPerStep = 3)
        val v = session.runStep("loop forever", "[L]")
        assertTrue(!v.passed && "3 turns" in v.reason, v.reason)
    }

    @Test
    fun `report FAILED verdict propagates`() {
        val dev = FakeDevice(screen())
        val chat = FakeChat(reportCall(status = "FAILED"))
        val session = ScenarioSession(chat, controller(dev))
        val v = session.runStep("Verify nothing", "[L]")
        assertTrue(!v.passed)
    }

    @Test
    fun `criterion is withheld from the command and appended to the first tool result`() {
        val dev = FakeDevice(screen())
        val chat = FakeChat(tapCall("웹툰"), reportCall())
        val session = ScenarioSession(chat, controller(dev))

        val v = session.runStep("Tap the \"웹툰\" tab", "[L]", criterion = "the \"웹툰 홈\" screen should appear")

        assertTrue(v.passed)
        val command = chat.requests[0].last()
        assertEquals("user", command.role)
        assertTrue("웹툰 홈" !in command.content!!, "criterion must not leak into the COMMAND (blind action)")
        val toolResult = chat.requests[1].last { it.role == "tool" }
        assertTrue("EXPECTED RESULT: the \"웹툰 홈\" screen should appear" in toolResult.content!!, toolResult.content!!)
        assertTrue("do not take extra actions" in toolResult.content!!)
    }

    @Test
    fun `report before any action is deflected once with the criterion, then accepted`() {
        val dev = FakeDevice(screen())
        val chat = FakeChat(reportCall(id = "early"), reportCall(status = "FAILED", id = "again"))
        val session = ScenarioSession(chat, controller(dev))

        val v = session.runStep("Tap the \"웹툰\" tab", "[L]", criterion = "something should appear")

        assertTrue(!v.passed, "the second (informed) report decides")
        val gate = chat.requests[1].last { it.role == "tool" }
        assertTrue("EXPECTED RESULT: something should appear" in gate.content!!, gate.content!!)
        assertTrue(dev.gestures.isEmpty(), "deflection must not touch the device")
    }

    @Test
    fun `live log traces each turn with llm and tool timings`() {
        val dev = FakeDevice(screen())
        val chat = FakeChat(tapCall("웹툰"), reportCall())
        val logged = mutableListOf<String>()
        val session = ScenarioSession(chat, controller(dev), log = logged::add)

        session.runStep("Tap the \"웹툰\" tab", "[L]")

        assertEquals(2, logged.size, "$logged")
        assertTrue(logged[0].startsWith("turn 1: llm ") && "tap(웹툰)" in logged[0], logged[0])
        assertTrue("report(PASSED)" in logged[1], logged[1])
    }

    @Test
    fun `guard results are flagged in the turn log`() {
        val dev = FakeDevice(screen())
        val chat = FakeChat(tapCall("우주정거장"), reportCall(status = "FAILED"))
        val logged = mutableListOf<String>()
        val session = ScenarioSession(chat, controller(dev), log = logged::add)

        session.runStep("Tap the \"우주정거장\" button", "[L]")

        assertTrue("tap(우주정거장)" in logged[0] && "← ERROR" in logged[0], logged[0])
    }

    @Test
    fun `bad tool arguments produce an error tool result, not a crash`() {
        val dev = FakeDevice(screen())
        val chat = FakeChat(
            ChatMessage("assistant", null, listOf(ToolCall("x", "tap", "not-json"))),
            reportCall(status = "FAILED"),
        )
        val session = ScenarioSession(chat, controller(dev))
        session.runStep("Tap", "[L]")
        val toolResult = chat.requests[1].last { it.role == "tool" }
        assertTrue("ERROR" in toolResult.content!!, toolResult.content!!)
        assertTrue(dev.gestures.isEmpty())
    }
}
