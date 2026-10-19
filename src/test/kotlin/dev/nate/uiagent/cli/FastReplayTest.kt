package dev.nate.uiagent.cli

import dev.nate.uiagent.FakeDevice
import dev.nate.uiagent.layout
import dev.nate.uiagent.el
import dev.nate.uiagent.Point
import dev.nate.uiagent.device.DeviceController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Replay with [DeviceController.fastObserve]: single-dump observations on the happy path,
 * and on a miss a poll for the screen replay expects (target groundable / evidence present)
 * that continues on the first frame that has it — no "two identical frames" settle.
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
    fun `evidence missing on the fast frame is polled for and taken on the first frame that has it`() {
        // post-tap fast observation catches a loading screen; the next poll has the evidence
        val midTransition = layout(tab)
        val dev = FakeDevice(home, midTransition, webtoonHome)
        val c = fastController(dev)
        c.fullLayout()
        val r = Replayer(c).replayStep(tapStep)

        assertNull(r.broken, "evidence must be polled for: ${r.broken}")
        assertEquals(3, dev.observeCount) // initial + post-tap fast + one poll
    }

    @Test
    fun `a slow load that holds a stable loading screen is not given up on`() {
        // a settle-based recheck would stop at the second identical loading frame and fail;
        // polling for the evidence keeps going until it lands (within the poll budget)
        val loading = layout(tab, el("로딩중", id = 1, center = Point(1, 1)))
        val dev = FakeDevice(home, loading, loading, loading, webtoonHome)
        val c = fastController(dev)
        c.fullLayout()
        val r = Replayer(c).replayStep(tapStep)

        assertNull(r.broken, "must wait through a stable loading screen: ${r.broken}")
        assertEquals(5, dev.observeCount)
    }

    @Test
    fun `grounding failure on the fast frame waits for the target`() {
        // scenario start observes a mid-transition frame where the target is not yet present
        val midTransition = layout(el("로딩중", id = 5, center = Point(1, 1)))
        val dev = FakeDevice(midTransition, home, webtoonHome)
        val c = fastController(dev)
        c.fullLayout() // fast: sees the frame without the target
        val r = Replayer(c).replayStep(tapStep)

        assertNull(r.broken, "grounding must poll for the target: ${r.broken}")
        assertEquals(listOf("tap 360 1176"), dev.gestures)
        assertEquals(3, dev.observeCount) // fast(1) + poll(1) + post-tap fast(1)
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
        assertTrue(dev.observeCount >= 3, "must have polled before giving up (n=${dev.observeCount})")
        assertTrue(dev.gestures.isEmpty())
    }

}
