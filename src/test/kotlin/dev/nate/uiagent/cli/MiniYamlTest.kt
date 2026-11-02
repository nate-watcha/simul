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
    fun `literal block keeps its lines and drops the common indent`() {
        val y = MiniYaml.parse(
            """
            context: |
              Watcha is a subscription OTT app.
                Guests meet a login wall.   # not a comment: inside the block
              Payment goes to the Play sheet.
            app: com.example.app
            """.trimIndent()
        )
        assertEquals(
            "Watcha is a subscription OTT app.\n  Guests meet a login wall.   # not a comment: inside the block\nPayment goes to the Play sheet.",
            MiniYaml.string(y, "context"),
        )
        assertEquals("com.example.app", MiniYaml.string(y, "app"), "parsing resumes after the block")
        assertFailsWith<IllegalArgumentException> { MiniYaml.parse("context: |\napp: x") }
    }

    @Test
    fun `rejects malformed lines`() {
        assertFailsWith<IllegalArgumentException> { MiniYaml.parse("just some text") }
    }
}
