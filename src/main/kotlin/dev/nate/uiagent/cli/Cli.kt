package dev.nate.uiagent.cli

import dev.nate.uiagent.device.AdbDevice
import dev.nate.uiagent.device.DeviceController
import dev.nate.uiagent.Trace
import dev.nate.uiagent.web.CdpClient
import dev.nate.uiagent.web.WebAwareDevice
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.system.exitProcess

/**
 * The `simul` CLI (plan §1):
 *
 *   simul run <path>... | --tag <tag> | --all   [--mode replay|llm|auto] [--dry-run]
 *   simul list
 *   simul "<natural-language command>"          (ad-hoc mode; works without .simul/)
 *
 * CI invariant: CI only replays. The default mode is auto — replay when a trace exists,
 * SKIP with a warning when it doesn't; the LLM interprets full scenarios only under an
 * explicit `--mode llm`.
 */
fun main(argv: Array<String>) {
    exitProcess(cliMain(argv, File(".").absoluteFile.parentFile))
}

fun cliMain(argv: Array<String>, cwd: File): Int = when (argv.firstOrNull()) {
    null -> {
        // bare `simul` on a TTY inside a project = interactive mode; otherwise usage
        val project = SimulProject.find(cwd)
        if (project != null && System.console() != null) Tui(project, cwd).run()
        else {
            printUsage()
            2
        }
    }
    "--help", "-h" -> {
        printUsage()
        0
    }
    "run" -> runCommand(argv.drop(1), cwd)
    "report" -> reportCommand(argv.drop(1), cwd)
    "list" -> listCommand(cwd)
    "status" -> statusCommand(cwd)
    "init" -> initCommand(argv.drop(1), cwd)
    "state" -> stateCommand(argv.drop(1), cwd)
    else -> adhocCommand(argv, cwd)
}

private fun statusCommand(cwd: File): Int {
    val project = SimulProject.find(cwd)
        ?: return err("no .simul/ directory found from $cwd upwards")
    project.versionWarning()?.let { println(it) }
    println(renderStatus(collectStatus(project), color = System.console() != null))
    return 0
}

private fun printUsage() {
    println(
        """
        simul $AGENT_VERSION — natural-language UI test runner

        usage:
          simul                               interactive mode (full-screen, TTY + .simul/ 필요)
          simul init [--app <package>] [--ci github]   set up .simul/ + Claude skill (+ nightly workflow)
          simul run <scenario.md>... [--mode replay|llm|auto] [--dry-run] [--url U]
          simul run --tag <tag> | --state <name> | --all [same options]
                    [--summary <file.json> [--label <phase>]]   batch summary for `simul report`
          simul report <summary.json>... [--format md|slack|junit] [--title T]
                    [--link Name=URL]... [--out FILE] [--check]  join phases → CI/Slack report
          simul status                        scenarios × trace × last run × states, at a glance
          simul list
          simul state save <name>             snapshot current app data → .simul/states/<name>.tar
          simul "<natural-language command>" [ad-hoc options]

        modes:
          auto (default)  replay if a trace exists, otherwise SKIP with a warning
          replay          play the committed trace deterministically; broken steps FAIL (no LLM)
          llm             interpret every step from scratch and (re)record the trace

        nightly pipeline (see `simul init --ci github`):
          simul run --all --mode replay --summary r/baseline.json   # committed traces
          simul run --all --mode llm    --summary r/record.json     # re-record every scenario
          simul run --all --mode replay --summary r/verify.json     # replay the fresh traces
          simul report r/baseline.json r/record.json r/verify.json --format md --check
        """.trimIndent()
    )
}

// -------------------------------------------------------------------------- run

