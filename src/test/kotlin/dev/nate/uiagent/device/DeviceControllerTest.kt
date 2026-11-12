package dev.nate.uiagent.device

import dev.nate.uiagent.layout
import dev.nate.uiagent.el
import dev.nate.uiagent.FakeDevice
import dev.nate.uiagent.LogicalLayout
import dev.nate.uiagent.Bounds
import dev.nate.uiagent.Point
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---------------------------------------------------------------- fixtures


private fun controller(device: Device) = DeviceController(device, settleIntervalMs = 0)

// ---------------------------------------------------------------- tests

class DeviceControllerTest {

    @Test
    fun `tap resolves label to coordinates and reports the diff`() {
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
    fun `a settle that never finds two equal frames is counted - every poll's dump and the loop that ran out`() {
        // stone run 41992964956: the pedia home changed on every poll, each first observation cost
        // 8 dumps, and the log had nothing but a 33s gap to show for it
        val restless = FakeDevice(*(1..9).map { layout(el("배너 $it")) }.toTypedArray())
        val c = controller(restless)
        c.fullLayout()
        assertEquals(8, c.meter.cost.dumps)
        assertEquals(1, c.meter.cost.unsettled)

        val still = controller(FakeDevice(layout(el("홈"))))
        still.fullLayout()
        assertEquals(2, still.meter.cost.dumps)
        assertEquals(0, still.meter.cost.unsettled)
    }

    @Test
    fun `a tap on another package's window releases the automation client first - in-app taps do not`() {
        // Google Play's purchase sheet ignores "Subscribe" while the layout server's UiAutomation
        // is connected (실측 2026-10-19); taps inside the app keep the server warm.
        val subscribe = el("Subscribe", interactions = listOf("clickable"), center = Point(360, 1168))
        fun tapOn(packages: Set<String>): List<String> {
            val dev = FakeDevice(LogicalLayout(layout(subscribe).elements, packages = packages))
            val c = DeviceController(dev, settleIntervalMs = 0, app = "com.frograms.wplay")
            c.fullLayout()
            c.tap("Subscribe")
            return dev.gestures
        }
        assertEquals(listOf("release", "tap 360 1168"), tapOn(setOf("com.android.vending")))
        assertEquals(listOf("tap 360 1168"), tapOn(setOf("com.frograms.wplay")))
        assertEquals(listOf("tap 360 1168"), tapOn(emptySet()), "legacy dump without full ids")
    }

    @Test
    fun `tap with unknown label acts as guard - no gesture, error message`() {
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
        val l = layout(el(null, interactions = listOf("clickable"), center = Point(606, 104)))
        val dev = FakeDevice(l, l, l, l)
        val c = controller(dev)
        c.fullLayout()

        c.tap("@x606y104")

        assertEquals(listOf("tap 606 104"), dev.gestures)
    }

    @Test
    fun `no-op tap returns NO CHANGE sentinel`() {
        val l = layout(el("배너", interactions = listOf("clickable"), center = Point(50, 50)))
        val dev = FakeDevice(l, l, l, l)
        val c = controller(dev)
        c.fullLayout()

        val result = c.tap("배너")

        assertContains(result, "NO CHANGE")
    }

    @Test
    fun `type taps the field then sets Unicode text against its resourceId`() {
        // Korean typing used to stop at an ASCII guard (adb `input text`); the accessibility
        // set-text path takes any script, targeted by the field's id after the focusing tap.
        val l = layout(el("검색", resourceId = "app:id/search_input", interactions = listOf("clickable", "focusable")))
        val dev = FakeDevice(l, l)
        val c = controller(dev)
        c.fullLayout()

        val result = c.type("검색", "타짜")

        assertEquals(listOf("tap 100 100", "text 타짜 into app:id/search_input"), dev.gestures)
        assertContains(result, "Typed \"타짜\"")
    }

    @Test
    fun `type into non-focusable is rejected`() {
        val l = layout(el("버튼", interactions = listOf("clickable")))
        val dev = FakeDevice(l, l)
        val c = controller(dev)
        c.fullLayout()

        val result = c.type("버튼", "abc")

        assertTrue(dev.gestures.isEmpty())
        assertContains(result, "not focusable")
    }

    /** stone 프로필 수정 (2026-11-12): heading, the field labelled by its value, its contentDescription inside it. */
    private fun linkField(value: String = "https://www.example.com/sky") = arrayOf(
        el("링크", kind = "label", center = Point(54, 978), bounds = Bounds(30, 961, 78, 995)),
        el(value, resourceId = "profile_edit_link",
            interactions = listOf("clickable", "long_clickable", "focusable", "editable"),
            center = Point(360, 1063), bounds = Bounds(30, 1011, 690, 1115)),
        el("링크", kind = "label", center = Point(360, 1063), bounds = Bounds(62, 1043, 658, 1083), parentId = 1),
    )

    @Test
    fun `type by a field's name reaches the field when the name is on text inside it and on its heading`() {
        val l = layout(*linkField())
        val dev = FakeDevice(l, l)
        var grounded: String? = null
        val c = DeviceController(dev, settleIntervalMs = 0, listener = object : ActionListener {
            override fun before(tool: String, args: List<Pair<String, String>>) {}
            override fun after(tool: String, args: List<Pair<String, String>>, resolved: dev.nate.uiagent.LogicalElement?, result: String) {
                grounded = resolved?.resourceId
            }
        })
        c.fullLayout()

        val result = c.type("링크", "www.example.com/sea")

        assertEquals(listOf("tap 360 1063", "text www.example.com/sea into profile_edit_link"), dev.gestures)
        assertContains(result, "Typed \"www.example.com/sea\"")
        assertEquals("profile_edit_link", grounded, "the trace records the field, not the text")
    }

    @Test
    fun `type by a field's name reaches the field when only the text inside it carries the name`() {
        // the heading has scrolled out of view: one match, and it is text
        val l = layout(*linkField().drop(1).map { it.copy(parentId = it.parentId?.minus(1)) }.toTypedArray())
        val dev = FakeDevice(l, l)
        val c = controller(dev)
        c.fullLayout()

        val result = c.type("링크", "x")

        assertEquals(listOf("tap 360 1063", "text x into profile_edit_link"), dev.gestures)
        assertContains(result, "Typed \"x\"")
    }

    @Test
    fun `type by a name whose field is not on screen says so and does nothing`() {
        // the heading alone at the bottom edge, its field still below the fold
        val l = layout(el("링크", kind = "label", center = Point(54, 1224), bounds = Bounds(30, 1217, 78, 1232)))
        val dev = FakeDevice(l, l)
        val c = controller(dev)
        c.fullLayout()

        val result = c.type("링크", "x")

        assertTrue(dev.gestures.isEmpty())
        assertContains(result, "is text, not an input field")
        assertContains(result, "no input field with that name is on the current screen")
    }

    @Test
    fun `type stays ambiguous when two fields carry the name`() {
        val a = linkField()
        val b = linkField().map { it.copy(center = Point(it.center.x, it.center.y + 200), parentId = it.parentId?.plus(3)) }
        val l = layout(*a, *b.toTypedArray())
        val dev = FakeDevice(l, l)
        val c = controller(dev)
        c.fullLayout()

        val result = c.type("링크", "x")

        assertTrue(dev.gestures.isEmpty())
        assertContains(result, "AMBIGUOUS")
    }

    @Test
    fun `scrollToFind finds target after one scroll`() {
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

    /** stone 프로필 수정 (2026-11-12): the form's scroll area, and the "링크" heading 15px into it at the bottom. */
    private val form = el(interactions = listOf("scrollable"), center = Point(360, 696), bounds = Bounds(0, 160, 720, 1232))
    private val cutHeading = el("링크", kind = "label", center = Point(54, 1224), bounds = Bounds(30, 1217, 78, 1232), parentId = 0)

    @Test
    fun `scrollToFind scrolls on when the target is cut by the edge the scroll brings content in from`() {
        val page1 = layout(form, cutHeading)
        val page2 = layout(form,
            el("링크", kind = "label", center = Point(54, 978), bounds = Bounds(30, 961, 78, 995), parentId = 0),
            el("https://a.b", resourceId = "profile_edit_link", interactions = listOf("clickable", "focusable", "editable"),
                center = Point(360, 1063), bounds = Bounds(30, 1011, 690, 1115), parentId = 0))
        val dev = FakeDevice(page1, page1, page2, page2)
        val c = controller(dev)
        c.fullLayout()

        val result = c.scrollToFind("@x360y696", "링크", "down")

        assertEquals(listOf("swipe"), dev.gestures)
        assertContains(result, "FOUND after 1 scroll(s) down")
    }

    @Test
    fun `scrollToFind takes a cut target as found when the container cannot scroll further`() {
        val page = layout(form, cutHeading)
        val dev = FakeDevice(page, page)
        val c = controller(dev)
        c.fullLayout()

        val result = c.scrollToFind("@x360y696", "링크", "down")

        assertEquals(listOf("swipe"), dev.gestures)
        assertContains(result, "FOUND without scrolling")
    }

    @Test
    fun `scrollToFind does not take the far edge, a container, or an element outside the scrollable for cut`() {
        // scrolling up brings content in from the top: the bottom edge cuts nothing that matters
        val up = layout(form, cutHeading)
        // a card that fills the scroll area down to its edge, with a title inside
        val card = layout(form,
            el("카드", interactions = listOf("clickable"), center = Point(360, 900), bounds = Bounds(0, 600, 720, 1232), parentId = 0),
            el("제목", kind = "label", center = Point(360, 700), bounds = Bounds(30, 680, 300, 720), parentId = 1))
        // a button pinned over the scroll area's bottom edge, not inside it
        val pinned = layout(form, el("완료", interactions = listOf("clickable"), center = Point(360, 1180), bounds = Bounds(0, 1130, 720, 1232)))

        for ((l, target, dir) in listOf(Triple(up, "링크", "up"), Triple(card, "카드", "down"), Triple(pinned, "완료", "down"))) {
            val dev = FakeDevice(l, l)
            val c = controller(dev)
            c.fullLayout()

            val result = c.scrollToFind("@x360y696", target, dir)

            assertTrue(dev.gestures.isEmpty(), "$target $dir")
            assertContains(result, "FOUND without scrolling")
        }
    }

    @Test
    fun `scrollToFind with several matches is FOUND in screen order, not AMBIGUOUS`() {
        val list = el("목록", resourceId = "recycler_view", interactions = listOf("focusable", "scrollable"),
            center = Point(360, 639), bounds = Bounds(0, 160, 720, 1118))
        val page1 = layout(list, el("항목A", interactions = listOf("clickable")))
        val page2 = layout(
            list,
            el("패키지 : 쿵푸팬더", interactions = listOf("clickable"), center = Point(330, 1005)),
            el("패키지 : 러브레터", interactions = listOf("clickable"), center = Point(686, 1005)),
        )
        val dev = FakeDevice(page1, page1, page2, page2)
        val c = controller(dev)
        c.fullLayout()

        val result = c.scrollToFind("목록", "패키지", "down")

        assertTrue(result.startsWith("FOUND after 1 scroll(s) down: 2 elements match '패키지', in screen order:"))
        assertContains(result, "- @x330y1005 ")
        assertContains(result, "- @x686y1005 ")
        assertTrue(result.indexOf("쿵푸팬더") < result.indexOf("러브레터"))
        assertFalse(result.contains("AMBIGUOUS"))
        assertFalse(result.contains("Repeat the action"))
    }

    @Test
    fun `scrollToFind returns FOUND without scrolling when target already visible`() {
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
    fun `volatile carousel content is suppressed from diffs after churning twice`() {
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
