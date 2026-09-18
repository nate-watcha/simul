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
