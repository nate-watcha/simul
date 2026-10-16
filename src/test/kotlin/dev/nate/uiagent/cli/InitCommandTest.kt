package dev.nate.uiagent.cli

import dev.nate.uiagent.device.DisplayProfile
import dev.nate.uiagent.tempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InitCommandTest {


    @Test
    fun `init scaffolds config, authoring guide and claude skill`() {
        val dir = tempDir()
        assertEquals(0, initCommand(listOf("--app", "com.example.app"), dir))

        val config = File(dir, ".simul/config.yaml").readText()
        assertTrue("app: com.example.app" in config, config)
        assertTrue("sizeClass: small" in config)
        assertTrue(File(dir, ".simul/scenarios").isDirectory)

        val setup = File(dir, ".claude/skills/simul-scenarios/references/setup.md").readText()
        assertTrue("appState" in setup && "deeplink" in setup, "preconditions/entry reference must ship")

        val external = File(dir, ".claude/skills/simul-scenarios/references/external.md").readText()
        assertTrue(external.isNotBlank(), "external-element reference must ship")

        val skill = File(dir, ".claude/skills/simul-scenarios/SKILL.md").readText()
        assertTrue(skill.startsWith("---\nname: simul"), skill.take(60))
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

}
