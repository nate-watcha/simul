package dev.nate.uiagent.cli

import dev.nate.uiagent.agent.LlmStats
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunSummaryTest {

    private fun project(): SimulProject {
        val root = File.createTempFile("simul-summary", "").let { it.delete(); it.mkdirs(); it }
        File(root, ".simul/scenarios/nav").mkdirs()
        File(root, ".simul/config.yaml").writeText("app: com.x\n")
        return SimulProject(root)
    }

    private fun step(i: Int, status: StepStatus, mode: String?, reason: String? = null, shots: List<String> = emptyList(), screen: List<String> = emptyList()) =
        StepReport(i, "step $i \"x\"", status, mode, emptyList(), emptyList(), reason, 1000, shots, screen = screen)

    @Test
    fun `outcome maps a failed run to its first failed step and screenshot under the report dir`() {
        val p = project()
        val md = File(p.scenariosDir, "nav/basic.md").apply { writeText("---\nname: nav-basic\ntags: [smoke]\n---\n1. a\n2. b\n3. c\n") }
        val scenario = ScenarioMd.parse(md)
        val reportDir = File(p.reportsDir, "nav-basic-20260919-020000")
        val result = ScenarioRunResult(
            "nav-basic", StepStatus.FAILED,
            listOf(
                step(1, StepStatus.PASSED, "replay"),
                step(2, StepStatus.FAILED, "replay", "replay broken: evidence not on screen: \"b\"", listOf("screenshots/step2-1-tap-before.png", "screenshots/step2-1-tap-after.png"), screen = listOf("로그인", "닫기")),
                step(3, StepStatus.SKIPPED, null),
            ),
            updatedTrace = null, durationMs = 9000, reportDir = reportDir,
        )

        val o = outcomeOf(p, md, scenario, result)
        assertEquals("nav/basic.md", o.path)
        assertEquals("FAILED", o.status)
        assertEquals("replay", o.mode)
        assertEquals(".simul/reports/nav-basic-20260919-020000", o.reportDir)
        assertEquals(Triple(1, 1, 1), Triple(o.passedSteps, o.failedSteps, o.skippedSteps))
        val f = assertNotNull(o.failedStep)
        assertEquals(2, f.index)
        assertEquals(".simul/reports/nav-basic-20260919-020000/screenshots/step2-1-tap-after.png", f.screenshot)
        assertEquals(listOf("로그인", "닫기"), f.screen)
        assertTrue(!o.traceUpdated)
        assertEquals(null, o.gif, "no recording → no gif field")
    }

    @Test
    fun `a recorded gif travels through the outcome and the summary json, relative to the app root`() {
        val p = project()
        val md = File(p.scenariosDir, "nav/basic.md").apply { writeText("---\nname: nav-basic\n---\n1. a\n") }
        val reportDir = File(p.reportsDir, "nav-basic-20260919-020000")
        val result = ScenarioRunResult("nav-basic", StepStatus.PASSED, listOf(step(1, StepStatus.PASSED, "replay")), null, 9000,
            reportDir = reportDir, media = listOf(File(reportDir, "run.mp4"), File(reportDir, "run.gif")))

        val o = outcomeOf(p, md, ScenarioMd.parse(md), result)
        assertEquals(".simul/reports/nav-basic-20260919-020000/run.gif", o.gif)

        val file = File(p.reportsDir, "nightly/verify.json")
        RunSummaryJson.write(RunSummary("verify", "replay", "", 1, AGENT_VERSION, null, null, listOf(o)), file)
        assertTrue("\"gif\": \".simul/reports/nav-basic-20260919-020000/run.gif\"" in file.readText())
        assertEquals(o.gif, RunSummaryJson.read(file).scenarios.single().gif)
    }

    @Test
    fun `setup failure surfaces as the failed step even though every step is SKIPPED`() {
        val p = project()
        val md = File(p.scenariosDir, "nav/basic.md").apply { writeText("---\nname: nav-basic\n---\n1. a\n") }
        val result = ScenarioRunResult(
            "nav-basic", StepStatus.FAILED,
            listOf(step(1, StepStatus.SKIPPED, null, "setup failed: deeplink not handled by app")),
            updatedTrace = null, durationMs = 100,
        )
        val o = outcomeOf(p, md, ScenarioMd.parse(md), result)
        assertEquals("setup failed: deeplink not handled by app", o.failedStep?.reason)
        assertEquals("❌ setup", cell(o))
    }

    @Test
    fun `llm record reports mode llm even when Verify steps were runner-judged`() {
        val p = project()
        val md = File(p.scenariosDir, "nav/basic.md").apply { writeText("---\nname: nav-basic\n---\n1. a\n2. b\n") }
        val trace = TraceFile("nav-basic", AGENT_VERSION, "5.4.0", emptyList())
        val result = ScenarioRunResult(
            "nav-basic", StepStatus.PASSED,
            listOf(step(1, StepStatus.PASSED, "runner"), step(2, StepStatus.PASSED, "llm")),
            updatedTrace = trace, durationMs = 100,
        )
        val o = outcomeOf(p, md, ScenarioMd.parse(md), result)
        assertEquals("llm", o.mode)
        assertTrue(o.traceUpdated)
        assertNull(o.failedStep)
    }

    @Test
    fun `summary json round-trips and omits absent fields`() {
        val p = project()
        val md = File(p.scenariosDir, "nav/basic.md").apply { writeText("---\nname: nav-basic\n---\n1. a\n") }
        val skipped = outcomeWithout(p, md, ScenarioMd.parse(md), "SKIPPED", "no trace")
        val crashed = outcomeWithout(p, md, ScenarioMd.parse(md), "CRASH", "OutOfMemoryError: boom", crashLog = File(p.reportsDir, "nav-basic-crash-1.log"))
        val s = RunSummary("record", "llm", "2026-09-19T02:00:00+09:00", 5000, AGENT_VERSION, null, "emu", listOf(skipped, crashed), androidCli = "1.0.16261425")

        val file = File(p.reportsDir, "nightly/record.json")
        RunSummaryJson.write(s, file)
        val text = file.readText()
        assertTrue("\"failedStep\"" !in text, "absent failedStep must be omitted\n$text")
        assertTrue("\"appVersionName\": null" in text)

        val back = RunSummaryJson.read(file)
        assertEquals(s, back)
        assertEquals(".simul/reports/nav-basic-crash-1.log", back.scenarios[1].reportDir)
    }

    @Test
    fun `llm stats of a recording round-trip through the summary and are absent for replay`() {
        val p = project()
        val md = File(p.scenariosDir, "nav/basic.md").apply { writeText("---\nname: nav-basic\n---\n1. a\n2. b\n") }
        val cost = LlmStats(5, 30_000, 120_000, 900, 14_000)
        val recorded = ScenarioRunResult("nav-basic", StepStatus.PASSED,
            listOf(step(1, StepStatus.PASSED, "llm").copy(llm = cost), step(2, StepStatus.PASSED, "runner")),
            updatedTrace = null, durationMs = 50_000)
        val replayed = ScenarioRunResult("nav-basic", StepStatus.PASSED,
            listOf(step(1, StepStatus.PASSED, "replay"), step(2, StepStatus.PASSED, "replay")),
            updatedTrace = null, durationMs = 9_000)
        val s = RunSummary("record", "llm", "2026-09-19T02:00:00+09:00", 5000, AGENT_VERSION, null, "emu",
            listOf(outcomeOf(p, md, ScenarioMd.parse(md), recorded), outcomeOf(p, md, ScenarioMd.parse(md), replayed)))
        assertEquals(cost, s.scenarios[0].llm)
        assertNull(s.scenarios[1].llm)

        val file = File(p.reportsDir, "nightly/record.json")
        RunSummaryJson.write(s, file)
        val text = file.readText()
        assertEquals(1, Regex("\"llm\": \\{").findAll(text).count(), text)
        assertEquals(s, RunSummaryJson.read(file))
    }
}
