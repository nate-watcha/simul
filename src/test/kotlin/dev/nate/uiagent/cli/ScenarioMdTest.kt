package dev.nate.uiagent.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    // ------------------------------------------------------------------ states

    private val stateful = """
        ---
        name: play-from-detail
        tags: [player, billing]
        states: [member, nosub, guest]     # 상태마다 trace 하나
        setup:
          launch: true
        ---

        작품 상세에서 재생을 누른다. 구독 여부에 따라 갈린다.

        1. Tap the "왓챠 인기작" row
        2. Tap the "content_cell" item
        3. Tap the "btn_play" button
           :: member → the "screen:player" screen should be shown
           :: nosub  -> the "screen:subscribe" screen should be shown
           :: guest  → the "btn_login" button should be shown
    """.trimIndent()

    @Test
    fun `states expand to one variant per state with its own criterion and setup`() {
        val s = ScenarioMd.parse(stateful)
        assertEquals(listOf("member", "nosub", "guest"), s.states)
        assertNull(s.state)
        assertEquals("작품 상세에서 재생을 누른다. 구독 여부에 따라 갈린다.", s.description)
        assertEquals("Tap the \"btn_play\" button", s.steps[2], "the template carries only unbound criteria")

        val v = s.variants()
        assertEquals(listOf("play-from-detail@member", "play-from-detail@nosub", "play-from-detail@guest"), v.map { it.name })
        assertEquals(listOf("member", "nosub", "guest"), v.map { it.state })
        assertEquals(listOf("player", "billing"), v[0].tags)
        assertEquals("Tap the \"왓챠 인기작\" row", v[0].steps[0], "steps without criteria are shared verbatim")
        assertEquals("Tap the \"btn_play\" button :: the \"screen:player\" screen should be shown", v[0].steps[2])
        assertEquals("Tap the \"btn_play\" button :: the \"screen:subscribe\" screen should be shown", v[1].steps[2], "-> is accepted too")
        assertEquals("Tap the \"btn_play\" button :: the \"btn_login\" button should be shown", v[2].steps[2])

        // the state is the precondition: each variant restores its own snapshot
        assertEquals("member", v[0].setup?.appState)
        assertEquals("member", v[0].precondition)
        assertTrue(v[0].setup!!.launch, "the shared setup is kept")
        assertEquals("guest", v[2].setup?.appState, "guest restores guest.tar when it exists, else just pm clear (AdbOps)")
        assertEquals("guest", v[2].precondition)

        assertEquals(v[1], s.variant("nosub"))
    }

    @Test
    fun `unbound criterion applies to every state unless a state overrides it`() {
        val s = ScenarioMd.parse(
            """
            ---
            name: tab
            states: [member, guest]
            ---
            1. Tap the "tab_library" tab
               :: the "screen:library" screen should be shown
               :: guest → the "btn_login" button should be shown
            2. Tap the "btn_back" button :: the "tab_home" tab should be selected
            """.trimIndent()
        )
        val (member, guest) = s.variants()
        assertEquals("Tap the \"tab_library\" tab :: the \"screen:library\" screen should be shown", member.steps[0])
        assertEquals("Tap the \"tab_library\" tab :: the \"btn_login\" button should be shown", guest.steps[0], "bound overrides unbound")
        assertEquals("Tap the \"btn_back\" button :: the \"tab_home\" tab should be selected", guest.steps[1], "inline :: is unbound")
    }

    @Test
    fun `single state behaves as a plain scenario but still keys its trace on the state`() {
        val s = ScenarioMd.parse("---\nname: one\nstates: [member]\n---\n1. Tap \"a\"\n   :: \"b\" is shown\n")
        val v = s.variants().single()
        assertEquals("one@member", v.name)
        assertEquals("Tap \"a\" :: \"b\" is shown", v.steps.single())
    }

    @Test
    fun `stateless scenario accepts continuation criteria and stays itself`() {
        val s = ScenarioMd.parse("---\nname: plain\n---\n1. Tap \"a\"\n   :: \"b\" is shown\n   :: \"c\" is selected\n2. Go back\n")
        assertEquals(listOf(s), s.variants())
        assertEquals("Tap \"a\" :: \"b\" is shown; \"c\" is selected", s.steps[0])
        assertEquals("Go back", s.steps[1])
    }

    @Test
    fun `authoring mistakes are rejected at parse time`() {
        val unknown = assertFailsWith<IllegalArgumentException> {
            ScenarioMd.parse("---\nname: x\nstates: [member]\n---\n1. Tap \"a\"\n   :: guset → \"b\" is shown\n")
        }
        assertTrue("guset" in unknown.message!!, unknown.message)

        val noStates = assertFailsWith<IllegalArgumentException> {
            ScenarioMd.parse("---\nname: x\n---\n1. Tap \"a\"\n   :: member → \"b\" is shown\n")
        }
        assertTrue("states:" in noStates.message!!, noStates.message)

        assertFailsWith<IllegalArgumentException>("criterion before any step") {
            ScenarioMd.parse("---\nname: x\n---\n:: \"b\" is shown\n1. Tap \"a\"\n")
        }
        assertFailsWith<IllegalArgumentException>("states and setup.appState contradict") {
            ScenarioMd.parse("---\nname: x\nstates: [member]\nsetup:\n  appState: member-basic\n---\n1. Tap \"a\"\n")
        }
        assertFailsWith<IllegalArgumentException>("duplicate state") {
            ScenarioMd.parse("---\nname: x\nstates: [member, member]\n---\n1. Tap \"a\"\n")
        }
    }
}
