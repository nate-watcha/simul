package dev.nate.uiagent.cli

import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SimulConfigTest {

    private fun load(yaml: String) = SimulConfig.load(createTempFile("config", ".yaml").also { it.writeText(yaml) }.toFile())

    private fun scenario(appState: String?) = Scenario("s", emptyList(),
        Scenario.Setup(deeplink = null, launch = true, appState = appState), listOf("Tap \"x\""))

    @Test
    fun `context and states reach the account line of a scenario`() {
        val c = load(
            """
            app: com.example.app
            context: |
              구독형 OTT 앱이다.
              비구독자는 구독 유도 화면을 만날 수 있다.
            states:
              guest: 로그인하지 않은 사용자
              nate: 로그인한 구독 계정   # trailing comment
            """.trimIndent()
        )
        assertEquals("구독형 OTT 앱이다.\n비구독자는 구독 유도 화면을 만날 수 있다.", c.context)
        assertEquals(mapOf("guest" to "로그인하지 않은 사용자", "nate" to "로그인한 구독 계정"), c.states)
        assertEquals("nate — 로그인한 구독 계정", c.accountState(scenario("nate")))
        assertNull(c.accountState(scenario("non-subscribers")), "undescribed state: no line, not a bare name")
        assertNull(c.accountState(scenario(null)), "no declared state: no line")
    }

    @Test
    fun `absent keys leave the prompt bare`() {
        val c = load("app: com.example.app\n")
        assertNull(c.context)
        assertEquals(emptyMap(), c.states)
    }
}
