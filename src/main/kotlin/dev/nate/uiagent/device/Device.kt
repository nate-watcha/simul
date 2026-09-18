package dev.nate.uiagent.device

import dev.nate.uiagent.LayoutAdapter
import dev.nate.uiagent.LogicalLayout
import dev.nate.uiagent.runProcess

/**
 * Low-level device access: raw observations and raw gestures. DeviceController builds the
 * grounded, diff-producing tool semantics on top; tests substitute a scripted fake.
 */
interface Device {
    /** Full logical layout (adapter-merged) of the current screen. */
    fun observe(): LogicalLayout

    fun tap(x: Int, y: Int)
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int)
    fun inputText(text: String)
    fun keyBack()
    fun sleep(ms: Long)
}

/** Live device via `android layout` + `adb shell input`. */
class AdbDevice(
    private val adbBin: String = "adb",
    private val androidBin: String = "android",
    private val onCommand: (List<String>) -> Unit = {},
) : Device {

    override fun observe(): LogicalLayout {
        // During app cold start `android layout` can transiently emit nothing (no window to
        // dump yet) — retry briefly instead of handing garbage to the parser.
        // `adb` honors ANDROID_SERIAL on its own, but the `android` CLI does not and errors
        // out when several devices are online — pass the serial through explicitly.
        val serial = System.getenv("ANDROID_SERIAL")
        val argv = listOf(androidBin, "layout") +
            (serial?.let { listOf("--device", it) } ?: emptyList())
        repeat(OBSERVE_RETRIES) {
            onCommand(argv)
            val out = runProcess(argv)
            if (out.isNotBlank()) return LayoutAdapter.adapt(out)
            Thread.sleep(OBSERVE_RETRY_DELAY_MS)
        }
        return LogicalLayout(emptyList())
    }

    private companion object {
        const val OBSERVE_RETRIES = 5
        const val OBSERVE_RETRY_DELAY_MS = 1000L
    }

    private fun input(vararg args: String) {
        val argv = listOf(adbBin, "shell", "input", *args)
        onCommand(argv)
        runProcess(argv)
    }

    override fun tap(x: Int, y: Int) = input("tap", "$x", "$y")

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) =
        input("swipe", "$x1", "$y1", "$x2", "$y2", "$durationMs")

    override fun inputText(text: String) = input("text", text.replace(" ", "%s"))

    override fun keyBack() = input("keyevent", "KEYCODE_BACK")

    override fun sleep(ms: Long) = Thread.sleep(ms)
}
