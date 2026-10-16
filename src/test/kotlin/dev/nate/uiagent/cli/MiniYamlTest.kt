package dev.nate.uiagent.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MiniYamlTest {

    @Test
    fun `parses the config yaml shape`() {
        val y = MiniYaml.parse(
            """
            agentVersion: ">=0.2"        # minimum tool version
            app: com.example.app
            llm:
              url: http://localhost:8080
            defaults:
              maxTurns: 12
              scrollLimit: 3
            """.trimIndent()
        )
        assertEquals(">=0.2", MiniYaml.string(y, "agentVersion"))
        assertEquals("com.example.app", MiniYaml.string(y, "app"))
        assertEquals("http://localhost:8080", MiniYaml.string(y, "llm", "url"))
        assertEquals("12", MiniYaml.string(y, "defaults", "maxTurns"))
    }

    @Test
    fun `rejects malformed lines`() {
        assertFailsWith<IllegalArgumentException> { MiniYaml.parse("just some text") }
    }
}
