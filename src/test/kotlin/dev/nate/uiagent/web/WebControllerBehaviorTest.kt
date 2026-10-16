package dev.nate.uiagent.web

import dev.nate.uiagent.StaticDevice
import dev.nate.uiagent.Bounds
import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.LogicalLayout
import dev.nate.uiagent.Origin
import dev.nate.uiagent.Point
import dev.nate.uiagent.device.DeviceController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M2/M3 controller semantics on web-merged observations (plan: diff off, hints on). */
class WebControllerBehaviorTest {

    private fun el(id: Int, label: String, origin: Origin = Origin.NATIVE, y: Int = 100 * (id + 1)) =
        LogicalElement(id, label, null, listOf("clickable"), emptyList(), null, Point(360, y), null, origin = origin)

    @Test
    fun `actions that change a web-merged screen return a full observation, not a diff`() {
        val device = StaticDevice(LogicalLayout(listOf(
            el(0, "닫기"),
            el(1, "구독 시작하기", origin = Origin.WEB, y = 1100),
        )))
        val c = DeviceController(device, settleIntervalMs = 0)
        c.fullLayout()
        device.layout = LogicalLayout(listOf(
            el(0, "닫기"),
            el(1, "결제하기", origin = Origin.WEB, y = 1100),
        ))
        val result = c.tap("구독 시작하기")
        assertTrue(result.contains("(web content: full observation)"), result)
        assertTrue(result.contains("결제하기"), "full layout should be rendered")
        assertFalse(result.contains("LAYOUT DIFF"), result)
        assertFalse(result.contains("NO CHANGE"), result)
    }

    @Test
    fun `a no-op action on a web-merged screen returns NO CHANGE, not a repeated observation`() {
        // 실측: a tap on the payment page's <h1> title changed nothing, but the model got the
        // identical full observation back and never learned the tap was a no-op.
        val device = StaticDevice(LogicalLayout(listOf(
            el(0, "닫기"),
            el(1, "결제하기", origin = Origin.WEB, y = 1100),
        )))
        val c = DeviceController(device, settleIntervalMs = 0)
        c.fullLayout()
        val result = c.tap("결제하기")
        assertTrue(result.contains("NO CHANGE — the action had no visible effect."), result)
        assertFalse(result.contains("web content"), result)
    }

    @Test
    fun `tapping plain web text under the webview scroller is refused without acting`() {
        // 실측: the payment page's <h1> and <h2> were tapped five times in a row.
        val elements = listOf(
            LogicalElement(0, null, null, listOf("scrollable", "focusable"), emptyList(), null, Point(360, 640), Bounds(0, 48, 720, 1232)),
            LogicalElement(1, "결제하기", null, emptyList(), emptyList(), "label", Point(173, 104), null, origin = Origin.WEB, parentId = 0),
            LogicalElement(2, "@x60y104", null, listOf("clickable"), emptyList(), null, Point(60, 104), null, origin = Origin.WEB, parentId = 0),
        )
        val device = StaticDevice(LogicalLayout(elements))
        val c = DeviceController(device, settleIntervalMs = 0)
        c.fullLayout()
        val result = c.tap("결제하기")
        assertEquals("ERROR: '결제하기' is plain text (no interactions); tapping it does nothing.", result)
    }

    @Test
    fun `a text inside a clickable card is still tappable and replay never guards`() {
        val card = LogicalElement(0, null, null, listOf("clickable"), emptyList(), null, Point(360, 430), null)
        val title = LogicalElement(1, "타짜", null, emptyList(), emptyList(), "label", Point(200, 400), null, parentId = 0)
        val orphan = LogicalElement(2, "나의 왓챠", null, emptyList(), emptyList(), "label", Point(240, 194), null)
        val device = StaticDevice(LogicalLayout(listOf(card, title, orphan)))
        val c = DeviceController(device, settleIntervalMs = 0)
        c.fullLayout()
        assertTrue(c.tap("타짜").startsWith("Tapped"), "text inside a clickable card lands on the card")
        assertTrue(c.tap("나의 왓챠").startsWith("ERROR"), "a top-level title is not a control")
        c.fastObserve = true
        assertTrue(c.tap("나의 왓챠").startsWith("Tapped"), "replay reproduces the recorded tap")
    }

    @Test
    fun `not-found on a web screen hints at cross-origin iframes`() {
        val device = StaticDevice(LogicalLayout(listOf(el(0, "버튼", origin = Origin.WEB))))
        val c = DeviceController(device, settleIntervalMs = 0)
        c.fullLayout()
        val result = c.tap("결제하기")
        assertTrue(result.startsWith("ERROR"), result)
        assertTrue(result.contains("cross-origin iframe"), result)
    }

    @Test
    fun `webNote surfaces in observations and tool results`() {
        val note = "WebView present but not inspectable (release build or debugging disabled)"
        val device = StaticDevice(LogicalLayout(listOf(el(0, "닫기")), webNote = note))
        val c = DeviceController(device, settleIntervalMs = 0)
        assertTrue(c.fullLayout().contains(note))
        assertTrue(c.tap("닫기").contains(note)) // diff path keeps the note visible
    }
}
