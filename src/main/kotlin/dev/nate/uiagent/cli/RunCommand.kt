package dev.nate.uiagent.cli

import dev.nate.uiagent.Trace
import dev.nate.uiagent.device.AdbOps
import dev.nate.uiagent.device.DeviceOps
import dev.nate.uiagent.web.DeviceStack
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * `simul run <scenario.md>... | --tag <tag> | --state <name> | --all [--mode replay|llm|auto]
 *            [--dry-run] [--url U] [--gif | --video] [--summary <file.json> [--label <phase>]]`
 *
 * CI invariant: CI only replays. The default mode is auto — replay when a trace exists, SKIP with
 * a warning when it doesn't; the LLM interprets full scenarios only under an explicit `--mode llm`.
 */

internal fun runCommand(args: List<String>, cwd: File): Int {
    var mode = RunMode.AUTO
    var dryRun = false
    var all = false
    var urlOverride: String? = null
    var stateFilter: String? = null
    var summaryPath: String? = null
    var label: String? = null
    var video = VideoOutput.NONE
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
            "--gif" -> video = VideoOutput.GIF
            "--video" -> video = VideoOutput.MP4
            else -> if (a.startsWith("--")) return err("unknown option $a") else paths += a
        }
        i++
    }

    val project = SimulProject.find(cwd)
        ?: return err("no .simul/ directory found from $cwd upwards — run inside an app repo with the .simul convention")
    project.versionWarning()?.let { println(it) }

    // A `states:` scenario is several runs — one variant per state, each with its own trace.
    val targets: List<Pair<File, Scenario>> = when {
        paths.isNotEmpty() -> paths.flatMap { p ->
            val md = resolvePath(cwd, p).takeIf { it.isFile } ?: File(project.scenariosDir, p).takeIf { it.isFile }
                ?: return err("scenario not found: $p")
            val variants = try { ScenarioMd.parse(md).variants() } catch (e: IllegalArgumentException) {
                return err("${md.relativeToOrSelf(cwd)}: ${e.message}")
            }
            variants.filter { stateFilter == null || it.precondition == stateFilter }.map { md to it }
        }
        all || tags.isNotEmpty() || stateFilter != null -> {
            var broken = false
            val found = project.scenarioVariants { md, why ->
                System.err.println("error: ${md.relativeTo(project.scenariosDir)}: $why"); broken = true
            }
            if (broken) return 2
            found.filter { (_, sc) ->
                (tags.isEmpty() || sc.tags.any { it in tags }) &&
                    (stateFilter == null || sc.precondition == stateFilter)
            }
        }
        else -> return err("nothing to run: give a scenario path, --tag <tag>, or --all")
    }
    if (targets.isEmpty()) return err("no scenarios matched")

    val llm = project.config.llm.let { if (urlOverride != null) it.copy(url = urlOverride) else it }
    // The key is only needed when the LLM actually runs; replay must work without the secret.
    if (mode != RunMode.REPLAY && !dryRun) llm.keyProblem()?.let { return err(it) }
    var anyFailed = false
    var anyRan = false
    val outcomes = mutableListOf<ScenarioOutcome>()
    val batchStart = System.currentTimeMillis()
    val startedAt = RunSummaryJson.stamp()

    val ops = AdbOps(statesDir = project.statesDir)
    BatchSession(project, ops, ::println).use { batch ->
        if (!dryRun) batch.pin()
        for ((md, scenario) in targets) {
            if (dryRun) {
                val trace = project.traceFileFor(md, scenario)
                val planned = if (mode == RunMode.AUTO) (if (trace.isFile) "replay" else "skip (no trace)") else mode.name.lowercase()
                println("${scenario.name} (${scenarioPath(project, md, scenario)})")
                println("  mode: $planned  trace: ${TraceState.of(md, trace).name.lowercase()}  tags: ${scenario.tags}" +
                    (scenario.state?.let { "  state: $it" } ?: ""))
                scenario.steps.forEachIndexed { idx, s -> println("  ${idx + 1}. $s") }
                continue
            }
            val r = batch.run(md, scenario, mode, llm, video)
            outcomes += r.outcome
            if (r.outcome.status != "SKIPPED") anyRan = true
            if (r.outcome.status == "FAILED" || r.outcome.status == "CRASH") anyFailed = true
        }
    }

    if (!anyRan && !dryRun) println("warning: no scenario was executed (all skipped)")
    if (summaryPath != null && !dryRun) {
        val file = resolvePath(cwd, summaryPath)
        val summary = RunSummary(
            label = label ?: mode.name.lowercase(),
            mode = mode.name.lowercase(),
            startedAt = startedAt,
            durationMs = System.currentTimeMillis() - batchStart,
            agentVersion = AGENT_VERSION,
            appVersionName = ops.appVersionName(project.config.app),
            device = ops.deviceName(),
            scenarios = outcomes,
            androidCli = ops.androidCliVersion(),
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
    llm: LlmConfig,
    traceFile: File,
    ops: DeviceOps,
    log: (String) -> Unit = ::println,
    video: VideoOutput = VideoOutput.NONE,
): ScenarioRunResult {
    val reportDir = ReportDirs.create(project.reportsDir, scenario.name)

    val jsonl = Trace(File(reportDir, "agent.jsonl"))
    val listener = SwitchableListener()
    var media: List<File> = emptyList()
    var recorderProblem: String? = null
    val result = DeviceStack(project.config.app, jsonl, project.config.scrollLimit, listener).use { stack ->
        // turn lines nest under the runner's step line in the live log
        // replay never builds a client — the key stays optional for CI that only replays
        val executor = SessionStepExecutor(stack.controller, { llm.client() }, llm.llmModel(), project.config.maxTurns,
            log = { log("    $it") })
        // steps are logged live by the runner — a scenario can run for minutes
        val runner = ScenarioRunner(stack.controller, listener, ops, executor, reportDir, project.config.app, log = log)

        log("── ${scenario.name} [${mode.name.lowercase()}] ${"─".repeat(30)}")
        // The screen recording spans setup through the last step — the whole journey, as a
        // teammate would watch it. It stops in `finally`: a crashed run must not leave
        // screenrecord running on the device for the rest of its time limit.
        val recorder = if (video != VideoOutput.NONE) ScreenRecorder().also { it.start() } else null
        try {
            runner.run(scenario, trace, mode)
        } finally {
            recorder?.let { media = it.stop(File(reportDir, "run.mp4")); recorderProblem = it.problem }
        }
    }

    log("${result.status}: ${scenario.name}  (${result.durationMs}ms, report: ${reportDir.path})")
    // what the recording cost: model time vs. the scenario's wall time, and the tokens it took
    result.llm?.let { log("  ${it.summary(totalMs = result.durationMs)}") }

    if (video != VideoOutput.NONE) {
        fun rel(f: File) = f.relativeToOrSelf(project.appRoot).path
        recorderProblem?.let { log("  video: $it") }
        media.forEach { log("  video: ${rel(it)}") }
        if (video == VideoOutput.GIF && media.size == 1) {
            // --gif wants the gif only: the mp4 is the intermediate and goes, so an artifact of the
            // report dir does not carry both (--video is the way to keep the raw recording)
            val gif = File(reportDir, "run.gif")
            val failure = Gif.convert(media[0], gif)
            if (failure == null) { media[0].delete(); media = listOf(gif); log("  gif: ${rel(gif)}") }
            else log("  gif: $failure")
        }
    }

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
    return result.copy(reportDir = reportDir, media = media)
}


/** Where one run's `report.json` / screenshots / agent.jsonl go: `<reports>/<scenario>-yyyyMMdd-HHmmss`. */
object ReportDirs {
    private const val STAMP = "yyyyMMdd-HHmmss"

    fun create(reportsDir: File, scenario: String): File =
        File(reportsDir, "$scenario-${SimpleDateFormat(STAMP).format(Date())}").also { it.mkdirs() }

    /** The newest report dir of [scenario] — exact-stamp match, so a scenario whose name prefixes another's never steals its reports. */
    fun latest(reportsDir: File, scenario: String): File? {
        val stamp = Regex("^${Regex.escape(scenario)}-\\d{8}-\\d{6}$")
        return reportsDir.listFiles { f -> f.isDirectory && stamp.matches(f.name) }?.maxByOrNull { it.name }
    }
}

/** Persist a harness crash so "it died" is never the only record. Returns the log file. */
fun writeCrashLog(reportsDir: File, name: String, t: Throwable): File {
    reportsDir.mkdirs()
    val file = File(reportsDir, "$name-crash-${System.currentTimeMillis()}.log")
    file.writeText(buildString {
        appendLine("crash while running '$name' at ${java.time.ZonedDateTime.now()}")
        appendLine(t.stackTraceToString())
    })
    return file
}
