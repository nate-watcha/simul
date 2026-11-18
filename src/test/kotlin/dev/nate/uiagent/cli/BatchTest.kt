package dev.nate.uiagent.cli

import dev.nate.uiagent.tempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatchTest {

    private fun project(): SimulProject {
        val root = tempDir()
        File(root, ".simul/scenarios").mkdirs()
        File(root, ".simul/config.yaml").writeText(
            """
            app: com.example.app
            llm:
              url: http://localhost:8080
            display:
              sizeClass: small
            device:
              settings:
                secure.autofill_service: null
            """.trimIndent()
        )
        File(root, ".simul/scenarios/home.md").writeText("---\nname: home\nsetup:\n  launch: true\n---\n1. \"홈\" 탭을 누른다\n")
        return SimulProject.find(root)!!
    }

    @Test
    fun `a physical device is woken, and pinned only when it is marked for simul`() {
        val p = project()
        val own = FakeOps().apply { emulator = false; display = "1080x2400@420"; settings["secure.autofill_service"] = "x" }
        val ownLog = mutableListOf<String>()
        BatchSession(p, own, ownLog::add).use { it.pin() }
        assertEquals(1, own.woken)
        assertTrue(own.display == "1080x2400@420" && own.settingLog.isEmpty(), "a person's phone is left as it is")
        assertEquals(2, ownLog.count { "warning:" in it && "simul_device" in it }, "the display and the settings each say why: $ownLog")

        val placed = FakeOps().apply { emulator = false; marked = true; display = "1080x2400@420"; settings["secure.autofill_service"] = "x" }
        BatchSession(p, placed, {}).use {
            it.pin()
            assertEquals("720x1280@320", placed.display)
            assertEquals(listOf("delete secure.autofill_service"), placed.settingLog)
        }
        assertEquals("1080x2400@420", placed.display, "put back on exit")
        assertEquals("x", placed.settings["secure.autofill_service"])
    }

    @Test
    fun `a device that does not answer is neither pinned nor run against, and every scenario says so`() {
        val p = project()
        val ops = FakeOps().apply { down = "adb shell does not answer"; settings["secure.autofill_service"] = "x" }
        val logged = mutableListOf<String>()
        val md = File(p.scenariosDir, "home.md")
        assertTrue(p.config.display != null && p.config.device.settings.isNotEmpty(), "there is something to pin")

        val outcome = BatchSession(p, ops, logged::add).use { batch ->
            batch.pin()
            batch.run(md, ScenarioMd.parse(md).variants().single(), RunMode.LLM, p.config.llm).outcome
        }

        assertEquals("CRASH", outcome.status)
        assertEquals("not run — device not responding: adb shell does not answer", outcome.reason)
        assertTrue(ops.setups.isEmpty() && ops.settingLog.isEmpty() && ops.display == null, "the device was left alone")
        assertTrue(logged.any { it.startsWith("device: not responding") }, "$logged")
    }

    // ------------------------------------------------------------------ network

    private fun runHome(p: SimulProject, ops: FakeOps, logged: MutableList<String>, file: String = "home.md"): List<BatchResult> {
        val md = File(p.scenariosDir, file)
        val scenario = ScenarioMd.parse(md).variants().single()
        // no pauses: what is waited for is what the fake device answers
        return BatchSession(p, ops, logged::add, pause = {}).use { batch ->
            batch.pin()
            List(2) { batch.run(md, scenario, RunMode.LLM, p.config.llm) }
        }
    }

    @Test
    fun `a device without a network that airplane mode does not bring back runs nothing, and says why once`() {
        val p = project()
        val ops = FakeOps().apply { offline = true }
        val logged = mutableListOf<String>()

        val results = runHome(p, ops, logged)

        assertTrue(results.all { it.deviceGone && it.outcome.status == "CRASH" }, "$logged")
        assertEquals("not run — device has no network: the device does not reach the internet, also after airplane mode on and off", results[0].outcome.reason)
        assertEquals(listOf("airplane on", "airplane off"), ops.airplaneLog, "tried once; the second scenario does not try again")
        assertTrue(ops.setups.isEmpty(), "nothing was started")
        assertEquals(1, logged.count { it.startsWith("device: no network") }, "$logged")
    }

    @Test
    fun `a person's phone without a network is not touched, and runs nothing`() {
        val p = project()
        val ops = FakeOps().apply { emulator = false; offline = true }

        val results = runHome(p, ops, mutableListOf())

        assertTrue(results.all { it.deviceGone })
        assertEquals("not run — device has no network: the device does not reach the internet", results[0].outcome.reason)
        assertTrue(ops.airplaneLog.isEmpty())
    }

    @Test
    fun `a scenario needs a network unless its setup or the project takes it away`() {
        val p = project()
        fun scenario(device: String) = ScenarioMd.parse("---\nname: s\nsetup:\n  launch: true\n$device---\n1. \"다시 시도\"가 보이는지 확인한다\n")
        assertTrue(needsNetwork(scenario(""), p.config))
        assertTrue(!needsNetwork(scenario("  device:\n    network: off\n"), p.config))
        assertTrue(needsNetwork(ScenarioMd.parse("1. \"홈\" 탭을 누른다\n"), p.config), "also one that opens nothing itself")
        // the project's baseline, and a scenario that says the opposite
        val offline = p.config.copy(device = p.config.device.copy(network = false))
        assertTrue(!needsNetwork(scenario(""), offline))
        assertTrue(needsNetwork(scenario("  device:\n    network: on\n"), offline))
    }
}
