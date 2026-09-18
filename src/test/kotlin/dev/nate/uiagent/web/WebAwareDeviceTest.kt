package dev.nate.uiagent.web

import dev.nate.uiagent.Bounds
import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.LogicalLayout
import dev.nate.uiagent.Origin
import dev.nate.uiagent.Point
import dev.nate.uiagent.device.Device
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WebAwareDeviceTest {

    private fun nativeEl(id: Int, label: String? = null, resourceId: String? = null, y: Int = 100) =
        LogicalElement(id, label, resourceId, listOf("clickable"), emptyList(), null, Point(360, y), null)

    private class FakeDevice(var layout: LogicalLayout) : Device {
        override fun observe(): LogicalLayout = layout
        override fun tap(x: Int, y: Int) {}
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
        override fun inputText(text: String) {}
        override fun keyBack() {}
        override fun sleep(ms: Long) {}
    }

    private val target = CdpClient.Target(
        id = "T1", url = "https://x/webview/a", title = "t",
        webSocketUrl = "ws://localhost:1/devtools/page/T1",
        rect = Bounds(0, 160, 720, 1232), visible = true, attached = true,
    )

    /** Scripted CdpClient that never touches adb. */
    private class FakeCdp(
        private val discovery: CdpClient.Discovery,
        private val evalResult: CdpClient.Eval,
    ) : CdpClient() {
        var discoverCalls = 0
        override fun discover(appId: String?): Discovery {
            discoverCalls++
            return discovery
        }

        override fun evaluate(target: Target, js: String, timeoutMs: Long): Eval = evalResult
        override fun readyState(target: Target): String = "complete"
    }

    private val extractOk = CdpClient.Eval.Ok(
        """{"iw":360,"rs":"complete","els":[
           {"label":"구독 시작하기","attrId":null,"tag":"button","type":"submit","role":null,
            "rect":{"x":20,"y":454,"width":320,"height":48},
            "state":{"checked":false,"disabled":false,"focused":false}}]}"""
    )

    @Test
    fun `native-only screens pass through when discovery finds nothing`() {
        val native = LogicalLayout(listOf(nativeEl(0, label = "홈")))
        val cdp = FakeCdp(CdpClient.Discovery(emptyList(), "no webview_devtools socket on the device"), extractOk)
        val merged = WebAwareDevice(FakeDevice(native), cdp, "com.x").observe()
        assertSame(native, merged) // no webview node -> no note either
        assertEquals(1, cdp.discoverCalls) // discovery always runs (the gate cannot be the layout)
    }

    @Test
    fun `web elements merge into the native list, resorted with fresh ids`() {
        val native = LogicalLayout(listOf(
            nativeEl(0, resourceId = "webview", y = 696).copy(interactions = listOf("focusable", "scrollable")),
            nativeEl(1, label = "닫기", y = 104),
        ))
        val cdp = FakeCdp(CdpClient.Discovery(listOf(target)), extractOk)
        val merged = WebAwareDevice(FakeDevice(native), cdp, "com.x").observe()

        assertEquals(3, merged.elements.size)
        assertTrue(merged.hasWeb)
        assertNull(merged.webNote)
        // sorted top-to-bottom: 닫기(104) < webview container(696) < button(center y 160+(454+24)*2=1116)
        assertEquals(listOf("닫기", null, "구독 시작하기"), merged.elements.map { it.label })
        assertEquals(listOf(0, 1, 2), merged.elements.map { it.id })
        val button = merged.elements.first { it.label == "구독 시작하기" }
        assertEquals(Origin.WEB, button.origin)
        assertEquals(Point(360, 1116), button.center)
    }

    private val extractRadioAndButton = CdpClient.Eval.Ok(
        """{"iw":360,"rs":"complete","els":[
           {"label":"베이직","attrId":"TicketSingular::GoogleIAB::Tall","tag":"input","type":"radio","role":null,
            "rect":{"x":180,"y":66,"width":160,"height":100},
            "state":{"checked":true,"disabled":false,"focused":false}},
           {"label":"구독 시작하기","attrId":null,"tag":"button","type":"submit","role":null,
            "rect":{"x":20,"y":454,"width":320,"height":48},
            "state":{"checked":false,"disabled":false,"focused":false}}]}"""
    )

    @Test
    fun `native a11y projection inside the webview rect is dropped in favor of CDP elements`() {
        val native = LogicalLayout(listOf(
            nativeEl(0, label = "닫기", y = 104),
            // Chrome's a11y projection of the same DOM: shared DOM id, shared label, anonymous
            nativeEl(1, resourceId = "TicketSingular::GoogleIAB::Tall", y = 400),
            nativeEl(2, label = "구독 시작하기", y = 1116),
            nativeEl(3, y = 500),
            // the scrollable container survives as the scroll gesture target
            nativeEl(4, y = 696).copy(interactions = listOf("focusable", "scrollable")),
        ))
        val cdp = FakeCdp(CdpClient.Discovery(listOf(target)), extractRadioAndButton)
        val merged = WebAwareDevice(FakeDevice(native), cdp, "com.x").observe()

        // projection nodes gone; CDP versions (with real state) and the container remain
        assertEquals(listOf("닫기", "베이직", null, "구독 시작하기"), merged.elements.map { it.label })
        val basic = merged.elements.first { it.label == "베이직" }
        assertEquals(Origin.WEB, basic.origin)
        assertEquals(listOf("checked"), basic.state)
    }

    @Test
    fun `a native dialog over the webview is NOT mistaken for a11y projection`() {
        // exit-confirmation dialog: inside the webview rect but sharing nothing with the page
        val native = LogicalLayout(listOf(
            nativeEl(0, label = "구독권 구매를 종료하시겠어요?", resourceId = "content", y = 612),
            nativeEl(1, label = "아니요", resourceId = "negative", y = 723),
            nativeEl(2, label = "네", resourceId = "positive", y = 723),
        ))
        val cdp = FakeCdp(CdpClient.Discovery(listOf(target)), extractRadioAndButton)
        val merged = WebAwareDevice(FakeDevice(native), cdp, "com.x").observe()

        assertTrue(merged.elements.any { it.label == "구독권 구매를 종료하시겠어요?" })
        assertTrue(merged.elements.any { it.label == "네" })
        assertTrue(merged.elements.any { it.label == "베이직" }) // web content still merged behind
    }

    @Test
    fun `discover failure degrades to native with the release-build guard note`() {
        val native = LogicalLayout(listOf(nativeEl(0, resourceId = "webview")))
        val cdp = FakeCdp(CdpClient.Discovery(emptyList(), "no webview_devtools socket for com.x"), extractOk)
        val merged = WebAwareDevice(FakeDevice(native), cdp, "com.x").observe()

        assertEquals(native.elements, merged.elements)
        assertTrue(merged.webNote!!.startsWith("WebView present but not inspectable (release build or debugging disabled)"))
        assertTrue(merged.webNote!!.contains("no webview_devtools socket for com.x"))
    }

    @Test
    fun `evaluate failure degrades to native with a note`() {
        val native = LogicalLayout(listOf(nativeEl(0, resourceId = "webview")))
        val cdp = FakeCdp(CdpClient.Discovery(listOf(target)), CdpClient.Eval.Failed("evaluate timed out after 3000ms"))
        val merged = WebAwareDevice(FakeDevice(native), cdp, "com.x").observe()

        assertEquals(native.elements, merged.elements)
        assertTrue(merged.webNote!!.contains("web content observation failed"))
        assertTrue(merged.webNote!!.contains("timed out"))
    }

    @Test
    fun `invisible targets lose to visible ones`() {
        val hidden = target.copy(id = "H", visible = false)
        val native = LogicalLayout(listOf(nativeEl(0, resourceId = "webview")))
        val cdp = object : CdpClient() {
            var evaluated: Target? = null
            override fun discover(appId: String?) = Discovery(listOf(hidden, target))
            override fun evaluate(target: Target, js: String, timeoutMs: Long): Eval {
                evaluated = target
                return extractOk
            }

            override fun readyState(target: Target) = "complete"
        }
        WebAwareDevice(FakeDevice(native), cdp, "com.x").observe()
        assertEquals("T1", cdp.evaluated!!.id)
    }

    @Test
    fun `a lingering invisible target never merges into a native screen`() {
        val dying = target.copy(visible = false)
        val native = LogicalLayout(listOf(nativeEl(0, label = "웹툰")))
        val cdp = FakeCdp(CdpClient.Discovery(listOf(dying)), extractOk)
        val merged = WebAwareDevice(FakeDevice(native), cdp, "com.x").observe()
        assertSame(native, merged) // no webview node on screen -> plain native, no note
    }
}
