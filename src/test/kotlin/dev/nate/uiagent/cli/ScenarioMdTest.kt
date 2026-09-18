package dev.nate.uiagent.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScenarioMdTest {

    @Test
    fun `parses frontmatter, steps, and ignores prose`() {
        val s = ScenarioMd.parse(
            """
            ---
            name: browse-greenbook
            tags: [smoke, subscription]
            setup:
              deeplink: myapp://tab/subscription
            ---

            이 시나리오는 구독 탭의 콘텐츠 상세 진입을 검증한다.

            1. 구독 탭에서 "그린북" 콘텐츠를 찾아 클릭해
            2. Verify 콘텐츠 상세 화면에 "재생하기" 버튼이 보이는지 확인
            3. "재생하기"를 눌러

            추가 설명 문단은 무시된다.
            """.trimIndent()
        )
        assertEquals("browse-greenbook", s.name)
        assertEquals(listOf("smoke", "subscription"), s.tags)
        assertEquals("myapp://tab/subscription", s.setup?.deeplink)
        assertEquals(false, s.setup?.launch)
        assertEquals(3, s.steps.size)
        assertEquals("구독 탭에서 \"그린북\" 콘텐츠를 찾아 클릭해", s.steps[0])
        assertEquals("\"재생하기\"를 눌러", s.steps[2])
    }

    @Test
    fun `launch true setup`() {
        val s = ScenarioMd.parse("---\nname: cold\nsetup:\n  launch: true\n---\n1. do something\n")
        assertTrue(s.setup!!.launch)
        assertNull(s.setup!!.deeplink)
    }

    @Test
    fun `no frontmatter still yields steps`() {
        val s = ScenarioMd.parse("1. first\n2. second\n")
        assertEquals(listOf("first", "second"), s.steps)
        assertNull(s.setup)
        assertTrue(s.tags.isEmpty())
    }

    @Test
    fun `appState and clearData setup parse`() {
        val s = ScenarioMd.parse(
            """
            ---
            name: coupon-feature
            setup:
              appState: member-basic
              launch: true
            ---
            1. Tap the "쿠폰 등록" button
            """.trimIndent()
        )
        assertEquals("member-basic", s.setup?.appState)
        assertTrue(s.setup!!.launch)

        val g = ScenarioMd.parse(
            """
            ---
            name: guest-x
            setup:
              clearData: true
              launch: true
            ---
            1. Tap the "로그인" button
            """.trimIndent()
        )
        assertTrue(g.setup!!.clearData)
        assertNull(g.setup!!.appState)
    }

    @Test
    fun `precondition derives from appState or clearData`() {
        assertEquals("member-basic", ScenarioMd.parse("---\nname: a\nsetup:\n  appState: member-basic\n---\n1. x \"y\"").precondition)
        assertEquals("guest", ScenarioMd.parse("---\nname: b\nsetup:\n  clearData: true\n---\n1. x \"y\"").precondition)
        assertNull(ScenarioMd.parse("---\nname: c\nsetup:\n  launch: true\n---\n1. x \"y\"").precondition)
    }
}
