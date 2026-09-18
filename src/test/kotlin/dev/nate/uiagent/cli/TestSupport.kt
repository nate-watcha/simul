package dev.nate.uiagent.cli

import dev.nate.uiagent.Bounds
import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.LogicalLayout
import dev.nate.uiagent.Point
import dev.nate.uiagent.device.Device
import java.io.File

// ---------------------------------------------------------------- element fixtures

internal fun el(
    label: String? = null,
    resourceId: String? = null,
    interactions: List<String> = emptyList(),
    state: List<String> = emptyList(),
    center: Point = Point(100, 100),
    bounds: Bounds? = null,
    id: Int = 0,
) = LogicalElement(id, label, resourceId, interactions, state, null, center, bounds)

internal fun layout(vararg elements: LogicalElement) = LogicalLayout(elements.toList())

/** Scripted device: observe() pops a queue (last repeats forever); gestures are recorded. */
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
}

// ---------------------------------------------------------------- runner fakes

internal class FakeOps(private val versionName: String? = "5.4.0") : DeviceOps {
    val setups = mutableListOf<Scenario.Setup>()
    var display: String? = null

    override fun setup(setup: Scenario.Setup, app: String?) { setups += setup }
    override fun screenshot(dest: File): Boolean = false
    override fun appVersionName(app: String?): String? = versionName
    override fun deviceName(): String? = "fake-device"
    override fun displayProfile(): String? = display
}

/** Step executor driven by a lambda (which may drive the controller to simulate agent actions). */
internal class ScriptedExecutor(private val behavior: (String) -> StepVerdict) : StepExecutor {
    var calls = 0
    val criteria = mutableListOf<String?>()

    override fun runStep(stepText: String, initialLayout: String?, criterion: String?): StepVerdict {
        calls++
        criteria += criterion
        return behavior(stepText)
    }
}

internal val neverCalledExecutor = ScriptedExecutor { throw AssertionError("LLM must not be called in pure replay") }