internal fun runCommand(args: List<String>, cwd: File): Int {
    var mode = RunMode.AUTO
    var dryRun = false
    var all = false
    var urlOverride: String? = null
    var stateFilter: String? = null
    var summaryPath: String? = null
    var label: String? = null
    val tags = mutableListOf<String>()
    val paths = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--mode" -> mode = when (args.getOrNull(++i)) {
                "replay" -> RunMode.REPLAY
                "llm" -> RunMode.LLM
                "auto" -> RunMode.AUTO
                else -> return err("invalid --mode (use replay|llm|auto)")
            }
            "--dry-run" -> dryRun = true
            "--all" -> all = true
            "--tag" -> tags += args.getOrNull(++i) ?: return err("--tag needs a value")
            "--state" -> stateFilter = args.getOrNull(++i) ?: return err("--state needs a value")
            "--url" -> urlOverride = args.getOrNull(++i) ?: return err("--url needs a value")
            "--summary" -> summaryPath = args.getOrNull(++i) ?: return err("--summary needs a file path")
            "--label" -> label = args.getOrNull(++i) ?: return err("--label needs a value")
            else -> if (a.startsWith("--")) return err("unknown option $a") else paths += a
        }
        i++
    }

    val project = SimulProject.find(cwd)
        ?: return err("no .simul/ directory found from $cwd upwards — run inside an app repo with the .simul convention")
    project.versionWarning()?.let { println(it) }

    val files: List<File> = when {
        paths.isNotEmpty() -> paths.map { p ->
            val direct = File(cwd, p).takeIf { it.isFile } ?: File(p).takeIf { it.isFile && it.isAbsolute }
            direct ?: File(project.scenariosDir, p).takeIf { it.isFile }
            ?: return err("scenario not found: $p")
        }
        all || tags.isNotEmpty() || stateFilter != null -> project.scenarios().filter { md ->
            val sc = ScenarioMd.parse(md)
            (tags.isEmpty() || sc.tags.any { it in tags }) &&
                (stateFilter == null || sc.precondition == stateFilter)
        }
        else -> return err("nothing to run: give a scenario path, --tag <tag>, or --all")
    }
    if (files.isEmpty()) return err("no scenarios matched")

    val url = urlOverride ?: project.config.llmUrl
    var anyFailed = false
    var anyRan = false
    val outcomes = mutableListOf<ScenarioOutcome>()
    val batchStart = System.currentTimeMillis()
    val startedAt = RunSummaryJson.stamp()

    val ops = AdbOps(statesDir = project.statesDir)
    // Force the configured emulator display for the whole run so traces recorded on one
    // device replay on any other. Never touch a physical device's screen, and put back
    // whatever was there before (a developer may have their own wm override active).
    var displayBefore: String? = null
    val display = project.config.display
    if (!dryRun && display != null) {
        val current = ops.displayProfile()
        when {
            !ops.isEmulator() ->
                println("warning: display profile $display ignored — target is not an emulator")
            current == display.toString() -> {} // already there, nothing to restore
            else -> {
                ops.setDisplay(display)
                displayBefore = current ?: "unknown"
                println("display: forced to $display (${display.dpWidth}dp wide) — restored on exit")
            }
        }
    }

    try {
        for (md in files) {
            val scenario = ScenarioMd.parse(md)
            val traceFile = project.traceFileFor(md)
            val trace = traceFile.takeIf { it.isFile }?.let { runCatching { TraceJson.read(it) }.getOrNull() }

            if (dryRun) {
                val planned = if (mode == RunMode.AUTO) (if (trace != null) "replay" else "skip (no trace)") else mode.name.lowercase()
                println("${scenario.name} (${md.relativeToOrSelf(project.scenariosDir)})")
                println("  mode: $planned  trace: ${traceStatus(md, traceFile)}  tags: ${scenario.tags}")
                scenario.steps.forEachIndexed { idx, s -> println("  ${idx + 1}. $s") }
                continue
            }

            val effective = when {
                mode != RunMode.AUTO -> mode
                trace != null -> RunMode.REPLAY
                else -> {
                    println("SKIPPED ${scenario.name}: no trace — authoring incomplete (record with --mode llm)")
                    outcomes += outcomeWithout(project, md, scenario, "SKIPPED", "no trace")
                    continue
                }
            }
            if (effective == RunMode.REPLAY && trace == null) {
                println("SKIPPED ${scenario.name}: no trace to replay (record with --mode llm)")
                outcomes += outcomeWithout(project, md, scenario, "SKIPPED", "no trace")
                continue
            }

            anyRan = true
            if (scenario.precondition == null) {
                println("note: ${scenario.name} declares no appState/clearData — runs against the device's current state")
            }
            // A harness/environment crash (OOM, classpath swapped under a live JVM, adb
            // vanishing) must not kill the rest of the batch — record it and move on.
            val result = try {
                executeScenario(project, scenario, trace, effective, url, traceFile, ops)
            } catch (t: Throwable) {
                val log = writeCrashLog(project.reportsDir, scenario.name, t)
                println("CRASH ${scenario.name}: ${t.javaClass.simpleName}: ${t.message ?: ""}")
                println("  stack trace → ${log.relativeTo(project.appRoot)}")
                anyFailed = true
                outcomes += outcomeWithout(project, md, scenario, "CRASH",
                    "${t.javaClass.simpleName}: ${t.message ?: ""}".trim(), crashLog = log)
                continue
            }
            if (!result.passed) anyFailed = true
            outcomes += outcomeOf(project, md, scenario, result)
        }
    } finally {
        displayBefore?.let { before ->
            val prior = DisplayProfile.parse(before)
            if (prior != null) ops.setDisplay(prior) else ops.resetDisplay()
            println("display: restored to ${if (prior != null) before else "device default"}")
        }
    }

    if (!anyRan && !dryRun) println("warning: no scenario was executed (all skipped)")
    if (summaryPath != null && !dryRun) {
        val file = File(summaryPath).takeIf { it.isAbsolute } ?: File(cwd, summaryPath)
        val summary = RunSummary(
            label = label ?: mode.name.lowercase(),
            mode = mode.name.lowercase(),
            startedAt = startedAt,
            durationMs = System.currentTimeMillis() - batchStart,
            agentVersion = AGENT_VERSION,
            appVersionName = ops.appVersionName(project.config.app),
            device = ops.deviceName(),
            scenarios = outcomes,
        )
        RunSummaryJson.write(summary, file)
        val shown = file.relativeToOrSelf(cwd).path.let { if (it.startsWith("..")) file.absolutePath else it }
        println("summary: $shown (${outcomes.count { it.passed }}/${outcomes.size} passed)")
    }
    return if (anyFailed) 1 else 0
}

