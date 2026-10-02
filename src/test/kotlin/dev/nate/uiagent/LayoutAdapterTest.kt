package dev.nate.uiagent

import dev.nate.uiagent.LayoutAdapter.flatten
import dev.nate.uiagent.device.renderFullLayout
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
        val rendered = renderFullLayout(layout.elements)
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
    fun `ids are sequential from zero in document order and a flat dump has no nesting`() {
        val layout = LayoutAdapter.adapt(fixture)
        assertEquals(layout.elements.indices.toList(), layout.elements.map { it.id })
        // Document order, not (y,x): the first node of the dump is the 보관함 tab target.
        assertEquals(listOf("보관함", null, "recycler_view", "구독 시작하기", "설정"), layout.elements.map { it.label ?: it.resourceId })
        assertTrue(layout.elements.all { it.parentId == null }, "legacy flat output is a forest of roots")
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
    fun `tree output keeps its nesting and is normalised to the flat-format vocabulary`() {
        val roots = LayoutAdapter.parseRawLayout(tree)
        assertEquals(5, roots.size)
        val raw = roots.flatten()
        assertEquals(8, raw.size, "every nested node counts")
        assertEquals(listOf("clickable", "focusable"), raw.first { it.text == null && it.center.x == 216 }.interactions)
        assertEquals(listOf("checked"), raw.first { it.center.x == 96 && it.isInteractive }.state)
        assertEquals("noticeButton", raw.first { it.contentDesc == "공지사항" && it.interactions.contains("clickable") }.resourceId)

        val layout = LayoutAdapter.adapt(tree)
        val tab = layout.elements.single { it.label == "개별 구매" }
        assertTrue("clickable" in tab.interactions, "the child TextView's label is absorbed by the tappable parent")
        assertNull(tab.parentId)
        assertEquals("recycler_view", layout.elements.first { it.resourceId == "recycler_view" }.resourceId)
        assertTrue(layout.elements.single { it.label == "전체" }.let { "checked" in it.state && "clickable" in it.interactions })
        // toolbar: focusable FrameLayout + clickable ImageView share the desc -> only the ImageView, at the top level
        val notice = layout.elements.single { it.label == "공지사항" }
        assertEquals("noticeButton", notice.resourceId)
        assertNull(notice.parentId)
    }

    @Test
    fun `flat legacy output still parses unchanged`() {
        val flat = """[{"text":"찾기","center":"[504,1199]","key":1},{"interactions":["clickable","focusable"],"center":"[504,1176]","key":1}]"""
        val layout = LayoutAdapter.adapt(flat)
        assertEquals("찾기", layout.elements.single().label)
    }

    /** 1.0.16406183: two status lines, the same tree, and the update notice after the array. */
    private val v16406183 = """
        Unpacking embedded installation...
        Installing layout instrumentation server...
        [{"class":"android.widget.TextView","text":"구독","bounds":"[75,155][217,260]","center":"[146,207]"},
         {"class":"android.view.View","interactions":["CLICKABLE","FOCUSABLE"],"bounds":"[288,2854][576,3064]","center":"[432,2959]",
          "children":[{"class":"android.widget.TextView","text":"개별 구매","bounds":"[349,2967][516,3035]","center":"[432,3001]"}]},
         {"class":"android.widget.ImageView","content-desc":"WX \u003c절친클럽\u003e","interactions":["CLICKABLE","FOCUSABLE"],"bounds":"[75,2298][454,2511]","center":"[264,2404]"}]

        A new version of Android CLI is available (1.0.99999999).
        Please run 'android update' to install it.
    """.trimIndent()

    @Test
    fun `1_0_16406183 output with status lines and a trailing update notice parses like 1_0_16261425`() {
        assertEquals(4, LayoutAdapter.parseRawLayout(v16406183).flatten().size)
        val layout = LayoutAdapter.adapt(v16406183)
        assertEquals(listOf("구독", "개별 구매", "WX <절친클럽>"), layout.elements.map { it.label })
        assertTrue("clickable" in layout.elements.first { it.label == "개별 구매" }.interactions)
    }

    @Test
    fun `--full dump nodes flagged hidden or off-screen are skipped with their subtrees`() {
        val full = """
            [{"class":"android.widget.FrameLayout","bounds":"[0,0][1440,3120]","center":"[720,1560]","children":[
              {"class":"android.widget.Button","text":"보이는 버튼","interactions":["CLICKABLE"],"bounds":"[0,0][100,100]","center":"[50,50]"},
              {"class":"android.widget.Button","text":"숨은 버튼","hidden":true,"interactions":["CLICKABLE"],"bounds":"[0,0][100,100]","center":"[50,50]",
               "children":[{"class":"android.widget.TextView","text":"숨은 자식","bounds":"[0,0][100,100]","center":"[50,50]"}]},
              {"class":"android.widget.Button","text":"스크롤 밖","off-screen":true,"interactions":["CLICKABLE"],"bounds":"[0,4000][100,4100]","center":"[50,4050]"}]}]
        """.trimIndent()
        val layout = LayoutAdapter.adapt(full)
        assertEquals(listOf("보이는 버튼"), layout.elements.map { it.label })
        assertNull(layout.elements.single().parentId, "the pruned root container is not a parent")
    }

    @Test
    fun `garbage and preamble-only output yield no elements`() {
        assertTrue(LayoutAdapter.parseRawLayout("Installing layout instrumentation server...\n").isEmpty())
        assertTrue(LayoutAdapter.parseRawLayout("").isEmpty())
        assertTrue(LayoutAdapter.parseRawLayout("[{\"center\":\"[1,1]\"").isEmpty(), "truncated dump")
    }
}

