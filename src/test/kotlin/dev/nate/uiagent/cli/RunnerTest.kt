package dev.nate.uiagent.cli

import dev.nate.uiagent.Point
import dev.nate.uiagent.device.DeviceController
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunnerTest {

    private val tabA = el("웹툰", interactions = listOf("clickable"), center = Point(360, 1176), id = 0)
    private val home = el("웹툰 홈", id = 1, center = Point(360, 300))
    private val screenA = layout(tabA)
    private val screenB = layout(tabA.copy(state = listOf("selected")), home)

    private fun tempDir(): File = File.createTempFile("uiagent-test", "").let {
        it.delete(); it.mkdirs(); it
    }

    private class Harness(dev: FakeDevice, behavior: (DeviceController, String) -> StepVerdict) {
        val listener = SwitchableListener()
        val controller = DeviceController(dev, settleIntervalMs = 0, listener = listener)
        val executor = ScriptedExecutor { text -> behavior(controller, text) }
        val ops = FakeOps()
        val reportDir = File.createTempFile("uiagent-report", "").let { it.delete(); it.mkdirs(); it }
        val runner = ScenarioRunner(controller, listener, ops, executor, reportDir, app = "com.example")
    }

    private val scenario = Scenario(
        name = "webtoon-tab",
        tags = listOf("smoke"),
        setup = Scenario.Setup(deeplink = "app://home", launch = false),
        steps = listOf("Tap the \"웹툰\" tab", "Verify \"웹툰 홈\" is shown"),
    )

    // ------------------------------------------------------------------ llm / record

    @Test
    fun `llm mode records a trace when all steps pass`() {
        val dev = FakeDevice(screenA, screenA, screenB, screenB)
        val h = Harness(dev) { c, text ->
            if (text.startsWith("Tap")) {
                c.tap("웹툰")
                StepVerdict(true, "tab selected")
            } else {
                throw AssertionError("anchored Verify must be runner-judged, not reach the LLM")
            }
        }

        val result = h.runner.run(scenario, trace = null, mode = RunMode.LLM)

        assertEquals(StepStatus.PASSED, result.status)
        assertEquals(listOf("llm", "runner"), result.steps.map { it.mode })
        assertEquals(1, h.executor.calls, "the Verify step must not spend an LLM turn")
        assertEquals(1, h.ops.setups.size, "setup must run once, by the runner not the model")

        val trace = assertNotNull(result.updatedTrace)
        assertEquals("webtoon-tab", trace.scenario)
        assertEquals("5.4.0", trace.appVersionName)
        assertEquals(2, trace.steps.size)
        val tap = trace.steps[0].actions.single()
        assertEquals("tap", tap.tool)
        assertEquals("웹툰", tap.target?.label)
        assertEquals(Point(360, 1176), tap.target?.lastCoords)
        assertEquals(listOf("웹툰"), trace.steps[0].evidence, "quoted label is the whole evidence")
        assertTrue(File(h.reportDir, "report.json").isFile)
    }

    @Test
    fun `failed step skips the rest and records no trace`() {
        val dev = FakeDevice(screenA, screenA)
        val h = Harness(dev) { _, text ->
            if (text.startsWith("Tap")) StepVerdict(false, "element not found") else StepVerdict(true, "unreachable")
        }

        val result = h.runner.run(scenario, trace = null, mode = RunMode.LLM)

        assertEquals(StepStatus.FAILED, result.status)
        assertEquals(listOf(StepStatus.FAILED, StepStatus.SKIPPED), result.steps.map { it.status })
        assertNull(result.steps[1].mode)
        assertNull(result.updatedTrace)
        assertEquals(1, h.executor.calls, "skipped steps must not reach the LLM")
    }

    // ------------------------------------------------------------------ runner verify / criterion

    @Test
    fun `anchored Verify FAILS deterministically when the anchor is absent`() {
        val dev = FakeDevice(screenA)
        val h = Harness(dev) { _, _ -> throw AssertionError("LLM must not judge an anchored Verify") }
        val s = scenario.copy(steps = listOf("Verify \"웹툰 홈\" is shown"))

        val result = h.runner.run(s, trace = null, mode = RunMode.LLM)

        assertEquals(StepStatus.FAILED, result.status)
        assertEquals("runner", result.steps[0].mode)
        assertTrue("not on screen" in result.steps[0].reason!!, "${result.steps[0].reason}")
        assertEquals(0, h.executor.calls)
    }

    @Test
    fun `negated Verify asserts absence`() {
        val dev = FakeDevice(screenA) // screenA has no "웹툰 홈"
        val h = Harness(dev) { _, _ -> throw AssertionError("no LLM") }
        val pass = h.runner.run(
            scenario.copy(steps = listOf("Verify the \"웹툰 홈\" screen is not shown")),
            trace = null, mode = RunMode.LLM,
        )
        assertEquals(StepStatus.PASSED, pass.status)

        val dev2 = FakeDevice(screenB) // "웹툰 홈" present → absence assertion fails
        val h2 = Harness(dev2) { _, _ -> throw AssertionError("no LLM") }
        val fail = h2.runner.run(
            scenario.copy(steps = listOf("Verify the \"웹툰 홈\" screen is not shown")),
            trace = null, mode = RunMode.LLM,
        )
        assertEquals(StepStatus.FAILED, fail.status)
        assertTrue("unexpectedly" in fail.steps[0].reason!!)
    }

    @Test
    fun `Verify with a state keyword checks the state, not mere presence`() {
        val dev = FakeDevice(screenA) // 웹툰 present but NOT selected
        val h = Harness(dev) { _, _ -> throw AssertionError("no LLM") }
        val result = h.runner.run(
            scenario.copy(steps = listOf("Verify the \"웹툰\" tab is selected")),
            trace = null, mode = RunMode.LLM,
        )
        assertEquals(StepStatus.FAILED, result.status)
        assertTrue("not selected" in result.steps[0].reason!!, "${result.steps[0].reason}")
    }

    @Test
    fun `Verify without quoted anchor falls back to the LLM`() {
        val dev = FakeDevice(screenB)
        val h = Harness(dev) { _, _ -> StepVerdict(true, "judged by llm") }
        val result = h.runner.run(
            scenario.copy(steps = listOf("Verify the home screen looks normal")),
            trace = null, mode = RunMode.LLM,
        )
        assertEquals(StepStatus.PASSED, result.status)
        assertEquals("llm", result.steps[0].mode)
        assertEquals(1, h.executor.calls)
    }

    @Test
    fun `criterion is split from the command and passed separately, full text stays in the trace`() {
        val dev = FakeDevice(screenA, screenA, screenB, screenB)
        val h = Harness(dev) { c, text ->
            assertEquals("Tap the \"웹툰\" tab", text, "criterion must not reach the COMMAND")
            c.tap("웹툰")
            StepVerdict(true, "ok")
        }
        val step = "Tap the \"웹툰\" tab :: the \"웹툰 홈\" screen should appear"
        val result = h.runner.run(scenario.copy(steps = listOf(step)), trace = null, mode = RunMode.LLM)

        assertEquals(StepStatus.PASSED, result.status)
        assertEquals(listOf<String?>("the \"웹툰 홈\" screen should appear"), h.executor.criteria)
        val ts = assertNotNull(result.updatedTrace).steps.single()
        assertEquals(step, ts.text, "trace keeps the full step text (replay matches on it)")
        assertTrue("웹툰 홈" in ts.evidence, "criterion anchor must be harvested as evidence: ${ts.evidence}")
    }

    @Test
    fun `criterion anchor missing on screen fails the step even when the LLM passed it`() {
        val dev = FakeDevice(screenA, screenA, screenB, screenB)
        val h = Harness(dev) { c, _ ->
            c.tap("웹툰")
            StepVerdict(true, "looks fine to me")
        }
        val step = "Tap the \"웹툰\" tab :: the \"없는화면\" title should appear"
        val result = h.runner.run(scenario.copy(steps = listOf(step)), trace = null, mode = RunMode.LLM)

        assertEquals(StepStatus.FAILED, result.status)
        assertTrue("criterion anchor not on screen" in result.steps[0].reason!!, "${result.steps[0].reason}")
        assertNull(result.updatedTrace, "an unverifiable criterion must not be committed to a trace")
    }

    // ------------------------------------------------------------------ replay

    private val goodTrace = TraceFile(
        scenario = "webtoon-tab",
        agentVersion = AGENT_VERSION,
        appVersionName = "5.4.0",
        steps = listOf(
            TraceStep(
                "Tap the \"웹툰\" tab",
                listOf(TraceAction("tap", target = TraceTarget(null, "웹툰", Point(360, 1176)))),
                listOf("웹툰 홈"),
            ),
            TraceStep("Verify \"웹툰 홈\" is shown", emptyList(), listOf("웹툰 홈")),
        ),
    )

    @Test
    fun `replay passes with zero LLM calls and no trace rewrite`() {
        val dev = FakeDevice(screenA, screenA, screenB, screenB)
        val listener = SwitchableListener()
        val controller = DeviceController(dev, settleIntervalMs = 0, listener = listener)
        val reportDir = tempDir()
        val runner = ScenarioRunner(controller, listener, FakeOps(), neverCalledExecutor, reportDir, "com.example")

        val result = runner.run(scenario, goodTrace, RunMode.REPLAY)

        assertEquals(StepStatus.PASSED, result.status)
        assertEquals(listOf("replay", "replay"), result.steps.map { it.mode })
        assertNull(result.updatedTrace, "clean replay must not rewrite the trace")
    }

    @Test
    fun `broken replay step FAILS without any LLM fallback`() {
        val brokenTrace = goodTrace.copy(
            steps = listOf(
                // label was hand-edited to something wrong
                goodTrace.steps[0].copy(
                    actions = listOf(TraceAction("tap", target = TraceTarget(null, "틀린라벨", Point(360, 1176)))),
                ),
                goodTrace.steps[1],
            ),
        )
        val dev = FakeDevice(screenA)
        val listener = SwitchableListener()
        val controller = DeviceController(dev, settleIntervalMs = 0, listener = listener)
        val runner = ScenarioRunner(controller, listener, FakeOps(), neverCalledExecutor,
            tempDir(), "com.example")

        val result = runner.run(scenario, brokenTrace, RunMode.REPLAY)

        assertEquals(StepStatus.FAILED, result.status)
        assertEquals(listOf(StepStatus.FAILED, StepStatus.SKIPPED), result.steps.map { it.status })
        assertTrue(result.steps[0].reason!!.startsWith("replay broken:"), "${result.steps[0].reason}")
        assertNull(result.updatedTrace)
        assertTrue(dev.gestures.isEmpty(), "no blind tap on a vanished target")
    }

    @Test
    fun `stale trace step text FAILS without LLM too`() {
        val stale = goodTrace.copy(
            steps = listOf(goodTrace.steps[0].copy(text = "완전 다른 옛날 스텝"), goodTrace.steps[1]),
        )
        val dev = FakeDevice(screenA)
        val listener = SwitchableListener()
        val controller = DeviceController(dev, settleIntervalMs = 0, listener = listener)
        val runner = ScenarioRunner(controller, listener, FakeOps(), neverCalledExecutor,
            tempDir(), "com.example")

        val result = runner.run(scenario, stale, RunMode.REPLAY)

        assertEquals(StepStatus.FAILED, result.status)
        assertTrue("trace missing/stale" in result.steps[0].reason!!, "${result.steps[0].reason}")
    }

    // ------------------------------------------------------------------ display profile

    @Test
    fun `recording stamps the current display into the trace`() {
        val dev = FakeDevice(screenA, screenA, screenB, screenB)
        val h = Harness(dev) { c, text ->
            if (text.startsWith("Tap")) c.tap("웹툰")
            StepVerdict(true, "ok")
        }
        h.ops.display = "720x1280@320"

        val result = h.runner.run(scenario, trace = null, mode = RunMode.LLM)

        assertEquals("720x1280@320", assertNotNull(result.updatedTrace).display)
    }

    @Test
    fun `replay on a different display logs a warning but still runs`() {
        val dev = FakeDevice(screenA, screenA, screenB, screenB)
        val listener = SwitchableListener()
        val controller = DeviceController(dev, settleIntervalMs = 0, listener = listener)
        val ops = FakeOps().also { it.display = "1600x2560@240" }
        val logged = mutableListOf<String>()
        val runner = ScenarioRunner(controller, listener, ops, neverCalledExecutor,
            tempDir(), "com.example", log = logged::add)

        val result = runner.run(scenario, goodTrace.copy(display = "720x1280@320"), RunMode.REPLAY)

        assertEquals(StepStatus.PASSED, result.status)
        assertTrue(logged.any { "trace recorded at 720x1280@320" in it && "1600x2560@240" in it }, "$logged")
    }

    // ------------------------------------------------------------------ robustness / logging

    @Test
    fun `broken setup fails the scenario without killing the process`() {
        val dev = FakeDevice(screenA)
        val listener = SwitchableListener()
        val controller = DeviceController(dev, settleIntervalMs = 0, listener = listener)
        val throwingOps = object : DeviceOps {
            override fun setup(setup: Scenario.Setup, app: String?) =
                throw RuntimeException("adb shell am start failed\n/system/bin/sh: syntax error")

            override fun screenshot(dest: File) = false
            override fun appVersionName(app: String?): String? = null
            override fun deviceName(): String? = null
        }
        val logged = mutableListOf<String>()
        val runner = ScenarioRunner(controller, listener, throwingOps, neverCalledExecutor,
            tempDir(), "com.example", log = logged::add)

        val result = runner.run(scenario, goodTrace, RunMode.REPLAY) // must not throw

        assertEquals(StepStatus.FAILED, result.status)
        assertTrue(result.steps.all { it.status == StepStatus.SKIPPED })
        assertTrue(result.steps[0].reason!!.startsWith("setup failed: adb shell am start failed"))
        assertTrue(logged.any { "setup failed" in it })
    }

    @Test
    fun `live log announces each step before running it and reports its result`() {
        val dev = FakeDevice(screenA, screenA, screenB, screenB)
        val listener = SwitchableListener()
        val controller = DeviceController(dev, settleIntervalMs = 0, listener = listener)
        val logged = mutableListOf<String>()
        val runner = ScenarioRunner(controller, listener, FakeOps(), neverCalledExecutor,
            tempDir(), "com.example", log = logged::add)

        runner.run(scenario, goodTrace, RunMode.REPLAY)

        val stepLines = logged.filter { it.trimStart().startsWith("step") }
        assertEquals(4, stepLines.size, "$logged")
        assertEquals("  step 1/2: Tap the \"웹툰\" tab", stepLines[0])
        assertTrue(stepLines[1].startsWith("  step 1: PASSED (replay, "), stepLines[1])
        assertEquals("  step 2/2: Verify \"웹툰 홈\" is shown", stepLines[2])
        assertTrue(stepLines[3].startsWith("  step 2: PASSED (replay, "), stepLines[3])
        // slow phases get their own lines
        assertTrue(logged.any { "setup:" in it }, "$logged")
        assertTrue(logged.any { "observing initial screen" in it }, "$logged")
    }
}

