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

    /**
     * The same observation without the dump's own settle wait: `android layout` (≥ 1.0.16251017)
     * first waits up to 3s for a 1s quiet window, which a busy screen (animation, screenrecord)
     * rarely grants — every dump then costs the full 3s and often a `--no-idle` re-request on
     * top. Replay polls for the screen it expects anyway ([DeviceController.waitUntil]), so it
     * asks for the frame as it is. Default: the plain observation.
     */
    fun observeFast(): LogicalLayout = observe()

    fun tap(x: Int, y: Int)
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int)

    /**
     * Put [text] into the input field the caller just tapped, replacing what it held.
     * [resourceId] names that field when it has one; null means whichever element holds input
     * focus. Any Unicode — the field is set through accessibility, not a keyboard (see [AdbDevice]).
     */
    fun inputText(text: String, resourceId: String?)
    fun keyBack()
    fun sleep(ms: Long)

    /**
     * Disconnect whatever accessibility-automation client the observation channel left attached,
     * right before a tap that must look like a user's ([DeviceController] decides when). No-op
     * for devices that keep none.
     */
    fun releaseAutomation() {}
}

/** Live device via `android layout` / `android input text` + `adb shell input`. */
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
    override fun observe(): LogicalLayout = observe(noIdle = false)

    /** `android layout --no-idle` from the first attempt — replay's observation (see [Device.observeFast]). */
    override fun observeFast(): LogicalLayout = observe(noIdle = true)

    /**
     * `adb` honors ANDROID_SERIAL on its own, but the `android` CLI does not and errors out when
     * several devices are online — every `android` call gets the serial explicitly.
     */
    private fun androidArgs(command: String): List<String> =
        listOf(androidBin, command) + (System.getenv("ANDROID_SERIAL")?.let { listOf("--device", it) } ?: emptyList())

    private fun observe(noIdle: Boolean): LogicalLayout {
        val argv = androidArgs("layout") + (if (noIdle) listOf("--no-idle") else emptyList())
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
            if (NO_IDLE_MARKER !in ex.stderr || "--no-idle" in argv) throw ex
            val noIdle = argv + "--no-idle"
            onCommand(noIdle)
            run(noIdle)
        }
    }

    private companion object {
        const val OBSERVE_RETRIES = 5
        const val NO_IDLE_MARKER = "Could not obtain idle state"
        /** The on-device server `android layout` starts and leaves resident between dumps. */
        const val LAYOUT_SERVER = "com.android.cli.interact.instrumentation"
    }

    private fun shell(vararg args: String) {
        val argv = listOf(adbBin, "shell", *args)
        onCommand(argv)
        run(argv)
    }

    private fun input(vararg args: String) = shell("input", *args)

    override fun tap(x: Int, y: Int) = input("tap", "$x", "$y")

    /**
     * `android layout` runs through an instrumentation server that stays resident after the dump
     * (warm dump 1.0s; a dump that has to start it again 2.2s, measured on a Mac emulator). While
     * it is alive the system reports an accessibility automation client (`dumpsys accessibility`
     * → `Ui Automation[...]`, `accessibility_enabled` = 1), and Google Play's purchase sheet drops
     * the tap on its "Subscribe" button in that state — the same `input tap` lands once the server
     * is stopped (kloud billing-subscribe-declined, 2026-10-19: two runner taps ignored, manual
     * taps fine, confirmed by force-stopping the server between two taps on a fresh sheet).
     */
    override fun releaseAutomation() = shell("am", "force-stop", LAYOUT_SERVER)

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) =
        input("swipe", "$x1", "$y1", "$x2", "$y2", "$durationMs")

    /**
     * `android input text` (CLI ≥ 1.0.16625370): the resident layout server sets the field's text
     * through the accessibility ACTION_SET_TEXT, so any Unicode goes in as written — `adb shell
     * input text` only ever carried ASCII, and every Korean typing step stopped at the harness
     * guard. The target is the field DeviceController just tapped: its resource-id when it has
     * one (exact regex; `--match first` tolerates a duplicate id elsewhere on screen), otherwise
     * whatever holds input focus. No keyboard takes part — IME composition and autocomplete are
     * not exercised — but EditText's TextWatcher and Compose's onValueChange fire as for typed
     * text, so search-as-you-type screens react the same.
     */
    override fun inputText(text: String, resourceId: String?) {
        val selector = resourceId?.let { "id=/^${regexEscape(it)}$/" } ?: "focused=true"
        val argv = androidArgs("input") + listOf("text", "--match", "first", text, selector)
        onCommand(argv)
        run(argv)
    }

    /** Selector values wrapped in slashes are regexes; `/` itself is escaped as `\/`. */
    private fun regexEscape(s: String): String =
        s.replace(Regex("[\\\\.\\[\\]{}()*+?^\$|/]")) { "\\" + it.value }

    override fun keyBack() = input("keyevent", "KEYCODE_BACK")

    override fun sleep(ms: Long) = Thread.sleep(ms)
}
