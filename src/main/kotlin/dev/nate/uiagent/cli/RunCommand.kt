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
 * With several devices attached the run goes over all of them at once ([Devices], [runOverDevices]) — there is
 * no option for it: the emulators that are up and the physical devices marked for simul, with the app under test
 * installed, are the ones it uses, and `ANDROID_SERIAL` names one as it always did.
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
            val named = if (ofTrace == null) variants else variants.filter { it.run == ofTrace.second }
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
    val runs = withEntries(project, try { withHosts(project, targets) } catch (e: IllegalArgumentException) { return err(e.message ?: "setup.after") }, mode)

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

    // the devices attached are the devices it runs on — a recording too: every way in is recorded before the
    // scenarios that start from one, and that order holds over the devices (runOverDevices)
    val serials = if (dryRun) Devices.ADB_PICKS else try { Devices.forRun(project.config.app) } catch (e: IllegalStateException) { return err(e.message ?: "no device") }
    val devices = serials.map { AdbOps(statesDir = project.statesDir, serial = it) }
    val ops = devices.first()
    if (devices.size > 1) {
        println("devices: ${devices.size} — ${serials.joinToString(", ")}")
        for (r in runOverDevices(project, runs, devices, mode, llm, video, runsDir, thenReplay = thenReplay).flatMap { it.all() }) {
            outcomes += r.outcome
            r.result?.let { results += it }
            if (r.outcome.status != "SKIPPED") anyRan = true
            if (r.outcome.status == "FAILED" || r.outcome.status == "CRASH") anyFailed = true
        }
    } else BatchSession(project, ops, ::println, runsDir).use { batch ->
        if (!dryRun) batch.pin()
        for ((md, scenario) in runs) {
            if (dryRun) {
                val trace = project.traceFileFor(md, scenario)
                val planned = if (mode == RunMode.AUTO) (if (trace.isFile) "replay" else "skip (no trace)") else mode.name.lowercase()
                println("${scenario.name} (${scenarioPath(project, md, scenario)})")
                println("  mode: $planned  trace: ${TraceState.of(md, trace).name.lowercase()}  tags: ${scenario.tags}" +
                    (scenario.state?.let { "  state: $it" } ?: "") + (if (scenario.data.isEmpty()) "" else "  with: " + scenario.data.entries.joinToString(", ") { (k, v) -> "$k=$v" }))
                scenario.via?.let { println("  starts from the way in to '$it' (${project.entryOf(it)?.file ?: SimulProject.ENTRY_FILE})") }
                scenario.steps.forEachIndexed { idx, s ->
                    scenario.sections.firstOrNull { it.first == idx }?.let { sec ->
                        println((if (sec.preparation) "  setup — asked of the model in every mode; `by` is done only when the state does not hold" else "  ## ${sec.title}") +
                            (sec.open?.let { o ->
                                " — starts" + (o.appState?.let { " as '$it'" } ?: "") +
                                    (if (o.elsewhere) " at ${o.deeplink ?: "the way in to '${o.screen}'"}" else "")
                            } ?: ""))
                    }
                    // the state it starts from is not a step of the test: said as it is written
                    if (scenario.ensure != null && idx == 0) {
                        println("  ${idx + 1}. ensure: $s")
                        scenario.ensure.by.forEach { println("     by: $it") }
                    } else println("  ${idx + 1}. $s")
                }
                for (f in scenario.followers) {
                    println("  then, where '## ${f.section}' ends: ${f.scenario.name} (${scenarioPath(project, f.md, f.scenario)})")
                    f.scenario.steps.forEachIndexed { idx, s -> println("    ${idx + 1}. $s") }
                }
                continue
            }
            val ran = batch.run(md, scenario, mode, llm, video)
            for (r in ran.all()) {
                outcomes += r.outcome
                r.result?.let { results += it }
                if (r.outcome.status != "SKIPPED") anyRan = true
                if (r.outcome.status == "FAILED" || r.outcome.status == "CRASH") anyFailed = true
            }
            // the scenarios a recording took along are replayed with it: a recording of theirs that did not pass has no trace and says so
            if (thenReplay && ran.outcome.passed) {
                for (replay in batch.run(md, scenario, RunMode.REPLAY, llm, video).all()) {
                    outcomes += replay.outcome
                    replay.result?.let { results += it }
                    if (replay.outcome.status == "FAILED" || replay.outcome.status == "CRASH") anyFailed = true
                }
            }
        }
    }

    if (!anyRan && !dryRun) println("warning: no scenario was executed (all skipped)")
    if (outcomes.size > 1) recap(outcomes).forEach(::println)
    // where the batch's time went, summed over its scenarios — the line to read before any single run's
    if (results.size > 1) println(
        if (devices.size == 1) timeLine(System.currentTimeMillis() - batchStart, results, ::humanDuration)
        // the parts are summed over the devices, so they are shares of the devices' time, not of the clock's
        else "wall ${humanDuration(System.currentTimeMillis() - batchStart)} on ${devices.size} devices — device " +
            timeLine(results.sumOf { it.wallMs }, results, ::humanDuration)
    )
    if (!dryRun && (summaryPath != null || runsDir != null)) {
        val ended = System.currentTimeMillis()
        val summary = RunSummary(
            label = label ?: mode.name.lowercase(),
            mode = mode.name.lowercase(),
            startedAt = startedAt,
            durationMs = ended - batchStart,
            agentVersion = AGENT_VERSION,
            appVersionName = ops.appVersionName(project.config.app),
            device = devices.mapNotNull { it.deviceName() }.distinct().joinToString(", ").ifEmpty { null },
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
    return (added + targets).sortedBy { (md, _) -> !SimulProject.isEntry(md) }
}

/**
 * [targets] with every scenario that starts after another's section ([Scenario.after]) moved into that
 * scenario's run ([Scenario.followers]): it is run there, where the section ends, not as a run of its own. The
 * other scenario is added when it was not asked for — the one asked for cannot be run without it — and takes
 * along only the scenarios that were asked for.
 */
internal fun withHosts(project: SimulProject, targets: List<Pair<File, Scenario>>): List<Pair<File, Scenario>> {
    val taken = targets.filter { it.second.after != null }
    if (taken.isEmpty()) return targets
    fun key(md: File) = Mentions.norm(md.absoluteFile.relativeTo(project.scenariosDir.absoluteFile).path)
    val byHost = taken.groupBy { Mentions.norm(it.second.after!!.file) }
        .mapValues { (_, fs) -> fs.map { (md, sc) -> Scenario.Follower(md, sc, sc.after!!.section) } }
    val asked = targets.filter { it.second.after == null }.map { key(it.first) }.toSet()
    val added = mutableSetOf<String>()
    return targets.flatMap { (md, sc) ->
        val after = sc.after
        when {
            after == null -> listOf(md to (byHost[key(md)]?.let { sc.copy(followers = it) } ?: sc))
            Mentions.norm(after.file) in asked || !added.add(Mentions.norm(after.file)) -> emptyList()
            else -> File(project.scenariosDir, after.file).let { host -> listOf(host to project.load(host).variants().single().copy(followers = byHost.getValue(Mentions.norm(after.file)))) }
        }
    }
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
    /** The scenario goes on from the screen another scenario's section left ([Scenario.After]): nothing is set up. */
    continued: Boolean = false,
    /** Called when a section of this scenario has ended, for the scenarios that start there ([BatchSession.run]). */
    follow: (section: String, passed: Boolean, back: () -> Unit) -> Unit = { _, _, _ -> },
): ScenarioRunResult {
    val t0 = System.currentTimeMillis()
    val reportDir = if (runsDir != null) ReportDirs.inRuns(runsDir, project, md, scenario)
                    else ReportDirs.create(project.reportsDir, scenario.name)

    val jsonl = Trace(File(reportDir, "agent.jsonl"))
    val listener = SwitchableListener()
    var recording: ScreenRecorder.Recording? = null
    var recorderProblem: String? = null
    val result = DeviceStack(project.config.app, jsonl, project.config.scrollLimit, listener, serial = ops.serial).use { stack ->
        // turn lines nest under the runner's step line in the live log
        // replay never builds a client — the key stays optional for CI that only replays
        val executor = SessionStepExecutor(stack.controller, { llm.client() }, llm.llmModel(), project.config.maxTurns,
            log = { log("    $it") }, appContext = project.config.context, accountState = project.config.accountState(scenario), accountLine = project.config::accountLine,
            problem = { llm.keyProblem() })
        // The screen recording starts when setup is done (state restored, app cold-started) and
        // spans the steps — the journey itself, not the pm clear and the splash. It stops in
        // `finally`: a crashed run must not leave screenrecord running on the device for the
        // rest of its time limit. A recorded replay keeps only the failed step's screencap.
        val recorder = if (video) ScreenRecorder(serial = ops.serial) else null
        // steps are logged live by the runner — a scenario can run for minutes
        val runner = ScenarioRunner(stack.controller, listener, ops, executor, reportDir, project.config.app,
            locale = project.config.device.locale, network = project.config.device.network, stepFrames = !(video && mode == RunMode.REPLAY), log = log, onSetupDone = { recorder?.start() },
            entries = project::entryOf, afterSection = follow)

        log("── ${scenario.name} [${mode.name.lowercase()}]${ops.serial?.let { " on $it" } ?: ""} ${"─".repeat(30)}")
        try {
            runner.run(scenario, trace, mode, continued)
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
        state = scenario.run,
        file = relPath,
        // one of a file's runs in each mode is called as the file is: the mode is not part of what it confirms
        title = scenario.title ?: if (scenario.perMode) scenario.name.removeSuffix("@${scenario.mode}") else scenario.name,
        owner = scenario.owner,
        description = ModeData.plain(fillValues(scenario.description, result.values), scenario.data.keys),
        mentions = scenario.mentions,
        screens = project.layoutsFor(project.screenOf(md)),
        traceRecordedAt = traceRecordedAt(traceFile, project.appRoot),
        env = ResultJson.Env(ops.appVersionName(project.config.app), ops.deviceName(), ops.androidCliVersion(), ops.osVersion()),
        traceOs = traceOs(traceFile),
        setup = scenario.setup?.let { ResultJson.Setup(it.deeplink, it.launch, scenario.precondition, scenario.via?.let(Modes::screenOf), scenario.via?.let(Modes::modeOf)) },
        screenMode = scenario.mode,
        modeData = scenario.data,
        perMode = scenario.perMode,
        after = scenario.after?.let { a -> ResultJson.After(a.screen, a.title, a.section, File(a.file).nameWithoutExtension, handedOver(project, scenario)) },
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
 * What the scenario [scenario] starts after had put on the screen for it: the labels both write in quotes — the
 * section's steps to find them, this scenario's to act on them (`"치이카와"`). The report says them beside where
 * the scenario's data came from.
 */
internal fun handedOver(project: SimulProject, scenario: Scenario): List<String> {
    val after = scenario.after ?: return emptyList()
    val host = runCatching { project.load(File(project.scenariosDir, after.file)) }.getOrNull() ?: return emptyList()
    val section = host.sections.firstOrNull { it.title == after.section } ?: return emptyList()
    val theirs = section.steps.flatMap { quotedLabels(host.steps[it]) }.toSet()
    return scenario.steps.flatMap(::quotedLabels).distinct().filter { it in theirs }
}

/**
 * `YYYY-MM-DD` the trace was last recorded: the date of its last commit when the file is
 * clean in git, else its modification date (a fresh, uncommitted recording). Null without a
 * trace. The report uses it to tell "acknowledged but not yet re-recorded".
 */
/** The OS the committed trace says it was recorded on ("API 37"); null without a trace or for one that does not say. */
fun traceOs(traceFile: File): String? = if (!traceFile.isFile) null else runCatching { TraceJson.read(traceFile).os }.getOrNull()

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
        val key = md.nameWithoutExtension + (scenario.run?.let { ".$it" } ?: "")
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
