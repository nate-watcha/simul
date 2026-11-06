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
 * How one `## title` section of a scenario went: PASSED/FAILED by its steps, SKIPPED when it did not run —
 * with a [reason] when its condition did not hold on the screen it entered, without one when an earlier
 * section had already failed. [steps] are the step numbers it owns.
 */
data class SectionReport(
    val title: String,
    val description: String,
    val status: StepStatus,
    val reason: String?,
    val steps: IntRange,
    /** The section's condition as the scenario declares it ([Scenario.Condition.describe]); null when it always runs. */
    val condition: String? = null,
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
 * A scenario with `## title` sections runs them in file order, each from a fresh setup (the app
 * started over, the model's conversation too). A section with a condition runs only when the screen
 * it enters shows it and is SKIPPED otherwise — which is not a failure, unless no section ran at all.
 * Nothing else is decided here: which sections exist and which are conditional is the scenario's.
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
            setupMs = System.currentTimeMillis() - t0
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
                sections = scenario.sections.map { SectionReport(it.title, it.description, StepStatus.SKIPPED, null, it.first + 1..it.first + it.size, it.condition?.describe()) })
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
        val modeName = if (mode == RunMode.LLM) "llm" else "replay"

        // A scenario without `##` headings is one unnamed part that always runs, from the setup above.
        val sectioned = scenario.sections.isNotEmpty()
        val parts = scenario.sections.ifEmpty { listOf(Scenario.Section("", "", 0, scenario.steps.size)) }
        val sectionReports = mutableListOf<SectionReport>()
        // A trace step is found by its section and its place in it (by its place alone without sections).
        val oldBySection = trace?.steps?.groupBy { it.section }
        fun oldSteps(part: Scenario.Section): List<TraceStep> = (if (sectioned) oldBySection?.get(part.title) else trace?.steps) ?: emptyList()
        /** What this run recorded (or replayed) of each part that ran to its end, by the part's index. */
        val performed = mutableMapOf<Int, List<TraceStep>>()
        var ran = 0

        for ((pi, part) in parts.withIndex()) {
            fun skipSteps() = part.steps.forEach { i ->
                reports += StepReport(i + 1, picks.fill(scenario.steps[i]), StepStatus.SKIPPED, null, emptyList(), emptyList(), null, 0, emptyList())
                log("  step ${i + 1}: SKIPPED")
            }
            fun close(status: StepStatus, reason: String? = null) {
                if (sectioned) sectionReports += SectionReport(part.title, picks.fill(part.description), status, reason, part.first + 1..part.first + part.size, part.condition?.describe())
            }
            if (failedAt != null) { skipSteps(); close(StepStatus.SKIPPED); continue }
            if (sectioned) log("  section ${pi + 1}/${parts.size}: ${part.title}")

            // Every section after the first starts the app over: what the one before it changed has to be
            // there on a fresh entry (it was saved, not just drawn), and no section depends on where another ended.
            var entryFailure: String? = null
            if (pi > 0) {
                llm.newSession()
                controller.forget()
                entryLayout = null
                val s = System.currentTimeMillis()
                try { scenario.setup?.let { enter(it, wantLocale) } } catch (ex: Exception) {
                    entryFailure = "setup failed: ${ex.message?.lineSequence()?.firstOrNull() ?: ex.toString()}"
                }
                setupMs += System.currentTimeMillis() - s
            }
            val condition = part.condition
            if (entryFailure == null && condition != null) {
                val shown = try { entryShows(condition, mode) } catch (ex: ProcessError) {
                    entryFailure = "device command failed: ${ex.brief}"; false
                }
                if (entryFailure == null && !shown) {
                    val why = "condition not met — the entry screen has no ${condition.describe()}"
                    log("  section ${part.title}: SKIPPED — $why")
                    skipSteps()
                    close(StepStatus.SKIPPED, why)
                    continue
                }
            }
            ran++

            val newSteps = mutableListOf<TraceStep>()
            val old = oldSteps(part)
            for (i in part.steps) {
                val text = scenario.steps[i]
                val n = i + 1
                if (failedAt != null) {
                    reports += StepReport(n, picks.fill(text), StepStatus.SKIPPED, null, emptyList(), emptyList(), null, 0, emptyList())
                    log("  step $n: SKIPPED")
                    continue
                }
                log("  step $n/${scenario.steps.size}: $text")
                val s0 = System.currentTimeMillis()
                val stepCost0 = controller.meter.cost
                val report = try {
                    when {
                        // the section never got to its steps: the app did not come up again
                        entryFailure != null -> StepReport(n, picks.fill(text), StepStatus.FAILED, modeName, emptyList(), emptyList(), entryFailure, 0, emptyList())
                        mode == RunMode.LLM -> runLlmStep(n, text)
                        else -> runReplayStep(n, text, old.getOrNull(i - part.first))
                    }
                } catch (ex: ProcessError) {
                    // The device did not do what the step needed (a dump that fails after its retries, a
                    // command past its time limit). That ends the step, not the scenario's record: the
                    // steps before it, this screen, the logcat and the recording still make a result.
                    StepReport(n, picks.fill(text), StepStatus.FAILED, modeName, emptyList(), emptyList(),
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
                if (done.status == StepStatus.FAILED) failedAt = n
                else {
                    if (done.mode == "replay" && n in coordDirtySteps) coordDirty = true
                    // the trace keeps the step as the scenario wrote it — `$name`, not the value this run picked
                    newSteps += picks.template(TraceStep(text, done.actions, done.evidence, done.stateEvidence, section = part.title.takeIf { sectioned }))
                }
            }
            if (failedAt == null) performed[pi] = newSteps
            close(if (failedAt == null) StepStatus.PASSED else StepStatus.FAILED)
        }

        // Skipping a section is the scenario's doing; skipping all of them tested nothing — and the usual
        // reason is that the element the conditions look at is gone, which is the app's.
        if (failedAt == null && ran == 0) {
            val why = "no section ran — the entry screen met none of their conditions: " +
                parts.joinToString("; ") { "${it.title} (${it.condition?.describe()})" }
            log("  $why")
            val shot = shoot("steps/${nn(1)}")
            reports[0] = reports[0].copy(status = StepStatus.FAILED, mode = modeName, reason = why, startedMs = System.currentTimeMillis() - t0,
                screenshots = listOfNotNull(shot), layout = saveLayout(1), screen = screenLabels(controller.currentElements))
            sectionReports[0] = sectionReports[0].copy(status = StepStatus.FAILED)
            failedAt = 1
        }

        val status = if (failedAt != null) StepStatus.FAILED else StepStatus.PASSED
        val updatedTrace = when {
            status != StepStatus.PASSED -> null
            mode == RunMode.LLM || coordDirty -> {
                // In file order whatever ran: a section this run skipped keeps the steps an earlier recording
                // left for it, as long as they are still the steps the scenario writes.
                val steps = parts.flatMapIndexed { pi, part ->
                    performed[pi] ?: oldSteps(part).takeIf { old -> old.map { it.text } == part.steps.map { scenario.steps[it] } }
                    ?: emptyList<TraceStep>().also { log("  note: section '${part.title}' was skipped and has no recorded steps — record again when its condition holds") }
                }
                TraceFile(scenario.name, AGENT_VERSION, ops.appVersionName(app) ?: trace?.appVersionName,
                    steps, display = ops.displayProfile() ?: trace?.display,
                    androidCli = ops.androidCliVersion() ?: trace?.androidCli)
            }
            else -> null
        }
        return ScenarioRunResult(
            scenario.name, status, reports, updatedTrace,
            System.currentTimeMillis() - t0, startedAtMs = t0,
            setupMs = setupMs, device = controller.meter.cost - cost0, values = picks.picked.toMap(),
            sections = sectionReports,
        )
    }

    /** Bring the app to the scenario's entry: state restore, cold start, deeplink — and the wait for its fully-drawn signal. */
    private fun enter(setup: Scenario.Setup, wantLocale: String?) {
        val what = listOfNotNull(
            setup.appState?.let { s -> "restore app state '$s' (pm clear + snapshot)" },
            if (setup.clearData && setup.appState == null) "clear app data (guest)" else null,
            if (setup.launch) "cold start (force-stop + launch)" else null,
            setup.deeplink?.let { d -> "deeplink $d" },
        ).joinToString(" + ").ifEmpty { "none" }
        log("  setup: $what")
        val s = System.currentTimeMillis()
        val drawn = ops.setup(app, appState = setup.appState, clearData = setup.clearData, launch = setup.launch, deeplink = setup.deeplink, locale = wantLocale)
        val phases = ops.setupPhases.takeIf { p -> p.isNotEmpty() }?.joinToString(" · ", " = ") { (name, ms) -> "$name ${secs(ms)}" } ?: ""
        log("  setup: done (${secs(System.currentTimeMillis() - s)}$phases${drawn?.let { d -> ", app fully drawn $d" } ?: ""})")
        if (drawn == null && app != null && (setup.launch || setup.deeplink != null)) {
            // The first observation may then catch a splash (an ad interstitial was
            // grounded as the scroll container, 실측) — the fix is in the app's entry screen.
            log("  setup: app did not report fully drawn within ${AdbOps.FULLY_DRAWN_TIMEOUT_MS / 1000}s — " +
                "the destination activity should call reportFullyDrawn() on its first frame (only the first call per launch counts)")
        }
    }

    /** The observation [entryShows] took of a section's entry screen, for the model's first step there (it must not look twice). */
    private var entryLayout: String? = null

    /**
     * Does the screen the section entered show its condition? The entry may still be loading, so a miss
     * is polled ([DeviceController.waitUntil]) before it counts — the same way in a recording and in a
     * replay, on the layout alone.
     */
    private fun entryShows(condition: Scenario.Condition, mode: RunMode): Boolean {
        controller.fastObserve = mode == RunMode.REPLAY
        try {
            var layout = firstObservation()
            if (!condition.holds(controller.currentElements)) {
                if (!controller.waitUntil(condition::holds)) return false
                // the model reads a settled screen, not the frame the poll happened to stop on
                if (mode == RunMode.LLM) layout = firstObservation()
            }
            if (mode == RunMode.LLM) entryLayout = layout
            return true
        } finally {
            controller.fastObserve = false
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
        val initial = entryLayout?.also { entryLayout = null } ?: if (controller.currentElements.isEmpty()) firstObservation() else null
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