class FailedStepScreenTest {
    private val tabA = el("웹툰", interactions = listOf("clickable"), center = Point(360, 1176), id = 0)
    private val gate = layout(el("로그인", id = 5, interactions = listOf("clickable")), el(resourceId = "close", id = 6))

    @Test
    fun `a broken replay step records the labels on screen and takes a screenshot`() {
        val dev = FakeDevice(gate, gate)
        val listener = SwitchableListener()
        val controller = DeviceController(dev, settleIntervalMs = 0, listener = listener)
        val reportDir = File.createTempFile("uiagent-report", "").let { it.delete(); it.mkdirs(); it }
        val ops = object : DeviceOps by FakeOps() {
            override fun screenshot(dest: File): Boolean { dest.parentFile.mkdirs(); dest.writeText("png"); return true }
        }
        val runner = ScenarioRunner(controller, listener, ops, neverCalledExecutor, reportDir, app = "com.example")
        val scenario = Scenario("t", emptyList(), null, listOf("Tap the \"웹툰\" tab"))
        val trace = TraceFile("t", AGENT_VERSION, null, listOf(
            TraceStep("Tap the \"웹툰\" tab", listOf(TraceAction("tap", target = TraceTarget(null, "웹툰", tabA.center))), listOf("웹툰")),
        ))

        val result = runner.run(scenario, trace, RunMode.REPLAY)

        val step = result.steps.single()
        assertEquals(StepStatus.FAILED, step.status)
        assertEquals(listOf("로그인", "close"), step.screen, "labels then resourceIds of what was actually on screen")
        assertEquals(listOf("screenshots/step1-failed.png"), step.screenshots)
        assertTrue("\"screen\"" in File(reportDir, "report.json").readText())
    }
}

