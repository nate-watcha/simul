package dev.nate.uiagent.web

import dev.nate.uiagent.Defaults
import dev.nate.uiagent.Trace
import dev.nate.uiagent.device.ActionListener
import dev.nate.uiagent.device.AdbDevice
import dev.nate.uiagent.device.Device
import dev.nate.uiagent.device.DeviceController

/**
 * The live device as the harness sees it: `android layout` + `adb shell input` ([AdbDevice]),
 * WebView contents merged in over CDP ([WebAwareDevice]), and the grounding/diff layer on top
 * ([DeviceController]). One place to build it — the runner, the TUI workbench and the ad-hoc
 * command all need exactly this stack. [close] releases the adb port forwards CDP opened.
 */
class DeviceStack(
    app: String?,
    trace: Trace = Trace.DISABLED,
    scrollLimit: Int = Defaults.SCROLL_LIMIT,
    listener: ActionListener? = null,
    /** A substitute for the live device (offline layout file); the CDP channel is then unused. */
    device: Device? = null,
) : AutoCloseable {
    private val cdp = CdpClient()

    val controller: DeviceController = DeviceController(
        device = device ?: WebAwareDevice(
            AdbDevice(onCommand = { argv -> trace.event("exec") { it["argv"] = argv.joinToString(" ") } }),
            cdp,
            app,
        ),
        trace = trace,
        maxScrollRounds = scrollLimit,
        listener = listener,
    )

    override fun close() = cdp.close()
}
