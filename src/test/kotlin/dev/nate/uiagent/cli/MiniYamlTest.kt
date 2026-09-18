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
    fun `parses frontmatter with inline list and nested setup`() {
        val y = MiniYaml.parse(
            """
            name: browse-greenbook
            tags: [smoke, subscription]
            setup:
              deeplink: myapp://tab/subscription
            """.trimIndent()
        )
        assertEquals("browse-greenbook", MiniYaml.string(y, "name"))
        assertEquals(listOf("smoke", "subscription"), MiniYaml.list(y, "tags"))
        assertEquals("myapp://tab/subscription", MiniYaml.string(y, "setup", "deeplink"))
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val y = MiniYaml.parse("# header\n\nkey: value\n  # indented comment\n")
        assertEquals("value", MiniYaml.string(y, "key"))
    }

    @Test
    fun `rejects malformed lines`() {
        assertFailsWith<IllegalArgumentException> { MiniYaml.parse("just some text") }
    }
}
