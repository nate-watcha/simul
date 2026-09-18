package dev.nate.uiagent.web

import dev.nate.uiagent.Bounds
import dev.nate.uiagent.Origin
import dev.nate.uiagent.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebLayoutTest {

    /** The payment page as extracted during the spike (label-wrapped radios + button + text). */
    private val extractResult = """
        {"iw":360,"rs":"complete","els":[
          {"label":"프리미엄","attrId":null,"tag":"input","type":"radio","role":null,
           "rect":{"x":20,"y":66,"width":160,"height":571},
           "state":{"checked":true,"disabled":false,"focused":false}},
          {"label":"베이직","attrId":"grade-basic","tag":"input","type":"radio","role":null,
           "rect":{"x":180,"y":66,"width":160,"height":571},
           "state":{"checked":false,"disabled":false,"focused":false}},
          {"label":"구독 시작하기","attrId":null,"tag":"button","type":"submit","role":null,
           "rect":{"x":20,"y":454,"width":320,"height":48},
           "state":{"checked":false,"disabled":false,"focused":false}},
          {"label":"15,900원","attrId":null,"tag":"text","type":null,"role":null,
           "rect":{"x":20,"y":138,"width":160,"height":24},
           "state":{"checked":false,"disabled":false,"focused":false}}
        ]}
    """.trimIndent()

    // WebView on-screen rect from the spike: screenX=0, screenY=160, 720x1072 physical px.
    private val webviewRect = Bounds(0, 160, 720, 1232)

    @Test
    fun `parses page envelope`() {
        val page = assertNotNull(WebLayout.parsePage(extractResult))
        assertEquals(360, page.innerWidth)
        assertEquals("complete", page.readyState)
        assertEquals(4, page.elements.size)
    }

    @Test
    fun `malformed extract result parses to null`() {
        assertNull(WebLayout.parsePage("garbage"))
        assertNull(WebLayout.parsePage("""{"rs":"complete"}""")) // missing iw
    }

    @Test
    fun `css rects transform to the screen coordinates validated on-device`() {
        val page = WebLayout.parsePage(extractResult)!!
        val els = WebLayout.toLogicalElements(page, webviewRect)
        // scale = 720/360 = 2; spike: tapping (520,862~863) flipped the 베이직 radio
        val basic = els.first { it.label == "베이직" }
        assertEquals(Point(520, 863), basic.center)
        assertEquals(Bounds(360, 292, 680, 1434), basic.bounds)
        assertTrue(els.all { it.origin == Origin.WEB })
    }

    @Test
    fun `attrId lands in resourceId`() {
        val els = WebLayout.toLogicalElements(WebLayout.parsePage(extractResult)!!, webviewRect)
        assertEquals("grade-basic", els.first { it.label == "베이직" }.resourceId)
        assertNull(els.first { it.label == "프리미엄" }.resourceId)
    }

    @Test
    fun `interaction mapping mirrors the native vocabulary`() {
        fun el(tag: String, type: String? = null, role: String? = null, disabled: Boolean = false) =
            WebLayout.WebElement("x", null, tag, type, role, WebLayout.WebRect(0.0, 0.0, 1.0, 1.0),
                checked = false, disabled = disabled, focused = false)

        assertEquals(listOf("clickable"), WebLayout.interactionsOf(el("a")))
        assertEquals(listOf("clickable"), WebLayout.interactionsOf(el("button", "submit")))
        assertEquals(listOf("clickable", "checkable"), WebLayout.interactionsOf(el("input", "radio")))
        assertEquals(listOf("clickable", "checkable"), WebLayout.interactionsOf(el("div", role = "checkbox")))
        assertEquals(listOf("clickable", "focusable"), WebLayout.interactionsOf(el("input", "text")))
        assertEquals(listOf("clickable", "focusable"), WebLayout.interactionsOf(el("textarea")))
        assertEquals(emptyList(), WebLayout.interactionsOf(el("text")))
        // disabled removes clickable but keeps the rest (plan M2)
        assertEquals(listOf("checkable"), WebLayout.interactionsOf(el("input", "radio", disabled = true)))
    }

    @Test
    fun `checked and focused map into state`() {
        val e = WebLayout.WebElement("x", null, "input", "checkbox", null,
            WebLayout.WebRect(0.0, 0.0, 1.0, 1.0), checked = true, disabled = false, focused = true)
        assertEquals(listOf("checked", "focused"), WebLayout.statesOf(e))
    }

    @Test
    fun `aria-selected maps into the native selected state`() {
        val tab = WebLayout.WebElement("멤버십", null, "div", null, "tab",
            WebLayout.WebRect(0.0, 0.0, 1.0, 1.0), checked = false, disabled = false,
            focused = false, selected = true)
        assertEquals(listOf("selected"), WebLayout.statesOf(tab))

        val page = WebLayout.parsePage(
            """{"iw":360,"rs":"complete","els":[
               {"label":"멤버십","attrId":"tab-membership","tag":"div","type":null,"role":"tab",
                "rect":{"x":0,"y":0,"width":100,"height":40},
                "state":{"checked":false,"selected":true,"disabled":false,"focused":false}}]}"""
        )!!
        assertEquals(true, page.elements[0].selected)
    }

    @Test
    fun `text nodes become label kind`() {
        val els = WebLayout.toLogicalElements(WebLayout.parsePage(extractResult)!!, webviewRect)
        assertEquals("label", els.first { it.label == "15,900원" }.kind)
        assertNull(els.first { it.label == "구독 시작하기" }.kind)
    }

    @Test
    fun `zero innerWidth cannot divide`() {
        val page = WebLayout.WebPage(0, "complete", emptyList())
        assertTrue(WebLayout.toLogicalElements(page, webviewRect).isEmpty())
    }
}
