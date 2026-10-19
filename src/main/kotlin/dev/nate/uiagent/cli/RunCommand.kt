package dev.nate.uiagent.cli

import dev.nate.uiagent.Trace
import dev.nate.uiagent.device.AdbOps
import dev.nate.uiagent.device.DeviceOps
import dev.nate.uiagent.runProcess
import dev.nate.uiagent.web.DeviceStack
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * `simul run <scenario.md>... | --tag <tag> | --state <name> | --all [--mode replay|llm|auto]
 *            [--dry-run] [--url U] [--video] [--runs <dir>] [--summary <file.json> [--label <phase>]]`
 *
 * CI invariant: CI only replays. The default mode is auto — replay when a trace exists, SKIP with
 * a warning when it doesn't; the LLM interprets full scenarios only under an explicit `--mode llm`.
 *
 * `--runs <dir>` lays the run out the way the report reads it (README "나이틀리 운영"): one
 * directory per scenario and state, `<dir>/<feature>/<name>.<state>/`, each with its
 * result.json, steps/, layout/, logcat.txt and replay.mp4, plus `<dir>/run.json` for the batch and
 * the HTML report of the run: `<dir>/index.html` + a page per scenario ([SitePages]).
 * Without it every run gets a stamped directory under `.simul/reports/` as before.
 */

