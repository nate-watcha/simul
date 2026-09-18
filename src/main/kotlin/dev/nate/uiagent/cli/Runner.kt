package dev.nate.uiagent.cli

import dev.nate.uiagent.Point
import dev.nate.uiagent.device.DeviceController
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

enum class RunMode { LLM, REPLAY, AUTO }

enum class StepStatus { PASSED, FAILED, SKIPPED }

data class StepReport(
    val index: Int,
    val text: String,
    val status: StepStatus,
    /** "llm" or "replay"; null for skipped steps. */
    val mode: String?,
    val actions: List<TraceAction>,
    val evidence: List<String>,
    val reason: String?,
    val durationMs: Long,
    val screenshots: List<String>,
    val stateEvidence: List<StateEvidence> = emptyList(),
)

data class ScenarioRunResult(
    val scenario: String,
    val status: StepStatus,
    val steps: List<StepReport>,
    /** Trace to persist — full re-record (llm) or coord-corrected replay. Null if nothing to write. */
    val updatedTrace: TraceFile?,
    val durationMs: Long,
) {
    val passed get() = status == StepStatus.PASSED
}

/**
 * The journeys.md execution contract, deterministically: run setup, execute steps in order
 * with per-step isolation, propagate the first FAILED as SKIPPED for the rest, and emit the
 * report. Mode decides how a step runs: llm (record) or replay (pure trace playback — the LLM
 * is never consulted; a broken step is a FAILED result).
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
        val t0 = System.currentTimeMillis()
        val modeName = if (mode == RunMode.LLM) "llm" else "replay"

        // A broken setup (bad deeplink, adb hiccup) fails THIS scenario — it must never kill
        // the process and take the rest of a --all batch with it.
        try {
            scenario.setup?.let {
                val what = listOfNotNull(
                    it.appState?.let { s -> "restore app state '$s' (pm clear + snapshot)" },
                    if (it.clearData && it.appState == null) "clear app data (guest)" else null,
                    if (it.launch) "cold start (force-stop + launch + splash wait)" else null,
                    it.deeplink?.let { d -> "deeplink $d" },
                ).joinToString(" + ").ifEmpty { "none" }
                log("  setup: $what")
                val s = System.currentTimeMillis()
                ops.setup(it, app)
                log("  setup: done (${sec(System.currentTimeMillis() - s)})")
            }
        } catch (ex: Exception) {
            val why = "setup failed: ${ex.message?.lineSequence()?.firstOrNull() ?: ex.toString()}"
            log("  $why")
            val steps = scenario.steps.mapIndexed { i, text ->
                StepReport(i + 1, text, StepStatus.SKIPPED, null, emptyList(), emptyList(),
                    if (i == 0) why else null, 0, emptyList())
            }
            val result = ScenarioRunResult(scenario.name, StepStatus.FAILED, steps, null,
                System.currentTimeMillis() - t0)
            writeReport(result, modeName)
            return result
        }

        // A trace replays coordinates and layouts of the display it was recorded on — if the
        // current display differs, warn before anything breaks (fix: `display:` in config.yaml).
        if (mode == RunMode.REPLAY && trace?.display != null) {
            val current = ops.displayProfile()
            if (current != null && current != trace.display) {
                log("  warning: trace recorded at ${trace.display} but device is $current — set display: in .simul/config.yaml")
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
            val done = report.copy(durationMs = System.currentTimeMillis() - s0)
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
                    newSteps, display = ops.displayProfile() ?: trace?.display)
            else -> null
        }
        val result = ScenarioRunResult(
            scenario.name, status, reports, updatedTrace,
            System.currentTimeMillis() - t0,
        )
        writeReport(result, modeName)
        return result
    }

    /** One step's result line, e.g. `PASSED (replay, 7.2s)` or `FAILED (llm, 41s) — reason`. */
    private fun renderResult(s: StepReport): String =
        "${s.status}${s.mode?.let { " ($it, ${sec(s.durationMs)})" } ?: ""}${s.reason?.let { " — $it" } ?: ""}"

    private fun sec(ms: Long): String = "%.1fs".format(ms / 1000.0)

    // ------------------------------------------------------------------ step modes

    /** Steps whose replay used a coordinate fallback — their lastCoords rewrite must be persisted. */
    private val coordDirtySteps = mutableSetOf<Int>()

    private fun runLlmStep(n: Int, text: String): StepReport {
        val spec = StepSpec.parse(text)
        if (spec.isVerify) {
            runnerVerifyStep(n, text)?.let { return it }
            log("  verify has no quoted anchor — falling back to LLM judgment")
        }
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
        // A criterion's quoted anchors must be machine-present, or the assertion the model just
        // passed would silently drop out of the evidence and CI would never re-check it —
        // record-time pass and replay gate must mean the same thing. (Absence criteria are
        // exempt: absence is not replay-gateable either way.)
        if (status == StepStatus.PASSED && spec.criterion != null && !isNegated(spec.criterion)) {
            val missing = quotedLabels(spec.criterion).filterNot { labelPresent(after, it) }
            if (missing.isNotEmpty()) {
                status = StepStatus.FAILED
                reason = "criterion anchor not on screen: " +
                    missing.joinToString(", ") { "\"$it\"" } + " — replay could not gate this"
            }
        }
        return StepReport(n, text, status, "llm", recorder.actions.toList(), ev.appeared,
            reason, 0, recorder.screenshots.toList(), stateEvidence = ev.states)
    }

    /**
     * Verify steps with quoted anchors are judged by the runner itself — replay's predicates
     * (labelPresent/statePresent) applied at record time, zero LLM turns, nothing appended to
     * the session. Returns null when the step is not machine-checkable (no quoted anchor);
     * the caller falls back to the LLM.
     */
    private fun runnerVerifyStep(n: Int, text: String): StepReport? {
        if (controller.currentElements.isEmpty()) {
            log("  observing initial screen (~3.5s/dump)…")
            controller.fullLayout()
        }
        var judgment = judgeVerify(text, controller.currentElements) ?: return null
        if (!judgment.passed) {
            // the previous action settled, but async content may have landed after it — settle
            // once more before declaring the anchor missing (mirrors the Replayer's retry)
            log("  verify missed (${judgment.reason}) — re-observing until stable…")
            controller.restabilize()
            judgment = judgeVerify(text, controller.currentElements) ?: return null
        }
        val els = controller.currentElements
        val ev = extractEvidence(text, els, els, controller.volatileKeys)
        val status = if (judgment.passed) StepStatus.PASSED else StepStatus.FAILED
        return StepReport(n, text, status, "runner", emptyList(), ev.appeared,
            judgment.reason, 0, emptyList(), stateEvidence = ev.states)
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

        // screenshots during replay come from the same listener; its recorded actions are unused
        val shotsOnly = StepRecorder(n, ::shoot)
        listener.delegate = shotsOnly
        // Single-dump observations for the whole replay attempt: the Replayer retries every
        // failure after a stabilized re-observation, so the verdict stays deterministic while
        // the happy path halves its `android layout` calls.
        controller.fastObserve = true
        val replay = try {
            // Re-observe only when there is no grounding yet (scenario start). Between steps
            // the previous action already observed — an observation costs seconds on-device.
            if (controller.currentElements.isEmpty()) {
                log("  observing initial screen (~3.5s/dump)…")
                controller.fullLayout()
            }
            replayer.replayStep(ts)
        } finally {
            controller.fastObserve = false
        }
        listener.delegate = null

        if (replay.broken == null) {
            if (replay.coordUpdates.isNotEmpty()) coordDirtySteps += n
            val updated = applyCoordUpdates(ts, replay.coordUpdates)
            return StepReport(n, text, StepStatus.PASSED, "replay", updated.actions, updated.evidence,
                null, 0, shotsOnly.screenshots.toList(), stateEvidence = updated.stateEvidence)
        }
        return StepReport(n, text, StepStatus.FAILED, "replay", emptyList(), emptyList(),
            "replay broken: ${replay.broken.why}", 0, shotsOnly.screenshots.toList())
    }

    private fun applyCoordUpdates(ts: TraceStep, updates: Map<Int, Point>): TraceStep {
        if (updates.isEmpty()) return ts
        val actions = ts.actions.mapIndexed { i, a ->
            val c = updates[i] ?: return@mapIndexed a
            a.copy(target = a.target?.copy(lastCoords = c))
        }
        return ts.copy(actions = actions)
    }

    private fun shoot(name: String): String? {
        if (!takeScreenshots) return null
        val rel = "screenshots/$name.png"
        return if (ops.screenshot(File(reportDir, rel))) rel else null
    }

    // ------------------------------------------------------------------ report

    @OptIn(ExperimentalSerializationApi::class)
    private val reportJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

    private fun writeReport(r: ScenarioRunResult, mode: String) {
        val obj: JsonObject = buildJsonObject {
            put("scenario", r.scenario)
            put("status", r.status.name)
            put("mode", mode)
            put("startedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(Date()))
            put("durationMs", r.durationMs)
            put("env", buildJsonObject {
                put("agentVersion", AGENT_VERSION)
                put("appVersionName", ops.appVersionName(app)?.let(::JsonPrimitive) ?: JsonNull)
                put("device", ops.deviceName()?.let(::JsonPrimitive) ?: JsonNull)
            })
            put("steps", buildJsonArray {
                r.steps.forEach { s ->
                    add(buildJsonObject {
                        put("step", s.index)
                        put("text", s.text)
                        put("status", s.status.name)
                        put("mode", s.mode?.let(::JsonPrimitive) ?: JsonNull)
                        put("actions", buildJsonArray { s.actions.forEach { add(TraceJson.actionJson(it)) } })
                        put("evidence", buildJsonArray {
                            s.evidence.forEach { add(JsonPrimitive(it)) }
                            s.stateEvidence.forEach { add(JsonPrimitive(it.toString())) } // "label=state"
                        })
                        put("reason", s.reason?.let(::JsonPrimitive) ?: JsonNull)
                        put("durationMs", s.durationMs)
                        put("screenshots", buildJsonArray { s.screenshots.forEach { add(JsonPrimitive(it)) } })
                    })
                }
            })
        }
        reportDir.mkdirs()
        File(reportDir, "report.json").writeText(reportJson.encodeToString(JsonObject.serializer(), obj) + "\n")
    }
}
