package dev.nate.uiagent.device

import dev.nate.uiagent.layout
import dev.nate.uiagent.el
import dev.nate.uiagent.FakeDevice
import dev.nate.uiagent.Bounds
import dev.nate.uiagent.Point
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---------------------------------------------------------------- fixtures


private fun controller(device: Device) = DeviceController(device, settleIntervalMs = 0)

// ---------------------------------------------------------------- tests

class DeviceControllerTest {

    @Test
    fun `tap resolves label to coordinates and reports the diff`() {
        val tab = el("웹툰", interactions = listOf("clickable"), center = Point(360, 1176))
        val before = layout(tab, el("구독"))
        val after = layout(tab.copy(state = listOf("selected")), el("구독"), el("웹툰 홈 배너", interactions = listOf("clickable")))
        val dev = FakeDevice(before, before, after, after) // stabilize needs 2 equal observations
        val c = controller(dev)
        c.fullLayout()

        val result = c.tap("웹툰")

        assertEquals(listOf("tap 360 1176"), dev.gestures)
        assertContains(result, "Tapped '웹툰'")
        assertContains(result, "+ {\"label\":\"웹툰 홈 배너\"")
        assertContains(result, "(state changed)")
    }

    @Test
    fun `tap with unknown label acts as guard - no gesture, error message`() {
        val l = layout(el("보관함", interactions = listOf("clickable")))
        val dev = FakeDevice(l, l)
        val c = controller(dev)
        c.fullLayout()

        val result = c.tap("없는거")

        assertTrue(dev.gestures.isEmpty())
        assertContains(result, "ERROR: no element matching '없는거'")
    }

    @Test
    fun `ambiguous label returns candidates with synthetic handles, no gesture`() {
        val l = layout(
            el("웹툰", interactions = listOf("clickable"), center = Point(360, 1176)),
            el("웹툰", interactions = listOf("clickable"), center = Point(100, 200)),
        )
        val dev = FakeDevice(l, l)
        val c = controller(dev)
        c.fullLayout()

        val result = c.tap("웹툰")

        assertTrue(dev.gestures.isEmpty())
        assertContains(result, "AMBIGUOUS")
        assertContains(result, "@x360y1176")
        assertContains(result, "@x100y200")
    }

    @Test
    fun `duplicate labels but single clickable auto-picks the clickable`() {
        val l = layout(
            el("웹툰", interactions = listOf("clickable"), center = Point(360, 1176)),
            el("웹툰", center = Point(100, 200)), // orphan text
        )
        val dev = FakeDevice(l, l, l, l)
        val c = controller(dev)
        c.fullLayout()

        c.tap("웹툰")

        assertEquals(listOf("tap 360 1176"), dev.gestures)
    }

    @Test
    fun `synthetic handle taps the element at those coordinates`() {
        val l = layout(el(null, interactions = listOf("clickable"), center = Point(606, 104)))
        val dev = FakeDevice(l, l, l, l)
        val c = controller(dev)
        c.fullLayout()

        c.tap("@x606y104")

        assertEquals(listOf("tap 606 104"), dev.gestures)
    }

    @Test
    fun `no-op tap returns NO CHANGE sentinel`() {
        val l = layout(el("배너", interactions = listOf("clickable"), center = Point(50, 50)))
        val dev = FakeDevice(l, l, l, l)
        val c = controller(dev)
        c.fullLayout()

        val result = c.tap("배너")

        assertContains(result, "NO CHANGE")
    }

    @Test
    fun `type rejects non-ASCII without acting`() {
        val l = layout(el("검색", interactions = listOf("clickable", "focusable")))
        val dev = FakeDevice(l, l)
        val c = controller(dev)
        c.fullLayout()

        val result = c.type("검색", "한글입력")

        assertTrue(dev.gestures.isEmpty())
        assertContains(result, "non-ASCII")
    }

    @Test
    fun `type into non-focusable is rejected`() {
        val l = layout(el("버튼", interactions = listOf("clickable")))
        val dev = FakeDevice(l, l)
        val c = controller(dev)
        c.fullLayout()

        val result = c.type("버튼", "abc")

        assertTrue(dev.gestures.isEmpty())
        assertContains(result, "not focusable")
    }

