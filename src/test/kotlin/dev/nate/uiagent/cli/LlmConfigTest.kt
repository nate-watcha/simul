package dev.nate.uiagent.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LlmConfigTest {

    private fun load(yaml: String, env: Map<String, String> = emptyMap()) =
        LlmConfig.fromYaml(MiniYaml.parse(yaml), env::get)

    @Test
    fun `defaults - local llama-server, no key`() {
        val c = load("app: x\n")
        assertEquals(LlmConfig.DEFAULT_URL, c.url)
        assertEquals("qwen", c.model)
        assertNull(c.apiKey)
        assertNull(c.keyProblem())
    }

    @Test
    fun `apiKey env reference reads the environment`() {
        val c = load("llm:\n  url: https://api.example.com\n  model: gpt-x\n  apiKey: \${OPENAI_API_KEY}\n",
            mapOf("OPENAI_API_KEY" to "sk-test"))
        assertEquals("https://api.example.com", c.url)
        assertEquals("gpt-x", c.model)
        assertEquals("sk-test", c.apiKey)
        assertNull(c.keyProblem())
    }

    @Test
    fun `unset env reference is not a load error but refuses to build a client`() {
        val c = load("llm:\n  apiKey: \${OPENAI_API_KEY}\n")
        assertNull(c.apiKey)
        assertNotNull(c.keyProblem())
        assertFailsWith<IllegalStateException> { c.client() }
    }

    @Test
    fun `no apiKey falls back to SIMUL_LLM_API_KEY`() {
        val c = load("llm:\n  url: http://localhost:8080\n", mapOf(LlmConfig.DEFAULT_KEY_ENV to "k1"))
        assertEquals("k1", c.apiKey)
    }

    @Test
    fun `literal key is accepted as-is`() {
        assertEquals("literal", load("llm:\n  apiKey: literal\n").apiKey)
    }
}