class AndroidCliMismatchTest {
    @Test
    fun `replay warns when the trace was recorded under another android CLI version`() {
        val screen = layout(el("로그인", id = 1, interactions = listOf("clickable")))
        val dev = FakeDevice(screen, screen)
        val listener = SwitchableListener()
        val controller = DeviceController(dev, settleIntervalMs = 0, listener = listener)
        val ops = FakeOps().apply { androidCli = "1.0.16261425" }
        val reportDir = File.createTempFile("uiagent-report", "").let { it.delete(); it.mkdirs(); it }
        val lines = mutableListOf<String>()
        val runner = ScenarioRunner(controller, listener, ops, neverCalledExecutor, reportDir, app = "com.example", log = lines::add)
        val trace = TraceFile("t", AGENT_VERSION, null, listOf(TraceStep("Verify \"로그인\" is shown", emptyList(), listOf("로그인"))), androidCli = "1.0.15498356")

        val result = runner.run(Scenario("t", emptyList(), null, listOf("Verify \"로그인\" is shown")), trace, RunMode.REPLAY)

        assertEquals(StepStatus.PASSED, result.status)
        assertTrue(lines.any { "android CLI 1.0.15498356" in it && "1.0.16261425" in it }, lines.joinToString("\n"))
        assertTrue("\"androidCli\": \"1.0.16261425\"" in File(reportDir, "report.json").readText())
    }
}
