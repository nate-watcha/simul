package dev.nate.uiagent

import dev.nate.uiagent.device.Device
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

// ---------------------------------------------------------------- element fixtures

internal fun el(
    label: String? = null,
    resourceId: String? = null,
    interactions: List<String> = emptyList(),
    state: List<String> = emptyList(),
    center: Point = Point(100, 100),
    bounds: Bounds? = null,
    id: Int = 0,
    kind: String? = null,
    origin: Origin = Origin.NATIVE,
    parentId: Int? = null,
) = LogicalElement(id, label, resourceId, interactions, state, kind, center, bounds, origin, parentId)

/** A layout in pre-order: ids are reassigned to the element's index, as the real adapter guarantees. */
internal fun layout(vararg elements: LogicalElement) =
    LogicalLayout(elements.mapIndexed { i, e -> if (e.id == i) e else e.copy(id = i) })

// ---------------------------------------------------------------- devices

/** Scripted device: `observe()` pops a queue of layouts (the last one repeats forever); gestures are recorded. */
internal class FakeDevice(vararg initial: LogicalLayout) : Device {
    val queue = ArrayDeque(initial.toList())
    var last: LogicalLayout = queue.first()
    val gestures = mutableListOf<String>()
    var observeCount = 0

    override fun observe(): LogicalLayout {
        observeCount++
        if (queue.isNotEmpty()) last = queue.removeFirst()
        return last
    }

    override fun tap(x: Int, y: Int) { gestures += "tap $x $y" }
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) { gestures += "swipe" }
    override fun inputText(text: String) { gestures += "text $text" }
    override fun keyBack() { gestures += "back" }
    override fun sleep(ms: Long) {}
    override fun releaseAutomation() { gestures += "release" }
}

/** One screen that never changes unless the test swaps [layout]; gestures are recorded. */
internal class StaticDevice(var layout: LogicalLayout) : Device {
    val gestures = mutableListOf<String>()
    override fun observe(): LogicalLayout = layout
    override fun tap(x: Int, y: Int) { gestures += "tap $x $y" }
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) { gestures += "swipe" }
    override fun inputText(text: String) { gestures += "text $text" }
    override fun keyBack() { gestures += "back" }
    override fun sleep(ms: Long) {}
}

// ---------------------------------------------------------------- misc

internal fun tempDir(prefix: String = "simul-test"): File =
    File.createTempFile(prefix, "").let { it.delete(); it.mkdirs(); it }

internal fun captureStdout(block: () -> Unit): String {
    val out = ByteArrayOutputStream()
    val old = System.out
    System.setOut(PrintStream(out, true))
    try { block() } finally { System.setOut(old) }
    return out.toString()
}
