package dev.nate.uiagent.device

import dev.nate.uiagent.ProcessFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `android layout` failure shapes of CLI ≥ 1.0.16406183 (non-zero exit + stderr diagnosis). */
class AdbDeviceTest {
    private val dump = """[{"class":"android.widget.Button","text":"확인","interactions":["CLICKABLE"],"bounds":"[0,0][10,10]","center":"[5,5]"}]"""

    private fun failure(argv: List<String>, stderr: String) = ProcessFailure(argv, 1, stderr)

    @Test
    fun `NO_IDLE re-requests the dump with --no-idle instead of waiting or failing`() {
        val calls = mutableListOf<List<String>>()
        val device = AdbDevice(onCommand = calls::add, retryDelayMs = 0) { argv ->
            if ("--no-idle" in argv) dump
            else throw failure(argv, "Could not obtain idle state\nThe following elements were producing layout events:\n  {...}\nRe-run layout with '--no-idle' to ignore layout events")
        }
        val layout = device.observe()
        assertEquals("확인", layout.elements.single().label)
        assertEquals(listOf(listOf("android", "layout"), listOf("android", "layout", "--no-idle")), calls)
    }

    @Test
    fun `releaseAutomation force-stops the layout server and a tap alone is just the touch`() {
        // Google Play drops its purchase-button tap while the resident `android layout` server
        // holds a UiAutomation connection (kloud billing-subscribe-declined, 2026-10-19).
        val calls = mutableListOf<List<String>>()
        val device = AdbDevice(onCommand = calls::add, retryDelayMs = 0) { "" }
        device.releaseAutomation()
        device.tap(360, 1168)
        assertEquals(
            listOf(
                listOf("adb", "shell", "am", "force-stop", "com.android.cli.interact.instrumentation"),
                listOf("adb", "shell", "input", "tap", "360", "1168"),
            ),
            calls,
        )
    }

    @Test
    fun `observeFast asks for --no-idle up front and does not re-request on NO_IDLE`() {
        val calls = mutableListOf<List<String>>()
        val device = AdbDevice(onCommand = calls::add, retryDelayMs = 0) { dump }
        assertEquals("확인", device.observeFast().elements.single().label)
        assertEquals(listOf(listOf("android", "layout", "--no-idle")), calls)
    }

    @Test
    fun `NO_ROOT during cold start is retried and the eventual dump is used`() {
        var n = 0
        val device = AdbDevice(retryDelayMs = 0) { argv ->
            if (++n < 3) throw failure(argv, "Could not obtain layout root") else dump
        }
        assertEquals("확인", device.observe().elements.single().label)
        assertEquals(3, n)
    }

    @Test
    fun `a persistent failure surfaces the CLI stderr instead of an empty screen`() {
        var n = 0
        val device = AdbDevice(retryDelayMs = 0) { argv -> n++; throw failure(argv, "Unrecognized response from instrumentation server") }
        val ex = assertFailsWith<ProcessFailure> { device.observe() }
        assertTrue("Unrecognized response" in ex.message!!)
        assertEquals(5, n, "all retries used")
    }

    @Test
    fun `blank output after every retry still yields an empty layout (pre-16406183 behaviour)`() {
        val device = AdbDevice(retryDelayMs = 0) { "" }
        assertTrue(device.observe().elements.isEmpty())
    }
}
