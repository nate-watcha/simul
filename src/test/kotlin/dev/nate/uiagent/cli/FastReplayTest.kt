package dev.nate.uiagent.cli

import dev.nate.uiagent.Point
import dev.nate.uiagent.device.DeviceController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Replay with [DeviceController.fastObserve]: single-dump observations on the happy path,
 * with a stabilized re-observation + retry on every failure so the verdict stays identical
 * to fully-stabilized replay.
 */
class FastReplayTest {

    private val tab = el("웹툰", interactions = listOf("clickable"), center = Point(360, 1176), id = 0)
    private val home = layout(tab)
    private val webtoonHome = layout(tab, el("웹툰 홈", id = 1, center = Point(360, 300)))

    private fun fastController(dev: FakeDevice) =
        DeviceController(dev, settleIntervalMs = 0).also { it.fastObserve = true }

    private val tapStep = TraceStep(
        "Tap the \"웹툰\" tab",
        listOf(TraceAction("tap", target = TraceTarget(null, "웹툰", Point(360, 1176)))),
        evidence = listOf("웹툰 홈"),
    )

    @Test
    fun `happy path uses one observation per action`() {
        val dev = FakeDevice(home, webtoonHome)
        val c = fastController(dev)
        c.fullLayout() // 1 obs
        val r = Replayer(c).replayStep(tapStep) // tap settle: 1 obs, evidence present

        assertNull(r.broken)
        assertEquals(2, dev.observeCount, "fast replay must not double-observe")
        assertEquals(listOf("tap 360 1176"), dev.gestures)
    }

    @Test
    fun `evidence missing on the fast frame recovers via restabilize`() {
        // post-tap fast observation catches a mid-transition frame without the evidence;
        // the stabilized recheck (2 identical observations) sees the final screen
        val midTransition = layout(tab)
        val dev = FakeDevice(home, midTransition, webtoonHome, webtoonHome)
        val c = fastController(dev)
        c.fullLayout()
        val r = Replayer(c).replayStep(tapStep)

        assertNull(r.broken, "evidence must be rechecked on a settled screen: ${r.broken}")
        assertEquals(4, dev.observeCount)
    }

    @Test
    fun `grounding failure on the fast frame recovers via restabilize`() {
        // scenario start observes a mid-transition frame where the target is not yet present
        val midTransition = layout(el("로딩중", id = 5, center = Point(1, 1)))
        val dev = FakeDevice(midTransition, home, home, webtoonHome)
        val c = fastController(dev)
        c.fullLayout() // fast: sees the frame without the target
        val r = Replayer(c).replayStep(tapStep)

        assertNull(r.broken, "grounding must retry on a settled screen: ${r.broken}")
        assertEquals(listOf("tap 360 1176"), dev.gestures)
        assertEquals(4, dev.observeCount) // fast(1) + restabilize(2) + post-tap fast(1)
    }

    @Test
    fun `a genuinely vanished target is still broken, judged on a settled screen`() {
        val dev = FakeDevice(home)
        val c = fastController(dev)
        c.fullLayout()
        val step = TraceStep(
            "Tap",
            listOf(TraceAction("tap", target = TraceTarget(null, "사라진 라벨", Point(100, 100)))),
            evidence = emptyList(),
        )
        val r = Replayer(c).replayStep(step)

        val broken = assertNotNull(r.broken)
        assertEquals(0, broken.atAction)
        assertTrue(dev.observeCount >= 3, "must have restabilized before giving up (n=${dev.observeCount})")
        assertTrue(dev.gestures.isEmpty())
    }

    @Test
    fun `restabilize restores fast mode afterwards`() {
        val dev = FakeDevice(home)
        val c = fastController(dev)
        c.fullLayout()
        c.restabilize()
        assertTrue(c.fastObserve)
    }
}
