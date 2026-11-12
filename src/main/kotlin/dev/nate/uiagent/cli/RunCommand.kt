package dev.nate.uiagent.cli

import dev.nate.uiagent.Trace
import dev.nate.uiagent.device.AdbOps
import dev.nate.uiagent.device.DeviceCost
import dev.nate.uiagent.device.DeviceOps
import dev.nate.uiagent.humanDuration
import dev.nate.uiagent.runProcess
import dev.nate.uiagent.secs
import dev.nate.uiagent.web.DeviceStack
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * `simul run <scenario.md>... | --tag <tag> | --state <name> | --all [--mode replay|llm|both|auto]
 *            [--dry-run] [--url U] [--video] [--runs <dir>] [--summary <file.json> [--label <phase>]]`
 *
 * CI invariant: CI only replays. The default mode is auto — replay when a trace exists, SKIP with
 * a warning when it doesn't; the LLM interprets full scenarios only under an explicit `--mode llm`.
 *
 * `--mode both` is the authoring loop: record with the LLM and, when the recording passed, replay
 * that trace on the spot — scenario by scenario, no video, no HTML. A trace the model could record
 * but the replayer cannot follow then shows up at the scenario it belongs to, while it is being
 * written, instead of after the whole set is recorded (stone 2026-11-05: 39 scenarios recorded,
 * then 45 minutes on three that would not replay). `simul report <scenario.md>` is the same pair
 * with the screen recording and the pages — the thing to show a person, not the loop.
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
    var thenReplay = false
    val tags = mutableListOf<String>()
    val paths = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--mode" -> mode = when (args.getOrNull(++i)) {
                "replay" -> RunMode.REPLAY
                "llm" -> RunMode.LLM
                "both" -> RunMode.LLM.also { thenReplay = true }
                "auto" -> RunMode.AUTO
                else -> return err("invalid --mode (use replay|llm|both|auto)")
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

    // one summary / one runs directory holds one phase; the nightly records and replays as two runs
    if (thenReplay && (summaryPath != null || runsPath != null)) return err("--mode both takes no --summary/--runs")

    val project = SimulProject.find(cwd)
        ?: return err("no .simul/ directory found from $cwd upwards — run inside an app repo with the .simul convention")
    project.versionWarning()?.let { println(it) }

    // A `states:` scenario is several runs — one variant per state, each with its own trace.
    val targets: List<Pair<File, Scenario>> = when {
        paths.isNotEmpty() -> paths.flatMap { p ->
            val file = resolvePath(cwd, p).takeIf { it.isFile } ?: File(project.scenariosDir, p).takeIf { it.isFile }
                ?: return err("scenario not found: $p")
            // a committed trace names one run: its scenario in the state it was recorded in
            val ofTrace = file.takeIf { it.name.endsWith(SimulProject.TRACE_SUFFIX) }?.let(project::scenarioOfTrace)
            val md = ofTrace?.first ?: file
            if (!md.isFile) return err("$p: no scenario beside this trace (${md.name})")
            val variants = try { project.load(md).variants() } catch (e: IllegalArgumentException) {
                return err("${md.relativeToOrSelf(cwd)}: ${e.message}")
            }
            val named = if (ofTrace == null) variants else variants.filter { it.state == ofTrace.second }
                .ifEmpty { return err("$p: ${md.name} has no such run (its traces: ${variants.joinToString { project.traceFileFor(md, it).name }})") }
            named.filter { stateFilter == null || it.precondition == stateFilter }.map { md to it }
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
    val runs = withEntries(project, targets, mode)

    val llm = project.config.llm.let { if (urlOverride != null) it.copy(url = urlOverride) else it }
    // The key is only needed when the LLM actually runs; replay must work without the secret.
    if (mode != RunMode.REPLAY && !dryRun) llm.keyProblem()?.let { return err(it) }
    val runsDir = runsPath?.let { resolvePath(cwd, it).absoluteFile }
    var anyFailed = false
    var anyRan = false
    val outcomes = mutableListOf<ScenarioOutcome>()
    val results = mutableListOf<ScenarioRunResult>()
    val batchStart = System.currentTimeMillis()
    val startedAt = RunSummaryJson.stamp(batchStart)

    val ops = AdbOps(statesDir = project.statesDir)
    BatchSession(project, ops, ::println, runsDir).use { batch ->
        if (!dryRun) batch.pin()
        for ((md, scenario) in runs) {
            if (dryRun) {
                val trace = project.traceFileFor(md, scenario)
                val planned = if (mode == RunMode.AUTO) (if (trace.isFile) "replay" else "skip (no trace)") else mode.name.lowercase()
                println("${scenario.name} (${scenarioPath(project, md, scenario)})")
                println("  mode: $planned  trace: ${TraceState.of(md, trace).name.lowercase()}  tags: ${scenario.tags}" +
                    (scenario.state?.let { "  state: $it" } ?: ""))
                scenario.via?.let { println("  starts from the way in to '$it' (${SimulProject.ENTRY_FILE})") }
                scenario.steps.forEachIndexed { idx, s ->
                    scenario.sections.firstOrNull { it.first == idx }?.let { sec ->
                        println("  ## ${sec.title}" + (if (sec.preparation) " — by the model, in every mode" else "") +
                            (sec.open?.let { o ->
                                " — starts" + (o.appState?.let { " as '$it'" } ?: "") +
                                    (if (o.elsewhere) " at ${o.deeplink ?: "the way in to '${o.screen}'"}" else "")
                            } ?: ""))
                    }
                    println("  ${idx + 1}. $s")
                }
                continue
            }
            val r = batch.run(md, scenario, mode, llm, video)
            outcomes += r.outcome
            r.result?.let { results += it }
            if (r.outcome.status != "SKIPPED") anyRan = true
            if (r.outcome.status == "FAILED" || r.outcome.status == "CRASH") anyFailed = true
            if (thenReplay && r.outcome.passed) {
                val replay = batch.run(md, scenario, RunMode.REPLAY, llm, video)
                val replayed = replay.outcome
                outcomes += replayed
                replay.result?.let { results += it }
                if (!replayed.passed) anyFailed = true
            }
        }
    }

    if (!anyRan && !dryRun) println("warning: no scenario was executed (all skipped)")
    if (outcomes.size > 1) recap(outcomes).forEach(::println)
    // where the batch's time went, summed over its scenarios — the line to read before any single run's
    if (results.size > 1) println(timeLine(System.currentTimeMillis() - batchStart, results, ::humanDuration))
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
            // a recording is half of a report (`simul report` lays it under <stamp>/record/); the replay that follows builds the pages for both
            if (mode != RunMode.LLM) runCatching { SitePages.build(dir, dir) }
                .onSuccess { println("report: ${shown(it.index)} — ${it.counts()}") }
                .onFailure { println("warning: report not written: ${it.message}") }
        }
    }
    return if (anyFailed) 1 else 0
}

