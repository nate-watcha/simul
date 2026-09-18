package dev.nate.uiagent.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class DescriptionTest {
    @Test
    fun `description is prose between frontmatter and first step, headings dropped`() {
        val s = ScenarioMd.parse(
            """
            ---
            name: x
            setup:
              launch: true
            ---

            # 제목은 무시

            이 시나리오는 탭 전환을 검증한다.
            두 번째 줄도 포함된다.

            1. Tap the "구독" tab
            2. Verify the "장르" chip is shown
            """.trimIndent()
        )
        assertEquals("이 시나리오는 탭 전환을 검증한다. 두 번째 줄도 포함된다.", s.description)
        assertEquals(2, s.steps.size)
    }

    @Test
    fun `no prose yields empty description`() {
        val s = ScenarioMd.parse("---\nname: y\n---\n1. Tap \"a\"")
        assertEquals("", s.description)
    }
}