internal fun runCommand(args: List<String>, cwd: File): Int {
    var mode = RunMode.AUTO
    var dryRun = false
    var all = false
    var urlOverride: String? = null
    var stateFilter: String? = null
    var summaryPath: String? = null
    var runsPath: String? = null
    var label: String? = null
    var video = false
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
            "--runs" -> runsPath = args.getOrNull(++i) ?: return err("--runs needs a directory")
            "--label" -> label = args.getOrNull(++i) ?: return err("--label needs a value")
            "--video" -> video = true
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
    val runsDir = runsPath?.let { resolvePath(cwd, it).absoluteFile }
    var anyFailed = false
    var anyRan = false
    val outcomes = mutableListOf<ScenarioOutcome>()
    val batchStart = System.currentTimeMillis()
    val startedAt = RunSummaryJson.stamp(batchStart)

    val ops = AdbOps(statesDir = project.statesDir)
    BatchSession(project, ops, ::println, runsDir).use { batch ->
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
    if (!dryRun && (summaryPath != null || runsDir != null)) {
        val ended = System.currentTimeMillis()
        val summary = RunSummary(
            label = label ?: mode.name.lowercase(),
            mode = mode.name.lowercase(),
            startedAt = startedAt,
            durationMs = ended - batchStart,
            agentVersion = AGENT_VERSION,
            appVersionName = ops.appVersionName(project.config.app),
            device = ops.deviceName(),
            scenarios = outcomes,
            androidCli = ops.androidCliVersion(),
            app = project.config.app,
            endedAt = RunSummaryJson.stamp(ended),
        )
        val passedLine = "(${outcomes.count { it.passed }}/${outcomes.size} passed)"
        fun shown(f: File) = f.relativeToOrSelf(cwd).path.let { if (it.startsWith("..")) f.absolutePath else it }
        summaryPath?.let { p ->
            val file = resolvePath(cwd, p)
            RunSummaryJson.write(summary, file)
            println("summary: ${shown(file)} $passedLine")
        }
        runsDir?.let { dir ->
            RunSummaryJson.write(summary, File(dir, "run.json"))
            println("runs: ${shown(dir)} $passedLine")
            runCatching { SitePages.build(dir, dir) }
                .onSuccess { println("report: ${shown(it.index)} — ${it.counts()}") }
                .onFailure { println("warning: report not written: ${it.message}") }
        }
    }
    return if (anyFailed) 1 else 0
}

internal fun executeScenario(
    project: SimulProject,
    md: File,
    scenario: Scenario,
    trace: TraceFile?,
    mode: RunMode,
    llm: LlmConfig,
    traceFile: File,
    ops: DeviceOps,
    log: (String) -> Unit = ::println,
    video: Boolean = false,
    runsDir: File? = null,
): ScenarioRunResult {
    val reportDir = if (runsDir != null) ReportDirs.inRuns(runsDir, project, md, scenario)
                    else ReportDirs.create(project.reportsDir, scenario.name)

    val jsonl = Trace(File(reportDir, "agent.jsonl"))
    val listener = SwitchableListener()
    var recording: ScreenRecorder.Recording? = null
    var recorderProblem: String? = null
    val result = DeviceStack(project.config.app, jsonl, project.config.scrollLimit, listener).use { stack ->
        // turn lines nest under the runner's step line in the live log
        // replay never builds a client — the key stays optional for CI that only replays
        val executor = SessionStepExecutor(stack.controller, { llm.client() }, llm.llmModel(), project.config.maxTurns,
            log = { log("    $it") })
        // The screen recording starts when setup is done (state restored, app cold-started) and
        // spans the steps — the journey itself, not the pm clear and the splash. It stops in
        // `finally`: a crashed run must not leave screenrecord running on the device for the
        // rest of its time limit. A recorded replay keeps only the failed step's screencap.
        val recorder = if (video) ScreenRecorder() else null
        // steps are logged live by the runner — a scenario can run for minutes
        val runner = ScenarioRunner(stack.controller, listener, ops, executor, reportDir, project.config.app,
            stepFrames = !(video && mode == RunMode.REPLAY), log = log, onSetupDone = { recorder?.start() })

        log("── ${scenario.name} [${mode.name.lowercase()}] ${"─".repeat(30)}")
        try {
            runner.run(scenario, trace, mode)
        } finally {
            recorder?.let { recording = it.stop(File(reportDir, "replay.mp4")); recorderProblem = it.problem }
        }
    }

    log("${result.status}: ${scenario.name}  (${result.durationMs}ms, report: ${reportDir.path})")
    // what the recording cost: model time vs. the scenario's wall time, and the tokens it took
    result.llm?.let { log("  ${it.summary(totalMs = result.durationMs)}") }

    fun rel(f: File) = f.relativeToOrSelf(project.appRoot).path
    val videoInfo = recording?.let { rec -> rec.file?.let { VideoInfo(it, rec.startedAtMs, rec.durationMs) } }
    if (video) {
        recorderProblem?.let { log("  video: $it") }
        recording?.files?.forEach { log("  video: ${rel(it)}") }
    }

    val full = result.copy(reportDir = reportDir, video = videoInfo)
    val relPath = md.relativeToOrSelf(project.scenariosDir).path
    ResultJson.write(full, mode.name.lowercase(), ResultJson.Meta(
        feature = areaOf(relPath),
        name = md.nameWithoutExtension,
        state = scenario.state,
        file = relPath,
        title = scenario.title ?: scenario.name,
        owner = scenario.owner,
        description = scenario.description,
        traceRecordedAt = traceRecordedAt(traceFile, project.appRoot),
        env = ResultJson.Env(ops.appVersionName(project.config.app), ops.deviceName(), ops.androidCliVersion()),
    ), reportDir)

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
    return full
}

/**
 * `YYYY-MM-DD` the trace was last recorded: the date of its last commit when the file is
 * clean in git, else its modification date (a fresh, uncommitted recording). Null without a
 * trace. The report uses it to tell "acknowledged but not yet re-recorded".
 */
fun traceRecordedAt(traceFile: File, appRoot: File): String? {
    if (!traceFile.isFile) return null
    val mtime = SimpleDateFormat("yyyy-MM-dd").format(Date(traceFile.lastModified()))
    val git = runCatching {
        val dirty = runProcess(listOf("git", "-C", appRoot.path, "status", "--porcelain", "--", traceFile.absolutePath)).isNotBlank()
        if (dirty) null
        else runProcess(listOf("git", "-C", appRoot.path, "log", "-1", "--format=%cs", "--", traceFile.absolutePath)).trim().ifEmpty { null }
    }.getOrNull()
    return git ?: mtime
}

/** Where one run's result.json / steps / layout / logcat go. */
object ReportDirs {
    private const val STAMP = "yyyyMMdd-HHmmss"

    /** Ad-hoc runs: `<reports>/<scenario>-yyyyMMdd-HHmmss`, a fresh directory per run. */
    fun create(reportsDir: File, scenario: String): File =
        File(reportsDir, "$scenario-${SimpleDateFormat(STAMP).format(Date())}").also { it.mkdirs() }

    /**
     * A nightly run laid out for the report: `<runs>/<feature>/<name>.<state>/` (`<name>/` for a
     * stateless scenario; root-level scenarios sit directly under `<runs>`). Rerunning into the
     * same runs dir replaces the scenario's directory, so no stale step of an earlier run survives.
     */
    fun inRuns(runsDir: File, project: SimulProject, md: File, scenario: Scenario): File {
        val relPath = md.relativeToOrSelf(project.scenariosDir).path
        val key = md.nameWithoutExtension + (scenario.state?.let { ".$it" } ?: "")
        val dir = File(runsDir, listOf(areaOf(relPath), key).filter { it.isNotEmpty() }.joinToString("/"))
        if (dir.isDirectory) dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

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
