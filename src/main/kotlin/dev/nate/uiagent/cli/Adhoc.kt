package dev.nate.uiagent.cli

import dev.nate.uiagent.Defaults
import dev.nate.uiagent.LayoutAdapter
import dev.nate.uiagent.LogicalLayout
import dev.nate.uiagent.Trace
import dev.nate.uiagent.agent.ScenarioSession
import dev.nate.uiagent.agent.SimulLlm
import dev.nate.uiagent.device.Device
import dev.nate.uiagent.web.DeviceStack
import java.io.File
import kotlin.system.exitProcess

/**
 * `simul "<natural-language command>"` and the Gradle `runAgent` task: one step against the live
 * device, with diff-based verification and the same `COMMAND :: CRITERION` contract as scenario
 * steps. Works without `.simul/`; with one, config.yaml supplies the LLM endpoint and app id.
 *
 *   ./gradlew runAgent --args="\"웹툰 탭을 눌러\""
 *   ./gradlew runAgent --args="\"...\" --layout-file layout.json"   (offline: static layout, no-op gestures)
 *
 * Options:
 *   --url URL            OpenAI-compatible base url (default: config.yaml, else the dev box)
 *   --model NAME         model name sent in requests
 *   --api-key KEY        bearer token for hosted endpoints (default: config.yaml / $SIMUL_LLM_API_KEY)
 *   --max-iterations N   max LLM turns (default 12)
 *   --layout-file F      static layout instead of a live device; gestures are no-ops
 *   --app ID             app package id, used to pick the right WebView devtools socket
 *   --trace-dir D        write a JSONL trace into D (default: traces/); --no-trace disables it
 *   --dump-layout        print the model-facing layout and exit
 */
fun main(argv: Array<String>) {
    exitProcess(adhocCommand(argv, File(".").absoluteFile.parentFile))
}

/** The llama-server on the development box — what `runAgent` talks to outside a `.simul/` project. */
private const val DEV_BOX_URL = "http://100.99.171.25:8080"

/** Static-layout device for offline flow validation: observes a file, ignores gestures. */
private class FileDevice(private val path: String) : Device {
    override fun observe(): LogicalLayout = LayoutAdapter.adapt(File(path).readText())
    override fun tap(x: Int, y: Int) = println("  [dry] tap $x $y")
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) = println("  [dry] swipe $x1,$y1 -> $x2,$y2")
    override fun inputText(text: String) = println("  [dry] text $text")
    override fun keyBack() = println("  [dry] back")
    override fun sleep(ms: Long) {}
}

internal fun adhocCommand(argv: Array<String>, cwd: File): Int {
    val project = SimulProject.find(cwd)
    val llm = project?.config?.llm ?: LlmConfig.fromYaml(emptyMap()).copy(url = DEV_BOX_URL)
    var url = llm.url
    var model = llm.model
    var apiKey: String? = null
    var maxIterations = Defaults.MAX_TURNS
    var layoutFile: String? = null
    var appId: String? = project?.config?.app
    var traceDir: String? = "traces"
    var dumpLayout = false
    val positional = mutableListOf<String>()

    var i = 0
    while (i < argv.size) {
        when (val a = argv[i]) {
            "--url" -> url = argv.getOrNull(++i) ?: return err("--url needs a value")
            "--model" -> model = argv.getOrNull(++i) ?: return err("--model needs a value")
            "--api-key" -> apiKey = argv.getOrNull(++i) ?: return err("--api-key needs a value")
            "--max-iterations" -> maxIterations = argv.getOrNull(++i)?.toIntOrNull() ?: return err("--max-iterations needs a number")
            "--layout-file" -> layoutFile = argv.getOrNull(++i) ?: return err("--layout-file needs a path")
            "--app" -> appId = argv.getOrNull(++i) ?: return err("--app needs a value")
            "--trace-dir" -> traceDir = argv.getOrNull(++i) ?: return err("--trace-dir needs a path")
            "--no-trace" -> traceDir = null
            "--dump-layout" -> dumpLayout = true
            else -> positional += a
        }
        i++
    }

    val trace = traceDir?.let { Trace.inDir(File(it)) } ?: Trace.DISABLED
    DeviceStack(appId, trace, device = layoutFile?.let(::FileDevice)).use { stack ->
        val controller = stack.controller
        if (dumpLayout) {
            println(controller.fullLayout())
            return 0
        }
        if (positional.isEmpty()) return err("missing COMMAND")
        // the key is resolved only now: --dump-layout must work without one
        val key = apiKey ?: run { llm.keyProblem()?.let { return err(it) }; llm.apiKey }
        val spec = StepSpec.parse(positional.joinToString(" "))

        println("command : ${spec.command}${spec.criterion?.let { " :: $it" } ?: ""}")
        println("url     : $url  (model $model${if (key != null) ", api key set" else ""})")
        println("device  : ${if (layoutFile != null) "file($layoutFile, dry gestures)" else "adb/emulator"}")
        println("─".repeat(60))

        trace.event("command") { it["text"] = spec.command }
        val t0 = System.currentTimeMillis()
        val initialLayout = controller.fullLayout()
        val session = ScenarioSession(SimulLlm.executor(url, key), controller, maxIterations,
            log = { println("  $it") }, model = SimulLlm.model(model))
        val verdict = session.runStep(spec.command, initialLayout, spec.criterion)
        val elapsed = System.currentTimeMillis() - t0

        println("─".repeat(60))
        trace.event("verdict") {
            it["status"] = if (verdict.passed) "PASSED" else "FAILED"
            it["reason"] = verdict.reason
        }
        println("${if (verdict.passed) "PASSED" else "FAILED"}: ${verdict.reason}  (${elapsed}ms)")
        verdict.llm?.let { println(it.summary(totalMs = elapsed)) }
        return if (verdict.passed) 0 else 1
    }
}
