package dev.nate.uiagent.cli

import dev.nate.uiagent.device.SettingChange
import dev.nate.uiagent.device.DeviceSettings
import dev.nate.uiagent.device.DeviceOps
import dev.nate.uiagent.Point
import dev.nate.uiagent.agent.LlmStats
import dev.nate.uiagent.secs
import dev.nate.uiagent.device.DeviceController
import java.io.File

enum class RunMode { LLM, REPLAY, AUTO }

enum class StepStatus { PASSED, FAILED, SKIPPED }

data class StepReport(
    val index: Int,
    val text: String,
    val status: StepStatus,
    /** "llm", "replay" or "runner"; null for skipped steps. */
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
    private val takeScreenshots: Boolean = true,
    /** Live progress sink: step start/result lines as they happen (a scenario can run for minutes). */
    private val log: (String) -> Unit = {},
) {
    private val replayer by lazy { Replayer(controller, log = log) }

    fun run(scenario: Scenario, trace: TraceFile?, mode: RunMode): ScenarioRunResult {
        require(mode != RunMode.AUTO) { "AUTO must be resolved to LLM/REPLAY (or skip) by the caller" }
        // A scenario's own device settings last exactly as long as the scenario: whatever
        // happens inside (setup failure, step FAILED, harness exception), the next scenario
        // starts from the baseline the run began with.
        val pinned = mutableListOf<SettingChange>()
        // logcat for exactly this scenario's span: cleared before setup, dumped after the last step
        runCatching { ops.logcatClear() }
        try {
            return runPinned(scenario, trace, mode, pinned)
        } finally {
            runCatching { ops.logcatDump(File(reportDir, "logcat.txt")) }
            if (pinned.isNotEmpty()) DeviceSettings.restore(ops, pinned) { log("  device: $it") }
        }
    }

    private fun runPinned(scenario: Scenario, trace: TraceFile?, mode: RunMode, pinned: MutableList<SettingChange>): ScenarioRunResult {
        val t0 = System.currentTimeMillis()

        // A broken setup (bad deeplink, adb hiccup) fails THIS scenario — it must never kill
        // the process and take the rest of a --all batch with it.
        try {
            scenario.setup?.let {
                // before the cold start, so the app reads the pinned values
                DeviceSettings.apply(ops, it.device, { msg -> log("  device: $msg") }, pinned)
                val what = listOfNotNull(
                    it.appState?.let { s -> "restore app state '$s' (pm clear + snapshot)" },
                    if (it.clearData && it.appState == null) "clear app data (guest)" else null,
                    if (it.launch) "cold start (force-stop + launch + splash wait)" else null,
                    it.deeplink?.let { d -> "deeplink $d" },
                ).joinToString(" + ").ifEmpty { "none" }
                log("  setup: $what")
                val s = System.currentTimeMillis()
                ops.setup(app, appState = it.appState, clearData = it.clearData, launch = it.launch, deeplink = it.deeplink)
                log("  setup: done (${secs(System.currentTimeMillis() - s)})")
            }
        } catch (ex: Exception) {
            val why = "setup failed: ${ex.message?.lineSequence()?.firstOrNull() ?: ex.toString()}"
            log("  $why")
            val steps = scenario.steps.mapIndexed { i, text ->
                StepReport(i + 1, text, StepStatus.SKIPPED, null, emptyList(), emptyList(),
                    if (i == 0) why else null, 0, emptyList())
            }
            return ScenarioRunResult(scenario.name, StepStatus.FAILED, steps, null,
                System.currentTimeMillis() - t0, startedAtMs = t0, setupFailed = true)
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
        val newSteps = mutableListOf<TraceStep>()
        var coordDirty = false
        var failedAt: Int? = null

        for ((i, text) in scenario.steps.withIndex()) {
            val n = i + 1
            if (failedAt != null) {
                reports += StepReport(n, text, StepStatus.SKIPPED, null, emptyList(), emptyList(), null, 0, emptyList())
                log("  step $n: SKIPPED")
                continue
            }
            log("  step $n/${scenario.steps.size}: $text")
            val s0 = System.currentTimeMillis()
            val report = try {
                when (mode) {
                    RunMode.LLM -> runLlmStep(n, text)
                    else -> runReplayStep(n, text, trace?.steps?.getOrNull(i))
                }
            } finally {
                listener.delegate = null
            }
            // what the screen looked like when the step ended — the report's frame for this step
            val stepShot = shoot("steps/${nn(n)}")
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
            log("  step $n: ${renderResult(done)}")
            if (done.status == StepStatus.FAILED) failedAt = n
            else {
                if (done.mode == "replay" && n in coordDirtySteps) coordDirty = true
                newSteps += TraceStep(text, done.actions, done.evidence, done.stateEvidence)
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
        )
    }

    /** One step's result line, e.g. `PASSED (replay, 7.2s)` or `FAILED (llm, 41s) — reason`. */
    private fun renderResult(s: StepReport): String =
        "${s.status}${s.mode?.let { " ($it, ${secs(s.durationMs)}${llmShare(s)})" } ?: ""}${s.reason?.let { " — $it" } ?: ""}"

    /** `, llm 12.3s/3 calls` — how much of an llm step was the model (the rest is device time). */
    private fun llmShare(s: StepReport): String =
        s.llm?.takeIf { it.calls > 0 }?.let { ", llm ${secs(it.durationMs)}/${it.calls} call${if (it.calls == 1) "" else "s"}" } ?: ""

    // ------------------------------------------------------------------ step modes

    /** Steps whose replay used a coordinate fallback — their lastCoords rewrite must be persisted. */
    private val coordDirtySteps = mutableSetOf<Int>()

    private fun runLlmStep(n: Int, text: String): StepReport {
        val spec = StepSpec.parse(text)
        if (spec.isVerify) {
            runnerVerifyStep(n, text)?.let { return it }
            log("  verify has no quoted anchor — falling back to LLM judgment")
        }
        // record mode keeps the per-action before/after shots: they are what a reviewer of a
        // fresh trace looks at
        val recorder = StepRecorder(n, ::shoot)
        listener.delegate = recorder
        // The session carries the screen state across steps via each action's diff, so a fresh
        // full observation (and its ~1k prompt tokens) is only needed at conversation start.
        val initial = if (controller.currentElements.isEmpty()) controller.fullLayout() else null
        val before = controller.currentElements
        val verdict = llm.runStep(spec.command, initial, spec.criterion)
        listener.delegate = null
        val after = controller.currentElements
        val ev = extractEvidence(text, before, after, controller.volatileKeys)
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
     * Verify steps with quoted anchors are judged by the runner itself — replay's predicates
     * (labelPresent/statePresent) applied at record time, zero LLM turns, nothing appended to
     * the session. Returns null when the step is not machine-checkable (no quoted anchor);
     * the caller falls back to the LLM.
     */
    private fun runnerVerifyStep(n: Int, text: String): StepReport? {
        if (controller.currentElements.isEmpty()) {
            log("  observing initial screen (~1s/dump)…")
            controller.fullLayout()
        }
        var judgment = judgeVerify(text, controller.currentElements) ?: return null
        if (!judgment.passed) {
            // the previous action settled, but async content may land after it — poll for the
            // anchor before declaring it missing (mirrors the Replayer's evidence wait)
            log("  verify missed (${judgment.reason}) — waiting for it…")
            controller.waitUntil { judgeVerify(text, it)?.passed == true }
            judgment = judgeVerify(text, controller.currentElements) ?: return null
        }
        val els = controller.currentElements
        val ev = extractEvidence(text, els, els, controller.volatileKeys)
        val status = if (judgment.passed) StepStatus.PASSED else StepStatus.FAILED
        return StepReport(n, text, status, "runner", emptyList(), ev.appeared,
            judgment.reason, 0, emptyList(), stateEvidence = ev.states, expectedEvidence = quotedLabels(text))
    }

    /**
     * Pure deterministic replay: a step the trace cannot ground or verify FAILS, full stop —
     * no LLM fallback. A broken replay means the screen genuinely diverged from the committed
     * trace; that divergence is the test result. The fix is a human decision: re-record with
     * --mode llm (screen legitimately changed) or fix the app (regression).
     */
    private fun runReplayStep(n: Int, text: String, traceStep: TraceStep?): StepReport {
        val ts = traceStep?.takeIf { it.text == text }
            ?: return StepReport(n, text, StepStatus.FAILED, "replay", emptyList(), emptyList(),
                "trace missing/stale for this step — record with --mode llm", 0, emptyList())

        // replay takes no per-action screenshots: the step-end frame (steps/NN.png) is the
        // report's picture of the step, and the recording has the motion
        val marker = StepRecorder(n)
        listener.delegate = marker
        // Single-dump observations for the whole replay attempt: the Replayer retries every
        // failure after a stabilized re-observation, so the verdict stays deterministic while
        // the happy path halves its `android layout` calls.
        controller.fastObserve = true
        val replay = try {
            // Re-observe only when there is no grounding yet (scenario start). Between steps
            // the previous action already observed — an observation costs seconds on-device.
            if (controller.currentElements.isEmpty()) {
                log("  observing initial screen (~1s/dump)…")
                controller.fullLayout()
            }
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

    private fun applyCoordUpdates(ts: TraceStep, updates: Map<Int, Point>): TraceStep {
        if (updates.isEmpty()) return ts
        val actions = ts.actions.mapIndexed { i, a ->
            val c = updates[i] ?: return@mapIndexed a
            a.copy(target = a.target?.copy(lastCoords = c))
        }
        return ts.copy(actions = actions)
    }

    /** `01`, `02`, … — step numbers as file names sort. */
    private fun nn(n: Int) = n.toString().padStart(2, '0')

    private fun shoot(name: String): String? {
        if (!takeScreenshots) return null
        val rel = if (name.startsWith("steps/")) "$name.png" else "screenshots/$name.png"
        return if (ops.screenshot(File(reportDir, rel))) rel else null
    }

    private fun saveLayout(n: Int): String? {
        val els = controller.currentElements
        if (els.isEmpty()) return null
        val rel = "layout/${nn(n)}.json"
        return runCatching { LayoutJson.write(els, File(reportDir, rel)); rel }.getOrNull()
    }
}
