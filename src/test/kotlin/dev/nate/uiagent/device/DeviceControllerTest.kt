package dev.nate.uiagent.device

import dev.nate.uiagent.Bounds
import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.LogicalLayout
import dev.nate.uiagent.Point
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ---------------------------------------------------------------- fixtures

private var idSeq = 0

private fun el(
    label: String? = null,
    resourceId: String? = null,
    interactions: List<String> = emptyList(),
    state: List<String> = emptyList(),
    center: Point = Point(100, 100 + idSeq * 10),
    bounds: Bounds? = null,
) = LogicalElement(idSeq++, label, resourceId, interactions, state, null, center, bounds)

private fun layout(vararg elements: LogicalElement) = LogicalLayout(elements.toList())

/**
 * Scripted device: `observe()` pops from a queue of layouts (the last one repeats forever).
 * Gestures are recorded; a gesture can trigger advancing to the next layout via [onGesture].
 */
private class FakeDevice(vararg initial: LogicalLayout) : Device {
    val queue = ArrayDeque(initial.toList())
    var last: LogicalLayout = queue.first()
    val gestures = mutableListOf<String>()

    override fun observe(): LogicalLayout {
        if (queue.isNotEmpty()) last = queue.removeFirst()
        return last
    }

    override fun tap(x: Int, y: Int) { gestures += "tap $x $y" }
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) { gestures += "swipe" }
    override fun inputText(text: String) { gestures += "text $text" }
    override fun keyBack() { gestures += "back" }
    override fun sleep(ms: Long) {}
}

private fun controller(device: Device) = DeviceController(device, settleIntervalMs = 0)

// ---------------------------------------------------------------- tests

class DeviceControllerTest {

    @Test
    fun `tap resolves label to coordinates and reports the diff`() {
        idSeq = 0
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
        idSeq = 0
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
        idSeq = 0
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
        idSeq = 0
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
        idSeq = 0
        val l = layout(el(null, interactions = listOf("clickable"), center = Point(606, 104)))
        val dev = FakeDevice(l, l, l, l)
        val c = controller(dev)
        c.fullLayout()

        c.tap("@x606y104")

        assertEquals(listOf("tap 606 104"), dev.gestures)
    }

    @Test
    fun `no-op tap returns NO CHANGE sentinel`() {
        idSeq = 0
        val l = layout(el("배너", interactions = listOf("clickable"), center = Point(50, 50)))
        val dev = FakeDevice(l, l, l, l)
        val c = controller(dev)
        c.fullLayout()

        val result = c.tap("배너")

        assertContains(result, "NO CHANGE")
    }

    @Test
    fun `type rejects non-ASCII without acting`() {
        idSeq = 0
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
        idSeq = 0
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
        idSeq = 0
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
    fun `scrollToFind returns FOUND without scrolling when target already visible`() {
        idSeq = 0
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
        idSeq = 0
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
    fun `report records verdict`() {
        idSeq = 0
        val l = layout(el("x"))
        val c = controller(FakeDevice(l, l))

        assertNull(c.verdict)
        val result = c.report("passed", "tab selected as expected")

        assertContains(result, "PASSED")
        assertEquals("PASSED", c.verdict?.status)
    }

    @Test
    fun `volatile carousel content is suppressed from diffs after churning twice`() {
        idSeq = 0
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