/**
 * [targets] with the ways in they need. A scenario that starts from its screen's way in — or a section of it
 * that starts at another screen's — plays that `_entry.md` from its trace; when the entry is not among the
 * targets and a recording finds its trace missing or older than the file, the entry is recorded first.
 * Every entry, added or asked for, runs ahead of the scenarios that start from it.
 */
internal fun withEntries(project: SimulProject, targets: List<Pair<File, Scenario>>, mode: RunMode): List<Pair<File, Scenario>> {
    val asked = targets.map { it.first.absoluteFile }.toSet()
    val needed = targets.flatMap { (_, sc) -> listOfNotNull(sc.via) + sc.sections.mapNotNull { it.open?.screen } }.distinct()
    val added = if (mode != RunMode.LLM) emptyList() else needed.mapNotNull { screen ->
        val md = File(project.scenariosDir, project.entryOf(screen)?.file ?: return@mapNotNull null)
        md.takeIf { it.absoluteFile !in asked && TraceState.of(it, project.traceFileFor(it)) != TraceState.OK }
            ?.let { it to project.load(it) }
    }
    return (added + targets).sortedBy { (md, _) -> md.name != SimulProject.ENTRY_FILE }
}

/**
 * The closing lines of a run of several scenarios: the count, then every scenario that did not pass
 * with the step it stopped at, why, and where its report is — what one otherwise digs out of each
 * result.json after the live log has scrolled away.
 */
