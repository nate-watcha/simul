package dev.nate.uiagent.cli

import dev.nate.uiagent.agent.llmFromJson
import dev.nate.uiagent.agent.llmJson
import dev.nate.uiagent.arr
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

/**
 * `report.json` — one scenario run as the runner saw it, written into the run's report dir and
 * read back by `simul status` (the newest verdict) and the html report (the step grid).
 */
object ReportJson {
    /** Environment facts stamped into every report. */
    data class Env(val appVersionName: String?, val device: String?, val androidCli: String?)

    fun write(r: ScenarioRunResult, mode: String, env: Env, dir: File) {
        val obj: JsonObject = buildJsonObject {
            put("scenario", r.scenario)
            put("status", r.status.name)
            put("mode", mode)
            put("startedAt", RunSummaryJson.stamp())
            put("durationMs", r.durationMs)
            r.llm?.let { put("llm", llmJson(it)) }
            put("env", buildJsonObject {
                put("agentVersion", AGENT_VERSION)
                put("appVersionName", env.appVersionName?.let(::JsonPrimitive) ?: JsonNull)
                put("device", env.device?.let(::JsonPrimitive) ?: JsonNull)
                put("androidCli", env.androidCli?.let(::JsonPrimitive) ?: JsonNull)
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
                        s.llm?.let { put("llm", llmJson(it)) }
                        put("screenshots", buildJsonArray { s.screenshots.forEach { add(JsonPrimitive(it)) } })
                        if (s.screen.isNotEmpty()) put("screen", buildJsonArray { s.screen.forEach { add(JsonPrimitive(it)) } })
                    })
                }
            })
        }
        dir.mkdirs()
        File(dir, "report.json").writeText(prettyJson.encodeToString(JsonObject.serializer(), obj) + "\n")
    }

    private fun parse(file: File): JsonObject? =
        runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()

    /** The steps of a report (state evidence comes back as `label=state` strings; actions are not read back). */
    fun readSteps(file: File): List<StepReport> = parse(file)?.arr("steps")?.mapNotNull { el ->
        val s = el as? JsonObject ?: return@mapNotNull null
        StepReport(
            index = s.int("step") ?: 0,
            text = s.str("text") ?: "",
            status = s.str("status")?.let { st -> StepStatus.entries.firstOrNull { it.name == st } } ?: StepStatus.FAILED,
            mode = s.str("mode"),
            actions = emptyList(),
            evidence = s.strList("evidence"),
            reason = s.str("reason"),
            durationMs = s.long("durationMs") ?: 0,
            screenshots = s.strList("screenshots"),
            screen = s.strList("screen"),
            llm = llmFromJson(s.obj("llm")),
        )
    } ?: emptyList()

    /** The scenario-level verdict of a report, as `simul status` shows it; null when unreadable. */
    fun readLastRun(file: File): LastRun? {
        val o = parse(file) ?: return null
        return LastRun(
            passed = o.str("status") == "PASSED",
            mode = o.str("mode") ?: "?",
            at = file.lastModified(),
            durationMs = o.long("durationMs"),
        )
    }
}
