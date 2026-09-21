package dev.nate.uiagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutAdapterTest {

    // Real-device 구독 탭 sample (abridged) from the design doc.
    private val fixture = """
    [
      {"interactions":["clickable","focusable"],"center":"[648,1176]","key":3506402},
      {"text":"보관함","center":"[648,1199]","key":3506402},
      {"content-desc":"보관함","center":"[648,1159]","key":3506402},
      {"interactions":["focusable"],"state":["selected"],"center":"[72,1176]","key":3506402},
      {"interactions":["focusable","scrollable"],"center":"[360,689]","bounds":"[0,260][720,1118]","resource-id":"recycler_view","key":3506402},
      {"text":"구독 시작하기","center":"[183,1060]","key":3506402},
      {"content-desc":"설정","interactions":["clickable","focusable"],"center":"[648,98]","resource-id":"profileImage","key":3506402}
    ]
    """.trimIndent()

    @Test
    fun `label attaches to nearest interactive target`() {
        val layout = LayoutAdapter.adapt(fixture)
        val lib = layout.elements.single { it.label == "보관함" }
        // The tab target is the anonymous clickable node at [648,1176], not the text node.
        assertTrue("clickable" in lib.interactions, "merged tab must be clickable")
        assertEquals(Point(648, 1176), lib.center, "must use the interactive node's center, not the label's")
        assertNull(lib.kind, "a merged interactive is not a label")
    }

    @Test
    fun `orphan label is retained as kind label with its own center`() {
        val layout = LayoutAdapter.adapt(fixture)
        val cta = layout.elements.single { it.label == "구독 시작하기" }
        assertEquals("label", cta.kind)
        assertTrue(cta.interactions.isEmpty())
        // Its own text center is kept so the harness can tap it (clickable ancestor receives the event).
        assertEquals(Point(183, 1060), cta.center)
    }

    @Test
    fun `self-labeled interactive keeps its content-desc`() {
        val layout = LayoutAdapter.adapt(fixture)
        val settings = layout.elements.single { it.label == "설정" }
        assertEquals("profileImage", settings.resourceId)
        assertTrue("clickable" in settings.interactions)
    }

    @Test
    fun `model-facing rendering never exposes raw geometry or keys`() {
        // Coordinate-hallucination guard: center/bounds stay harness-side. The one exception
        // is the synthetic "@x..y.." handle of label-less elements — that IS the tap handle.
        val layout = LayoutAdapter.adapt(fixture)
        val rendered = dev.nate.uiagent.device.renderFullLayout(layout.elements)
        assertFalse(rendered.contains("center"), "center must not leak to the model")
        assertFalse(rendered.contains("bounds"), "bounds must not leak to the model")
        assertFalse(rendered.contains("3506402"), "unstable key must not leak")
        assertTrue(rendered.contains("보관함"))
    }

    @Test
    fun `duplicate self-labeled interactives dedupe to the clickable one`() {
        val dupes = """
        [
          {"text":"영화","interactions":["focusable"],"center":"[100,100]"},
          {"content-desc":"영화","interactions":["clickable","focusable"],"center":"[100,100]"}
        ]
        """.trimIndent()
        val layout = LayoutAdapter.adapt(dupes)
        val movie = layout.elements.filter { it.label == "영화" }
        assertEquals(1, movie.size, "duplicate label must collapse to one element")
        assertTrue("clickable" in movie.single().interactions, "the clickable node must win")
    }

    @Test
    fun `ids are sequential from zero and top-to-bottom`() {
        val layout = LayoutAdapter.adapt(fixture)
        assertEquals(layout.elements.indices.toList(), layout.elements.map { it.id })
        // First element (id 0) is the top-most: 설정 at y=98.
        assertEquals("설정", layout.elements.first().label)
    }

    @Test
    fun `crlf in text is normalized`() {
        val raw = """[{"text":"line1\r\nline2","interactions":["clickable"],"center":"[10,10]"}]"""
        val layout = LayoutAdapter.adapt(raw)
        assertEquals("line1 line2", layout.elements.single().label)
    }

    private fun assertFalse(cond: Boolean, msg: String) = assertTrue(!cond, msg)
}

class LayoutAdapterTreeFormatTest {
    /** `android layout` ≥ 1.0.16261425: preamble line, nested children, UPPERCASE vocab, full ids. */
    private val tree = """
        Installing layout instrumentation server...
        [{"class":"android.widget.TextView","text":"구독","bounds":"[40,70][116,126]","center":"[78,98]"},
         {"class":"android.widget.FrameLayout","resource-id":"com.frograms.wplay:id/menu_item_notice","content-desc":"공지사항","interactions":["FOCUSABLE"],"bounds":"[440,66][528,130]","center":"[484,98]",
          "children":[{"class":"android.widget.ImageView","resource-id":"com.frograms.wplay:id/noticeButton","content-desc":"공지사항","interactions":["CLICKABLE","FOCUSABLE"],"bounds":"[440,66][504,130]","center":"[472,98]"}]},
         {"class":"android.view.View","interactions":["CHECKABLE","CLICKABLE","FOCUSABLE"],"state":["CHECKED"],"bounds":"[40,156][152,252]","center":"[96,204]",
          "children":[{"class":"android.widget.TextView","text":"전체","bounds":"[72,184][120,224]","center":"[96,204]"}]},
         {"class":"android.view.View","interactions":["CLICKABLE","FOCUSABLE"],"bounds":"[144,1120][288,1232]","center":"[216,1176]",
          "children":[{"class":"android.widget.TextView","text":"개별 구매","bounds":"[171,1181][261,1217]","center":"[216,1199]"}]},
         {"class":"androidx.recyclerview.widget.RecyclerView","resource-id":"com.frograms.wplay:id/recycler_view","interactions":["FOCUSABLE","SCROLLABLE"],"bounds":"[0,260][720,1118]","center":"[360,689]"}]
    """.trimIndent()

    @Test
    fun `tree output is flattened and normalised to the flat-format vocabulary`() {
        val raw = LayoutAdapter.parseRawLayout(tree)
        assertEquals(8, raw.size, "every nested node counts")
        assertEquals(listOf("clickable", "focusable"), raw.first { it.text == null && it.center.x == 216 }.interactions)
        assertEquals(listOf("checked"), raw.first { it.center.x == 96 && it.isInteractive }.state)
        assertEquals("noticeButton", raw.first { it.contentDesc == "공지사항" && it.interactions.contains("clickable") }.resourceId)

        val layout = LayoutAdapter.adapt(tree)
        val tab = layout.elements.first { it.label == "개별 구매" }
        assertTrue("clickable" in tab.interactions, "label from the child TextView attaches to the tappable parent")
        assertEquals("recycler_view", layout.elements.first { it.resourceId == "recycler_view" }.resourceId)
        assertTrue(layout.elements.any { it.label == "전체" && "checked" in it.state })
    }

    @Test
    fun `flat legacy output still parses unchanged`() {
        val flat = """[{"text":"찾기","center":"[504,1199]","key":1},{"interactions":["clickable","focusable"],"center":"[504,1176]","key":1}]"""
        val layout = LayoutAdapter.adapt(flat)
        assertEquals("찾기", layout.elements.single().label)
    }

    @Test
    fun `garbage and preamble-only output yield no elements`() {
        assertTrue(LayoutAdapter.parseRawLayout("Installing layout instrumentation server...\n").isEmpty())
        assertTrue(LayoutAdapter.parseRawLayout("").isEmpty())
    }
}
