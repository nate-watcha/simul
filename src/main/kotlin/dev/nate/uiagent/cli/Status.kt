package dev.nate.uiagent.cli

import dev.nate.uiagent.json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
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

enum class TraceState { NONE, OK, STALE }

data class ScenarioStatus(
    val relPath: String,
    val name: String,
    val steps: Int,
    val tags: List<String>,
    val trace: TraceState,
    /** setup.appState name, or "guest" for clearData, or null. */
    val precondition: String?,
    val lastRun: LastRun?,
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
    val scenarios = project.scenarios().map { md ->
        val s = ScenarioMd.parse(md)
        val traceFile = project.traceFileFor(md)
        ScenarioStatus(
            relPath = md.relativeTo(project.scenariosDir).path,
            name = s.name,
            steps = s.steps.size,
            tags = s.tags,
            trace = when {
                !traceFile.isFile -> TraceState.NONE
                traceFile.lastModified() < md.lastModified() -> TraceState.STALE
                else -> TraceState.OK
            },
            precondition = s.precondition,
            lastRun = latestRun(project.reportsDir, s.name),
        )
    }
    val states = (project.statesDir.listFiles { f -> f.isFile && f.extension == "tar" } ?: emptyArray())
        .map { StateFile(it.nameWithoutExtension, it.length(), it.lastModified()) }
        .sortedBy { it.name }
    return StatusReport(scenarios, states)
}

/** Newest report for [name]: dirs are `<name>-yyyyMMdd-HHmmss` — exact-stamp match so a
 *  scenario whose name prefixes another's never steals its reports. */
private fun latestRun(reportsDir: File, name: String): LastRun? {
    val stamp = Regex("^${Regex.escape(name)}-\\d{8}-\\d{6}$")
    val dir = (reportsDir.listFiles { f -> f.isDirectory && stamp.matches(f.name) } ?: return null)
        .maxByOrNull { it.name } ?: return null
    val report = File(dir, "report.json").takeIf { it.isFile } ?: return null
    val o = runCatching { json.parseToJsonElement(report.readText()).jsonObject }.getOrNull() ?: return null
    return LastRun(
        passed = o["status"]?.jsonPrimitive?.contentOrNull == "PASSED",
        mode = o["mode"]?.jsonPrimitive?.contentOrNull ?: "?",
        at = report.lastModified(),
        durationMs = o["durationMs"]?.jsonPrimitive?.longOrNull,
    )
}

// -------------------------------------------------------------------- rendering

fun renderStatus(r: StatusReport, now: Long = System.currentTimeMillis(), color: Boolean = true): String {
    fun c(code: String, s: String) = if (color) "\u001B[${code}m$s\u001B[0m" else s
    val sb = StringBuilder()
    if (r.scenarios.isEmpty()) {
        sb.appendLine("no scenarios — write one under .simul/scenarios/ (see .claude/skills/simul-scenarios/references/scenarios.md)")
    } else {
        val pathW = maxOf(8, r.scenarios.maxOf { it.relPath.length })
        val preW = maxOf(5, r.scenarios.maxOf { (it.precondition ?: "-").length })
        sb.appendLine(
            "SCENARIO".padEnd(pathW) + "  STEPS  TRACE   LAST RUN            " +
                "STATE".padEnd(preW) + "  TAGS"
        )
        for (s in r.scenarios) {
            val trace = when (s.trace) {
                TraceState.OK -> c("32", "ok    ")
                TraceState.STALE -> c("33", "stale!")
                TraceState.NONE -> c("90", "none  ")
            }
            val run = s.lastRun?.let {
                val mark = if (it.passed) c("32", "✓ PASSED") else c("31", "✗ FAILED")
                "$mark ${ago(now - it.at).padEnd(8)}"
            } ?: c("90", "never".padEnd(17))
            sb.appendLine(
                s.relPath.padEnd(pathW) + "  " + s.steps.toString().padEnd(5) + "  " + trace + "  " +
                    run + "  " + (s.precondition ?: "-").padEnd(preW) + "  " + s.tags.joinToString(",")
            )
        }
    }
    sb.appendLine()
    sb.append("states: ")
    if (r.states.isEmpty()) sb.append(c("90", "(none — create with `simul state save <name>`)"))
    else sb.append(r.states.joinToString("  ") { "${it.name} (${it.sizeBytes / 1024}KB, ${ago(now - it.modifiedAt)})" })
    return sb.toString()
}

/** The primary grouping axis is the SCREEN/FEATURE — the scenario's top-level directory.
 *  States cut across screens, so they stay per-row metadata (precondition chip), not groups. */
fun featureGroup(relPath: String): String = relPath.substringBefore('/', "(root)")

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

fun ago(deltaMs: Long): String {
    val m = deltaMs / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "${m}m ago"
        m < 60 * 24 -> "${m / 60}h ago"
        else -> "${m / (60 * 24)}d ago"
    }
}