internal fun executeScenario(
    project: SimulProject,
    scenario: Scenario,
    trace: TraceFile?,
    mode: RunMode,
    url: String,
    traceFile: File,
    ops: DeviceOps,
    log: (String) -> Unit = ::println,
): ScenarioRunResult {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    val reportDir = File(project.reportsDir, "${scenario.name}-$stamp")
    reportDir.mkdirs()

    val jsonl = Trace(File(reportDir, "agent.jsonl"))
    val listener = SwitchableListener()
    val adb = AdbDevice(onCommand = { argv -> jsonl.event("exec") { it["argv"] = argv.joinToString(" ") } })
    val cdp = CdpClient()
    val controller = DeviceController(
        device = WebAwareDevice(adb, cdp, project.config.app),
        trace = jsonl,
        maxScrollRounds = project.config.scrollLimit,
        listener = listener,
    )

    // turn lines nest under the runner's step line in the live log
    val llm = SessionStepExecutor(controller, url, project.config.maxTurns, log = { log("    $it") })
    // steps are logged live by the runner — a scenario can run for minutes
    val runner = ScenarioRunner(controller, listener, ops, llm, reportDir, project.config.app, log = log)

    log("── ${scenario.name} [${mode.name.lowercase()}] ${"─".repeat(30)}")
    val result = try {
        runner.run(scenario, trace, mode)
    } finally {
        cdp.close() // release the adb port forwards this run created
    }

    log("${result.status}: ${scenario.name}  (${result.durationMs}ms, report: ${reportDir.path})")

    result.updatedTrace?.let { updated ->
        TraceJson.write(updated, traceFile)
        if (mode == RunMode.REPLAY) {
            // coordinate-fallback rewrite: leave a copy beside the report too; never auto-commit
            TraceJson.write(updated, File(reportDir, "trace.updated.json"))
            log("  trace updated (lastCoords) — review the diff of ${traceFile.path}")
        } else {
            log("  trace recorded: ${traceFile.path}")
        }
    }
    return result.copy(reportDir = reportDir)
}

// -------------------------------------------------------------------------- init

/**
 * Scaffold the `.simul/` convention in a target app repo: config template, scenarios dir,
 * and a Claude Code skill (SKILL.md + the scenario-authoring reference as
 * `references/scenarios.md`) so agent sessions in that repo write scenarios by the book.
 * Never overwrites existing files — rerunning after a simul upgrade refreshes only what is
 * missing (delete a doc to refresh it).
 */
