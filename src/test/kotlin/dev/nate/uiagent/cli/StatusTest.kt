package dev.nate.uiagent.cli

import dev.nate.uiagent.tempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatusTest {

    private fun project(): SimulProject {
        val root = tempDir()
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
        File(d, "result.json").writeText("""{"scenario":"$name","status":"$status","mode":"$mode","duration_ms":1234,"steps":[]}""")
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

}
