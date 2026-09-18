package dev.nate.uiagent.web

import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.LogicalLayout
import dev.nate.uiagent.Origin
import dev.nate.uiagent.Point
import dev.nate.uiagent.device.Device
import dev.nate.uiagent.device.DeviceController
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M2/M3 controller semantics on web-merged observations (plan: diff off, hints on). */
class WebControllerBehaviorTest {

    private fun el(id: Int, label: String, origin: Origin = Origin.NATIVE, y: Int = 100 * (id + 1)) =
        LogicalElement(id, label, null, listOf("clickable"), emptyList(), null, Point(360, y), null, origin = origin)

    private class StaticDevice(var layout: LogicalLayout) : Device {
        override fun observe(): LogicalLayout = layout
        override fun tap(x: Int, y: Int) {}
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
        override fun inputText(text: String) {}
        override fun keyBack() {}
        override fun sleep(ms: Long) {}
    }

    @Test
    fun `actions on web-merged screens return a full observation, not a diff`() {
        val device = StaticDevice(LogicalLayout(listOf(
            el(0, "닫기"),
            el(1, "구독 시작하기", origin = Origin.WEB, y = 1100),
        )))
        val c = DeviceController(device, settleIntervalMs = 0)
        c.fullLayout()
        val result = c.tap("구독 시작하기")
        assertTrue(result.contains("(web content: full observation)"), result)
        assertTrue(result.contains("구독 시작하기"), "full layout should be rendered")
        assertFalse(result.contains("LAYOUT DIFF"), result)
        assertFalse(result.contains("NO CHANGE"), result)
    }

    @Test
    fun `native-only screens keep the diff path`() {
        val device = StaticDevice(LogicalLayout(listOf(el(0, "홈"))))
        val c = DeviceController(device, settleIntervalMs = 0)
        c.fullLayout()
        val result = c.tap("홈")
        assertTrue(result.contains("NO CHANGE"), result) // static layout: action had no effect
        assertFalse(result.contains("web content"), result)
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
    fun `not-found on a native screen has no iframe hint`() {
        val device = StaticDevice(LogicalLayout(listOf(el(0, "버튼"))))
        val c = DeviceController(device, settleIntervalMs = 0)
        c.fullLayout()
        assertFalse(c.tap("결제하기").contains("iframe"))
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
