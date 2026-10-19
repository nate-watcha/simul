package dev.nate.uiagent.cli

import dev.nate.uiagent.ago
import java.io.File

/**
 * The project-at-a-glance data model: every scenario joined with its trace freshness, its
 * declared precondition, and the latest run result mined from `.simul/reports/`. This is what
 * `simul status` prints and what the interactive TUI renders as its home screen — the answer
 * to "what exists and what is broken" that plain directories don't make explicit.
 */
data class StatusReport(
    val scenarios: List<ScenarioStatus>,
    val states: List<StateFile>,
)

enum class TraceState {
    NONE, OK, STALE;

    companion object {
        /** A trace older than its scenario file is stale: the steps may have changed since it was recorded. */
        fun of(md: File, traceFile: File): TraceState = when {
            !traceFile.isFile -> NONE
            traceFile.lastModified() < md.lastModified() -> STALE
            else -> OK
        }
    }
}

data class ScenarioStatus(
    val relPath: String,
    val name: String,
    val steps: Int,
    val tags: List<String>,
    val trace: TraceState,
    /** setup.appState name, or "guest" for clearData, or null. */
    val precondition: String?,
    val lastRun: LastRun?,
    /** The variant of a `states:` scenario this row is (its trace/report identity); null otherwise. */
    val state: String? = null,
)

data class LastRun(
    val passed: Boolean,
    val mode: String,
    /** Epoch millis of the run (report dir mtime — robust across report format versions). */
    val at: Long,
    val durationMs: Long?,
)

data class StateFile(val name: String, val sizeBytes: Long, val modifiedAt: Long)

fun collectStatus(project: SimulProject): StatusReport {
    val scenarios = project.scenarioVariants().map { (md, s) ->
        val traceFile = project.traceFileFor(md, s)
        ScenarioStatus(
            relPath = md.relativeTo(project.scenariosDir).path,
            name = s.name,
            steps = s.steps.size,
            tags = s.tags,
            trace = TraceState.of(md, traceFile),
            precondition = s.precondition,
            lastRun = latestRun(project.reportsDir, s.name),
            state = s.state,
        )
    }
    val states = (project.statesDir.listFiles { f -> f.isFile && f.extension == "tar" } ?: emptyArray())
        .map { StateFile(it.nameWithoutExtension, it.length(), it.lastModified()) }
        .sortedBy { it.name }
    return StatusReport(scenarios, states)
}

private fun latestRun(reportsDir: File, name: String): LastRun? =
    ReportDirs.latest(reportsDir, name)?.let { File(it, ResultJson.FILE) }?.takeIf { it.isFile }?.let(ResultJson::readLastRun)

// -------------------------------------------------------------------- rendering

fun renderStatus(r: StatusReport, now: Long = System.currentTimeMillis(), color: Boolean = true): String {
    fun c(style: (String) -> String, s: String) = if (color) style(s) else s
    val sb = StringBuilder()
    if (r.scenarios.isEmpty()) {
        sb.appendLine("no scenarios — write one under .simul/scenarios/ (see .claude/skills/simul-scenarios/SKILL.md)")
    } else {
        val pathW = maxOf(8, r.scenarios.maxOf { TuiText.width(it.relPath) })
        val preW = maxOf(5, r.scenarios.maxOf { TuiText.width(it.precondition ?: "-") })
        sb.appendLine(TuiText.pad("SCENARIO", pathW) + "  STEPS  TRACE   LAST RUN            " + TuiText.pad("STATE", preW) + "  TAGS")
        for (s in r.scenarios) {
            val trace = when (s.trace) {
                TraceState.OK -> c(TuiText::green, "ok    ")
                TraceState.STALE -> c(TuiText::yellow, "stale!")
                TraceState.NONE -> c(TuiText::dim, "none  ")
            }
            val run = s.lastRun?.let {
                val mark = if (it.passed) c(TuiText::green, "✓ PASSED") else c(TuiText::red, "✗ FAILED")
                "$mark ${TuiText.pad(ago(now - it.at), 8)}"
            } ?: c(TuiText::dim, TuiText.pad("never", 17))
            sb.appendLine(
                TuiText.pad(s.relPath, pathW) + "  " + TuiText.pad(s.steps.toString(), 5) + "  " + trace + "  " +
                    run + "  " + TuiText.pad(s.precondition ?: "-", preW) + "  " + s.tags.joinToString(",")
            )
        }
    }
    sb.appendLine()
    sb.append("states: ")
    if (r.states.isEmpty()) sb.append(c(TuiText::dim, "(none — create with `simul state save <name>`)"))
    else sb.append(r.states.joinToString("  ") { "${it.name} (${it.sizeBytes / 1024}KB, ${ago(now - it.modifiedAt)})" })
    return sb.toString()
}

/**
 * A feature area = the scenario's directory (`billing/play/x.md@member` → `billing/play`; root-level
 * scenarios → ""). The report's tiles, the TUI's list headers and its "run group" key all cut by it.
 * States cut across areas, so they stay per-row metadata (the precondition chip), never groups.
 */
fun areaOf(relPath: String): String = relPath.substringBeforeLast('/', "")
