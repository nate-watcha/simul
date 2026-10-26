package dev.nate.uiagent.cli

import dev.nate.uiagent.device.DeviceOps
import dev.nate.uiagent.device.DeviceSettings
import dev.nate.uiagent.device.DisplayProfile
import dev.nate.uiagent.device.SettingChange
import java.io.File

/** One scenario's place in a batch: the summary row, and the full result when it actually ran. */
class BatchResult(val outcome: ScenarioOutcome, val result: ScenarioRunResult?)

/**
 * A batch of scenario runs against one device — what `simul run` and the TUI's run panel share.
 * [pin] forces the configured display profile and `settings` baseline for the whole batch;
 * [close] puts both back exactly as they were (a developer's own `wm` override included), whatever
 * happened in between. [run] turns the three non-results — no trace to replay, a harness crash, a
 * scenario SKIPPED by `auto` mode — into outcome rows, so one broken scenario never ends the batch.
 */
class BatchSession(
    private val project: SimulProject,
    private val ops: DeviceOps,
    private val log: (String) -> Unit,
    /** `--runs <dir>`: lay report dirs out for the report builder (see ReportDirs.inRuns); null = stamped dirs under .simul/reports. */
    private val runsDir: File? = null,
) : AutoCloseable {
    private var displayBefore: String? = null
    private val pinned = mutableListOf<SettingChange>()

    fun pin() {
        // Never touch a physical device's screen, and read the current value BEFORE forcing it.
        val display = project.config.display
        if (display != null) {
            val current = ops.displayProfile()
            when {
                !ops.isEmulator() -> log("warning: display profile $display ignored — target is not an emulator")
                current == display.toString() -> {} // already there, nothing to restore
                else -> {
                    ops.setDisplay(display)
                    displayBefore = current ?: "unknown"
                    log("display: forced to $display (${display.dpWidth}dp wide) — restored on exit")
                }
            }
        }
        // Same for the emulator `settings` baseline: change only what differs, restore on exit.
        // (The app locale baseline is per scenario — `pm clear` wipes it — see ScenarioRunner.)
        DeviceSettings.apply(ops, project.config.device.settings, { log("device: $it — restored on exit") }, pinned)
    }

    /**
     * Run [scenario] in [mode] (AUTO = replay when a trace exists, else SKIPPED). [scenarioLog]
     * receives the live step lines; the batch-level [log] gets the skip/crash summary.
     */
    fun run(
        md: File,
        scenario: Scenario,
        mode: RunMode,
        llm: LlmConfig,
        video: Boolean = false,
        scenarioLog: (String) -> Unit = log,
    ): BatchResult {
        val traceFile = project.traceFileFor(md, scenario)
        val trace = traceFile.takeIf { it.isFile }?.let { runCatching { TraceJson.read(it) }.getOrNull() }
        val effective = if (mode == RunMode.AUTO) (if (trace != null) RunMode.REPLAY else null) else mode
        if (effective == null || (effective == RunMode.REPLAY && trace == null)) {
            val why = if (mode == RunMode.AUTO) "no trace — authoring incomplete (record with --mode llm)"
                      else "no trace to replay (record with --mode llm)"
            log("SKIPPED ${scenario.name}: $why")
            return BatchResult(outcomeWithout(project, md, scenario, "SKIPPED", "no trace"), null)
        }
        if (scenario.precondition == null) {
            log("note: ${scenario.name} declares no appState/clearData — runs against the device's current state")
        }
        // A harness/environment crash (OOM, classpath swapped under a live JVM, adb vanishing)
        // must not kill the rest of the batch — record it and move on.
        val result = try {
            executeScenario(project, md, scenario, trace, effective, llm, traceFile, ops, scenarioLog, video, runsDir)
        } catch (t: Throwable) {
            val crash = writeCrashLog(project.reportsDir, scenario.name, t)
            val what = "${t.javaClass.simpleName}: ${t.message ?: ""}".trim()
            log("CRASH ${scenario.name}: $what")
            log("  stack trace → ${crash.relativeTo(project.appRoot)}")
            return BatchResult(outcomeWithout(project, md, scenario, "CRASH", what, crashLog = crash), null)
        }
        return BatchResult(outcomeOf(project, md, scenario, result), result)
    }

    override fun close() {
        if (pinned.isNotEmpty()) DeviceSettings.restore(ops, pinned) { log("device: $it") }
        displayBefore?.let { before ->
            val prior = DisplayProfile.parse(before)
            if (prior != null) ops.setDisplay(prior) else ops.resetDisplay()
            log("display: restored to ${if (prior != null) before else "device default"}")
        }
    }
}
