package dev.nate.uiagent.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InitCommandTest {

    private fun tempDir(): File = File.createTempFile("simul-init", "").let { it.delete(); it.mkdirs(); it }

    @Test
    fun `init scaffolds config, authoring guide and claude skill`() {
        val dir = tempDir()
        assertEquals(0, initCommand(listOf("--app", "com.example.app"), dir))

        val config = File(dir, ".simul/config.yaml").readText()
        assertTrue("app: com.example.app" in config, config)
        assertTrue("sizeClass: small" in config)
        assertTrue(File(dir, ".simul/scenarios").isDirectory)

        val setup = File(dir, ".claude/skills/simul-scenarios/references/setup.md").readText()
        assertTrue(setup.startsWith("# 전제 상태와 진입") && "appState" in setup && "deeplink" in setup, "preconditions/entry reference must ship")

        val external = File(dir, ".claude/skills/simul-scenarios/references/external.md").readText()
        assertTrue(external.startsWith("# 외부 요소 목록") && "Press home" in external, "external-element reference must ship")

        val skill = File(dir, ".claude/skills/simul-scenarios/SKILL.md").readText()
        assertTrue(skill.startsWith("---\nname: simul"), skill.take(60))
        assertTrue("따옴표" in skill, "skill must carry the authoring rules itself")
        assertFalse(File(dir, ".claude/skills/simul-scenarios/references/scenarios.md").exists(), "scenarios.md was merged into SKILL.md")
        assertTrue("references/setup.md" in skill, "skill must point at the preconditions/entry reference")

        // the scaffolded config must parse with the shipped defaults
        val parsed = SimulConfig.load(File(dir, ".simul/config.yaml"))
        assertEquals("com.example.app", parsed.app)
        assertEquals(DisplayProfile(720, 1280, 320), parsed.display)
    }

    @Test
    fun `rerunning init never overwrites existing files`() {
        val dir = tempDir()
        initCommand(listOf("--app", "com.example.app"), dir)
        File(dir, ".simul/config.yaml").appendText("# team edit\n")
        assertEquals(0, initCommand(emptyList(), dir))
        assertTrue("# team edit" in File(dir, ".simul/config.yaml").readText())
    }

    @Test
    fun `init takes no CI flag — the nightly workflow belongs to the app repo (see README)`() {
        val dir = tempDir()
        assertEquals(2, initCommand(listOf("--ci", "github"), dir))
        assertTrue(!File(dir, ".github").exists())
    }
}