    @Test
    fun `scrollToFind finds target after one scroll`() {
        val list = el("목록", resourceId = "recycler_view", interactions = listOf("focusable", "scrollable"),
            center = Point(360, 639), bounds = Bounds(0, 160, 720, 1118))
        val page1 = layout(list, el("항목A", interactions = listOf("clickable")))
        val page2 = layout(list, el("그린북", interactions = listOf("clickable"), center = Point(360, 500)))
        val dev = FakeDevice(page1, page1, page2, page2)
        val c = controller(dev)
        c.fullLayout()

        val result = c.scrollToFind("목록", "그린북", "down")

        assertEquals(listOf("swipe"), dev.gestures)
        assertContains(result, "FOUND after 1 scroll(s)")
        assertContains(result, "그린북")
    }

    @Test
    fun `scrollToFind with several matches is FOUND in screen order, not AMBIGUOUS`() {
        val list = el("목록", resourceId = "recycler_view", interactions = listOf("focusable", "scrollable"),
            center = Point(360, 639), bounds = Bounds(0, 160, 720, 1118))
        val page1 = layout(list, el("항목A", interactions = listOf("clickable")))
        val page2 = layout(
            list,
            el("패키지 : 쿵푸팬더", interactions = listOf("clickable"), center = Point(330, 1005)),
            el("패키지 : 러브레터", interactions = listOf("clickable"), center = Point(686, 1005)),
        )
        val dev = FakeDevice(page1, page1, page2, page2)
        val c = controller(dev)
        c.fullLayout()

        val result = c.scrollToFind("목록", "패키지", "down")

        assertTrue(result.startsWith("FOUND after 1 scroll(s) down: 2 elements match '패키지', in screen order:"))
        assertContains(result, "- @x330y1005 ")
        assertContains(result, "- @x686y1005 ")
        assertTrue(result.indexOf("쿵푸팬더") < result.indexOf("러브레터"))
        assertFalse(result.contains("AMBIGUOUS"))
        assertFalse(result.contains("Repeat the action"))
    }

    @Test
    fun `scrollToFind returns FOUND without scrolling when target already visible`() {
        val list = el("목록", interactions = listOf("scrollable"), bounds = Bounds(0, 0, 720, 1000))
        val l = layout(list, el("그린북", interactions = listOf("clickable")))
        val dev = FakeDevice(l, l)
        val c = controller(dev)
        c.fullLayout()

        val result = c.scrollToFind("목록", "그린북", "down")

        assertTrue(dev.gestures.isEmpty())
        assertContains(result, "FOUND without scrolling")
    }

    @Test
    fun `scrollToFind stops when layout stops changing`() {
        val list = el("목록", interactions = listOf("scrollable"), bounds = Bounds(0, 0, 720, 1000))
        val page = layout(list, el("항목A"))
        val dev = FakeDevice(page, page) // scrolling changes nothing
        val c = controller(dev)
        c.fullLayout()

        val result = c.scrollToFind("목록", "그린북", "down")

        assertEquals(listOf("swipe"), dev.gestures) // exactly one attempt, then stall detected
        assertContains(result, "NOT_FOUND")
        assertContains(result, "stopped changing")
    }

    @Test
    fun `volatile carousel content is suppressed from diffs after churning twice`() {
        val stable = el("고정", interactions = listOf("clickable"), center = Point(100, 100))
        val banner1 = el("배너A", interactions = listOf("clickable"), center = Point(360, 400))
        val banner2 = el("배너B", interactions = listOf("clickable"), center = Point(360, 400))
        val tab = el("웹툰", interactions = listOf("clickable"), center = Point(360, 1176))

        // initial stabilization sees the banner flip A->B->A->B: A and B each churn >=2 times
        val dev = FakeDevice(
            layout(stable, banner1, tab),
            layout(stable, banner2, tab),
            layout(stable, banner1, tab),
            layout(stable, banner2, tab),
            layout(stable, banner2, tab),
            // after tap: banner flips again AND a real new element appears
            layout(stable, banner1, tab, el("새 화면", center = Point(360, 600))),
            layout(stable, banner1, tab, el("새 화면", center = Point(360, 600))),
        )
        val c = controller(dev)
        c.fullLayout()

        val result = c.tap("웹툰")

        assertContains(result, "새 화면")
        assertTrue("배너" !in result, "carousel banners must be suppressed, got: $result")
        assertContains(result, "auto-rotating")
    }
}