internal fun initCommand(args: List<String>, cwd: File): Int {
    var app: String? = null
    var ci: String? = null
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--app" -> app = args.getOrNull(++i) ?: return err("--app needs a value")
            "--ci" -> ci = args.getOrNull(++i)?.takeIf { it == "github" } ?: return err("--ci needs a provider (github)")
            else -> return err("unknown option $a")
        }
        i++
    }

    fun resource(name: String): String? =
        object {}.javaClass.getResourceAsStream("/simul/$name")?.bufferedReader()?.readText()

    val scenarios = resource("scenarios.md") ?: return err("packaging error: /simul/scenarios.md missing")
    val skill = resource("SKILL.md") ?: return err("packaging error: /simul/SKILL.md missing")

    val config = """
        agentVersion: ">=$AGENT_VERSION"
        app: ${app ?: "your.app.package   # TODO: set the application id under test"}
        llm:
          url: http://localhost:8080   # llama-server (OpenAI-compatible)
        defaults:
          maxTurns: 12
          scrollLimit: 3
        display:
          sizeClass: small   # small=720x1280@320(360dp) | medium(640dp) | large(1066dp)
    """.trimIndent() + "\n"

    val files = listOfNotNull(
        File(cwd, ".simul/config.yaml") to config,
        File(cwd, ".claude/skills/simul-scenarios/SKILL.md") to skill,
        File(cwd, ".claude/skills/simul-scenarios/references/scenarios.md") to scenarios,
        if (ci == "github") {
            val wf = resource("nightly.yml") ?: return err("packaging error: /simul/nightly.yml missing")
            File(cwd, ".github/workflows/simul-nightly.yml") to wf
        } else null,
    )
    File(cwd, ".simul/scenarios").mkdirs()
    for ((file, content) in files) {
        if (file.exists()) {
            println("kept    ${file.relativeTo(cwd)}")
        } else {
            file.parentFile?.mkdirs()
            file.writeText(content)
            println("created ${file.relativeTo(cwd)}")
        }
    }
    val legacyReadme = File(cwd, ".simul/README.md")
    if (legacyReadme.exists()) {
        println("note    .simul/README.md is superseded by .claude/skills/simul-scenarios/references/scenarios.md — delete it to avoid stale guidance")
    }
    println(
        """

        next steps:
          1. .simul/config.yaml 의 app / llm.url 확인
          2. .claude/skills/simul-scenarios/references/scenarios.md 읽고 첫 시나리오 작성 → .simul/scenarios/<그룹>/<이름>.md
          3. simul run <그룹>/<이름>.md --mode llm   (녹화 후 trace를 md와 함께 커밋)
        """.trimIndent()
    )
    if (ci == "github") {
        println(
            """
              4. .github/workflows/simul-nightly.yml 의 TODO(빌드/설치 커맨드, AVD 이름)를 채우고
                 self-hosted 러너(라벨 self-hosted+simul, simul·adb·emulator·llama-server 상주)를 붙인다.
                 Slack 알림은 레포 secret SLACK_WEBHOOK_URL (incoming webhook) 하나면 된다.
            """.trimIndent()
        )
    }
    return 0
}

// -------------------------------------------------------------------------- state

/**
 * `simul state save <name>` — snapshot the app's current data (login/profile/flags) so
 * scenarios can restore it via `setup.appState: <name>` instead of logging in through the UI.
 */
private fun stateCommand(args: List<String>, cwd: File): Int {
    if (args.getOrNull(0) != "save" || args.size != 2) return err("usage: simul state save <name>")
    val name = args[1]
    if (!name.matches(Regex("[A-Za-z0-9._-]+"))) return err("state name must be [A-Za-z0-9._-]+")
    val project = SimulProject.find(cwd) ?: return err("no .simul/ directory found from $cwd upwards")
    val app = project.config.app ?: return err("config.yaml needs `app:` to save app state")
    val dest = File(project.statesDir, "$name.tar")
    print("saving ${app} data → ${dest.relativeTo(project.appRoot)} … ")
    return if (AdbOps().saveState(app, dest)) {
        println("done (${dest.length() / 1024}KB)")
        println("use it in a scenario:  setup:\n                         appState: $name\n                         launch: true")
        0
    } else 1
}

// -------------------------------------------------------------------------- list

private fun listCommand(cwd: File): Int {
    val project = SimulProject.find(cwd)
        ?: return err("no .simul/ directory found from $cwd upwards")
    project.versionWarning()?.let { println(it) }
    val files = project.scenarios()
    if (files.isEmpty()) {
        println("no scenarios under ${project.scenariosDir.path}")
        return 0
    }
    for (md in files) {
        val s = ScenarioMd.parse(md)
        val tags = if (s.tags.isEmpty()) "" else "  tags=${s.tags.joinToString(",")}"
        println("${md.relativeTo(project.scenariosDir).path}  steps=${s.steps.size}  trace=${traceStatus(md, project.traceFileFor(md))}$tags")
    }
    return 0
}

private fun traceStatus(md: File, traceFile: File): String = when {
    !traceFile.isFile -> "none"
    traceFile.lastModified() < md.lastModified() -> "stale"
    else -> "ok"
}

// -------------------------------------------------------------------------- ad-hoc

/** `simul "<command>"` — the single-command executor, with the project's LLM url as default. */
private fun adhocCommand(argv: Array<String>, cwd: File): Int {
    val args = argv.toMutableList()
    val project = SimulProject.find(cwd)
    if ("--url" !in args) {
        args += listOf("--url", project?.config?.llmUrl ?: SimulConfig.DEFAULT_URL)
    }
    if ("--app" !in args) {
        project?.config?.app?.let { args += listOf("--app", it) }
    }
    dev.nate.uiagent.agent.main(args.toTypedArray()) // exits the process itself
    return 0
}

private fun err(msg: String): Int {
    System.err.println("error: $msg")
    return 2
}
