package dev.nate.uiagent.device

import dev.nate.uiagent.LayoutAdapter
import dev.nate.uiagent.LogicalLayout
import dev.nate.uiagent.ProcessFailure
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
    private val retryDelayMs: Long = 1000L,
    private val run: (List<String>) -> String = { runProcess(it) },
) : Device {

    /**
     * One `android layout` per observation. Since CLI 1.0.16406183 a failed dump exits
     * non-zero instead of printing nothing, so the retry loop handles both shapes:
     *  - NO_ROOT ("Could not obtain layout root", app still cold-starting): retry after a pause.
     *  - NO_IDLE ("Could not obtain idle state"): the CLI (≥ 1.0.16251017) waits up to 3s for a
     *    1s quiet window before dumping, and a screen that keeps producing layout events (video,
     *    spinner, carousel) never grants one. The harness has its own settle loop
     *    (DeviceController.stabilize) so the dump is simply re-requested with `--no-idle`.
     * After the retries a persistent failure propagates with the CLI's stderr — the step then
     * FAILs with the real reason instead of grounding against an empty screen.
     */
    override fun observe(): LogicalLayout {
        // `adb` honors ANDROID_SERIAL on its own, but the `android` CLI does not and errors
        // out when several devices are online — pass the serial through explicitly.
        val serial = System.getenv("ANDROID_SERIAL")
        val argv = listOf(androidBin, "layout") +
            (serial?.let { listOf("--device", it) } ?: emptyList())
        var failure: ProcessFailure? = null
        repeat(OBSERVE_RETRIES) { attempt ->
            val out = try {
                layoutDump(argv)
            } catch (ex: ProcessFailure) {
                failure = ex
                ""
            }
            if (out.isNotBlank()) return LayoutAdapter.adapt(out)
            if (attempt < OBSERVE_RETRIES - 1) Thread.sleep(retryDelayMs)
        }
        failure?.let { throw it }
        return LogicalLayout(emptyList())
    }

    private fun layoutDump(argv: List<String>): String {
        onCommand(argv)
        return try {
            run(argv)
        } catch (ex: ProcessFailure) {
            if (NO_IDLE_MARKER !in ex.stderr) throw ex
            val noIdle = argv + "--no-idle"
            onCommand(noIdle)
            run(noIdle)
        }
    }

    private companion object {
        const val OBSERVE_RETRIES = 5
        const val NO_IDLE_MARKER = "Could not obtain idle state"
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
