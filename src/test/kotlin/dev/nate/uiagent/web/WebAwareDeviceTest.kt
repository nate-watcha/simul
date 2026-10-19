package dev.nate.uiagent.web

import dev.nate.uiagent.StaticDevice
import dev.nate.uiagent.Bounds
import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.LogicalLayout
import dev.nate.uiagent.Origin
import dev.nate.uiagent.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WebAwareDeviceTest {

    private fun nativeEl(id: Int, label: String? = null, resourceId: String? = null, y: Int = 100) =
        LogicalElement(id, label, resourceId, listOf("clickable"), emptyList(), null, Point(360, y), null)

    private val target = CdpClient.Target(
        id = "T1",
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

    /** Same page, but the document is 2070 CSS px tall with a 592 px viewport, scrolled to the top. */
    private val extractTallPage = CdpClient.Eval.Ok(
        """{"iw":360,"ih":592,"sy":0,"sh":2070,"rs":"complete","els":[
           {"label":"결제하기","attrId":null,"tag":"text","type":null,"role":null,
            "rect":{"x":52,"y":15,"width":69,"height":26},
            "state":{"checked":false,"disabled":false,"focused":false}}]}"""
    )

    @Test
    fun `a page taller than the viewport carries a scroll hint note`() {
        // 실측: the payment page's only submit button sat two screens below the fold and the
        // model, seeing nothing clickable, tapped page titles instead of scrolling.
        val native = LogicalLayout(listOf(nativeEl(0, resourceId = "webview", y = 696)))
        val cdp = FakeCdp(CdpClient.Discovery(listOf(target)), extractTallPage)
        val merged = WebAwareDevice(StaticDevice(native), cdp, "com.x").observe()
        assertEquals(
            "the web page continues past the viewport (about 2.5 screens below)",
            merged.webNote,
        )
    }

    @Test
    fun `a page that fits the viewport has no scroll hint`() {
        val fits = CdpClient.Eval.Ok("""{"iw":360,"ih":592,"sy":0,"sh":600,"rs":"complete","els":[]}""")
        val native = LogicalLayout(listOf(nativeEl(0, resourceId = "webview", y = 696)))
        val merged = WebAwareDevice(StaticDevice(native), FakeCdp(CdpClient.Discovery(listOf(target)), fits), "com.x").observe()
        assertNull(merged.webNote) // 8 px of overflow is not a screen's worth of content
    }

    @Test
    fun `scroll hint reports content above once scrolled down`() {
        val page = WebLayout.WebPage(360, emptyList(), innerHeight = 592, scrollY = 1478, scrollHeight = 2070)
        assertEquals(
            "the web page continues past the viewport (about 2.5 screens above)",
            WebLayout.scrollHint(page),
        )
        val mid = WebLayout.WebPage(360, emptyList(), innerHeight = 592, scrollY = 300, scrollHeight = 2070)
        assertEquals(
            "the web page continues past the viewport (about 2.0 screens below, less than 1 screen above)",
            WebLayout.scrollHint(mid),
        )
        // geometry absent (older extraction fixtures) -> no hint
        assertNull(WebLayout.scrollHint(WebLayout.WebPage(360, emptyList())))
    }

    private fun webEl(label: String, x0: Int, y0: Int, x1: Int, y1: Int) =
        LogicalElement(-1, label, null, emptyList(), emptyList(), "label", Point((x0 + x1) / 2, (y0 + y1) / 2), Bounds(x0, y0, x1, y1), origin = Origin.WEB)

    @Test
    fun `reading order groups a row by overlapping vertical bands, then sorts left to right`() {
        // 실측 payment page: "정가 | 소장: 11,000원" on one line with a smaller "정가", a struck-out
        // price next to the real one, and a two-line heading whose center sits below its first word.
        val els = listOf(
            webEl("소장: 11,000원", 158, 550, 342, 588),
            webEl("정가", 74, 552, 126, 590),               // center 1 px lower: a plain y sort put it second
            webEl("7,700 캐시", 509, 800, 650, 846),
            webEl("11,000 캐시", 362, 804, 500, 842),       // struck-out, smaller font
            webEl("7,700 캐시에 소장할게요", 40, 1014, 458, 1136),  // wraps to two lines
            webEl("11,000", 40, 1020, 190, 1080),
            webEl("결제하기", 104, 78, 244, 130),
        )
        assertEquals(
            listOf("결제하기", "정가", "소장: 11,000원", "11,000 캐시", "7,700 캐시", "11,000", "7,700 캐시에 소장할게요"),
            WebAwareDevice.readingOrder(els).map { it.label },
        )
    }

    @Test
    fun `a native label that concatenates web labels is a11y projection and is dropped`() {
        // Chrome joins sibling spans without separators; the web side has them split.
        val extract = CdpClient.Eval.Ok(
            """{"iw":360,"rs":"complete","els":[
           {"label":"할인","attrId":null,"tag":"text","type":null,"role":null,"rect":{"x":37,"y":400,"width":20,"height":16},"state":{}},
           {"label":"소장","attrId":null,"tag":"text","type":null,"role":null,"rect":{"x":66,"y":400,"width":20,"height":16},"state":{}},
           {"label":"11,000 캐시","attrId":null,"tag":"text","type":null,"role":null,"rect":{"x":181,"y":402,"width":60,"height":14},"state":{}},
           {"label":"7,700 캐시","attrId":null,"tag":"text","type":null,"role":null,"rect":{"x":255,"y":400,"width":70,"height":16},"state":{}}]}"""
        )
        val native = LogicalLayout(listOf(
            nativeEl(0, resourceId = "webview", y = 696).copy(interactions = listOf("scrollable")),
            LogicalElement(1, "할인소장11,000 캐시7,700 캐시", null, emptyList(), emptyList(), "label", Point(360, 823), null, parentId = 0),
            LogicalElement(2, "확인", null, listOf("clickable"), emptyList(), null, Point(360, 900), null, parentId = 0), // a dialog button: shares nothing
        ))
        val cdp = FakeCdp(CdpClient.Discovery(listOf(target)), extract)
        val merged = WebAwareDevice(StaticDevice(native), cdp, "com.x").observe()
        assertEquals(listOf(null, "확인", "할인", "소장", "11,000 캐시", "7,700 캐시"), merged.elements.map { it.label })
    }

    @Test
    fun `without a webview resource-id the native scrollable with the WebView rect anchors the page`() {
        // 실측: once Chrome projects a11y nodes the WebView node loses its id but keeps its bounds.
        val host = LogicalElement(0, null, null, listOf("scrollable", "focusable"), emptyList(), null, Point(360, 696), Bounds(0, 160, 720, 1232))
        val native = LogicalLayout(listOf(host, nativeEl(1, label = "닫기", y = 104)))
        val cdp = FakeCdp(CdpClient.Discovery(listOf(target)), extractOk)
        val merged = WebAwareDevice(StaticDevice(native), cdp, "com.x").observe()
        assertEquals(listOf(null, "구독 시작하기", "닫기"), merged.elements.map { it.label })
        assertEquals(listOf(null, 0, null), merged.elements.map { it.parentId })
    }

    @Test
    fun `native-only screens pass through when discovery finds nothing`() {
        val native = LogicalLayout(listOf(nativeEl(0, label = "홈")))
        val cdp = FakeCdp(CdpClient.Discovery(emptyList(), "no webview_devtools socket on the device"), extractOk)
        val merged = WebAwareDevice(StaticDevice(native), cdp, "com.x").observe()
        assertSame(native, merged) // no webview node -> no note either
        assertEquals(1, cdp.discoverCalls) // discovery always runs (the gate cannot be the layout)
    }

    @Test
    fun `web elements nest under the native webview node with fresh pre-order ids`() {
        val native = LogicalLayout(listOf(
            nativeEl(0, resourceId = "webview", y = 696).copy(interactions = listOf("focusable", "scrollable")),
            nativeEl(1, label = "닫기", y = 104),
        ))
        val cdp = FakeCdp(CdpClient.Discovery(listOf(target)), extractOk)
        val merged = WebAwareDevice(StaticDevice(native), cdp, "com.x").observe()

        assertEquals(3, merged.elements.size)
        assertTrue(merged.hasWeb)
        assertNull(merged.webNote)
        // native document order, the page's elements right after the webview node as its children
        assertEquals(listOf(null, "구독 시작하기", "닫기"), merged.elements.map { it.label })
        assertEquals(listOf(0, 1, 2), merged.elements.map { it.id })
        assertEquals(listOf(null, 0, null), merged.elements.map { it.parentId })
        val button = merged.elements.first { it.label == "구독 시작하기" }
        assertEquals(Origin.WEB, button.origin)
        assertEquals(Point(360, 1116), button.center) // center y 160+(454+24)*2
    }

    @Test
    fun `web elements sit at the top level when no webview node is exposed`() {
        val native = LogicalLayout(listOf(nativeEl(0, label = "닫기", y = 104)))
        val cdp = FakeCdp(CdpClient.Discovery(listOf(target)), extractRadioAndButton)
        val merged = WebAwareDevice(StaticDevice(native), cdp, "com.x").observe()
        // web siblings keep reading order: 베이직 (y 392) before 구독 시작하기 (y 1116)
        assertEquals(listOf("닫기", "베이직", "구독 시작하기"), merged.elements.map { it.label })
        assertTrue(merged.elements.all { it.parentId == null })
    }

    @Test
    fun `a native overlay whose scaffolding was dropped re-parents to the webview node`() {
        val native = LogicalLayout(listOf(
            nativeEl(0, resourceId = "webview", y = 696).copy(interactions = listOf("focusable", "scrollable")),
            nativeEl(1, y = 500).copy(parentId = 0),                      // anonymous a11y scaffolding: dropped
            nativeEl(2, label = "네", resourceId = "positive", y = 723).copy(parentId = 1), // dialog button: kept
        ))
        val cdp = FakeCdp(CdpClient.Discovery(listOf(target)), extractOk)
        val merged = WebAwareDevice(StaticDevice(native), cdp, "com.x").observe()
        assertEquals(listOf(null, "네", "구독 시작하기"), merged.elements.map { it.label })
        assertEquals(listOf(null, 0, 0), merged.elements.map { it.parentId })
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
        val merged = WebAwareDevice(StaticDevice(native), cdp, "com.x").observe()

        // projection nodes gone; CDP versions (with real state) and the container remain
        assertEquals(listOf("닫기", null, "베이직", "구독 시작하기"), merged.elements.map { it.label })
        val basic = merged.elements.first { it.label == "베이직" }
        assertEquals(Origin.WEB, basic.origin)
        assertEquals(listOf("checked"), basic.state)
    }

    @Test
    fun `the a11y projection of a covered web element is dropped and never anchors the page`() {
        // 실측 (payment page, checkbox scrolled under the CTA bar): CDP reports the checkbox hidden,
        // Chrome still projects it as a native CheckBox whose id contains "webview". Without the
        // hidden element in the dedup sets it survived, and the "webview" id made it the anchor.
        val extract = CdpClient.Eval.Ok(
            """{"iw":360,"rs":"complete","els":[
           {"label":"(필수) 언제든 해지 가능하며","attrId":"webview-legal-checkbox","tag":"input","type":"checkbox","role":null,
            "rect":{"x":20,"y":474,"width":320,"height":62},"hidden":true,
            "state":{"checked":false,"disabled":false,"focused":false}},
           {"label":"구독 시작하기","attrId":null,"tag":"button","type":"submit","role":null,
            "rect":{"x":20,"y":454,"width":320,"height":48},
            "state":{"checked":false,"disabled":false,"focused":false}}]}"""
        )
        val host = LogicalElement(0, null, null, listOf("scrollable", "focusable"), emptyList(), null, Point(360, 697), Bounds(0, 162, 720, 1232))
        val native = LogicalLayout(listOf(
            host,
            LogicalElement(1, "(필수) 언제든 해지 가능하며", "webview-legal-checkbox", listOf("clickable", "focusable"), emptyList(), null, Point(360, 1170), Bounds(40, 1108, 680, 1232), parentId = 0),
            LogicalElement(2, "구독 시작하기", null, listOf("clickable"), emptyList(), null, Point(360, 1118), Bounds(40, 1070, 680, 1166), parentId = 0),
        ))
        val merged = WebAwareDevice(StaticDevice(native), FakeCdp(CdpClient.Discovery(listOf(target)), extract), "com.x").observe()
        assertEquals(listOf(null, "구독 시작하기"), merged.elements.map { it.label })
        assertEquals(listOf(null, 0), merged.elements.map { it.parentId }) // under the WebView host, not the checkbox
        assertEquals(Origin.WEB, merged.elements[1].origin)
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
        val merged = WebAwareDevice(StaticDevice(native), cdp, "com.x").observe()

        assertTrue(merged.elements.any { it.label == "구독권 구매를 종료하시겠어요?" })
        assertTrue(merged.elements.any { it.label == "네" })
        assertTrue(merged.elements.any { it.label == "베이직" }) // web content still merged behind
    }

    @Test
    fun `discover failure degrades to native with the release-build guard note`() {
        val native = LogicalLayout(listOf(nativeEl(0, resourceId = "webview")))
        val cdp = FakeCdp(CdpClient.Discovery(emptyList(), "no webview_devtools socket for com.x"), extractOk)
        val merged = WebAwareDevice(StaticDevice(native), cdp, "com.x").observe()

        assertEquals(native.elements, merged.elements)
        assertTrue(merged.webNote!!.startsWith("WebView present but not inspectable (release build or debugging disabled)"))
        assertTrue(merged.webNote!!.contains("no webview_devtools socket for com.x"))
    }

    @Test
    fun `evaluate failure degrades to native with a note`() {
        val native = LogicalLayout(listOf(nativeEl(0, resourceId = "webview")))
        val cdp = FakeCdp(CdpClient.Discovery(listOf(target)), CdpClient.Eval.Failed("evaluate timed out after 3000ms"))
        val merged = WebAwareDevice(StaticDevice(native), cdp, "com.x").observe()

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
        WebAwareDevice(StaticDevice(native), cdp, "com.x").observe()
        assertEquals("T1", cdp.evaluated!!.id)
    }

    @Test
    fun `a lingering invisible target never merges into a native screen`() {
        val dying = target.copy(visible = false)
        val native = LogicalLayout(listOf(nativeEl(0, label = "웹툰")))
        val cdp = FakeCdp(CdpClient.Discovery(listOf(dying)), extractOk)
        val merged = WebAwareDevice(StaticDevice(native), cdp, "com.x").observe()
        assertSame(native, merged) // no webview node on screen -> plain native, no note
    }
}
