package dev.nate.uiagent.cli

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

    @Test
    fun `html is self-contained with verdicts, steps, on-screen labels and an inlined thumbnail`() {
        val root = root()
        val rel = reportDir(root, "coupon", """
            {"step":1,"text":"Tap the \"Coupons\" menu","status":"PASSED","mode":"replay","actions":[],"evidence":["Coupons"],"reason":null,"durationMs":4200,"screenshots":["screenshots/step1-1-tap-after.png"]},
            {"step":2,"text":"Tap the \"Apply\" button","status":"FAILED","mode":"replay","actions":[],"evidence":[],"reason":"replay broken: cannot re-ground target (label=Apply)","durationMs":9000,"screenshots":["screenshots/step2-failed.png"],"screen":["Coupons","Register coupon"]},
            {"step":3,"text":"Verify \"Done\" is shown","status":"SKIPPED","mode":null,"actions":[],"evidence":[],"reason":null,"durationMs":0,"screenshots":[]}
        """.trimIndent())
        val failed = ScenarioOutcome("coupon", "checkout/coupon.md", listOf("billing"), "FAILED", "replay", 13_200, rel, false, 1, 1, 1,
            FailedStep(2, "Tap the \"Apply\" button", "replay broken: cannot re-ground target (label=Apply)", "$rel/screenshots/step2-failed.png", listOf("Coupons", "Register coupon")), null)
        val ok = ScenarioOutcome("tabs", "nav/tabs.md", emptyList(), "PASSED", "replay", 8000, ".simul/reports/missing", false, 4, 0, 0, null, null)
        val r = PipelineReport.build("simul nightly", listOf(RunSummary("baseline", "replay", "2026-09-21T02:00:00+09:00", 30_000, "0.2.0", "1.26.8", "emu", listOf(failed, ok), androidCli = "1.0.16261425")),
            links = listOf("Run" to "https://x/y"))

        val html = HtmlReport.render(r, root)

        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue("<title>simul nightly</title>" in html)
        assertTrue("android CLI 1.0.16261425" in html)
        assertTrue("<a href=\"https://x/y\">Run</a>" in html)
        assertTrue("checkout/coupon.md" in html && "❌ failed" in html, "verdict row")
        assertTrue("<li class=\"st-failed\">" in html && "cannot re-ground target" in html, "failed step with reason")
        assertTrue("on screen: <code>Coupons</code> · <code>Register coupon</code>" in html)
        assertTrue("data:image/jpeg;base64," in html, "failed step screenshot inlined as a thumbnail")
        assertTrue("evidence: Coupons" in html, "passed steps show their evidence")
        assertTrue("<details id=\"s0\" class=\"sev-fail\" open>" in html, "attention scenarios start expanded")
        assertTrue("nav/tabs.md" in html && "<details id=\"s1\" class=\"sev-ok\">" in html, "missing report dir degrades gracefully")
        assertTrue("<script" !in html, "no scripts — safe to open from an artifact")
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
    fun `report command writes html`() {
        val root = root()
        val ok = ScenarioOutcome("tabs", "nav/tabs.md", emptyList(), "PASSED", "replay", 8000, null, false, 4, 0, 0, null, null)
        RunSummaryJson.write(RunSummary("verify", "replay", "", 1000, "0.2.0", null, null, listOf(ok)), File(root, "v.json"))
        assertEquals(0, reportCommand(listOf("v.json", "--format", "html", "--out", "r.html"), root))
        assertTrue(File(root, "r.html").readText().contains("✅ stable") || File(root, "r.html").readText().contains("✅ passed"))
    }
}
