package dev.nate.uiagent.cli

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
)

/** Build the outcome row for a scenario the runner executed. */
fun outcomeOf(project: SimulProject, md: File, scenario: Scenario, r: ScenarioRunResult): ScenarioOutcome {
    val failed = r.steps.firstOrNull { it.status == StepStatus.FAILED }
        // a setup failure leaves every step SKIPPED with the reason on the first one
        ?: r.steps.firstOrNull { it.reason != null }.takeIf { r.status == StepStatus.FAILED }
    val reportRel = r.reportDir?.relativeToOrSelf(project.appRoot)?.path
    return ScenarioOutcome(
        name = scenario.name,
        path = md.relativeToOrSelf(project.scenariosDir).path,
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
    )
}

/** Outcome row for a scenario that never ran (no trace to replay) or crashed the harness. */
fun outcomeWithout(project: SimulProject, md: File, scenario: Scenario, status: String, reason: String, crashLog: File? = null): ScenarioOutcome =
    ScenarioOutcome(
        name = scenario.name,
        path = md.relativeToOrSelf(project.scenariosDir).path,
        tags = scenario.tags,
        status = status,
        mode = null,
        durationMs = 0,
        reportDir = crashLog?.relativeToOrSelf(project.appRoot)?.path,
        traceUpdated = false,
        passedSteps = 0, failedSteps = 0, skippedSteps = scenario.steps.size,
        failedStep = null,
        reason = reason,
    )

@OptIn(ExperimentalSerializationApi::class)
object RunSummaryJson {
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

    fun stamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(Date())

    fun write(s: RunSummary, file: File) {
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(json.encodeToString(JsonObject.serializer(), toJson(s)) + "\n")
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
                })
            }
        })
    }

    fun read(file: File): RunSummary = parse(file.readText())

    fun parse(text: String): RunSummary {
        val o = parser.parseToJsonElement(text).jsonObject
        val env = o["env"] as? JsonObject
        fun str(obj: JsonObject?, key: String) = (obj?.get(key) as? JsonPrimitive)?.contentOrNull
        return RunSummary(
            label = str(o, "label") ?: "run",
            mode = str(o, "mode") ?: "replay",
            startedAt = str(o, "startedAt") ?: "",
            durationMs = (o["durationMs"] as? JsonPrimitive)?.longOrNull ?: 0,
            agentVersion = str(env, "agentVersion") ?: "",
            appVersionName = str(env, "appVersionName"),
            device = str(env, "device"),
            androidCli = str(env, "androidCli"),
            scenarios = (o["scenarios"] as? JsonArray)?.map { el ->
                val s = el.jsonObject
                val steps = s["steps"] as? JsonObject
                val f = s["failedStep"] as? JsonObject
                ScenarioOutcome(
                    name = str(s, "name") ?: "",
                    path = str(s, "path") ?: (str(s, "name") ?: ""),
                    tags = (s["tags"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    status = str(s, "status") ?: "FAILED",
                    mode = str(s, "mode"),
                    durationMs = (s["durationMs"] as? JsonPrimitive)?.longOrNull ?: 0,
                    reportDir = str(s, "reportDir"),
                    traceUpdated = (s["traceUpdated"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    passedSteps = (steps?.get("passed") as? JsonPrimitive)?.intOrNull ?: 0,
                    failedSteps = (steps?.get("failed") as? JsonPrimitive)?.intOrNull ?: 0,
                    skippedSteps = (steps?.get("skipped") as? JsonPrimitive)?.intOrNull ?: 0,
                    failedStep = f?.let {
                        FailedStep(
                            index = (it["index"] as? JsonPrimitive)?.intOrNull ?: 0,
                            text = str(it, "text") ?: "",
                            reason = str(it, "reason"),
                            screenshot = str(it, "screenshot"),
                            screen = (it["screen"] as? JsonArray)?.mapNotNull { e -> e.jsonPrimitive.contentOrNull } ?: emptyList(),
                        )
                    },
                    reason = str(s, "reason"),
                )
            } ?: emptyList(),
        )
    }
}
