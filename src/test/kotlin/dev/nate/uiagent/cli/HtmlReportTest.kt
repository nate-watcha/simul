package dev.nate.uiagent.cli

import dev.nate.uiagent.tempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlReportTest {


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
    fun `gifs fold under a scenario's line, one per phase that recorded, copied into media next to the html`() {
        val root = tempDir()
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
        assertEquals("billing/play", areaOf("billing/play/trailer.md@member"))
        assertEquals("", areaOf("smoke.md"))
        val root = tempDir()
        fun ok(path: String) = ScenarioOutcome(path, path, emptyList(), "PASSED", "replay", 1000, null, false, 1, 0, 0, null, null)
        val r = PipelineReport.build("n", listOf(RunSummary("verify", "replay", "", 1, "0.2.0", null, null, listOf(ok("auth/a.md"), ok("billing/play/t.md"), ok("auth/b.md"), ok("smoke.md")))))
        val html = HtmlReport.render(r, root)
        val tiles = Regex("<a class=\"tile ok\" href=\"#(g-[^\"]+)\"><span class=name>([^<]*)</span>").findAll(html).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(listOf("g-auth" to "auth", "g-billing_play" to "billing/play", "g-root" to "(root)"), tiles)
        assertTrue(html.indexOf("auth/b.md") < html.indexOf("billing/play/t.md"), "an area's scenarios stay together")
    }

    @Test
    fun `report command writes the html and its debug page side by side`() {
        val root = tempDir()
        val ok = ScenarioOutcome("tabs", "nav/tabs.md", emptyList(), "PASSED", "replay", 8000, null, false, 4, 0, 0, null, null)
        RunSummaryJson.write(RunSummary("verify", "replay", "", 1000, "0.2.0", null, null, listOf(ok)), File(root, "v.json"))
        assertEquals(0, reportCommand(listOf("v.json", "--format", "html", "--out", "r/report.html"), root))
        val html = File(root, "r/report.html").readText()
        assertTrue("✅ 앱 정상" in html)
        assertTrue("<script" !in html && "verify (replay)" !in html, "the status board carries no scripts and no pipeline phases")
        assertTrue("<a href=\"report.debug.html\">debug</a>" in html && "href=\"report.debug.html#s0\"" in html)
        assertTrue("<a href=\"report.html#s0\">report</a>" in File(root, "r/report.debug.html").readText())
        assertTrue(!File(root, "r/media").exists(), "no gifs → no media dir")
        // other formats write only their file
        assertEquals(0, reportCommand(listOf("v.json", "--format", "md", "--out", "r/report.md"), root))
        assertTrue(!File(root, "r/report.debug.md").exists())
    }
}
