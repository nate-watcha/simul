package dev.nate.uiagent.cli

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportTest {

    private fun outcome(
        path: String, status: String, mode: String? = "replay", failedAt: Int? = null,
        reason: String? = null, traceUpdated: Boolean = false, skipReason: String? = null,
    ) = ScenarioOutcome(
        name = path.substringAfterLast('/').removeSuffix(".md"), path = path, tags = listOf("smoke"),
        status = status, mode = mode, durationMs = 12_300, reportDir = ".simul/reports/x-20260919-020000",
        traceUpdated = traceUpdated, passedSteps = 2, failedSteps = if (failedAt != null) 1 else 0, skippedSteps = 0,
        failedStep = failedAt?.let { FailedStep(it, "Tap the \"Apply\" button", reason ?: "replay broken: evidence not on screen", "shots/step$it-1-tap-after.png", listOf("Coupons", "Register coupon")) },
        reason = skipReason,
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
        return PipelineReport.build("t", phases).rows.single().verdict.kind
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
        assertEquals(VerdictKind.CRASH, r.rows.single().verdict.kind)
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
        assertEquals(VerdictKind.FAIL, b.verdict.kind)
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
    fun `markdown has the phase table, verdicts and an attention section with reasons`() {
        val md = renderMarkdown(nightly())
        assertTrue(md.startsWith("## ⚠️ simul nightly 2026-09-19"), md.lineSequence().first())
        assertTrue("| Scenario | baseline (replay) | record (llm) | verify (replay) | Verdict |" in md, md)
        assertTrue("| `checkout/coupon.md` | ❌ step 3 | ✅ 12s | ✅ 12s | 🔁 screen changed — trace re-recorded |" in md, md)
        assertTrue("| `deeplink/new.md` | ⏭ no trace | ✅ 12s | ✅ 12s | 🆕 first trace recorded |" in md, md)
        assertTrue("### Needs attention" in md)
        assertTrue("step 3 `Tap the \"Apply\" button` — replay broken: evidence not on screen" in md, md)
        assertTrue("checkout/coupon.trace.json" in md, "must point at the re-recorded trace\n$md")
        assertTrue("- on screen: `Coupons` · `Register coupon`" in md, "failed steps list what the screen showed\n$md")
        assertTrue("[Run](https://github.com/o/r/actions/runs/1)" in md)
        assertTrue("app 5.4.0" in md && "sdk_gphone64_arm64" in md && "android CLI 1.0.16261425" in md, md)
        assertTrue("nav/a.md" !in md.substringAfter("### Needs attention"), "stable rows stay out of the attention list")
    }

    @Test
    fun `slack payload is valid block kit with fallback text, escaped mrkdwn and a link context`() {
        val r = nightly()
        val payload = dev.nate.uiagent.json.parseToJsonElement(renderSlack(r)).jsonObject
        assertTrue(payload["text"]!!.jsonPrimitive.content.startsWith("⚠️ simul nightly 2026-09-19: 2 need attention"))
        val blocks = payload["blocks"]!!.jsonArray
        assertEquals("header", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(blocks.size in 4..6, "header + counts + 2 attention + context, got ${blocks.size}")
        val text = blocks.joinToString { it.toString() }
        assertTrue("checkout/coupon.md" in text && "evidence not on screen" in text, text)
        assertTrue("on screen: Coupons · Register coupon" in text, text)
        assertTrue("<https://github.com/o/r/actions/runs/1|Run>" in text, text)
        assertTrue("\"&lt;" !in renderSlack(r) || "<" !in renderSlack(r).substringAfter("Apply"), "mrkdwn control chars must be escaped")
    }

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
        val dir = File.createTempFile("simul-report", "").let { it.delete(); it.mkdirs(); it }
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
        val dir = File.createTempFile("simul-report", "").let { it.delete(); it.mkdirs(); it }
        RunSummaryJson.write(summary("baseline", "replay", outcome("nav/a.md", "FAILED", failedAt = 2)), File(dir, "b.json"))
        RunSummaryJson.write(summary("record", "llm", outcome("nav/a.md", "PASSED", mode = "llm", traceUpdated = true)), File(dir, "r.json"))
        RunSummaryJson.write(summary("verify", "replay", outcome("nav/a.md", "PASSED")), File(dir, "v.json"))
        assertEquals(0, reportCommand(listOf("b.json", "r.json", "v.json", "--check", "--out", "o.md"), dir), "CHANGED is a warning, not a gate failure")
        RunSummaryJson.write(summary("verify", "replay", outcome("nav/a.md", "FAILED", failedAt = 1)), File(dir, "v.json"))
        assertEquals(1, reportCommand(listOf("b.json", "r.json", "v.json", "--check", "--out", "o.md"), dir), "TRACE_FLAKE gates")
    }

    @Test
    fun `humanDuration formats seconds, minutes and hours`() {
        assertEquals("12s", humanDuration(12_300))
        assertEquals("1m24s", humanDuration(84_000))
        assertEquals("2h05m", humanDuration(2 * 3600_000L + 5 * 60_000L))
    }
}
