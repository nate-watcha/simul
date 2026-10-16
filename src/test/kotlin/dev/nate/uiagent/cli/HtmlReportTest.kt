package dev.nate.uiagent.cli

import dev.nate.uiagent.agent.LlmStats
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlReportTest {

    private fun root(): File = File.createTempFile("simul-html", "").let { it.delete(); it.mkdirs(); it }

    private fun reportDir(root: File, name: String, stepsJson: String): String {
        val rel = ".simul/reports/$name-20260921-020000"
        val dir = File(root, rel).apply { File(this, "screenshots").mkdirs() }
        File(dir, "report.json").writeText("""{"scenario":"$name","status":"FAILED","mode":"replay","steps":[$stepsJson]}""")
        ImageIO.write(BufferedImage(720, 1280, BufferedImage.TYPE_INT_ARGB), "png", File(dir, "screenshots/step2-failed.png"))
        return rel
    }

    private val couponSteps = """
        {"step":1,"text":"Tap the \"Coupons\" menu","status":"PASSED","mode":"replay","actions":[],"evidence":["Coupons"],"reason":null,"durationMs":4200,"screenshots":["screenshots/step1-1-tap-after.png"]},
        {"step":2,"text":"Tap the \"Apply\" button","status":"FAILED","mode":"replay","actions":[],"evidence":[],"reason":"replay broken: cannot re-ground target (label=Apply)","durationMs":9000,"screenshots":["screenshots/step2-failed.png"],"screen":["Coupons","Register coupon"]},
        {"step":3,"text":"Verify \"Done\" is shown","status":"SKIPPED","mode":null,"actions":[],"evidence":[],"reason":null,"durationMs":0,"screenshots":[]}
    """.trimIndent()

    @Test
    fun `the report answers does-the-app-work - headline, area tiles, the broken ones with their step, checklists per area`() {
        val root = root()
        val rel = reportDir(root, "coupon", couponSteps)
        File(root, ".simul/scenarios/checkout").mkdirs()
        File(root, ".simul/scenarios/checkout/README.md").writeText("# 결제\n\n쿠폰·구독 결제 흐름\n")
        val failed = ScenarioOutcome("coupon", "checkout/coupon.md", listOf("billing"), "FAILED", "replay", 13_200, rel, false, 1, 1, 1,
            FailedStep(2, "Tap the \"Apply\" button", "replay broken: cannot re-ground target (label=Apply)", "$rel/screenshots/step2-failed.png", listOf("Coupons", "Register coupon")), null,
            description = "쿠폰을 등록하면 결제 화면에 할인이 적용된다.")
        val ok = ScenarioOutcome("tabs", "nav/tabs.md", emptyList(), "PASSED", "replay", 8000, ".simul/reports/missing", false, 4, 0, 0, null, null)
        val r = PipelineReport.build("simul nightly", listOf(RunSummary("baseline", "replay", "2026-09-21T02:00:00+09:00", 30_000, "0.2.0", "1.26.8", "emu", listOf(ok, failed), androidCli = "1.0.16261425")),
            links = listOf("Run" to "https://x/y"))

        val html = HtmlReport.render(r, root, debugHref = "report.debug.html")

        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue("<title>simul nightly</title>" in html)
        assertTrue("<p class=\"headline broken\">❌ 1개 안 됨</p>" in html, "the one answer first")
        assertTrue("<p class=counts>2개 시나리오 · ❌ 1 안 됨 · ✅ 1 동작</p>" in html)
        assertTrue("android CLI 1.0.16261425" in html && "<a href=\"https://x/y\">Run</a>" in html && "<a href=\"report.debug.html\">debug</a>" in html)
        // tiles: one per directory, broken areas first, README first line as the name
        val tiles = Regex("<a class=\"tile ([a-z]+)\" href=\"#([^\"]+)\"><span class=name>([^<]*)</span>").findAll(html).map { it.groupValues.drop(1) }.toList()
        assertEquals(listOf(listOf("broken", "g-checkout", "결제"), listOf("ok", "g-nav", "nav")), tiles)
        assertTrue("<span class=dir>checkout/</span><span class=state>❌ 1 안 됨</span>" in html, "tile shows its dir under the README name and its counts")
        // the broken section: description, failing step, reason, what was on screen, screenshot
        assertTrue("<section class=broken><h2>안 되는 것</h2><article id=\"b1\" class=\"item broken\">" in html)
        assertTrue("<div class=desc>❌ 쿠폰을 등록하면 결제 화면에 할인이 적용된다.</div>" in html)
        assertTrue("❌ <b>baseline</b> · step 2 <q>Tap the &quot;Apply&quot; button</q>" in html, "which step failed")
        assertTrue("<p class=reason>replay broken: cannot re-ground target (label=Apply)</p>" in html)
        assertTrue("on screen: <code>Coupons</code> · <code>Register coupon</code>" in html)
        assertTrue("<figure class=shot><img src=\"data:image/jpeg;base64," in html, "failing screenshot inlined")
        assertTrue("<tr class=\"st-failed\"><td class=n>2</td><td class=t>Tap the &quot;Apply&quot; button</td><td class=m>❌</td></tr>" in html, "steps folded as a grid")
        assertTrue("<a href=\"report.debug.html#s1\">debug</a>" in html)
        // the checklists: an area section per directory, each scenario a line with its state and where it broke
        assertTrue("<section class=area id=\"g-checkout\"><h2>결제 <span class=dir>checkout/</span>" in html)
        assertTrue("<li id=\"s1\" class=\"broken\"><details><summary><span class=icon>❌</span><span class=desc>쿠폰을 등록하면 결제 화면에 할인이 적용된다.</span><span class=\"tag broken\">step 2</span><span class=path>checkout/coupon.md</span></summary>" in html)
        assertTrue("<li id=\"s0\" class=\"ok\"><details><summary><span class=icon>✅</span><span class=desc>tabs</span><span class=path>nav/tabs.md</span></summary>" in html, "no description → name; nothing to tag")
        assertTrue("녹화 없음" in html, "no gif anywhere → says so instead of an empty body")
        // nothing from the pipeline or the debug page leaks in
        assertTrue("baseline (replay)" !in html && "evidence:" !in html && "(replay, " !in html && "tok" !in html, "no phase columns / evidence / mode / duration / token noise")
        assertTrue("<script" !in html, "no scripts — safe to open from an artifact")
    }

    @Test
    fun `a clean night says so and has no broken section`() {
        val root = root()
        val ok = ScenarioOutcome("tabs", "nav/tabs.md", emptyList(), "PASSED", "replay", 8000, null, false, 4, 0, 0, null, null, description = "하단 탭으로 오간다.")
        val changed = ScenarioOutcome("x", "nav/x.md", emptyList(), "FAILED", "replay", 8000, null, false, 1, 1, 0, FailedStep(2, "Tap", "replay broken", null), null)
        val recorded = changed.copy(status = "PASSED", mode = "llm", failedStep = null, traceUpdated = true)
        val r = PipelineReport.build("n", listOf(
            RunSummary("baseline", "replay", "", 1, "0.2.0", null, null, listOf(ok, changed)),
            RunSummary("record", "llm", "", 1, "0.2.0", null, null, listOf(ok, recorded)),
            RunSummary("verify", "replay", "", 1, "0.2.0", null, null, listOf(ok, changed.copy(status = "PASSED", failedStep = null))),
        ))
        val html = HtmlReport.render(r, root)
        assertTrue("<p class=\"headline changed\">⚠️ 앱 정상, 확인할 게 1개</p>" in html, html.substringBefore("</header>"))
        assertTrue("<section class=broken>" !in html)
        assertTrue("<section class=review><h2>확인 필요" in html && "🔁 <a href=\"#s1\">x</a>" in html && ".simul/scenarios/nav/x.trace.json" in html, "re-recorded scenario listed for review")
        assertTrue("<span class=\"tag changed\">바뀜 · step 2</span>" in html, "a re-recorded one says where the old trace broke")
        val clean = HtmlReport.render(PipelineReport.build("n", listOf(RunSummary("verify", "replay", "", 1, "0.2.0", null, null, listOf(ok)))), root)
        assertTrue("<p class=\"headline ok\">✅ 앱 정상</p>" in clean && "<section class=review>" !in clean)
    }

    @Test
    fun `the debug page keeps every step with mode, duration, llm usage, evidence and the failing screenshots`() {
        val root = root()
        val rel = reportDir(root, "coupon", couponSteps)
        val record = ScenarioOutcome("coupon", "checkout/coupon.md", emptyList(), "FAILED", "llm", 13_200, rel, false, 1, 1, 1,
            FailedStep(2, "Tap the \"Apply\" button", "replay broken: cannot re-ground target (label=Apply)", null), null, llm = LlmStats(2, 1700, 3700, 50, 2200))
        val r = PipelineReport.build("simul nightly", listOf(RunSummary("record", "llm", "", 1, "0.2.0", null, null, listOf(record))))

        val html = HtmlReport.renderDebug(r, root, reportHref = "report.html")

        assertTrue("<title>simul nightly — debug</title>" in html)
        assertTrue("record (llm): model 1s of 13s wall · 2 calls" in html, "llm usage lines live here")
        assertTrue("<a href=\"report.html#s0\">report</a>" in html, "links back to the card")
        assertTrue("<li class=\"st-passed\"><span class=st>PASSED</span> <code>Tap the &quot;Coupons&quot; menu</code> <span class=dim>(replay, 4s)</span>" in html)
        assertTrue("evidence: Coupons" in html)
        assertTrue("<li class=\"st-failed\">" in html && "cannot re-ground target" in html && "on screen: <code>Coupons</code>" in html)
        assertTrue("data:image/jpeg;base64," in html, "failed step screenshot inlined")
    }

    @Test
    fun `gifs fold under a scenario's line, one per phase that recorded, copied into media next to the html`() {
        val root = root()
        val rel = reportDir(root, "tabs", """{"step":1,"text":"Tap \"Home\"","status":"PASSED","mode":"replay","actions":[],"evidence":[],"reason":null,"durationMs":1000,"screenshots":[]}""")
        File(root, "$rel/run.gif").writeBytes(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61))
        val baseline = ScenarioOutcome("tabs", "nav/tabs.md", emptyList(), "PASSED", "replay", 8000, rel, false, 1, 0, 0, null, null, gif = "$rel/run.gif")
        val record = ScenarioOutcome("tabs", "nav/tabs.md", emptyList(), "PASSED", "llm", 30_000, rel, true, 1, 0, 0, null, null)
        val verify = ScenarioOutcome("tabs", "nav/tabs.md", emptyList(), "PASSED", "replay", 7000, rel, false, 1, 0, 0, null, null, gif = "$rel/run.gif")
        val r = PipelineReport.build("n", listOf(
            RunSummary("baseline", "replay", "", 1, "0.2.0", null, null, listOf(baseline)),
            RunSummary("record", "llm", "", 1, "0.2.0", null, null, listOf(record)),
            RunSummary("verify", "replay", "", 1, "0.2.0", null, null, listOf(verify)),
        ))
        val outDir = File(root, "out").apply { mkdirs() }

        val html = HtmlReport.render(r, root, mediaDir = File(outDir, "media"))

        assertTrue(File(outDir, "media/nav_tabs.md-baseline.gif").length() == 6L && File(outDir, "media/nav_tabs.md-verify.gif").length() == 6L, "gifs copied next to the html")
        assertTrue("</summary><div class=body><div class=gifs><figure class=gif><img src=\"media/nav_tabs.md-baseline.gif\"" in html, "gifs open under the line")
        assertEquals(2, Regex("<figure class=gif>").findAll(html).count(), "the record phase (no gif) shows none")
        assertTrue("data:image/gif" !in html, "gifs are linked, never inlined")
        assertTrue("<th>baseline</th><th>record</th><th>verify</th>" in html && "<td class=m>✅</td><td class=m>✅</td><td class=m>✅</td>" in html, "step grid has a column per phase")
        assertTrue("녹화 없음" !in html)

        // without a media dir (stdout), the page points at the gif where it lies under the root
        assertTrue("<img src=\"$rel/run.gif\"" in HtmlReport.render(r, root))
    }

    @Test
    fun `areas are directories, nested ones included, root-level scenarios last under root`() {
        assertEquals("billing/play", HtmlReport.groupOf("billing/play/trailer.md@member"))
        assertEquals("", HtmlReport.groupOf("smoke.md"))
        val root = root()
        fun ok(path: String) = ScenarioOutcome(path, path, emptyList(), "PASSED", "replay", 1000, null, false, 1, 0, 0, null, null)
        val r = PipelineReport.build("n", listOf(RunSummary("verify", "replay", "", 1, "0.2.0", null, null, listOf(ok("auth/a.md"), ok("billing/play/t.md"), ok("auth/b.md"), ok("smoke.md")))))
        val html = HtmlReport.render(r, root)
        val tiles = Regex("<a class=\"tile ok\" href=\"#(g-[^\"]+)\"><span class=name>([^<]*)</span>").findAll(html).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(listOf("g-auth" to "auth", "g-billing_play" to "billing/play", "g-root" to "(root)"), tiles)
        assertTrue(html.indexOf("auth/b.md") < html.indexOf("billing/play/t.md"), "an area's scenarios stay together")
    }

    @Test
    fun `thumbnail downsizes to the requested width and unreadable files yield null`() {
        val root = root()
        val png = File(root, "shot.png").also { ImageIO.write(BufferedImage(720, 1280, BufferedImage.TYPE_INT_ARGB), "png", it) }
        val uri = HtmlReport.thumbnail(png, width = 100)!!
        val bytes = java.util.Base64.getDecoder().decode(uri.substringAfter("base64,"))
        val img = ImageIO.read(bytes.inputStream())
        assertEquals(100 to 177, img.width to img.height)
        assertEquals(null, HtmlReport.thumbnail(File(root, "nope.png")))
    }

    @Test
    fun `report command writes the html and its debug page side by side`() {
        val root = root()
        val ok = ScenarioOutcome("tabs", "nav/tabs.md", emptyList(), "PASSED", "replay", 8000, null, false, 4, 0, 0, null, null)
        RunSummaryJson.write(RunSummary("verify", "replay", "", 1000, "0.2.0", null, null, listOf(ok)), File(root, "v.json"))
        assertEquals(0, reportCommand(listOf("v.json", "--format", "html", "--out", "r/report.html"), root))
        val html = File(root, "r/report.html").readText()
        assertTrue("✅ 앱 정상" in html)
        assertTrue("<a href=\"report.debug.html\">debug</a>" in html && "href=\"report.debug.html#s0\"" in html)
        assertTrue("<a href=\"report.html#s0\">report</a>" in File(root, "r/report.debug.html").readText())
        assertTrue(!File(root, "r/media").exists(), "no gifs → no media dir")
        // other formats write only their file
        assertEquals(0, reportCommand(listOf("v.json", "--format", "md", "--out", "r/report.md"), root))
        assertTrue(!File(root, "r/report.debug.md").exists())
    }
}
