package dev.nate.uiagent.cli

import dev.nate.uiagent.device.SettingChange
import dev.nate.uiagent.device.DeviceSettings
import dev.nate.uiagent.device.AdbOps
import dev.nate.uiagent.device.DeviceOps
import dev.nate.uiagent.Point
import dev.nate.uiagent.ProcessError
import dev.nate.uiagent.agent.LlmStats
import dev.nate.uiagent.secs
import dev.nate.uiagent.device.DeviceController
import dev.nate.uiagent.device.DeviceCost
import java.io.File

enum class RunMode { LLM, REPLAY, AUTO }

enum class StepStatus { PASSED, FAILED, SKIPPED }

data class StepReport(
    val index: Int,
    val text: String,
    val status: StepStatus,
    /** "llm" or "replay"; null for skipped steps. */
    val mode: String?,
    val actions: List<TraceAction>,
    /** Evidence present when the step ended (replay: what the gate found; record: what it extracted). */
    val evidence: List<String>,
    val reason: String?,
    val durationMs: Long,
    val screenshots: List<String>,
    val stateEvidence: List<StateEvidence> = emptyList(),
    /** Model calls/time/tokens of an llm step; null when the model was not consulted. */
    val llm: LlmStats? = null,
    /**
     * What the screen exposed when the step FAILED — the labels/ids the harness could see —
     * so a report answers "then what WAS there?" without the device (a replay that cannot
     * ground its target has taken no action and therefore has no screenshot of its own).
     */
    val screen: List<String> = emptyList(),
    /** Wall-clock start of the step, millis since the scenario started. */
    val startedMs: Long = 0,
    /** The gestures of the step with the moment each went to the device (see [ActionMark]). */
    val marks: List<ActionMark> = emptyList(),
    /** What the step had to show to pass: the trace's evidence (replay) or the criterion's quoted anchors (record). */
    val expectedEvidence: List<String> = emptyList(),
    /** `layout/NN.json` of the screen when the step ended, relative to the report dir; null when not captured. */
    val layout: String? = null,
)

/**
 * How one `## title` section of a scenario went: PASSED/FAILED by its steps, SKIPPED when an earlier
 * section had already failed. [steps] are the step numbers it owns; [preparation] marks the `## 준비`
 * section, whose steps the model performed whatever the run's mode.
 */
data class SectionReport(
    val title: String,
    val description: String,
    val status: StepStatus,
    val steps: IntRange,
    val preparation: Boolean = false,
    /** Where the section started when that was not the scenario's entry (its `deeplink:` / `entry:` line). */
    val open: Scenario.Open? = null,
)

/** The screen recording of a run: where its t=0 lies on the wall clock, so steps can be seeked to. */
data class VideoInfo(val file: File, val startedAtMs: Long, val durationMs: Long?)

data class ScenarioRunResult(
    val scenario: String,
    val status: StepStatus,
    val steps: List<StepReport>,
    /** Trace to persist — full re-record (llm) or coord-corrected replay. Null if nothing to write. */
    val updatedTrace: TraceFile?,
    val durationMs: Long,
    /** Wall-clock start of the scenario (epoch millis) — the zero of every step's `startedMs`. */
    val startedAtMs: Long = 0,
    /** The scenario never reached its steps: setup (state restore, launch, deeplink) broke. */
    val setupFailed: Boolean = false,
    /** Where this run's result.json / steps / layout / logcat were written (set by the CLI). */
    val reportDir: File? = null,
    /** Screen recording of the run (`replay.mp4`), set by the CLI when `--video` was given. */
    val video: VideoInfo? = null,
    /** How much of [durationMs] was setup (state restore, launch, the wait for the app's fully-drawn signal). */
    val setupMs: Long = 0,
    /** What the steps cost on the device: observations, pauses, gestures, screenshots ([timeLine]). */
    val device: DeviceCost = DeviceCost(),
    /** The scenario's `values:` as this run picked them (`name` → value) — the report shows the prose and steps with them. */
    val values: Map<String, String> = emptyMap(),
    /** The scenario's whole wall time, [durationMs] plus what the CLI did around it (stopping the recording, logcat, result.json, the trace). */
    val wallMs: Long = 0,
    /** The scenario's `## title` sections in file order — the order they ran in; empty for a scenario without them. */
    val sections: List<SectionReport> = emptyList(),
) {
    val passed get() = status == StepStatus.PASSED

    /** Model usage summed over the steps; null when no step consulted the model (replay). */
    val llm: LlmStats? get() = LlmStats.sum(steps.map { it.llm })
}

