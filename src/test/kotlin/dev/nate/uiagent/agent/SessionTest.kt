package dev.nate.uiagent.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.LogicalLayout
import dev.nate.uiagent.Point
import dev.nate.uiagent.device.Device
import dev.nate.uiagent.device.DeviceController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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

    /** Scripted Koog executor: pops one reply per call, records every request's message list. */
    private class FakeExecutor(vararg replies: Message.Assistant) : PromptExecutor() {
        val queue = ArrayDeque(replies.toList())
        val requests = mutableListOf<List<Message>>()
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            requests += prompt.messages
            return queue.removeFirst()
        }
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = emptyFlow()
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = throw UnsupportedOperationException()
        override fun close() {}
    }

    private val meta get() = ResponseMetaInfo.create(KoogClock.System)

    private fun call(tool: String, args: String, id: String) =
        Message.Assistant(parts = listOf(MessagePart.Tool.Call(id = id, tool = tool, args = args)), metaInfo = meta)

    private fun tapCall(target: String, id: String = "c1") = call("tap", """{"target":"$target"}""", id)

    private fun reportCall(status: String = "PASSED", id: String = "c9") =
        call("report", """{"status":"$status","reason":"ok"}""", id)

    private fun text(content: String) = Message.Assistant(content = content, metaInfo = meta)

    /** Role as the OpenAI wire sees it: Koog carries tool results as user-side parts. */
    private fun kind(m: Message): String =
        if (m is Message.User && m.parts.any { it is MessagePart.Tool.Result }) "tool" else m.role.name.lowercase()

    private fun toolResults(m: Message) = m.parts.filterIsInstance<MessagePart.Tool.Result>()
    private fun lastToolResult(request: List<Message>) = toolResults(request.last { kind(it) == "tool" }).last()

    private fun screen() = LogicalLayout(listOf(
        LogicalElement(0, "웹툰", null, listOf("clickable"), emptyList(), null, Point(360, 1176), null),
        LogicalElement(1, "찾기", null, listOf("clickable"), emptyList(), null, Point(500, 1176), null),
    ))

    private fun controller(dev: FakeDevice) = DeviceController(dev, settleIntervalMs = 0).also { it.fullLayout() }

    @Test
    fun `steps share one growing conversation - later steps send the bare command`() {
        val dev = FakeDevice(screen())
        val llm = FakeExecutor(tapCall("웹툰"), reportCall(), tapCall("찾기", "c2"), reportCall(id = "c10"))
        val session = ScenarioSession(llm, controller(dev))

        val v1 = session.runStep("Tap the \"웹툰\" tab", "[LAYOUT]")
        val v2 = session.runStep("Tap the \"찾기\" tab", null)

        assertTrue(v1.passed && v2.passed)
        assertEquals(listOf("tap 360 1176", "tap 500 1176"), dev.gestures)
        assertEquals(4, llm.requests.size, "report() ends the step — no closing text turn")

        // request 1: system + first user (command + layout)
        val r1 = llm.requests[0]
        assertEquals(listOf("system", "user"), r1.map(::kind))
        assertEquals(SYSTEM_PROMPT_V1, r1[0].textContent())
        assertTrue(r1[1].textContent().startsWith("COMMAND: ") && "INITIAL LAYOUT:\n[LAYOUT]" in r1[1].textContent())
        // request 3 (step 2 opening): FULL history (ChatMemory) + new bare command — append-only growth
        val r3 = llm.requests[2]
        assertEquals(listOf("system", "user", "assistant", "tool", "assistant", "tool", "user"), r3.map(::kind))
        assertEquals(r1[1], r3[1], "earlier messages must be identical (prefix cache)")
        assertEquals("COMMAND: Tap the \"찾기\" tab", r3.last().textContent())
        // tool result answers the right call id
        assertEquals("c1", toolResults(r3[3]).single().id)
    }

    @Test
    fun `text reply without tools fails the step but keeps the conversation intact`() {
        val dev = FakeDevice(screen())
        val llm = FakeExecutor(text("I think I am done"), reportCall())
        val session = ScenarioSession(llm, controller(dev))
        val v = session.runStep("Tap \"웹툰\"", "[L]")
        assertTrue(!v.passed && "no report()" in v.reason && "I think I am done" in v.reason, v.reason)

        val v2 = session.runStep("Verify \"웹툰\"", null)
        assertTrue(v2.passed)
        assertEquals(listOf("system", "user", "assistant", "user"), llm.requests[1].map(::kind),
            "the failed step's exchange stays in the conversation")
    }

    @Test
    fun `turn cap per step`() {
        val dev = FakeDevice(screen())
        val llm = FakeExecutor(tapCall("웹툰", "a"), tapCall("찾기", "b"), tapCall("웹툰", "c"))
        val session = ScenarioSession(llm, controller(dev), maxTurnsPerStep = 3)
        val v = session.runStep("loop forever", "[L]")
        assertTrue(!v.passed && "3 turns" in v.reason, v.reason)
        assertEquals(3, llm.requests.size)
    }

    @Test
    fun `report FAILED verdict propagates`() {
        val dev = FakeDevice(screen())
        val llm = FakeExecutor(reportCall(status = "FAILED"))
        val session = ScenarioSession(llm, controller(dev))
        val v = session.runStep("Verify nothing", "[L]")
        assertTrue(!v.passed)
    }

    @Test
    fun `criterion is withheld from the command and appended to the first tool result`() {
        val dev = FakeDevice(screen())
        val llm = FakeExecutor(tapCall("웹툰"), reportCall())
        val session = ScenarioSession(llm, controller(dev))

        val v = session.runStep("Tap the \"웹툰\" tab", "[L]", criterion = "the \"웹툰 홈\" screen should appear")

        assertTrue(v.passed)
        val command = llm.requests[0].last()
        assertEquals("user", kind(command))
        assertTrue("웹툰 홈" !in command.textContent(), "criterion must not leak into the COMMAND (blind action)")
        val toolResult = lastToolResult(llm.requests[1]).output
        assertTrue("EXPECTED RESULT: the \"웹툰 홈\" screen should appear" in toolResult, toolResult)
        assertTrue("do not take extra actions" in toolResult)
        assertTrue(!toolResult.startsWith("\"") && "\\n" !in toolResult,
            "tool results reach the model as plain text, not JSON-quoted: $toolResult")
    }

    @Test
    fun `report before any action is deflected once with the criterion, then accepted`() {
        val dev = FakeDevice(screen())
        val llm = FakeExecutor(reportCall(id = "early"), reportCall(status = "FAILED", id = "again"))
        val session = ScenarioSession(llm, controller(dev))

        val v = session.runStep("Tap the \"웹툰\" tab", "[L]", criterion = "something should appear")

        assertTrue(!v.passed, "the second (informed) report decides")
        val gate = lastToolResult(llm.requests[1]).output
        assertTrue("EXPECTED RESULT: something should appear" in gate, gate)
        assertTrue(dev.gestures.isEmpty(), "deflection must not touch the device")
    }

    @Test
    fun `live log traces each turn with llm and tool timings`() {
        val dev = FakeDevice(screen())
        val llm = FakeExecutor(tapCall("웹툰"), reportCall())
        val logged = mutableListOf<String>()
        val session = ScenarioSession(llm, controller(dev), log = logged::add)

        session.runStep("Tap the \"웹툰\" tab", "[L]")

        assertEquals(2, logged.size, "$logged")
        assertTrue(logged[0].startsWith("turn 1: llm ") && "tap(웹툰)" in logged[0], logged[0])
        assertTrue(logged[1].startsWith("turn 2: llm ") && "report(PASSED)" in logged[1], logged[1])
    }

    @Test
    fun `guard results are flagged in the turn log`() {
        val dev = FakeDevice(screen())
        val llm = FakeExecutor(tapCall("우주정거장"), reportCall(status = "FAILED"))
        val logged = mutableListOf<String>()
        val session = ScenarioSession(llm, controller(dev), log = logged::add)

        session.runStep("Tap the \"우주정거장\" button", "[L]")

        assertTrue("tap(우주정거장)" in logged[0] && "← ERROR" in logged[0], logged[0])
    }

    @Test
    fun `missing tool argument produces an error tool result, not a crash`() {
        val dev = FakeDevice(screen())
        val llm = FakeExecutor(call("tap", """{"label":"웹툰"}""", "x"), reportCall(status = "FAILED"))
        val session = ScenarioSession(llm, controller(dev))
        session.runStep("Tap", "[L]")
        val toolResult = lastToolResult(llm.requests[1]).output
        assertTrue(toolResult.startsWith("ERROR: tool 'tap' requires parameter 'target'"), toolResult)
        assertTrue(dev.gestures.isEmpty())
    }

    @Test
    fun `bad tool arguments produce an error tool result, not a crash`() {
        val dev = FakeDevice(screen())
        val llm = FakeExecutor(call("tap", "not-json", "x"), reportCall(status = "FAILED"))
        val session = ScenarioSession(llm, controller(dev))
        val v = session.runStep("Tap", "[L]")
        assertTrue(!v.passed && "agent error" !in v.reason, v.reason)
        val toolResult = lastToolResult(llm.requests[1])
        assertTrue(toolResult.isError, "koog marks the unparsable call as an error result: ${toolResult.output}")
        assertTrue(dev.gestures.isEmpty())
    }
}
