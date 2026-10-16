package dev.nate.uiagent.cli

import dev.nate.uiagent.agent.llmJson
import dev.nate.uiagent.agent.llmFromJson
import dev.nate.uiagent.agent.LlmStats
import dev.nate.uiagent.arr
import dev.nate.uiagent.bool
import dev.nate.uiagent.int
import dev.nate.uiagent.json
import dev.nate.uiagent.long
import dev.nate.uiagent.obj
import dev.nate.uiagent.prettyJson
import dev.nate.uiagent.str
import dev.nate.uiagent.strList
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * The batch-level record of one `simul run` invocation (`--summary <file>`): every scenario
 * the batch touched with its outcome, so a CI pipeline can join several runs — the nightly
 * baseline replay, the LLM re-recording, the replay of what was just recorded — into one
 * report (`simul report`). Per-scenario detail (screenshots, agent.jsonl) stays in the
 * scenario's report dir, which the summary points at.
 */
data class RunSummary(
    /** Phase name a report shows as a column, e.g. baseline / record / verify. */
    val label: String,
    /** Requested mode: replay | llm | auto. */
    val mode: String,
    val startedAt: String,
    val durationMs: Long,
    val agentVersion: String,
    val appVersionName: String?,
    val device: String?,
    val scenarios: List<ScenarioOutcome>,
    /** `android` CLI version on the machine that ran the batch (observation format). */
    val androidCli: String? = null,
)

/** PASSED | FAILED | SKIPPED | CRASH — the scenario-level result inside a batch. */
data class ScenarioOutcome(
    val name: String,
    /** Scenario file relative to `.simul/scenarios/` — the stable identity across runs. */
    val path: String,
    val tags: List<String>,
    val status: String,
    /** Effective mode this scenario ran in (replay | llm); null when it did not run. */
    val mode: String?,
    val durationMs: Long,
    /** Report dir relative to the app root; null when nothing ran. */
    val reportDir: String?,
    /** True when this run wrote `<name>.trace.json` (recording, or a lastCoords rewrite). */
    val traceUpdated: Boolean,
    val passedSteps: Int,
    val failedSteps: Int,
    val skippedSteps: Int,
    val failedStep: FailedStep?,
    /** Why a SKIPPED/CRASH scenario did not complete. */
    val reason: String?,
    /** Model calls/time/tokens when the scenario consulted the LLM (record phase); null otherwise. */
    val llm: LlmStats? = null,
    /** Screen recording of the run as a GIF (`--gif`), relative to the app root; null when not recorded. */
    val gif: String? = null,
    /** The scenario's one-line prose (what the journey checks) — what a report shows as its title. */
    val description: String = "",
) {
    val passed get() = status == "PASSED"
}

/** The step that broke a scenario: index, sentence, harness reason, last screenshot taken. */
data class FailedStep(
    val index: Int,
    val text: String,
    val reason: String?,
    val screenshot: String?,
    /** Labels/ids visible when the step failed (see StepReport.screen). */
    val screen: List<String> = emptyList(),
) {
    /** The scenario never reached its steps — setup (deeplink, state restore, launch) broke. */
    val isSetup: Boolean get() = reason?.startsWith("setup failed") == true
}

/**
 * A scenario's identity across phases: its file relative to `.simul/scenarios/`, plus `@<state>`
 * for a variant of a `states:` scenario (`billing/play.md@member`). [traceRelPath] inverts it.
 */
fun scenarioPath(project: SimulProject, md: File, scenario: Scenario): String =
    md.relativeToOrSelf(project.scenariosDir).path + (scenario.state?.let { "@$it" } ?: "")

/** The trace file (relative to `.simul/scenarios/`) a [scenarioPath] records into. */
fun traceRelPath(path: String): String {
    val at = path.indexOf(".md@")
    return if (at >= 0) path.substring(0, at) + "@" + path.substring(at + ".md@".length) + ".trace.json"
    else path.removeSuffix(".md") + ".trace.json"
}

/** Build the outcome row for a scenario the runner executed. */
fun outcomeOf(project: SimulProject, md: File, scenario: Scenario, r: ScenarioRunResult): ScenarioOutcome {
    val failed = r.steps.firstOrNull { it.status == StepStatus.FAILED }
        // a setup failure leaves every step SKIPPED with the reason on the first one
        ?: r.steps.firstOrNull { it.reason != null }.takeIf { r.status == StepStatus.FAILED }
    val reportRel = r.reportDir?.relativeToOrSelf(project.appRoot)?.path
    return ScenarioOutcome(
        name = scenario.name,
        path = scenarioPath(project, md, scenario),
        tags = scenario.tags,
        status = r.status.name,
        mode = r.steps.firstNotNullOfOrNull { s -> s.mode?.takeIf { it != "runner" } }
            ?: r.steps.firstNotNullOfOrNull { it.mode },
        durationMs = r.durationMs,
        reportDir = reportRel,
        traceUpdated = r.updatedTrace != null,
        passedSteps = r.steps.count { it.status == StepStatus.PASSED },
        failedSteps = r.steps.count { it.status == StepStatus.FAILED },
        skippedSteps = r.steps.count { it.status == StepStatus.SKIPPED },
        failedStep = failed?.let { s ->
            FailedStep(
                index = s.index,
                text = s.text,
                reason = s.reason,
                screenshot = s.screenshots.lastOrNull()?.let { shot -> reportRel?.let { "$it/$shot" } ?: shot },
                screen = s.screen,
            )
        },
        reason = null,
        llm = r.llm,
        gif = r.media.firstOrNull { it.extension == "gif" }?.relativeToOrSelf(project.appRoot)?.path,
        description = scenario.description,
    )
}

