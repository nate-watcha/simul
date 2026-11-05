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
}