internal fun recap(outcomes: List<ScenarioOutcome>): List<String> = buildList {
    add("")
    add("── ${outcomes.count { it.passed }}/${outcomes.size} passed ──")
    for (o in outcomes.filterNot { it.passed }) {
        val where = o.failedStep?.let { " step ${it.index}: ${it.text}" } ?: ""
        val why = (o.failedStep?.reason ?: o.reason)?.let { " — $it" } ?: ""
        add("${o.status} ${o.path}${o.mode?.let { " [$it]" } ?: ""}$where$why")
        o.reportDir?.let { add("  $it") }
    }
}

/**
 * Where a stretch of wall time went, each part with its share:
 * `time 106.0s = setup 7% 7.6s · llm 26% 27.5s · observe 39% 41.0s/38 dumps (idle wait refused 2× 6.1s,
 * 1 settle ran out) · waits 6% 6.2s · gestures 2% 2.1s/9 · screenshots 12% 12.3s/24 · wrap-up 2% 2.1s · other 7% 7.2s`.
 * `wrap-up` is what follows a scenario's last step (stopping the recording, the logcat dump,
 * result.json, the trace); `other` is the rest of [wallMs] — the runner's own work between the
 * measured parts and, for a batch, whatever ran between scenarios.
 */
internal fun timeLine(wallMs: Long, runs: List<ScenarioRunResult>, time: (Long) -> String = ::secs): String {
    val share = { ms: Long -> if (wallMs > 0) "${ms * 100 / wallMs}% " else "" }
    fun part(name: String, ms: Long) = if (ms > 0) "$name ${share(ms)}${time(ms)}" else null
    val setup = runs.sumOf { it.setupMs }
    val llm = runs.sumOf { it.llm?.durationMs ?: 0 }
    val device = runs.fold(DeviceCost()) { sum, r -> sum + r.device }
    val wrapUp = runs.sumOf { (it.wallMs - it.durationMs).coerceAtLeast(0) }
    val parts = listOfNotNull(part("setup", setup), part("llm", llm)) + device.parts(time, share) +
        listOfNotNull(part("wrap-up", wrapUp), part("other", wallMs - setup - llm - device.totalMs - wrapUp))
    return "time ${time(wallMs)} = ${parts.joinToString(" · ")}"
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
    val t0 = System.currentTimeMillis()
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
            log = { log("    $it") }, appContext = project.config.context, accountState = project.config.accountState(scenario), accountLine = project.config::accountLine,
            problem = { llm.keyProblem() })
        // The screen recording starts when setup is done (state restored, app cold-started) and
        // spans the steps — the journey itself, not the pm clear and the splash. It stops in
        // `finally`: a crashed run must not leave screenrecord running on the device for the
        // rest of its time limit. A recorded replay keeps only the failed step's screencap.
        val recorder = if (video) ScreenRecorder() else null
        // steps are logged live by the runner — a scenario can run for minutes
        val runner = ScenarioRunner(stack.controller, listener, ops, executor, reportDir, project.config.app,
            locale = project.config.device.locale, stepFrames = !(video && mode == RunMode.REPLAY), log = log, onSetupDone = { recorder?.start() },
            entries = project::entryOf)

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
        description = fillValues(scenario.description, result.values),
        mentions = scenario.mentions,
        traceRecordedAt = traceRecordedAt(traceFile, project.appRoot),
        env = ResultJson.Env(ops.appVersionName(project.config.app), ops.deviceName(), ops.androidCliVersion()),
        setup = scenario.setup?.let { ResultJson.Setup(it.deeplink, it.launch, scenario.precondition, scenario.via) },
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
    val timed = full.copy(wallMs = System.currentTimeMillis() - t0)
    log("  ${timeLine(timed.wallMs, listOf(timed))}")
    return timed
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
