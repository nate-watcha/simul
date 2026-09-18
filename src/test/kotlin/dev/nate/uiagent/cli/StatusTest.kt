package dev.nate.uiagent.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatusTest {

    private fun project(): SimulProject {
        val root = File.createTempFile("simul-status", "").let { it.delete(); it.mkdirs(); it }
        File(root, ".simul/scenarios/guest").mkdirs()
        File(root, ".simul/config.yaml").writeText("app: com.x\n")
        return SimulProject(root)
    }

    private fun scenario(p: SimulProject, rel: String, name: String, setup: String = "  launch: true") {
        val f = File(p.scenariosDir, rel)
        f.parentFile.mkdirs()
        f.writeText("---\nname: $name\ntags: [t1]\nsetup:\n$setup\n---\n1. Tap the \"a\" button\n2. Verify \"b\" is shown\n")
    }

    private fun report(p: SimulProject, name: String, stamp: String, status: String, mode: String = "replay") {
        val d = File(p.reportsDir, "$name-$stamp"); d.mkdirs()
        File(d, "report.json").writeText("""{"scenario":"$name","status":"$status","mode":"$mode","durationMs":1234,"steps":[]}""")
    }

    @Test
    fun `joins scenario, trace freshness, precondition and latest run`() {
        val p = project()
        scenario(p, "guest/a.md", "guest-a")
        scenario(p, "member/b.md", "member-b", setup = "  appState: member-basic\n  launch: true")
        // trace for a: fresh; for b: none
        val aMd = File(p.scenariosDir, "guest/a.md")
        p.traceFileFor(aMd).writeText("{}")
        p.traceFileFor(aMd).setLastModified(aMd.lastModified() + 10_000)
        report(p, "guest-a", "20260101-010101", "FAILED")
        report(p, "guest-a", "20260102-010101", "PASSED")   // newer wins
        File(p.statesDir, "member-basic.tar").apply { parentFile.mkdirs(); writeText("x") }

        val r = collectStatus(p)
        val a = r.scenarios.first { it.name == "guest-a" }
        assertEquals(TraceState.OK, a.trace)
        assertEquals(2, a.steps)
        assertNull(a.precondition)
        assertEquals(true, a.lastRun?.passed, "newest report must win")

        val b = r.scenarios.first { it.name == "member-b" }
        assertEquals(TraceState.NONE, b.trace)
        assertEquals("member-basic", b.precondition)
        assertNull(b.lastRun)

        assertEquals(listOf("member-basic"), r.states.map { it.name })
    }

    @Test
    fun `stale trace and prefix-named scenarios do not cross-match reports`() {
        val p = project()
        scenario(p, "guest/a.md", "coupon")
        scenario(p, "guest/b.md", "coupon-extra")
        val aMd = File(p.scenariosDir, "guest/a.md")
        p.traceFileFor(aMd).writeText("{}")
        p.traceFileFor(aMd).setLastModified(aMd.lastModified() - 100_000) // older than md
        report(p, "coupon-extra", "20260101-010101", "PASSED")

        val r = collectStatus(p)
        assertEquals(TraceState.STALE, r.scenarios.first { it.name == "coupon" }.trace)
        assertNull(r.scenarios.first { it.name == "coupon" }.lastRun, "coupon must not match coupon-extra reports")
        assertEquals(true, r.scenarios.first { it.name == "coupon-extra" }.lastRun?.passed)
    }

    @Test
    fun `renders aligned table without color when disabled`() {
        val p = project()
        scenario(p, "guest/a.md", "guest-a", setup = "  clearData: true\n  launch: true")
        val out = renderStatus(collectStatus(p), color = false)
        assertTrue("SCENARIO" in out && "guest/a.md" in out, out)
        assertTrue("none" in out && "never" in out, out)
        assertTrue("guest" in out, "clearData precondition must render as guest\n$out")
        assertTrue("\u001B" !in out, "no ANSI when color=false")
    }

    @Test
    fun `crash log persists the stack trace under reports`() {
        val p = project()
        val f = writeCrashLog(p.reportsDir, "billing-cash-page", RuntimeException("boom"))
        assertTrue(f.name.startsWith("billing-cash-page-crash-"), f.name)
        val text = f.readText()
        assertTrue("RuntimeException" in text && "boom" in text, text.take(200))
    }

    @Test
    fun `feature group is the top-level directory`() {
        assertEquals("bottom_nav", featureGroup("bottom_nav/basic.md"))
        assertEquals("(root)", featureGroup("loose.md"))
    }

    @Test
    fun `run --state filters by required precondition`() {
        val p = project()
        scenario(p, "guest/a.md", "guest-a", setup = "  clearData: true\n  launch: true")
        scenario(p, "member/b.md", "member-b", setup = "  appState: member-basic\n  launch: true")
        scenario(p, "misc/c.md", "misc-c")

        val out = java.io.ByteArrayOutputStream()
        val old = System.out
        System.setOut(java.io.PrintStream(out, true))
        try {
            assertEquals(0, runCommand(listOf("--state", "guest", "--dry-run"), p.appRoot))
        } finally {
            System.setOut(old)
        }
        val text = out.toString()
        assertTrue("guest-a" in text, text)
        assertTrue("member-b" !in text && "misc-c" !in text, text)
    }
}