/**
 * The scenario execution contract, deterministically: run setup, execute steps in order
 * with per-step isolation, propagate the first FAILED as SKIPPED for the rest, and return the
 * result. Mode decides how a step runs: llm (record) or replay (pure trace playback — the LLM
 * is never consulted; a broken step is a FAILED result).
 *
 * A scenario with `## title` sections runs them in file order, each from the app started over at the
 * scenario's entry (stopped and opened again, its data kept; the model's conversation is new too). Its `## 준비` section, when it has one, goes first and
 * to the model in every mode: a replay cannot tell what state the last run left on the server, so
 * bringing the app to the state the other sections start from is the one thing a replay asks the
 * model for. Its steps are never traced; everything after it replays without a model as before.
 *
 * A scenario that starts from its screen's way in ([Scenario.via]) gets there before its first step and
 * before each section: the app is opened as that screen's `_entry.md` says and the entry's steps are played
 * from the entry's trace ([walkIn]) — no model in any mode, and none of it among the scenario's steps. A
 * section may start somewhere else ([Scenario.Open]): at an address, or at another screen by its way in.
 * Such a section, or one whose sentence mentions another screen, is a look at that screen: when it fails the
 * sections after it still run — they do not start from what it did — and the scenario has failed all the same.
 *
 * Every executed step leaves what the report needs in [reportDir]: `steps/NN.png` (the screen
 * when the step ended) and `layout/NN.json` (its layout tree); the scenario leaves `logcat.txt`
 * for its whole span. The CLI adds the recording and writes `result.json` (see ResultJson).
 */
