package dev.nate.uiagent.cli

import dev.nate.uiagent.tempDir
import dev.nate.uiagent.agent.LlmStats
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportTest {

    private fun outcome(
        path: String, status: String, mode: String? = "replay", failedAt: Int? = null,
        reason: String? = null, traceUpdated: Boolean = false, skipReason: String? = null, llm: LlmStats? = null,
    ) = ScenarioOutcome(
        name = path.substringAfterLast('/').removeSuffix(".md"), path = path, tags = listOf("smoke"),
        status = status, mode = mode, durationMs = 12_300, reportDir = ".simul/reports/x-20260919-020000",
        traceUpdated = traceUpdated, passedSteps = 2, failedSteps = if (failedAt != null) 1 else 0, skippedSteps = 0,
        failedStep = failedAt?.let { FailedStep(it, "Tap the \"Apply\" button", reason ?: "replay broken: evidence not on screen", "shots/step$it-1-tap-after.png", listOf("Coupons", "Register coupon")) },
        reason = skipReason,
        llm = llm,
    )

    private fun summary(label: String, mode: String, vararg outs: ScenarioOutcome) = RunSummary(
        label = label, mode = mode, startedAt = "2026-09-19T02:00:00+09:00", durationMs = 60_000,
        agentVersion = "0.2.0", appVersionName = "5.4.0", device = "sdk_gphone64_arm64", scenarios = outs.toList(), androidCli = "1.0.16261425",
    )

    private fun verdict(record: String?, verify: String?, before: String? = null): VerdictKind {
        val phases = mutableListOf<RunSummary>()
        before?.let { phases += summary("smoke", "replay", outcome("nav/a.md", it, failedAt = 3.takeIf { _ -> it == "FAILED" })) }
        record?.let { phases += summary("record", "llm", outcome("nav/a.md", it, mode = "llm", failedAt = 3.takeIf { _ -> it == "FAILED" }, traceUpdated = it == "PASSED", skipReason = "no steps".takeIf { _ -> it == "SKIPPED" })) }
        verify?.let { phases += summary("verify", "replay", outcome("nav/a.md", it, failedAt = 3.takeIf { _ -> it == "FAILED" }, skipReason = "no trace".takeIf { _ -> it == "SKIPPED" })) }
        return PipelineReport.build("t", phases).rows.single().verdict
    }

    // ------------------------------------------------------------------ verdicts

    @Test
    fun `record then verify verdict matrix`() {
        assertEquals(VerdictKind.STABLE, verdict("PASSED", "PASSED"))
        assertEquals(VerdictKind.TRACE_FLAKE, verdict("PASSED", "FAILED"), "recorded but the fresh trace does not replay")
        assertEquals(VerdictKind.REGRESSION, verdict("FAILED", "FAILED"))
        assertEquals(VerdictKind.REGRESSION, verdict("FAILED", "PASSED"), "verify replayed the OLD trace — still a regression")
        assertEquals(VerdictKind.REGRESSION, verdict("FAILED", "SKIPPED"), "no trace to fall back on either")
        assertEquals(VerdictKind.SKIPPED, verdict("SKIPPED", "SKIPPED"))
        assertEquals(VerdictKind.PASS, verdict("SKIPPED", "PASSED"), "nothing recorded, the existing trace replays")
    }

    @Test
    fun `a replay before the llm phase is only a column and a lone replay degrades to pass or fail`() {
        assertEquals(VerdictKind.STABLE, verdict("PASSED", "PASSED", before = "FAILED"))
        assertEquals(VerdictKind.REGRESSION, verdict("FAILED", "PASSED", before = "PASSED"))
        assertEquals(VerdictKind.PASS, verdict(null, "PASSED"))
        assertEquals(VerdictKind.FAIL, verdict(null, "FAILED"))
    }

    @Test
    fun `crash dominates and roles follow mode order`() {
        val phases = listOf(
            summary("smoke", "replay", outcome("nav/a.md", "PASSED")),
            summary("record", "llm", outcome("nav/a.md", "CRASH", mode = null, skipReason = "OutOfMemoryError")),
            summary("verify", "replay", outcome("nav/a.md", "PASSED")),
        )
        val r = PipelineReport.build("t", phases)
        assertEquals(VerdictKind.CRASH, r.rows.single().verdict)
        assertEquals(listOf(Role.OTHER, Role.RECORD, Role.VERIFY), r.phases.map { it.role })
        assertEquals(Severity.FAIL, r.overall)
    }

    @Test
    fun `scenario missing from a phase is shown as absent and duplicate labels are disambiguated`() {
        val phases = listOf(
            summary("run", "replay", outcome("nav/a.md", "PASSED"), outcome("nav/b.md", "FAILED", failedAt = 1)),
            summary("run", "replay", outcome("nav/a.md", "PASSED")),
        )
        val r = PipelineReport.build("t", phases)
        assertEquals(listOf("run", "run#2"), r.phases.map { it.label })
        val b = r.rows.first { it.path == "nav/b.md" }
        assertNull(b.outcomes[1])
        assertEquals(VerdictKind.FAIL, b.verdict)
        assertEquals("–", cell(b.outcomes[1]))
    }

    // ------------------------------------------------------------------ renderers

    private fun nightly() = PipelineReport.build(
        "simul nightly 2026-09-19",
        listOf(
            summary("record", "llm", outcome("nav/a.md", "PASSED", mode = "llm", traceUpdated = true), outcome("checkout/coupon.md", "FAILED", mode = "llm", failedAt = 3), outcome("deeplink/new.md", "SKIPPED", mode = null, skipReason = "no steps")),
            summary("verify", "replay", outcome("nav/a.md", "PASSED"), outcome("checkout/coupon.md", "PASSED"), outcome("deeplink/new.md", "SKIPPED", mode = null, skipReason = "no trace")),
        ),
        links = listOf("Run" to "https://github.com/o/r/actions/runs/1"),
    )

    @Test
    fun `junit has one suite per phase and failures carry the reason`() {
        val xml = renderJunit(nightly())
        assertTrue("""<testsuite name="record (llm)" tests="3" failures="1" errors="0" skipped="1"""" in xml, xml)
        assertTrue("""<testsuite name="verify (replay)" tests="3" failures="0"""" in xml, xml)
        assertTrue("""<testcase classname="record" name="checkout/coupon.md"""" in xml)
        assertTrue("<failure message=\"step 3 `Tap the &quot;Apply&quot; button`" in xml, xml)
        assertTrue("<skipped message=\"no trace\"/>" in xml)
    }

    // ------------------------------------------------------------------ CLI

    @Test
    fun `report command joins summary files, writes --out and gates with --check`() {
        val dir = tempDir()
        val record = summary("record", "llm", outcome("nav/a.md", "FAILED", mode = "llm", failedAt = 2, reason = "element not found"))
        val verify = summary("verify", "replay", outcome("nav/a.md", "PASSED"))
        RunSummaryJson.write(record, File(dir, "r/record.json"))
        RunSummaryJson.write(verify, File(dir, "r/verify.json"))

        val rc = reportCommand(listOf("r/record.json", "r/verify.json", "--format", "md", "--out", "r/report.md", "--title", "T", "--link", "Run=https://x/y"), dir)
        assertEquals(0, rc, "without --check the renderer never fails the job")
        val md = File(dir, "r/report.md").readText()
        assertTrue("## ❌ T" in md && "❌ regression suspected" in md, md)
        assertTrue("- **record** step 2 `Tap the \"Apply\" button` — element not found" in md, md)

        assertEquals(1, reportCommand(listOf("r/record.json", "r/verify.json", "--check", "--out", "r/x.md"), dir))
        assertEquals(2, reportCommand(listOf("r/missing.json"), dir))
        assertEquals(2, reportCommand(listOf("r/record.json", "--format", "pdf"), dir))
        assertEquals(2, reportCommand(emptyList(), dir), "bare `simul report` needs a .simul project to replay")
        assertEquals(2, reportCommand(listOf("nav/a.md"), dir), "`simul report <scenario.md>` is the same pipeline, scoped — it needs the project too")
        assertEquals(2, reportCommand(listOf("nav/a.md", "r/record.json"), dir), "scenario files and summaries never mix")
    }

    @Test
    fun `--check passes when every scenario is stable and fails on a flaky fresh trace`() {
        val dir = tempDir()
        RunSummaryJson.write(summary("record", "llm", outcome("nav/a.md", "PASSED", mode = "llm", traceUpdated = true)), File(dir, "r.json"))
        RunSummaryJson.write(summary("verify", "replay", outcome("nav/a.md", "PASSED")), File(dir, "v.json"))
        assertEquals(0, reportCommand(listOf("r.json", "v.json", "--check", "--out", "o.md"), dir))
        RunSummaryJson.write(summary("verify", "replay", outcome("nav/a.md", "FAILED", failedAt = 1)), File(dir, "v.json"))
        assertEquals(1, reportCommand(listOf("r.json", "v.json", "--check", "--out", "o.md"), dir), "TRACE_FLAKE gates")
    }

}
