package dev.nate.uiagent.cli

import dev.nate.uiagent.Point
import dev.nate.uiagent.device.Device
import dev.nate.uiagent.device.DeviceController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayerTest {

    private fun controller(device: Device) = DeviceController(device, settleIntervalMs = 0)

    @Test
    fun `replays a tap by label and passes on evidence`() {
        val tab = el("웹툰", interactions = listOf("clickable"), center = Point(360, 1176), id = 0)
        val before = layout(tab, el("구독", id = 1, center = Point(500, 1176)))
        val after = layout(tab, el("구독", id = 1, center = Point(500, 1176)), el("웹툰 홈", id = 2, center = Point(360, 300)))
        val dev = FakeDevice(before, before, after, after)
        val c = controller(dev)
        c.fullLayout()

        val step = TraceStep(
            "Tap the \"웹툰\" tab",
            listOf(TraceAction("tap", target = TraceTarget(null, "웹툰", Point(360, 1176)))),
            evidence = listOf("웹툰 홈"),
        )
        val r = Replayer(c).replayStep(step)

        assertNull(r.broken)
        assertEquals(listOf("tap 360 1176"), dev.gestures)
        assertTrue(r.coordUpdates.isEmpty(), "label grounding must not rewrite lastCoords")
    }

    @Test
    fun `resourceId wins over a changed label`() {
        val btn = el("새 라벨", resourceId = "play_button", interactions = listOf("clickable"), center = Point(200, 400), id = 0)
        val l = layout(btn)
        val dev = FakeDevice(l, l, l, l)
        val c = controller(dev)
        c.fullLayout()

        val step = TraceStep(
            "Tap play",
            listOf(TraceAction("tap", target = TraceTarget("play_button", "옛 라벨", Point(999, 999)))),
            evidence = emptyList(),
        )
        val r = Replayer(c).replayStep(step)

        assertNull(r.broken)
        assertEquals(listOf("tap 200 400"), dev.gestures)
    }

    @Test
    fun `ambiguous label is disambiguated by lastCoords and rewrites them`() {
        val a = el("웹툰", interactions = listOf("clickable"), center = Point(360, 1180), id = 0)
        val b = el("웹툰", interactions = listOf("clickable"), center = Point(100, 200), id = 1)
        val l = layout(a, b)
        val dev = FakeDevice(l, l, l, l)
        val c = controller(dev)
        c.fullLayout()

        val step = TraceStep(
            "Tap",
            listOf(TraceAction("tap", target = TraceTarget(null, "웹툰", Point(360, 1176)))),
            evidence = emptyList(),
        )
        val r = Replayer(c).replayStep(step)

        assertNull(r.broken)
        assertEquals(listOf("tap 360 1180"), dev.gestures)
        assertEquals(Point(360, 1180), r.coordUpdates[0])
    }

    @Test
    fun `vanished labeled target is broken, not blind-tapped`() {
        val l = layout(el("다른거", interactions = listOf("clickable"), id = 0))
        val dev = FakeDevice(l, l)
        val c = controller(dev)
        c.fullLayout()

        val step = TraceStep(
            "Tap",
            listOf(TraceAction("tap", target = TraceTarget(null, "사라진 라벨", Point(100, 100)))),
            evidence = emptyList(),
        )
        val r = Replayer(c).replayStep(step)

        assertNotNull(r.broken)
        assertEquals(0, r.broken!!.atAction)
        assertTrue(dev.gestures.isEmpty())
    }

    @Test
    fun `unlabeled target grounds by coordinates within tolerance`() {
        val moved = el(null, interactions = listOf("clickable"), center = Point(610, 110), id = 0)
        val l = layout(moved)
        val dev = FakeDevice(l, l, l, l)
        val c = controller(dev)
        c.fullLayout()

        val step = TraceStep(
            "Tap",
            listOf(TraceAction("tap", target = TraceTarget(null, null, Point(606, 104)))),
            evidence = emptyList(),
        )
        val r = Replayer(c).replayStep(step)

        assertNull(r.broken)
        assertEquals(listOf("tap 610 110"), dev.gestures)
        assertEquals(Point(610, 110), r.coordUpdates[0], "coordinate fallback must rewrite lastCoords")
    }

    @Test
    fun `state evidence gates replay — selected tab passes, unselected breaks`() {
        val selected = el("구독", interactions = listOf("clickable"), state = listOf("selected"),
            center = Point(72, 1176), id = 0)
        val dev = FakeDevice(layout(selected))
        val c = controller(dev)
        c.fullLayout()

        val pass = Replayer(c).replayStep(TraceStep("Tap the \"구독\" tab", emptyList(), listOf("구독"),
            stateEvidence = listOf(StateEvidence("구독", "selected"))))
        assertNull(pass.broken)

        val broken = Replayer(c).replayStep(TraceStep("Tap the \"웹툰\" tab", emptyList(), listOf("구독"),
            stateEvidence = listOf(StateEvidence("구독", "checked")))).broken
        assertNotNull(broken)
        assertTrue("구독=checked" in broken.why, broken.why)
    }

    @Test
    fun `missing evidence after actions is broken`() {
        val tab = el("웹툰", interactions = listOf("clickable"), center = Point(360, 1176), id = 0)
        val l = layout(tab)
        val dev = FakeDevice(l, l, l, l)
        val c = controller(dev)
        c.fullLayout()

        val step = TraceStep(
            "Tap the \"웹툰\" tab",
            listOf(TraceAction("tap", target = TraceTarget(null, "웹툰", Point(360, 1176)))),
            evidence = listOf("웹툰 홈"),
        )
        val r = Replayer(c).replayStep(step)

        assertNotNull(r.broken)
        assertNull(r.broken!!.atAction)
        assertTrue("evidence" in r.broken!!.why)
    }
}