/** Outcome row for a scenario that never ran (no trace to replay) or crashed the harness. */
fun outcomeWithout(project: SimulProject, md: File, scenario: Scenario, status: String, reason: String, crashLog: File? = null): ScenarioOutcome =
    ScenarioOutcome(
        name = scenario.name,
        path = scenarioPath(project, md, scenario),
        tags = scenario.tags,
        status = status,
        mode = null,
        durationMs = 0,
        reportDir = crashLog?.relativeToOrSelf(project.appRoot)?.path,
        traceUpdated = false,
        passedSteps = 0, failedSteps = 0, skippedSteps = scenario.steps.size,
        failedStep = null,
        reason = reason,
        description = scenario.description,
    )

object RunSummaryJson {
    /** ISO-8601 with offset — the `startedAt` of summaries and reports. */
    fun stamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(Date())

    fun write(s: RunSummary, file: File) {
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(prettyJson.encodeToString(JsonObject.serializer(), toJson(s)) + "\n")
    }

    fun toJson(s: RunSummary): JsonObject = buildJsonObject {
        put("label", s.label)
        put("mode", s.mode)
        put("startedAt", s.startedAt)
        put("durationMs", s.durationMs)
        put("env", buildJsonObject {
            put("agentVersion", s.agentVersion)
            put("appVersionName", s.appVersionName?.let(::JsonPrimitive) ?: JsonNull)
            put("device", s.device?.let(::JsonPrimitive) ?: JsonNull)
            s.androidCli?.let { put("androidCli", it) }
        })
        put("scenarios", buildJsonArray {
            s.scenarios.forEach { o ->
                add(buildJsonObject {
                    put("name", o.name)
                    put("path", o.path)
                    put("tags", buildJsonArray { o.tags.forEach { add(JsonPrimitive(it)) } })
                    put("status", o.status)
                    put("mode", o.mode?.let(::JsonPrimitive) ?: JsonNull)
                    put("durationMs", o.durationMs)
                    put("reportDir", o.reportDir?.let(::JsonPrimitive) ?: JsonNull)
                    put("traceUpdated", o.traceUpdated)
                    put("steps", buildJsonObject {
                        put("passed", o.passedSteps)
                        put("failed", o.failedSteps)
                        put("skipped", o.skippedSteps)
                    })
                    o.failedStep?.let { f ->
                        put("failedStep", buildJsonObject {
                            put("index", f.index)
                            put("text", f.text)
                            put("reason", f.reason?.let(::JsonPrimitive) ?: JsonNull)
                            put("screenshot", f.screenshot?.let(::JsonPrimitive) ?: JsonNull)
                            if (f.screen.isNotEmpty()) put("screen", buildJsonArray { f.screen.forEach { add(JsonPrimitive(it)) } })
                        })
                    }
                    o.reason?.let { put("reason", it) }
                    o.llm?.let { put("llm", llmJson(it)) }
                    o.gif?.let { put("gif", it) }
                    if (o.description.isNotEmpty()) put("description", o.description)
                })
            }
        })
    }

    fun read(file: File): RunSummary = parse(file.readText())

    fun parse(text: String): RunSummary {
        val o = json.parseToJsonElement(text).jsonObject
        val env = o.obj("env")
        return RunSummary(
            label = o.str("label") ?: "run",
            mode = o.str("mode") ?: "replay",
            startedAt = o.str("startedAt") ?: "",
            durationMs = o.long("durationMs") ?: 0,
            agentVersion = env?.str("agentVersion") ?: "",
            appVersionName = env?.str("appVersionName"),
            device = env?.str("device"),
            androidCli = env?.str("androidCli"),
            scenarios = o.arr("scenarios")?.map { el ->
                val s = el.jsonObject
                val steps = s.obj("steps")
                ScenarioOutcome(
                    name = s.str("name") ?: "",
                    path = s.str("path") ?: (s.str("name") ?: ""),
                    tags = s.strList("tags"),
                    status = s.str("status") ?: "FAILED",
                    mode = s.str("mode"),
                    durationMs = s.long("durationMs") ?: 0,
                    reportDir = s.str("reportDir"),
                    traceUpdated = s.bool("traceUpdated") ?: false,
                    passedSteps = steps?.int("passed") ?: 0,
                    failedSteps = steps?.int("failed") ?: 0,
                    skippedSteps = steps?.int("skipped") ?: 0,
                    failedStep = s.obj("failedStep")?.let {
                        FailedStep(
                            index = it.int("index") ?: 0,
                            text = it.str("text") ?: "",
                            reason = it.str("reason"),
                            screenshot = it.str("screenshot"),
                            screen = it.strList("screen"),
                        )
                    },
                    reason = s.str("reason"),
                    llm = llmFromJson(s.obj("llm")),
                    gif = s.str("gif"),
                    description = s.str("description") ?: "",
                )
            } ?: emptyList(),
        )
    }
}