class LayoutTreeTest {
    /** Layout V2 shapes from the real app: a list row card (several texts + a button) and bottom-nav items. */
    private val listAndNav = """
        [{"class":"androidx.recyclerview.widget.RecyclerView","resource-id":"com.x:id/recycler_view","interactions":["FOCUSABLE","SCROLLABLE"],"bounds":"[0,260][720,600]","center":"[360,430]","children":[
           {"class":"android.view.View","interactions":["SCROLLABLE"],"bounds":"[0,260][720,600]","center":"[360,430]","children":[
             {"class":"android.view.View","interactions":["CLICKABLE","FOCUSABLE"],"bounds":"[0,260][720,600]","center":"[360,430]","children":[
               {"class":"android.widget.TextView","text":"타짜","bounds":"[40,300][200,340]","center":"[120,320]"},
               {"class":"android.widget.TextView","text":"2006 · 범죄","bounds":"[40,350][200,380]","center":"[120,365]"},
               {"class":"android.view.View","interactions":["CLICKABLE","FOCUSABLE"],"bounds":"[40,500][200,560]","center":"[120,530]","children":[
                 {"class":"android.widget.TextView","text":"감상하기","bounds":"[60,510][180,550]","center":"[120,530]"}]}]}]}]},
         {"class":"android.view.View","interactions":["CLICKABLE","FOCUSABLE"],"bounds":"[0,1120][144,1232]","center":"[72,1176]","children":[
           {"class":"android.widget.TextView","text":"구독","bounds":"[40,1181][104,1217]","center":"[72,1199]"}]},
         {"class":"android.view.View","interactions":["CLICKABLE","FOCUSABLE"],"state":["SELECTED"],"bounds":"[144,1120][288,1232]","center":"[216,1176]","children":[
           {"class":"android.widget.TextView","text":"개별 구매","bounds":"[171,1181][261,1217]","center":"[216,1199]"}]}]
    """.trimIndent()

    @Test
    fun `elements are pre-order with parentId links and render indented by depth`() {
        val layout = LayoutAdapter.adapt(listAndNav)
        assertEquals(listOf(null, 0, 1, 2, 2, 2, null, null), layout.elements.map { it.parentId })
        assertEquals(layout.elements.indices.toList(), layout.elements.map { it.id })
        assertEquals(
            """
            {"label":"recycler_view","resourceId":"recycler_view","interactions":["focusable","scrollable"]}
              {"label":"@x360y430","interactions":["scrollable"]}
                {"label":"@x360y430","interactions":["clickable","focusable"]}
                  {"label":"타짜","kind":"label"}
                  {"label":"2006 · 범죄","kind":"label"}
                  {"label":"감상하기","interactions":["clickable","focusable"]}
            {"label":"구독","interactions":["clickable","focusable"]}
            {"label":"개별 구매","interactions":["clickable","focusable"],"state":["selected"]}
            """.trimIndent(),
            renderFullLayout(layout.elements),
        )
    }

