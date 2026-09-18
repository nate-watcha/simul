package dev.nate.uiagent.agent

import dev.nate.uiagent.LayoutAdapter
import dev.nate.uiagent.Trace
import dev.nate.uiagent.device.AdbDevice
import dev.nate.uiagent.device.Device
import dev.nate.uiagent.device.DeviceController
import dev.nate.uiagent.LogicalLayout
import dev.nate.uiagent.web.CdpClient
import dev.nate.uiagent.web.WebAwareDevice
import java.io.File
import kotlin.system.exitProcess

/**
 * Ad-hoc entry point: tool-calling agent (one-step [ScenarioSession]) with diff-based verification.
 *
 *   ./gradlew runAgent --args="\"웹툰 탭을 눌러\""
 *   ./gradlew runAgent --args="\"...\" --layout-file layout.json"   (offline: static layout, no-op gestures)
 *
 * Options:
 *   --url URL            llama-server base url (default http://100.99.171.25:8080)
 *   --max-iterations N   max LLM turns (default 12)
 *   --layout-file F      static layout instead of a live device; gestures are no-ops
 *   --app ID             app package id, used to pick the right WebView devtools socket
 *   --trace-dir D        write a JSONL trace into D (default: traces/)
 *   --dump-layout        print the model-facing layout and exit
 */

/** Static-layout device for offline flow validation: observes a file, ignores gestures. */
private class FileDevice(private val path: String) : Device {
    override fun observe(): LogicalLayout = LayoutAdapter.adapt(File(path).readText())
    override fun tap(x: Int, y: Int) = println("  [dry] tap $x $y")
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) = println("  [dry] swipe $x1,$y1 -> $x2,$y2")
    override fun inputText(text: String) = println("  [dry] text $text")
    override fun keyBack() = println("  [dry] back")
    override fun sleep(ms: Long) {}
}

fun main(argv: Array<String>) {
    var url = "http://100.99.171.25:8080"
    var maxIterations = 12
    var layoutFile: String? = null
    var appId: String? = null
    var traceDir: String? = "traces"
    var dumpLayout = false
    val positional = mutableListOf<String>()

    var i = 0
    while (i < argv.size) {
        when (val a = argv[i]) {
            "--url" -> url = argv[++i]
            "--max-iterations" -> maxIterations = argv[++i].toInt()
            "--layout-file" -> layoutFile = argv[++i]
            "--app" -> appId = argv[++i]
            "--trace-dir" -> traceDir = argv[++i]
            "--no-trace" -> traceDir = null
            "--dump-layout" -> dumpLayout = true
            else -> positional += a
        }
        i++
    }

    val trace = traceDir?.let { Trace.inDir(File(it)) } ?: Trace.DISABLED
    val cdp = CdpClient()
    Runtime.getRuntime().addShutdownHook(Thread { cdp.close() }) // main() exits via exitProcess
    val device: Device = layoutFile?.let { FileDevice(it) }
        ?: WebAwareDevice(
            AdbDevice(onCommand = { argv2 -> trace.event("exec") { it["argv"] = argv2.joinToString(" ") } }),
            cdp,
            appId,
        )
    val controller = DeviceController(device, trace)

    if (dumpLayout) {
        println(controller.fullLayout())
        exitProcess(0)
    }
    if (positional.isEmpty()) {
        System.err.println("error: missing COMMAND")
        exitProcess(2)
    }
    val command = positional.joinToString(" ")

    println("command : $command")
    println("url     : $url")
    println("device  : ${if (layoutFile != null) "file($layoutFile, dry gestures)" else "adb/emulator"}")
    println("─".repeat(60))

    trace.event("command") { it["text"] = command }
    val t0 = System.currentTimeMillis()
    val initialLayout = controller.fullLayout()
    val session = ScenarioSession(HttpChatClient(url), controller, maxIterations, log = { println("  $it") })

    // `COMMAND :: CRITERION` — same contract as scenario steps (criterion delivered post-action)
    val sep = command.indexOf(" :: ")
    val verdict = if (sep < 0) session.runStep(command, initialLayout)
    else session.runStep(
        command.take(sep).trim(), initialLayout,
        command.substring(sep + 4).trim().ifEmpty { null },
    )
    val elapsed = System.currentTimeMillis() - t0

    println("─".repeat(60))
    trace.event("verdict") {
        it["status"] = if (verdict.passed) "PASSED" else "FAILED"
        it["reason"] = verdict.reason
    }
    println("${if (verdict.passed) "PASSED" else "FAILED"}: ${verdict.reason}  (${elapsed}ms)")
    exitProcess(if (verdict.passed) 0 else 1)
}
