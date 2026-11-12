package dev.nate.uiagent.cli

import dev.nate.uiagent.agent.ScenarioSession
import dev.nate.uiagent.device.DevicePins
import dev.nate.uiagent.device.DeviceSetting
import dev.nate.uiagent.device.DeviceOps
import dev.nate.uiagent.FakeDevice
import dev.nate.uiagent.StaticDevice
import dev.nate.uiagent.layout
import dev.nate.uiagent.el
import dev.nate.uiagent.tempDir
import dev.nate.uiagent.Point
import dev.nate.uiagent.agent.LlmStats
import dev.nate.uiagent.LogicalLayout
import dev.nate.uiagent.ProcessTimeout
import dev.nate.uiagent.device.Device
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

    private class Harness(dev: Device, behavior: (DeviceController, String) -> ScenarioSession.Verdict) {
        val listener = SwitchableListener()
        val controller = DeviceController(dev, settleIntervalMs = 0, listener = listener)
        val executor = ScriptedExecutor { text -> behavior(controller, text) }
        val ops = FakeOps()
        val reportDir = tempDir()
        val runner = ScenarioRunner(controller, listener, ops, executor, reportDir, app = "com.example")
    }

    private val scenario = Scenario(
        name = "webtoon-tab",
        tags = listOf("smoke"),
        setup = Scenario.Setup(deeplink = "app://home", launch = false),
        steps = listOf("Tap the \"웹툰\" tab", "Verify \"웹툰 홈\" is shown"),
    )

    // ------------------------------------------------------------------ values

    @Test
    fun `a scenario value is picked off what the screen shows, typed, and kept in the trace by its name`() {
        fun screen(name: String) =
            layout(el(name, resourceId = "name_field", interactions = listOf("clickable", "focusable"), center = Point(360, 400)))
        val shown = StaticDevice(screen("노을")) // the name the last run saved
        val dev = object : Device by shown {
            override fun inputText(text: String, resourceId: String?) { shown.layout = screen(text) }
        }
        val commands = mutableListOf<String>()
        val h = Harness(dev) { c, command ->
            commands += command
            c.type("name_field", quotedLabels(command).last())
            ScenarioSession.Verdict(true, "ok")
        }
        val rename = Scenario(
            name = "rename", tags = emptyList(), setup = null,
            steps = listOf("\"name_field\" 필드에 \"\$name\"을 입력한다 :: \"\$name\"이 보인다"),
            values = mapOf("name" to listOf("노을", "바다")),
        )

        val recorded = h.runner.run(rename, trace = null, mode = RunMode.LLM)

        assertEquals(StepStatus.PASSED, recorded.status, recorded.steps.first().reason)
        assertEquals(listOf("\"name_field\" 필드에 \"바다\"을 입력한다"), commands, "the model is given the value, and not the one already on screen")
        assertEquals(mapOf("name" to "바다"), recorded.values)
        assertTrue("\"바다\"" in recorded.steps.first().text, "the report reads with the value in place")
        val step = recorded.updatedTrace!!.steps.single()
        assertEquals(rename.steps.single(), step.text)
        assertEquals("\$name", step.actions.single().text, "a trace must not change with the value a run happened to pick")
        assertTrue("\$name" in step.evidence && "바다" !in step.evidence, step.evidence.toString())

        // the next run finds "바다" saved, so it takes the other candidate — typed and gated on without a model
        val replayed = h.runner.run(rename, trace = recorded.updatedTrace, mode = RunMode.REPLAY)

        assertEquals(StepStatus.PASSED, replayed.status, replayed.steps.first().reason)
        assertEquals(mapOf("name" to "노을"), replayed.values)
        assertEquals("노을", shown.layout.elements.single().label)
        assertEquals(1, h.executor.calls, "replay never consults the model")
    }

    // ------------------------------------------------------------------ sections

    /** An app whose follow button toggles a state that outlives the app's restart — the server's. */
    private class FollowApp(following: Boolean?) {
        private fun screen(following: Boolean?) = when (following) {
            null -> layout(el("프로필", center = Point(360, 300)))
            else -> layout(el(if (following) "팔로우 취소" else "팔로우", resourceId = "my_follow_button",
                interactions = listOf("clickable"), center = Point(360, 1094)))
        }
        private val shown = StaticDevice(screen(following))
        var following: Boolean? = following
            set(value) { field = value; shown.layout = screen(value) }
        val device = object : Device by shown {
            override fun tap(x: Int, y: Int) { this@FollowApp.following = this@FollowApp.following?.not() }
        }
    }

    private val follow = Scenario(
        name = "follow", tags = emptyList(), setup = Scenario.Setup(deeplink = "app://users/1", launch = false),
        steps = listOf("\"팔로우 취소\"를 누른다", "\"팔로우\"가 보이는지 확인한다", "\"팔로우\"를 누른다", "\"팔로우 취소\"가 보이는지 확인한다"),
        sections = listOf(
            Scenario.Section("팔로우 취소", "팔로우를 취소할 수 있다.", 0, 2),
            Scenario.Section("팔로우 추가", "", 2, 2),
        ),
    )

    /** The same two sections behind a preparation that leaves the account following, whatever it found. */
    private val prepared = follow.copy(
        steps = listOf("팔로우하고 있지 않으면 \"팔로우\"를 눌러 팔로우한다") + follow.steps,
        sections = listOf(Scenario.Section("준비", "", 0, 1, preparation = true)) + follow.sections.map { it.copy(first = it.first + 1) },
    )

    /**
     * Taps what a "누른다" step quotes; a step passes when what it quotes is (or was, for a tap) on screen.
     * The preparation's sentence ("…않으면 …") taps only when there is something to tap and passes either way.
     */
    private fun followHarness(app: FollowApp) = Harness(app.device) { c, command ->
        val label = quotedLabels(command).first()
        val there = c.currentElements.any { it.matchesExact(label) }
        if (there && ("누른다" in command || "않으면" in command)) c.tap(label)
        if ("않으면" in command) ScenarioSession.Verdict(true, if (there) "followed" else "already following")
        else ScenarioSession.Verdict(there, if (there) "ok" else "\"$label\" is not on screen")
    }

    @Test
    fun `sections run in file order, each from a fresh setup and conversation, and replay the same way`() {
        val app = FollowApp(following = true)
        val h = followHarness(app)

        val recorded = h.runner.run(follow, trace = null, mode = RunMode.LLM)

        assertEquals(StepStatus.PASSED, recorded.status, recorded.steps.firstOrNull { it.status == StepStatus.FAILED }?.reason)
        assertEquals(listOf(StepStatus.PASSED, StepStatus.PASSED), recorded.sections.map { it.status })
        assertEquals(listOf(1..2, 3..4), recorded.sections.map { it.steps })
        assertEquals(1, h.ops.setups.size, "the account state is restored once, for the scenario")
        assertEquals(listOf("com.example|false|app://users/1"), h.ops.reopens, "the second section starts the app over, its data kept")
        assertEquals(listOf(false, false), h.executor.sessions, "and the model's conversation with it")
        assertEquals(true, app.following, "both directions ran, so the account is where it started")
        assertEquals(listOf("팔로우 취소", "팔로우 취소", "팔로우 추가", "팔로우 추가"), recorded.updatedTrace!!.steps.map { it.section })

        val replayed = h.runner.run(follow, trace = recorded.updatedTrace, mode = RunMode.REPLAY)

        assertEquals(StepStatus.PASSED, replayed.status, replayed.steps.firstOrNull { it.status == StepStatus.FAILED }?.reason)
        assertEquals(4, h.executor.calls, "replay never consults the model")
        assertEquals(2, h.ops.setups.size)
        assertEquals(2, h.ops.reopens.size)
    }

    @Test
    fun `the preparation is the model's in a replay too, and what follows it replays without one`() {
        val app = FollowApp(following = false) // the last run stopped halfway: unfollowed, not followed back
        val h = followHarness(app)

        val recorded = h.runner.run(prepared, trace = null, mode = RunMode.LLM)

        assertEquals(StepStatus.PASSED, recorded.status, recorded.steps.firstOrNull { it.status == StepStatus.FAILED }?.reason)
        assertEquals(listOf(true, false, false), recorded.sections.map { it.preparation })
        assertEquals(listOf(true, false, false), h.executor.sessions, "the preparation has a conversation of its own")
        assertEquals(2, h.ops.reopens.size, "the preparation tapped, so the first section starts the app over as well")
        assertEquals(follow.steps, recorded.updatedTrace!!.steps.map { it.text }, "a preparation is never traced")

        // flipped again at replay: only the preparation asks the model, and both directions still run
        app.following = false
        val replayed = h.runner.run(prepared, trace = recorded.updatedTrace, mode = RunMode.REPLAY)

        assertEquals(StepStatus.PASSED, replayed.status, replayed.steps.firstOrNull { it.status == StepStatus.FAILED }?.reason)
        assertEquals(listOf("llm", "replay", "replay", "replay", "replay"), replayed.steps.map { it.mode })
        assertEquals(6, h.executor.calls, "5 of the recording, 1 for the replay's preparation")
        assertEquals("followed", replayed.steps[0].reason, "the report says what the model found or did")
        assertEquals(true, app.following)

        // already as it should be: the preparation touches nothing, and the app is not started over for the first section
        val reopens = h.ops.reopens.size
        val again = h.runner.run(prepared, trace = recorded.updatedTrace, mode = RunMode.REPLAY)
        assertEquals(StepStatus.PASSED, again.status)
        assertTrue(again.steps[0].actions.isEmpty())
        assertEquals(reopens + 1, h.ops.reopens.size, "only the second section's")
        // result.json carries the sections for the report
        val read = ResultJson.read(writeResult(again, "replay", h.ops, h.reportDir))!!
        assertEquals(listOf("팔로우 취소", "팔로우 추가"), read.tests.map { it.title })
        assertEquals("준비", read.sectionOf(1)?.title)
    }

    @Test
    fun `a replay without a model FAILED at the preparation instead of crashing`() {
        val h = followHarness(FollowApp(following = true))
        val trace = h.runner.run(prepared, trace = null, mode = RunMode.LLM).updatedTrace!!
        h.executor.problem = "llm.apiKey references \$SIMUL_LLM_API_KEY but it is not set in the environment"

        val result = h.runner.run(prepared, trace = trace, mode = RunMode.REPLAY)

        assertEquals(StepStatus.FAILED, result.status)
        assertEquals(StepStatus.FAILED, result.steps[0].status)
        assertTrue("the preparation needs the model" in result.steps[0].reason!!, result.steps[0].reason)
        assertEquals(listOf(StepStatus.FAILED, StepStatus.SKIPPED, StepStatus.SKIPPED), result.sections.map { it.status })
        assertEquals(5, h.executor.calls, "nothing was asked of a model that is not there")
    }

    // ------------------------------------------------------------------ device settings

    @Test
    fun `scenario device settings are pinned before setup and restored when the scenario ends`() {
        val dev = FakeDevice(screenA, screenA, screenB, screenB)
        val h = Harness(dev) { c, _ -> c.tap("웹툰"); ScenarioSession.Verdict(true, "ok") }
        h.ops.settings["secure.credential_service"] = ""  // the config baseline
        val pinned = scenario.copy(setup = Scenario.Setup(deeplink = "app://home", launch = false,
            device = DevicePins(settings = listOf(DeviceSetting("secure", "credential_service", "com.google/.Pw")))))

        val result = h.runner.run(pinned, trace = null, mode = RunMode.LLM)

        assertEquals(StepStatus.PASSED, result.status)
        assertEquals(listOf("put secure.credential_service=com.google/.Pw", "put secure.credential_service="), h.ops.settingLog)
        assertEquals("", h.ops.settings["secure.credential_service"], "next scenario starts from the baseline")
    }

    @Test
    fun `device settings are restored even when setup fails, and a physical device is left alone`() {
        val dev = FakeDevice(screenA)
        val failing = object : DeviceOps by FakeOps() {
            val inner = FakeOps()
            override fun isEmulator() = true
            override fun settingGet(namespace: String, key: String) = inner.settingGet(namespace, key)
            override fun settingPut(namespace: String, key: String, value: String) = inner.settingPut(namespace, key, value)
            override fun settingDelete(namespace: String, key: String) = inner.settingDelete(namespace, key)
            override fun setup(app: String?, appState: String?, clearData: Boolean, launch: Boolean, deeplink: String?, locale: String?): String? = throw RuntimeException("adb shell am start failed")
        }
        val listener = SwitchableListener()
        val controller = DeviceController(dev, settleIntervalMs = 0, listener = listener)
        val reportDir = tempDir()
        val runner = ScenarioRunner(controller, listener, failing, neverCalledExecutor, reportDir, "com.example")
        val pinned = scenario.copy(setup = Scenario.Setup(deeplink = "app://home", launch = false,
            device = DevicePins(settings = listOf(DeviceSetting("secure", "autofill_service", "com.x/.Af")))))

        val result = runner.run(pinned, trace = null, mode = RunMode.REPLAY)

        assertEquals(StepStatus.FAILED, result.status)
        assertEquals(listOf("put secure.autofill_service=com.x/.Af", "delete secure.autofill_service"), failing.inner.settingLog)

        val phone = Harness(FakeDevice(screenA)) { _, _ -> ScenarioSession.Verdict(true, "ok") }
        phone.ops.emulator = false
        phone.runner.run(pinned, trace = null, mode = RunMode.LLM)
        assertTrue(phone.ops.settingLog.isEmpty(), "never rewrite a person's phone settings")
    }

    @Test
    fun `app locale is set inside setup after the clear that wipes it, a scenario override wins, and the prior locale comes back`() {
        val dev = FakeDevice(screenA, screenA, screenB, screenB)
        val h = Harness(dev) { c, _ -> c.tap("웹툰"); ScenarioSession.Verdict(true, "ok") }
        // a developer's emulator where the app happens to be in English
        h.ops.appLocale = "en-US"
        val runner = ScenarioRunner(h.controller, h.listener, h.ops, h.executor, h.reportDir, app = "com.example", locale = "ko-KR")
        val guest = scenario.copy(setup = Scenario.Setup(deeplink = null, launch = true, clearData = true))

        assertEquals(StepStatus.PASSED, runner.run(guest, trace = null, mode = RunMode.LLM).status)
        assertEquals(listOf("locale ko-KR", "locale en-US"), h.ops.localeLog, "set once by setup (after the clear), restored once at the end")
        assertEquals("en-US", h.ops.appLocale)

        h.ops.localeLog.clear()
        val english = guest.copy(setup = guest.setup!!.copy(device = DevicePins(locale = "en-US")))
        runner.run(english, trace = null, mode = RunMode.LLM)
        assertEquals(listOf("locale en-US"), h.ops.localeLog, "the scenario's own locale beats the config baseline; nothing to restore when it already matched")

        h.ops.localeLog.clear()
        val noSetup = scenario.copy(setup = null)
        runner.run(noSetup, trace = null, mode = RunMode.LLM)
        assertTrue(h.ops.localeLog.isEmpty(), "no setup, no launch — the app's language is left alone")
    }

    // ------------------------------------------------------------------ llm / record

    @Test
    fun `llm steps carry the model's time and tokens into the result and report json`() {
        val dev = FakeDevice(screenA, screenA, screenB, screenB)
        val cost = LlmStats(calls = 3, durationMs = 1200, promptTokens = 20_000, completionTokens = 300, peakPromptTokens = 9_000)
        val h = Harness(dev) { c, text ->
            if (text.startsWith("Tap")) c.tap("웹툰")
            ScenarioSession.Verdict(true, "ok", llm = cost)
        }

        val result = h.runner.run(scenario, trace = null, mode = RunMode.LLM)

        assertEquals(StepStatus.PASSED, result.status)
        assertEquals(cost, result.steps[0].llm, "each llm step keeps its own stats")
        assertEquals(cost, result.steps[1].llm, "the Verify step is the model's too")
        assertEquals(LlmStats.sum(listOf(cost, cost)), result.llm, "scenario stats = sum over llm steps")

        val json = writeResult(result, "llm", h.ops, h.reportDir).readText()
        assertTrue("\"llm\": {" in json && "\"peakPromptTokens\": 9000" in json, json)
        assertEquals(3, Regex("\"llm\": \\{").findAll(json).count(), "scenario-level + one per step\n$json")
    }

    @Test
    fun `the result accounts for the device time of its steps and the log says what the first observation cost`() {
        val dev = FakeDevice(screenA, screenA, screenB, screenB)
        val listener = SwitchableListener()
        val controller = DeviceController(dev, settleIntervalMs = 0, listener = listener)
        val log = mutableListOf<String>()
        val executor = ScriptedExecutor { text ->
            if (text.startsWith("Tap")) controller.tap("웹툰")
            ScenarioSession.Verdict(true, "ok")
        }
        val runner = ScenarioRunner(controller, listener, FakeOps(), executor, tempDir(), app = "com.example", log = log::add)

        val result = runner.run(scenario, trace = null, mode = RunMode.LLM)

        assertEquals(4, result.device.dumps, "first observation (2) + the tap's settle (2)")
        assertEquals(1, result.device.gestures)
        assertTrue(log.any { it.trim().startsWith("first observation") && "2 dumps" in it }, log.joinToString("\n"))
        assertTrue(log.any { "step 1: PASSED" in it && "observe" in it && "4 dumps" in it }, log.joinToString("\n"))
        // one line for the whole run: every measured part, and the remainder so the parts add up
        val line = timeLine(10_000, listOf(result.copy(setupMs = 2_000, wallMs = 9_000, durationMs = 8_000)))
        assertTrue(line.startsWith("time 10.0s = setup 20% 2.0s") && "4 dumps" in line && "wrap-up 10% 1.0s" in line && "other" in line, line)
    }

    @Test
    fun `llm mode records a trace when all steps pass`() {
        val dev = FakeDevice(screenA, screenA, screenB, screenB)
        val h = Harness(dev) { c, text ->
            if (text.startsWith("Tap")) {
                c.tap("웹툰")
                ScenarioSession.Verdict(true, "tab selected")
            } else {
                ScenarioSession.Verdict(true, "shown")
            }
        }

        val result = h.runner.run(scenario, trace = null, mode = RunMode.LLM)

        assertEquals(StepStatus.PASSED, result.status)
        assertEquals(listOf("llm", "llm"), result.steps.map { it.mode })
        assertEquals(2, h.executor.calls, "every step is the model's, the Verify step included")
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
        assertTrue(File(h.reportDir, "logcat.txt").exists().not(), "the fake dumps no logcat; nothing else is written by the runner itself")
    }

    @Test
    fun `failed step skips the rest and records no trace`() {
        val dev = FakeDevice(screenA, screenA)
        val h = Harness(dev) { _, text ->
            if (text.startsWith("Tap")) ScenarioSession.Verdict(false, "element not found") else ScenarioSession.Verdict(true, "unreachable")
        }

        val result = h.runner.run(scenario, trace = null, mode = RunMode.LLM)

        assertEquals(StepStatus.FAILED, result.status)
        assertEquals(listOf(StepStatus.FAILED, StepStatus.SKIPPED), result.steps.map { it.status })
        assertNull(result.steps[1].mode)
        assertNull(result.updatedTrace)
        assertEquals(1, h.executor.calls, "skipped steps must not reach the LLM")
    }

    // ------------------------------------------------------------------ verify / criterion

    @Test
    fun `a Verify step is judged by the model like any other step`() {
        val dev = FakeDevice(screenB)
        val h = Harness(dev) { _, _ -> ScenarioSession.Verdict(true, "judged by llm") }
        val result = h.runner.run(
            scenario.copy(steps = listOf("Verify \"웹툰 홈\" is shown")),
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
            ScenarioSession.Verdict(true, "ok")
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
            ScenarioSession.Verdict(true, "looks fine to me")
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
            ScenarioSession.Verdict(true, "ok")
        }
        h.ops.display = "720x1280@320"

        val result = h.runner.run(scenario, trace = null, mode = RunMode.LLM)

        assertEquals("720x1280@320", assertNotNull(result.updatedTrace).display)
    }

    // ------------------------------------------------------------------ robustness / logging

    @Test
    fun `broken setup fails the scenario without killing the process`() {
        val dev = FakeDevice(screenA)
        val listener = SwitchableListener()
        val controller = DeviceController(dev, settleIntervalMs = 0, listener = listener)
        val throwingOps = object : DeviceOps by FakeOps() {
            override fun setup(app: String?, appState: String?, clearData: Boolean, launch: Boolean, deeplink: String?, locale: String?): String? =
                throw RuntimeException("adb shell am start failed\n/system/bin/sh: syntax error")
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
    fun `a device command that fails ends the step as FAILED and the scenario still has a result`() {
        val dev = object : Device by FakeDevice(screenA) {
            override fun observe(): LogicalLayout = throw ProcessTimeout(listOf("android", "layout"), 20_000)
            override fun observeFast(): LogicalLayout = observe()
        }
        val listener = SwitchableListener()
        val controller = DeviceController(dev, settleIntervalMs = 0, listener = listener)
        val runner = ScenarioRunner(controller, listener, FakeOps(), neverCalledExecutor, tempDir(), "com.example")

        val result = runner.run(scenario, goodTrace, RunMode.REPLAY) // must not throw: a CRASH leaves no result.json

        assertEquals(listOf(StepStatus.FAILED, StepStatus.SKIPPED), result.steps.map { it.status })
        assertEquals("device command failed: no answer within 20s: android layout", result.steps[0].reason)
    }


    // ------------------------------------------------------------------ a screen's way in, a section's own start

    private fun webtoonEntry(trace: TraceFile?, account: String? = null) =
        ScreenEntry("웹툰", Scenario.Setup(deeplink = "app://home", launch = false), listOf("Tap the \"웹툰\" tab"), account, trace, "웹툰/_entry.md")

    /** [scenario]'s check alone, started from the 웹툰 screen's way in — its tap is the entry's, not the scenario's. */
    private val inside = scenario.copy(steps = listOf("Verify \"웹툰 홈\" is shown"), via = "웹툰")
    private val insideTrace = goodTrace.copy(steps = goodTrace.steps.drop(1))

    private fun replayInside(entry: ScreenEntry, dev: FakeDevice = FakeDevice(screenA, screenA, screenB, screenB)): Pair<ScenarioRunResult, FakeDevice> {
        val listener = SwitchableListener()
        val controller = DeviceController(dev, settleIntervalMs = 0, listener = listener)
        val runner = ScenarioRunner(controller, listener, FakeOps(), neverCalledExecutor, tempDir(), "com.example",
            entries = { name -> entry.takeIf { name == "웹툰" } })
        return runner.run(inside, insideTrace, RunMode.REPLAY) to dev
    }

    @Test
    fun `a scenario that starts from its screen's way in is walked there from the entry's trace - no model, nothing of it among its steps`() {
        val (result, dev) = replayInside(webtoonEntry(goodTrace.copy(steps = goodTrace.steps.take(1))))

        assertEquals(StepStatus.PASSED, result.status, result.steps.firstOrNull()?.reason)
        assertEquals(listOf("Verify \"웹툰 홈\" is shown"), result.steps.map { it.text })
        assertEquals(listOf("tap 360 1176"), dev.gestures.filter { it.startsWith("tap") }, "the way in was played")
    }

    @Test
    fun `a way in that is not recorded or no longer plays is a failed setup that says so`() {
        val (missing, _) = replayInside(webtoonEntry(null))
        assertTrue(missing.setupFailed)
        assertTrue("run 웹툰/_entry.md with --mode llm" in missing.steps[0].reason!!, missing.steps[0].reason)

        val gone = goodTrace.steps[0].copy(actions = listOf(TraceAction("tap", target = TraceTarget(null, "틀린라벨", Point(360, 1176)))))
        val (broken, dev) = replayInside(webtoonEntry(goodTrace.copy(steps = listOf(gone))), FakeDevice(screenA))
        assertTrue(broken.setupFailed)
        assertTrue(broken.steps[0].reason!!.startsWith("setup failed: the way in to '웹툰' broke at its step 1"), broken.steps[0].reason)
        assertTrue(dev.gestures.none { it.startsWith("tap") })
    }

    @Test
    fun `in another account than the way in was recorded in, its labels are not asked for`() {
        // the entry saw a label of the account it was recorded in; this account's screen does not have it
        val seen = goodTrace.steps[0].copy(evidence = listOf("member의 작품"))
        val (same, _) = replayInside(webtoonEntry(goodTrace.copy(steps = listOf(seen)), account = null))
        assertTrue(same.setupFailed, "the same account: the way in is held to everything it recorded")
        val (other, dev) = replayInside(webtoonEntry(goodTrace.copy(steps = listOf(seen)), account = "member"))
        assertEquals(StepStatus.PASSED, other.status, other.steps.firstOrNull()?.reason)
        assertEquals(1, dev.gestures.count { it.startsWith("tap") })
    }

    @Test
    fun `a failed look at another screen does not skip the sections after it, and a section with a place of its own starts there`() {
        // not following: "팔로우 취소" is not there to tap, so the first section fails
        val looking = follow.copy(sections = listOf(
            Scenario.Section("팔로우 취소", "[[다른 화면]]에서도 취소로 보인다.", 0, 2, open = Scenario.Open(deeplink = "app://elsewhere")),
            Scenario.Section("팔로우 추가", "", 2, 2),
        ))
        val h = followHarness(FollowApp(following = false))

        val result = h.runner.run(looking, trace = null, mode = RunMode.LLM)

        assertEquals(StepStatus.FAILED, result.status)
        assertEquals(listOf(StepStatus.FAILED, StepStatus.PASSED), result.sections.map { it.status }, "the section after the look ran")
        assertEquals(listOf(StepStatus.FAILED, StepStatus.SKIPPED, StepStatus.PASSED, StepStatus.PASSED), result.steps.map { it.status })
        assertEquals(listOf("com.example|false|app://elsewhere", "com.example|false|app://users/1"), h.ops.reopens, "the first section is opened at its own address, the next at the scenario's")
        assertEquals(Scenario.Open(deeplink = "app://elsewhere"), result.sections[0].open)
        assertNull(result.updatedTrace, "a failed scenario records no trace")

        // a section the others build on still ends the scenario there
        val plain = followHarness(FollowApp(following = false)).runner.run(follow, trace = null, mode = RunMode.LLM)
        assertEquals(listOf(StepStatus.FAILED, StepStatus.SKIPPED), plain.sections.map { it.status })
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
        val reportDir = tempDir()
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
        assertEquals(listOf("steps/01.png"), step.screenshots, "replay keeps one frame per step: the screen when it ended")
        assertTrue("\"screen\"" in writeResult(result, "replay", ops, reportDir).readText())
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
        val reportDir = tempDir()
        val lines = mutableListOf<String>()
        val runner = ScenarioRunner(controller, listener, ops, neverCalledExecutor, reportDir, app = "com.example", log = lines::add)
        val trace = TraceFile("t", AGENT_VERSION, null, listOf(TraceStep("Verify \"로그인\" is shown", emptyList(), listOf("로그인"))), androidCli = "1.0.15498356")

        val result = runner.run(Scenario("t", emptyList(), null, listOf("Verify \"로그인\" is shown")), trace, RunMode.REPLAY)

        assertEquals(StepStatus.PASSED, result.status)
        assertTrue(lines.any { "android CLI 1.0.15498356" in it && "1.0.16261425" in it }, lines.joinToString("\n"))
        assertTrue("\"android_cli\": \"1.0.16261425\"" in writeResult(result, "replay", ops, reportDir).readText())
    }
}