    @Test
    fun `a card with several texts stays anonymous while a single-text card absorbs its label`() {
        val cards = """
            [{"class":"android.view.View","interactions":["CLICKABLE"],"bounds":"[0,0][720,300]","center":"[360,150]","children":[
               {"class":"android.widget.TextView","text":"제목","center":"[100,40]"},
               {"class":"android.widget.TextView","text":"부제","center":"[100,90]"}]},
             {"class":"android.view.View","interactions":["CLICKABLE"],"bounds":"[0,300][720,600]","center":"[360,450]","children":[
               {"class":"android.widget.TextView","text":"단독 제목","center":"[100,340]"}]}]
        """.trimIndent()
        val layout = LayoutAdapter.adapt(cards)
        assertEquals(listOf("@x360y150", "제목", "부제", "단독 제목"), layout.elements.map { it.label ?: "@x${it.center.x}y${it.center.y}" })
        assertEquals(listOf(null, 0, 0, null), layout.elements.map { it.parentId })
        assertEquals(listOf(null, "label", "label", null), layout.elements.map { it.kind })
    }

    @Test
    fun `a pruned container hands its children to the grandparent`() {
        val nested = """
            [{"class":"android.view.View","interactions":["CLICKABLE"],"center":"[360,150]","children":[
               {"class":"android.widget.LinearLayout","center":"[360,150]","children":[
                 {"class":"android.widget.TextView","text":"안쪽 텍스트","center":"[100,40]"}]}]}]
        """.trimIndent()
        val layout = LayoutAdapter.adapt(nested)
        // the hoisted single text child is then absorbed by the clickable
        assertEquals(listOf("안쪽 텍스트"), layout.elements.map { it.label })
        assertTrue("clickable" in layout.elements.single().interactions)
    }

    @Test
    fun `a text child labels its own parent even when a sibling interactive is geometrically closer`() {
        val siblings = """
            [{"class":"android.view.View","interactions":["CLICKABLE"],"center":"[100,100]","children":[
               {"class":"android.widget.TextView","text":"내 라벨","center":"[100,140]"}]},
             {"class":"android.view.View","interactions":["CLICKABLE"],"center":"[100,150]"}]
        """.trimIndent()
        val layout = LayoutAdapter.adapt(siblings)
        assertEquals("내 라벨", layout.elements.first { it.center == Point(100, 100) }.label)
        assertNull(layout.elements.first { it.center == Point(100, 150) }.label)
    }

    @Test
    fun `an absorbed text child that carries a resourceId stays nested as evidence`() {
        // kloud: clickable row > TextView(tvDeleteHistory "삭제") — traces record tvDeleteHistory as appeared evidence
        val row = """
            [{"class":"android.view.View","interactions":["CLICKABLE"],"bounds":"[0,0][720,100]","center":"[360,50]","children":[
               {"class":"android.widget.TextView","resource-id":"com.x:id/tvDeleteHistory","text":"삭제","center":"[650,50]"}]},
             {"class":"android.view.View","interactions":["CLICKABLE"],"bounds":"[0,100][720,200]","center":"[360,150]","children":[
               {"class":"android.widget.TextView","text":"구독","center":"[360,150]"}]}]
        """.trimIndent()
        val layout = LayoutAdapter.adapt(row)
        assertEquals(listOf("삭제", "삭제", "구독"), layout.elements.map { it.label })
        assertEquals(listOf(null, "tvDeleteHistory", null), layout.elements.map { it.resourceId })
        assertEquals(listOf(null, 0, null), layout.elements.map { it.parentId })
        assertEquals("label", layout.elements[1].kind)
    }

    @Test
    fun `a nearby text with a different label is kept instead of being consumed`() {
        // header text near a row that already took its own child's label: the old geometric pass
        // consumed it (label lost); it must survive as a label element
        val near = """
            [{"class":"android.widget.TextView","text":"최근 검색한 항목","center":"[340,60]"},
             {"class":"android.view.View","interactions":["CLICKABLE"],"bounds":"[0,0][720,120]","center":"[360,60]","children":[
               {"class":"android.widget.TextView","text":"삭제","center":"[650,60]"}]},
             {"class":"android.widget.TextView","text":"삭제","center":"[360,70]"}]
        """.trimIndent()
        val layout = LayoutAdapter.adapt(near)
        // "삭제" duplicate of the row's label is consumed; the header stays
        assertEquals(listOf("최근 검색한 항목", "삭제"), layout.elements.map { it.label })
        assertEquals(listOf("label", null), layout.elements.map { it.kind })
    }

    @Test
    fun `empty layout renders a sentinel`() {
        assertEquals("(no elements)", renderFullLayout(emptyList()))
    }
}
