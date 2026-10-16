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

    private fun verdict(baseline: String?, record: String?, verify: String?): VerdictKind {
        val phases = mutableListOf<RunSummary>()
        baseline?.let { phases += summary("baseline", "replay", outcome("nav/a.md", it, failedAt = 3.takeIf { _ -> it == "FAILED" }, skipReason = "no trace".takeIf { _ -> it == "SKIPPED" })) }
        record?.let { phases += summary("record", "llm", outcome("nav/a.md", it, mode = "llm", failedAt = 3.takeIf { _ -> it == "FAILED" }, traceUpdated = it == "PASSED")) }
        verify?.let { phases += summary("verify", "replay", outcome("nav/a.md", it, failedAt = 3.takeIf { _ -> it == "FAILED" })) }
        return PipelineReport.build("t", phases).rows.single().verdict
    }

    // ------------------------------------------------------------------ verdicts

    @Test
    fun `three-phase verdict matrix`() {
        assertEquals(VerdictKind.STABLE, verdict("PASSED", "PASSED", "PASSED"))
        assertEquals(VerdictKind.CHANGED, verdict("FAILED", "PASSED", "PASSED"), "baseline broke, fresh recording replays")
        assertEquals(VerdictKind.REGRESSION, verdict("FAILED", "FAILED", "FAILED"))
        assertEquals(VerdictKind.REGRESSION, verdict("FAILED", "FAILED", "PASSED"), "verify replayed the OLD trace — still a regression")
        assertEquals(VerdictKind.RECORD_FLAKE, verdict("PASSED", "FAILED", "PASSED"))
        assertEquals(VerdictKind.TRACE_FLAKE, verdict("PASSED", "PASSED", "FAILED"))
        assertEquals(VerdictKind.NEW, verdict("SKIPPED", "PASSED", "PASSED"), "no committed trace yet")
        assertEquals(VerdictKind.SKIPPED, verdict("SKIPPED", "SKIPPED", "SKIPPED"))
    }

    @Test
    fun `two-phase (record then verify) and single replay degrade sensibly`() {
        assertEquals(VerdictKind.STABLE, verdict(null, "PASSED", "PASSED"))
        assertEquals(VerdictKind.TRACE_FLAKE, verdict(null, "PASSED", "FAILED"))
        assertEquals(VerdictKind.REGRESSION, verdict(null, "FAILED", "PASSED"))
        assertEquals(VerdictKind.PASS, verdict("PASSED", null, null))
        assertEquals(VerdictKind.FAIL, verdict("FAILED", null, null))
    }

    @Test
    fun `crash dominates and roles follow mode order`() {
        val phases = listOf(
            summary("baseline", "replay", outcome("nav/a.md", "PASSED")),
            summary("record", "llm", outcome("nav/a.md", "CRASH", mode = null, skipReason = "OutOfMemoryError")),
            summary("verify", "replay", outcome("nav/a.md", "PASSED")),
        )
        val r = PipelineReport.build("t", phases)
        assertEquals(VerdictKind.CRASH, r.rows.single().verdict)
        assertEquals(listOf(Role.BASELINE, Role.RECORD, Role.VERIFY), r.phases.map { it.role })
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
            summary("baseline", "replay", outcome("nav/a.md", "PASSED"), outcome("checkout/coupon.md", "FAILED", failedAt = 3), outcome("deeplink/new.md", "SKIPPED", mode = null, skipReason = "no trace")),
            summary("record", "llm", outcome("nav/a.md", "PASSED", mode = "llm", traceUpdated = true), outcome("checkout/coupon.md", "PASSED", mode = "llm", traceUpdated = true), outcome("deeplink/new.md", "PASSED", mode = "llm", traceUpdated = true)),
            summary("verify", "replay", outcome("nav/a.md", "PASSED"), outcome("checkout/coupon.md", "PASSED"), outcome("deeplink/new.md", "PASSED")),
        ),
        links = listOf("Run" to "https://github.com/o/r/actions/runs/1"),
    )

    @Test
    fun `junit has one suite per phase and failures carry the reason`() {
        val xml = renderJunit(nightly())
        assertTrue("""<testsuite name="baseline (replay)" tests="3" failures="1" errors="0" skipped="1"""" in xml, xml)
        assertTrue("""<testsuite name="record (llm)" tests="3" failures="0"""" in xml, xml)
        assertTrue("""<testcase classname="baseline" name="checkout/coupon.md"""" in xml)
        assertTrue("<failure message=\"step 3 `Tap the &quot;Apply&quot; button`" in xml, xml)
        assertTrue("<skipped message=\"no trace\"/>" in xml)
    }

    // ------------------------------------------------------------------ CLI

    @Test
    fun `report command joins summary files, writes --out and gates with --check`() {
        val dir = tempDir()
        val baseline = summary("baseline", "replay", outcome("nav/a.md", "FAILED", failedAt = 2))
        val record = summary("record", "llm", outcome("nav/a.md", "FAILED", mode = "llm", failedAt = 2, reason = "element not found"))
        RunSummaryJson.write(baseline, File(dir, "r/baseline.json"))
        RunSummaryJson.write(record, File(dir, "r/record.json"))

        val rc = reportCommand(listOf("r/baseline.json", "r/record.json", "--format", "md", "--out", "r/report.md", "--title", "T", "--link", "Run=https://x/y"), dir)
        assertEquals(0, rc, "without --check the renderer never fails the job")
        val md = File(dir, "r/report.md").readText()
        assertTrue("## ❌ T" in md && "❌ regression suspected" in md, md)
        assertTrue("- **record** step 2 `Tap the \"Apply\" button` — element not found" in md, md)

        assertEquals(1, reportCommand(listOf("r/baseline.json", "r/record.json", "--check", "--out", "r/x.md"), dir))
        assertEquals(2, reportCommand(listOf("r/missing.json"), dir))
        assertEquals(2, reportCommand(listOf("r/baseline.json", "--format", "pdf"), dir))
        assertEquals(2, reportCommand(emptyList(), dir))
    }

    @Test
    fun `--check passes on warnings (changed, new) and fails only on failures`() {
        val dir = tempDir()
        RunSummaryJson.write(summary("baseline", "replay", outcome("nav/a.md", "FAILED", failedAt = 2)), File(dir, "b.json"))
        RunSummaryJson.write(summary("record", "llm", outcome("nav/a.md", "PASSED", mode = "llm", traceUpdated = true)), File(dir, "r.json"))
        RunSummaryJson.write(summary("verify", "replay", outcome("nav/a.md", "PASSED")), File(dir, "v.json"))
        assertEquals(0, reportCommand(listOf("b.json", "r.json", "v.json", "--check", "--out", "o.md"), dir), "CHANGED is a warning, not a gate failure")
        RunSummaryJson.write(summary("verify", "replay", outcome("nav/a.md", "FAILED", failedAt = 1)), File(dir, "v.json"))
        assertEquals(1, reportCommand(listOf("b.json", "r.json", "v.json", "--check", "--out", "o.md"), dir), "TRACE_FLAKE gates")
    }

}
