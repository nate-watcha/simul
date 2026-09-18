package dev.nate.uiagent.cli

import dev.nate.uiagent.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecorderTest {

    @Test
    fun `records tap with grounding target and skips guard errors`() {
        val r = StepRecorder(1)
        val target = el("그린북", interactions = listOf("clickable"), center = Point(360, 815))

        r.after("tap", listOf("target" to "없는거"), null, "ERROR: no element matching '없는거'")
        r.after("tap", listOf("target" to "그린북"), target, "Tapped '그린북'.\nLAYOUT DIFF: ...")
        r.after("report", listOf("status" to "PASSED", "reason" to "ok"), null, "Verdict recorded")

        assertEquals(1, r.actions.size)
        val a = r.actions[0]
        assertEquals("tap", a.tool)
        assertEquals(TraceTarget(null, "그린북", Point(360, 815)), a.target)
    }

    @Test
    fun `records scrollToFind args in call order, even NOT_FOUND explorations`() {
        val r = StepRecorder(1)
        r.after(
            "scrollToFind",
            listOf("scrollable" to "recycler_view", "target" to "그린북", "direction" to "down"),
            null,
            "NOT_FOUND: '그린북' did not appear...",
        )
        assertEquals(listOf("scrollable", "target", "direction"), r.actions[0].args.keys.toList())
        assertEquals("그린북", r.actions[0].args["target"])
    }

    @Test
    fun `records type with text`() {
        val r = StepRecorder(2)
        val field = el("검색", resourceId = "search_input", interactions = listOf("clickable", "focusable"))
        r.after("type", listOf("target" to "검색", "text" to "abc"), field, "Typed \"abc\" into '검색'.\nNO CHANGE")
        assertEquals("type", r.actions[0].tool)
        assertEquals("abc", r.actions[0].text)
        assertEquals("search_input", r.actions[0].target?.resourceId)
    }

    @Test
    fun `quoted labels present on screen are the whole evidence`() {
        val before = listOf(el("구독", id = 0))
        val after = listOf(
            el("구독", id = 0),
            el("그린북", id = 1, center = Point(1, 1)), // dynamic content title — must NOT leak in
            el("재생하기", id = 2, center = Point(2, 2)),
            el("공유", id = 3, center = Point(3, 3)),
        )
        val ev0 = extractEvidence("Verify \"재생하기\" 버튼이 보이는지 확인", before, after, emptySet())
        val ev = ev0.appeared
        assertEquals(listOf("재생하기"), ev)
    }

    @Test
    fun `free-text labels are never harvested — only resourceIds of new nodes`() {
        val before = listOf(el("탭", id = 0))
        val after = listOf(
            el("탭", id = 0),
            el("5", id = 1, center = Point(1, 1)), // banner page indicator — no id, no label harvest
            el("오늘만 무료! 이달의 웹툰", id = 2, center = Point(2, 2)), // dynamic content — ignored
            el("쿠폰 등록", resourceId = "couponRegister", id = 3, center = Point(3, 3)),
            el(null, resourceId = "couponList", id = 4, center = Point(4, 4)),
        )
        val ev = extractEvidence("Go back", before, after, emptySet())
        assertEquals(listOf("couponRegister", "couponList"), ev.appeared)
    }

    @Test
    fun `volatile keys are excluded from the id harvest`() {
        val before = listOf(el("탭", id = 0))
        val after = listOf(
            el("탭", id = 0),
            el("배너A", resourceId = "banner", id = 1, center = Point(1, 1)),
            el("상세", resourceId = "detailHeader", id = 2, center = Point(2, 2)),
        )
        val ev = extractEvidence("Tap \"없는라벨\"", before, after, volatileKeys = setOf("배너A|banner"))
        assertEquals(listOf("detailHeader"), ev.appeared)
    }

    // ------------------------------------------------------------ state evidence

    @Test
    fun `a tab gaining selected during the step becomes state evidence`() {
        val before = listOf(
            el("구독", id = 0, interactions = listOf("clickable")),
            el("웹툰", id = 1, state = listOf("selected"), center = Point(1, 1)),
        )
        val after = listOf(
            el("구독", id = 0, interactions = listOf("clickable"), state = listOf("selected")),
            el("웹툰", id = 1, center = Point(1, 1)),
        )
        val ev = extractEvidence("Tap the \"구독\" tab", before, after, emptySet())
        assertEquals(listOf("구독"), ev.appeared)
        assertEquals(listOf(StateEvidence("구독", "selected")), ev.states)
    }

    @Test
    fun `transient focused state is not asserted`() {
        val before = listOf(el("검색", id = 0, interactions = listOf("focusable")))
        val after = listOf(el("검색", id = 0, interactions = listOf("focusable"), state = listOf("focused")))
        val ev = extractEvidence("Tap \"검색\"", before, after, emptySet())
        assertEquals(emptyList(), ev.states)
    }

    @Test
    fun `a quoted element's current stable state is asserted even without a change`() {
        // launch tab: 구독 is already selected before the tap — still worth asserting
        val screen = listOf(el("구독", id = 0, state = listOf("selected")))
        val ev = extractEvidence("Tap the \"구독\" tab", screen, screen, emptySet())
        assertEquals(listOf(StateEvidence("구독", "selected")), ev.states)
    }

    @Test
    fun `state evidence is keyed by resourceId when the element has one`() {
        val before = listOf(el("보관함", resourceId = "tab_library", id = 0))
        val after = listOf(el("보관함", resourceId = "tab_library", id = 0, state = listOf("selected")))
        val ev = extractEvidence("Tap the \"보관함\" tab", before, after, emptySet())
        assertEquals(listOf(StateEvidence("tab_library", "selected")), ev.states)
    }

    @Test
    fun `statePresent matches label plus state, not label alone`() {
        val els = listOf(
            el("구독", id = 0, state = listOf("selected")),
            el("웹툰", id = 1, center = Point(1, 1)),
        )
        assertEquals(true, statePresent(els, StateEvidence("구독", "selected")))
        assertEquals(false, statePresent(els, StateEvidence("웹툰", "selected")))
        assertEquals(false, statePresent(els, StateEvidence("없음", "selected")))
    }
}
