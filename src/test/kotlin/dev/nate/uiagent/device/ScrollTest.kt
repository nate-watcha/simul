package dev.nate.uiagent.device

import dev.nate.uiagent.Bounds
import dev.nate.uiagent.LayoutAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScrollTest {

    

    // Real-device 구독 탭 layout with a top banner carousel overlapping the outer list.
    private val nested = LayoutAdapter.adapt(
        """
        [
          {"interactions":["focusable","scrollable"],"center":"[360,639]","bounds":"[0,160][720,1118]","resource-id":"recycler_view"},
          {"interactions":["focusable","scrollable"],"center":"[360,880]","bounds":"[0,643][720,1118]","resource-id":"rv_cell"},
          {"interactions":["scrollable"],"center":"[360,321]","bounds":"[0,0][720,643]"},
          {"content-desc":"영화 인기 TOP 100","interactions":["clickable","focusable"],"center":"[360,880]"}
        ]
        """.trimIndent()
    )

    private fun recyclerView() = nested.elements.single { it.resourceId == "recycler_view" }
    private fun banner() = nested.elements.single { it.bounds == Bounds(0, 0, 720, 643) }

    @Test
    fun `down swipe on outer list starts above the bottom overlay and is long`() {
        val s = SwipeGeometry.swipeCoords(recyclerView(), "down", nested.elements)
        // region recycler_view [0,160][720,1118], h=958: start 160+958*68/100=811, end 160+95=255
        assertEquals(360, s[0], "swipe column = region center x")
        assertEquals(160 + 958 * 68 / 100, s[1])
        assertEquals(160 + 958 * 10 / 100, s[3])
        assertTrue(s[1] < 1118 * 8 / 10, "start must clear the bottom overlay zone")
        assertTrue(s[1] - s[3] > 400, "stroke must be long enough to bubble through nested rails")
    }

    @Test
    fun `picking the banner redirects to the tallest focusable scroller`() {
        // Even if the model picks the banner (a horizontal pager, not focusable), a vertical scroll
        // acts on the tall focusable recycler_view.
        val s = SwipeGeometry.swipeCoords(banner(), "down", nested.elements)
        assertEquals(160 + 958 * 68 / 100, s[1], "must use recycler_view, not the banner")
        assertTrue(s[3] < s[1])
    }

    @Test
    fun `single scrollable uses a long stroke clearing the bottom`() {
        val simple = LayoutAdapter.adapt(
            """[{"interactions":["scrollable"],"center":"[360,689]","bounds":"[0,260][720,1118]","resource-id":"list"}]"""
        )
        val e = simple.elements.single()
        val s = SwipeGeometry.swipeCoords(e, "down", simple.elements)
        // region [260,1118], h=858: start 260+858*68/100=843, end 260+85=345
        assertEquals(360, s[0])
        assertEquals(260 + 858 * 68 / 100, s[1])
        assertEquals(260 + 858 * 10 / 100, s[3])
    }

    @Test
    fun `vertical scroll prefers the focusable list over a taller bare pager`() {
        // recycler_view is focusable; the bare ["scrollable"] is TALLER but a horizontal pager.
        val overlap = LayoutAdapter.adapt(
            """
            [
              {"interactions":["focusable","scrollable"],"center":"[360,689]","bounds":"[0,260][720,1118]","resource-id":"recycler_view"},
              {"interactions":["scrollable"],"center":"[360,704]","bounds":"[0,260][720,1149]"}
            ]
            """.trimIndent()
        )
        val pager = overlap.elements.single { it.resourceId == null }
        // Even if the model picks the pager, the swipe must act on recycler_view's region [260,1118].
        val s = SwipeGeometry.swipeCoords(pager, "down", overlap.elements)
        assertEquals(260 + 858 * 68 / 100, s[1], "must use focusable recycler_view (1118), not the pager (1149)")
        assertEquals(260 + 858 * 10 / 100, s[3])
    }

    @Test
    fun `horizontal scroll swipes within the picked rail bounds`() {
        val rail = LayoutAdapter.adapt(
            """[{"interactions":["scrollable"],"center":"[360,880]","bounds":"[0,643][720,1118]","resource-id":"rail"}]"""
        ).elements.single()
        val s = SwipeGeometry.swipeCoords(rail, "left", listOf(rail))
        val cy = (643 + 1118) / 2
        assertEquals(cy, s[1])
        assertEquals(cy, s[3])
        assertTrue(s[0] > s[2], "left means finger moves left (endX < startX)")
    }

    @Test
    fun `element without bounds falls back to synthetic region`() {
        val noBounds = LayoutAdapter.adapt(
            """[{"interactions":["scrollable"],"center":"[360,600]"}]"""
        ).elements.single()
        assertNotNull(noBounds)
        val s = SwipeGeometry.swipeCoords(noBounds, "down", listOf(noBounds))
        // synthetic region is centered on the element center x
        assertEquals(360, s[0])
        assertTrue(s[3] < s[1])
    }
}