class ScenarioRunner(
    private val controller: DeviceController,
    private val listener: SwitchableListener,
    private val ops: DeviceOps,
    private val llm: StepExecutor,
    private val reportDir: File,
    private val app: String?,
    /** config.yaml `device.locale`: the app language every scenario runs in unless its setup says otherwise. */
    private val locale: String? = null,
    private val takeScreenshots: Boolean = true,
    /**
     * A `steps/NN.png` for every step, or only for the failed one. The CLI turns it off for a replay
     * that is being screen-recorded: the mp4 has every frame and the report's pages only need the
     * failed step's, while a `screencap -p` costs ~3s per step on a software-rendered emulator.
     */
    private val stepFrames: Boolean = true,
    /** Live progress sink: step start/result lines as they happen (a scenario can run for minutes). */
    private val log: (String) -> Unit = {},
    /** Runs once setup (state restore, cold start, deeplink) is done and the first step is about to run — the CLI starts the screen recording here. */
    private val onSetupDone: () -> Unit = {},
    /** How a run picks among the candidates of a scenario's `values:` entry that are not on screen. */
    private val chooseValue: (List<String>) -> String = { it.random() },
    /** A screen's way in by the screen's name ([SimulProject.entryOf]) — read when it is needed, so one recorded earlier in this run is found. */
    private val entries: (String) -> ScreenEntry? = { null },
) {
    private val replayer by lazy { Replayer(controller, log = log) }

    /** This scenario run's picks for its `values:` ([run] starts each scenario with none). */
    private var picks = ValuePicks(emptyMap())

    fun run(scenario: Scenario, trace: TraceFile?, mode: RunMode): ScenarioRunResult {
        require(mode != RunMode.AUTO) { "AUTO must be resolved to LLM/REPLAY (or skip) by the caller" }
        // A scenario's own device settings last exactly as long as the scenario: whatever
        // happens inside (setup failure, step FAILED, harness exception), the next scenario
        // starts from the baseline the run began with.
        val pinned = mutableListOf<SettingChange>()
        // The app locale the scenario replaced, to put back (`""` = it followed the system); null = untouched.
        var localeBefore: String? = null
        // logcat for exactly this scenario's span: cleared before setup, dumped after the last step
        runCatching { ops.logcatClear() }
        try {
            return runPinned(scenario, trace, mode, pinned) { localeBefore = it }
        } finally {
            runCatching { ops.logcatDump(File(reportDir, "logcat.txt")) }
            if (pinned.isNotEmpty()) DeviceSettings.restore(ops, pinned) { log("  device: $it") }
            localeBefore?.let { was ->
                runCatching { ops.setAppLocales(app!!, was.ifEmpty { null }) }
                    .onSuccess { log("  device: app locale restored to ${was.ifEmpty { "system" }}") }
                    .onFailure { e -> log("  device: warning: could not restore app locale to ${was.ifEmpty { "system" }}: ${e.message?.lineSequence()?.firstOrNull()}") }
            }
        }
    }

    private fun runPinned(
        scenario: Scenario, trace: TraceFile?, mode: RunMode,
        pinned: MutableList<SettingChange>, localeReplaced: (String) -> Unit,
    ): ScenarioRunResult {
        val t0 = System.currentTimeMillis()
        val cost0 = controller.meter.cost
        var setupMs = 0L
        picks = ValuePicks(scenario.values, log, chooseValue)

        // A broken setup (bad deeplink, adb hiccup) fails THIS scenario — it must never kill
        // the process and take the rest of a --all batch with it.
        var wantLocale: String? = null
        try {
            scenario.setup?.let {
                // before the cold start, so the app reads the pinned values
                DeviceSettings.apply(ops, it.device.settings, { msg -> log("  device: $msg") }, pinned)
                // The app locale is set inside ops.setup (after the pm clear that would wipe it);
                // read what it was first so the scenario can leave the app as it found it.
                wantLocale = (it.device.locale ?: locale)?.takeIf { app != null }
                if (wantLocale != null) {
                    val was = ops.appLocales(app!!)
                    if (was != wantLocale) localeReplaced(was ?: "")
                    log("  device: app locale $wantLocale (was ${was ?: "system"})")
                }
                enter(it, wantLocale)
            }
            scenario.via?.let { walkIn(it, scenario.precondition) }
            setupMs = System.currentTimeMillis() - t0
            // whatever this controller saw before, the app has just been brought to the scenario's entry
            controller.forget()
            onSetupDone()
        } catch (ex: Exception) {
            val why = "setup failed: ${ex.message?.lineSequence()?.firstOrNull() ?: ex.toString()}"
            log("  $why")
            val steps = scenario.steps.mapIndexed { i, text ->
                StepReport(i + 1, text, StepStatus.SKIPPED, null, emptyList(), emptyList(),
                    if (i == 0) why else null, 0, emptyList())
            }
            return ScenarioRunResult(scenario.name, StepStatus.FAILED, steps, null,
                System.currentTimeMillis() - t0, startedAtMs = t0, setupFailed = true,
                sections = scenario.sections.map { SectionReport(it.title, it.description, StepStatus.SKIPPED, it.first + 1..it.first + it.size, it.preparation, it.open) })
        }

        // A trace replays coordinates and layouts of the display it was recorded on — if the
        // current display differs, warn before anything breaks (fix: `display:` in config.yaml).
        if (mode == RunMode.REPLAY && trace?.display != null) {
            val current = ops.displayProfile()
            if (current != null && current != trace.display) {
                log("  warning: trace recorded at ${trace.display} but device is $current — set display: in .simul/config.yaml")
            }
        }
        // The observation channel itself has a version: `android layout` output changed shape
        // between CLI releases and silently starved a replay of half the screen once.
        if (mode == RunMode.REPLAY && trace?.androidCli != null) {
            val current = ops.androidCliVersion()
            if (current != null && current != trace.androidCli) {
                log("  warning: trace recorded with android CLI ${trace.androidCli} but this machine has $current — if grounding breaks, suspect the layout output format first")
            }
        }

        val reports = mutableListOf<StepReport>()
        var coordDirty = false
        var failedAt: Int? = null
        /** A section failed that the ones after it build on: they are skipped. A failed look at another screen does not set it. */
        var stop = false
        val modeName = if (mode == RunMode.LLM) "llm" else "replay"

        // A scenario without `##` headings is one unnamed part, run from the setup above.
        val sectioned = scenario.sections.isNotEmpty()
        val parts = scenario.sections.ifEmpty { listOf(Scenario.Section("", "", 0, scenario.steps.size)) }
        val sectionReports = mutableListOf<SectionReport>()
        // A trace step is found by its section and its place in it (by its place alone without sections).
        val oldBySection = trace?.steps?.groupBy { it.section }
        val newSteps = mutableListOf<TraceStep>()
        /** Did the part before this one touch the device? A preparation that found nothing to do leaves the entry screen as it was. */
        var touched = true

        for ((pi, part) in parts.withIndex()) {
            fun close(status: StepStatus) {
                if (sectioned) sectionReports += SectionReport(part.title, picks.fill(part.description), status, part.first + 1..part.first + part.size, part.preparation, part.open)
            }
            if (stop) {
                part.steps.forEach { i ->
                    reports += StepReport(i + 1, picks.fill(scenario.steps[i]), StepStatus.SKIPPED, null, emptyList(), emptyList(), null, 0, emptyList())
                    log("  step ${i + 1}: SKIPPED")
                }
                close(StepStatus.SKIPPED)
                continue
            }
            if (sectioned) log("  section ${pi + 1}/${parts.size}: ${part.title}" + if (part.preparation) " (by the model)" else "")

            // Every section after the first starts the app over — stopped and opened again at the scenario's
            // entry, its data kept ([enter]): what the one before it changed has to be there in a new process
            // (it was saved, not just drawn), and no section depends on where another ended. The exception is
            // a preparation that had nothing to do — the app still shows its entry. A section with a place of
            // its own to start ([Scenario.Open]) is opened there, the first one too.
            var entryFailure: String? = null
            if (pi > 0 || part.open != null) {
                controller.forget()
                if (touched || part.open != null) {
                    val s = System.currentTimeMillis()
                    try {
                        val (setup, via) = startOf(scenario, part)
                        setup?.let { enter(it, wantLocale, again = true) }
                        via?.let { walkIn(it, scenario.precondition) }
                    } catch (ex: Exception) {
                        entryFailure = "setup failed: ${ex.message?.lineSequence()?.firstOrNull() ?: ex.toString()}"
                    }
                    setupMs += System.currentTimeMillis() - s
                } else log("  setup: kept — the preparation changed nothing, the app is still on its entry screen")
            }
            llm.newSession(preparation = part.preparation)
            // the one part of a replay that needs the model — say so instead of crashing on the missing key
            if (entryFailure == null && part.preparation) llm.unavailable()?.let { entryFailure = "the preparation needs the model — $it" }

            val old = (if (sectioned) oldBySection?.get(part.title) else trace?.steps) ?: emptyList()
            val gestures0 = controller.meter.cost.gestures
            var broke = false
            for (i in part.steps) {
                val text = scenario.steps[i]
                val n = i + 1
                if (broke) {
                    reports += StepReport(n, picks.fill(text), StepStatus.SKIPPED, null, emptyList(), emptyList(), null, 0, emptyList())
                    log("  step $n: SKIPPED")
                    continue
                }
                log("  step $n/${scenario.steps.size}: $text")
                val s0 = System.currentTimeMillis()
                val stepCost0 = controller.meter.cost
                val report = try {
                    when {
                        // the section never got to its steps: the app did not come up again, or there is no model to prepare it
                        entryFailure != null -> StepReport(n, picks.fill(text), StepStatus.FAILED, if (part.preparation) "llm" else modeName, emptyList(), emptyList(), entryFailure, 0, emptyList())
                        part.preparation -> runPreparationStep(n, text, mode)
                        mode == RunMode.LLM -> runLlmStep(n, text)
                        else -> runReplayStep(n, text, old.getOrNull(i - part.first))
                    }
                } catch (ex: ProcessError) {
                    // The device did not do what the step needed (a dump that fails after its retries, a
                    // command past its time limit). That ends the step, not the scenario's record: the
                    // steps before it, this screen, the logcat and the recording still make a result.
                    StepReport(n, picks.fill(text), StepStatus.FAILED, if (part.preparation) "llm" else modeName, emptyList(), emptyList(),
                        "device command failed: ${ex.brief}", 0, emptyList())
                } finally {
                    listener.delegate = null
                }
                // what the screen looked like when the step ended — the report's frame for this step
                val stepShot = if (stepFrames || report.status == StepStatus.FAILED) shoot("steps/${nn(n)}") else null
                val layoutRel = saveLayout(n)
                var done = report.copy(
                    durationMs = System.currentTimeMillis() - s0,
                    startedMs = s0 - t0,
                    screenshots = report.screenshots + listOfNotNull(stepShot),
                    layout = layoutRel,
                )
                if (done.status == StepStatus.FAILED) {
                    // keep the failing screen in the report: the labels the harness could see
                    done = done.copy(screen = screenLabels(controller.currentElements))
                }
                reports += done
                log("  step $n: ${renderResult(done, controller.meter.cost - stepCost0)}")
                if (done.status == StepStatus.FAILED) { broke = true; if (failedAt == null) failedAt = n }
                else if (!part.preparation) {
                    if (done.mode == "replay" && n in coordDirtySteps) coordDirty = true
                    // the trace keeps the step as the scenario wrote it — `$name`, not the value this run picked
                    newSteps += picks.template(TraceStep(text, done.actions, done.evidence, done.stateEvidence, section = part.title.takeIf { sectioned }))
                }
            }
            touched = !part.preparation || controller.meter.cost.gestures > gestures0
            close(if (broke) StepStatus.FAILED else StepStatus.PASSED)
            if (broke) {
                if (!part.looksElsewhere) stop = true
                else if (pi < parts.lastIndex) log("  section '${part.title}' looked at another screen and failed — the sections after it run")
            }
        }

        val status = if (failedAt != null) StepStatus.FAILED else StepStatus.PASSED
        val updatedTrace = when {
            status != StepStatus.PASSED -> null
            mode == RunMode.LLM || coordDirty ->
                TraceFile(scenario.name, AGENT_VERSION, ops.appVersionName(app) ?: trace?.appVersionName,
                    newSteps, display = ops.displayProfile() ?: trace?.display,
                    androidCli = ops.androidCliVersion() ?: trace?.androidCli)
            else -> null
        }
        return ScenarioRunResult(
            scenario.name, status, reports, updatedTrace,
            System.currentTimeMillis() - t0, startedAtMs = t0,
            setupMs = setupMs, device = controller.meter.cost - cost0, values = picks.picked.toMap(),
            sections = sectionReports,
        )
    }

    /**
     * Bring the app to the scenario's entry: state restore, cold start, deeplink — and the wait for its
     * fully-drawn signal. [again] is a section's entry after the first: the account state the scenario
     * restored is not restored a second time, the app is only stopped and opened again
     * ([DeviceOps.reopen]) — a restore is what a reinstall does, and what a section follows is a user
     * who closed the app and came back. (A setup that opens nothing has nothing to reopen and runs as before.)
     */
    private fun enter(setup: Scenario.Setup, wantLocale: String?, again: Boolean = false) {
        val reopen = again && (setup.launch || setup.deeplink != null)
        val what = listOfNotNull(
            if (reopen) "restart the app (force-stop, its data kept)" else null,
            setup.appState?.takeUnless { reopen }?.let { s -> "restore app state '$s' (pm clear + snapshot)" },
            if (setup.clearData && setup.appState == null && !reopen) "clear app data (guest)" else null,
            if (setup.launch) (if (reopen) "launch" else "cold start (force-stop + launch)") else null,
            setup.deeplink?.let { d -> "deeplink $d" },
        ).joinToString(" + ").ifEmpty { "none" }
        log("  setup: $what")
        val s = System.currentTimeMillis()
        val drawn = if (reopen) ops.reopen(app, launch = setup.launch, deeplink = setup.deeplink)
                    else ops.setup(app, appState = setup.appState, clearData = setup.clearData, launch = setup.launch, deeplink = setup.deeplink, locale = wantLocale)
        val phases = ops.setupPhases.takeIf { p -> p.isNotEmpty() }?.joinToString(" · ", " = ") { (name, ms) -> "$name ${secs(ms)}" } ?: ""
        log("  setup: done (${secs(System.currentTimeMillis() - s)}$phases${drawn?.let { d -> ", app fully drawn $d" } ?: ""})")
        if (drawn == null && app != null && (setup.launch || setup.deeplink != null)) {
            // The first observation may then catch a splash (an ad interstitial was
            // grounded as the scroll container, 실측) — the fix is in the app's entry screen.
            log("  setup: app did not report fully drawn within ${AdbOps.FULLY_DRAWN_TIMEOUT_MS / 1000}s — " +
                "the destination activity should call reportFullyDrawn() on its first frame (only the first call per launch counts)")
        }
    }

    /**
     * Where [part] starts: the address to open the app at and, when the way from there is a screen's, that
     * screen. The scenario's own entry unless the section has an `deeplink:` / `entry:` line.
     */
    private fun startOf(scenario: Scenario, part: Scenario.Section): Pair<Scenario.Setup?, String?> {
        val open = part.open ?: return scenario.setup to scenario.via
        val base = scenario.setup ?: Scenario.Setup(deeplink = null, launch = false)
        open.deeplink?.let { return base.copy(deeplink = it, launch = false) to null }
        val screen = open.screen!!
        val entry = entries(screen) ?: throw IllegalStateException("section '${part.title}' starts at screen '$screen', which has no ${SimulProject.ENTRY_FILE}")
        return base.copy(deeplink = entry.setup.deeplink, launch = entry.setup.launch) to screen
    }

    /**
     * The rest of the way in to [screen] once the app is open at its entry's address: the steps of the screen's
     * `_entry.md`, played from that file's trace. The model is not asked in any mode — the entry is recorded
     * and judged as a scenario of its own, here it is only how one gets to where the scenario starts — and
     * nothing of it is among the scenario's steps or in its recording. In another account than the entry was
     * recorded in ([account]) the labels a step saw appear are that account's data: its actions and the states
     * it left (a tab selected) are still held to, the labels are not. Anything that does not play is a failed setup.
     */
    private fun walkIn(screen: String, account: String?) {
        val entry = entries(screen) ?: throw IllegalStateException("screen '$screen' has no ${SimulProject.ENTRY_FILE}")
        val trace = entry.trace ?: throw IllegalStateException("the way in to '$screen' is not recorded — run ${entry.file} with --mode llm")
        val otherAccount = account != entry.precondition
        log("  setup: the way in to '$screen' — ${entry.steps.size} step(s) from ${entry.file}" +
            if (otherAccount) " (recorded as ${entry.precondition ?: "no account"}: its labels are not asked for)" else "")
        controller.forget()
        controller.fastObserve = true
        try {
            firstObservation()
            entry.steps.forEachIndexed { i, text ->
                val recorded = trace.steps.getOrNull(i)?.takeIf { it.text == text }
                    ?: throw IllegalStateException("the way in to '$screen' changed since it was recorded — run ${entry.file} with --mode llm")
                val played = replayer.replayStep(if (otherAccount) recorded.copy(evidence = emptyList()) else recorded)
                played.broken?.let { throw IllegalStateException("the way in to '$screen' broke at its step ${i + 1} \"$text\": ${it.why}") }
            }
        } finally {
            controller.fastObserve = false
            // What the walk saw were single fast frames, the last of them possibly mid-transition. Whoever comes next
            // looks again: the model is handed the screen it starts from, and a step's evidence is what appeared since then.
            controller.forget()
        }
    }

    /**
     * One step's result line with where its time went, e.g. `PASSED (replay, 7.2s, observe 5.9s/4 dumps)`
     * or `FAILED (llm, 41s, llm 5.4s/2 calls, observe 33.1s/10 dumps (idle wait refused 8× 24.3s, 1 settle ran out), screenshots 1.9s/3) — reason`.
     */
    private fun renderResult(s: StepReport, cost: DeviceCost): String {
        val device = cost.brief().joinToString("") { ", $it" }
        return "${s.status}${s.mode?.let { " ($it, ${secs(s.durationMs)}${llmShare(s)}$device)" } ?: ""}${s.reason?.let { " — $it" } ?: ""}"
    }

    /** `, llm 12.3s/3 calls` — how much of an llm step was the model (the rest is device time). */
    private fun llmShare(s: StepReport): String =
        s.llm?.takeIf { it.calls > 0 }?.let { ", llm ${secs(it.durationMs)}/${it.calls} call${if (it.calls == 1) "" else "s"}" } ?: ""

    // ------------------------------------------------------------------ step modes

    /** Steps whose replay used a coordinate fallback — their lastCoords rewrite must be persisted. */
    private val coordDirtySteps = mutableSetOf<Int>()

    private fun runLlmStep(n: Int, written: String): StepReport {
        // The session carries the screen state across steps via each action's diff, so a fresh
        // full observation (and its ~1k prompt tokens) is only needed at conversation start.
        val initial = if (controller.currentElements.isEmpty()) firstObservation() else null
        val before = controller.currentElements
        val text = filled(written, before)
        val spec = StepSpec.parse(text)
        // record mode keeps the per-action before/after shots: they are what a reviewer of a
        // fresh trace looks at
        val recorder = StepRecorder(n, ::shoot, anchors = quotedLabels(spec.command))
        listener.delegate = recorder
        val verdict = llm.runStep(spec.command, initial, spec.criterion)
        listener.delegate = null
        val after = controller.currentElements
        val ev = extractEvidence(text, before, after, controller.volatileKeys,
            scrolled = recorder.actions.any { it.tool == "scroll" || it.tool == "scrollToFind" })
        var status = if (verdict.passed) StepStatus.PASSED else StepStatus.FAILED
        var reason = verdict.reason
        val anchors = spec.criterion?.takeUnless { isNegated(it) }?.let(::quotedLabels) ?: emptyList()
        // A criterion's quoted anchors must be machine-present, or the assertion the model just
        // passed would silently drop out of the evidence and CI would never re-check it —
        // record-time pass and replay gate must mean the same thing. (Absence criteria are
        // exempt: absence is not replay-gateable either way.)
        if (status == StepStatus.PASSED && anchors.isNotEmpty()) {
            val missing = anchors.filterNot { labelPresent(after, it) }
            if (missing.isNotEmpty()) {
                status = StepStatus.FAILED
                reason = "criterion anchor not on screen: " +
                    missing.joinToString(", ") { "\"$it\"" } + " — replay could not gate this"
            }
        }
        return StepReport(n, text, status, "llm", recorder.actions.toList(), ev.appeared,
            reason, 0, recorder.screenshots.toList(), stateEvidence = ev.states, llm = verdict.llm,
            marks = recorder.marks.toList(), expectedEvidence = anchors)
    }

    /**
     * One step of the scenario's `## 준비` section: the model brings the app to the state the sentence
     * describes — or finds it already there and does nothing — whatever the run's mode. Nothing of it
     * goes to the trace: what a preparation has to do depends on what the last run left behind, which is
     * exactly what a trace cannot know. A recording keeps the before/after frame of each action as for
     * any step of its own; a replay keeps the step-end frame only.
     */
    private fun runPreparationStep(n: Int, written: String, mode: RunMode): StepReport {
        val initial = if (controller.currentElements.isEmpty()) firstObservation() else null
        val text = filled(written, controller.currentElements)
        val spec = StepSpec.parse(text)
        val recorder = if (mode == RunMode.LLM) StepRecorder(n, ::shoot) else StepRecorder(n)
        listener.delegate = recorder
        val verdict = llm.runStep(spec.command, initial, spec.criterion)
        listener.delegate = null
        return StepReport(n, text, if (verdict.passed) StepStatus.PASSED else StepStatus.FAILED, "llm", recorder.actions.toList(), emptyList(),
            verdict.reason, 0, recorder.screenshots.toList(), llm = verdict.llm, marks = recorder.marks.toList())
    }

    /**
     * Pure deterministic replay: a step the trace cannot ground or verify FAILS, full stop —
     * no LLM fallback. A broken replay means the screen genuinely diverged from the committed
     * trace; that divergence is the test result. The fix is a human decision: re-record with
     * --mode llm (screen legitimately changed) or fix the app (regression).
     */
    private fun runReplayStep(n: Int, written: String, traceStep: TraceStep?): StepReport {
        val recorded = traceStep?.takeIf { it.text == written }
            ?: return StepReport(n, picks.fill(written), StepStatus.FAILED, "replay", emptyList(), emptyList(),
                "trace missing/stale for this step — record with --mode llm", 0, emptyList())

        // replay takes no per-action screenshots: the step-end frame (steps/NN.png) is the
        // report's picture of the step, and the recording has the motion
        val marker = StepRecorder(n)
        listener.delegate = marker
        var text = written
        var ts = recorded
        // Single-dump observations for the whole replay attempt: the Replayer retries every
        // failure after a stabilized re-observation, so the verdict stays deterministic while
        // the happy path halves its `android layout` calls.
        controller.fastObserve = true
        val replay = try {
            // Re-observe only when there is no grounding yet (scenario start). Between steps
            // the previous action already observed — an observation costs seconds on-device.
            if (controller.currentElements.isEmpty()) firstObservation()
            text = filled(written, controller.currentElements)
            ts = picks.fill(recorded)
            replayer.replayStep(ts)
        } finally {
            controller.fastObserve = false
        }
        listener.delegate = null

        val expected = ts.evidence + ts.stateEvidence.map { it.toString() }
        if (replay.broken == null) {
            if (replay.coordUpdates.isNotEmpty()) coordDirtySteps += n
            val updated = applyCoordUpdates(ts, replay.coordUpdates)
            return StepReport(n, text, StepStatus.PASSED, "replay", updated.actions, updated.evidence,
                null, 0, emptyList(), stateEvidence = updated.stateEvidence,
                marks = marker.marks.toList(), expectedEvidence = expected)
        }
        return StepReport(n, text, StepStatus.FAILED, "replay", emptyList(), expected - replay.missing.toSet(),
            "replay broken: ${replay.broken.why}", 0, emptyList(),
            marks = marker.marks.toList(), expectedEvidence = expected)
    }

    /**
     * The step as this run performs it: a `$name` of the scenario's `values:` becomes the value picked
     * for it — picked here, against [screen], when this is the first step to use it ([ValuePicks]).
     */
    private fun filled(written: String, screen: List<dev.nate.uiagent.LogicalElement>): String {
        picks.settle(written, screen)
        picks.learnQuotes(written)
        return picks.fill(written)
    }

    private fun applyCoordUpdates(ts: TraceStep, updates: Map<Int, Point>): TraceStep {
        if (updates.isEmpty()) return ts
        val actions = ts.actions.mapIndexed { i, a ->
            val c = updates[i] ?: return@mapIndexed a
            a.copy(target = a.target?.copy(lastCoords = c))
        }
        return ts.copy(actions = actions)
    }

    /**
     * The scenario's (a section's) first look at the screen, with its own log line: nothing else runs before the
     * first model call, so a landing screen that will not settle shows here and nowhere else — 33s
     * of a 45s step, visible only as a gap between two timestamps (stone run 41992964956).
     */
    private fun firstObservation(): String {
        val before = controller.meter.cost
        val t0 = System.currentTimeMillis()
        return controller.fullLayout().also {
            val cost = controller.meter.cost - before
            log("    first observation ${secs(System.currentTimeMillis() - t0)}${cost.observed()?.let { o -> " — $o" } ?: ""}")
        }
    }

    /** `01`, `02`, … — step numbers as file names sort. */
    private fun nn(n: Int) = n.toString().padStart(2, '0')

    private fun shoot(name: String): String? {
        if (!takeScreenshots) return null
        val rel = if (name.startsWith("steps/")) "$name.png" else "screenshots/$name.png"
        return controller.meter.timed(controller.meter::shot) { if (ops.screenshot(File(reportDir, rel))) rel else null }
    }

    private fun saveLayout(n: Int): String? {
        val els = controller.currentElements
        if (els.isEmpty()) return null
        val rel = "layout/${nn(n)}.json"
        return runCatching { LayoutJson.write(els, File(reportDir, rel)); rel }.getOrNull()
    }
}
