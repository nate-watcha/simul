package dev.nate.uiagent.cli

import dev.nate.uiagent.agent.LlmStats
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
 * `result.json` — one scenario run, in the report's own schema (README "나이틀리 운영"). It sits in
 * the run's report dir next to what it indexes:
 *
 * ```
 * result.json          this file
 * steps/01.png …       the screen when each step ended
 * layout/01.json …     the layout tree at that moment (LayoutJson)
 * logcat.txt           the device log over the scenario (threadtime + epoch stamps)
 * replay.mp4           the screen recording (--video); every step and action carries its
 *                      `video_ms`, so a page can seek the player to it
 * ```
 *
 * Times: `started_at_ms` is the scenario's epoch start; a step's `started_ms` counts from it,
 * as does an action's `at_ms`; `video_ms` is the same moment as an offset into replay.mp4.
 * Written once by the CLI after the recording is stopped (the runner only produces the
 * result); read back by `simul status` (the newest verdict) and the report builder.
 */
object ResultJson {
    const val FILE = "result.json"

    /** Environment facts stamped into every result. */
    data class Env(val appVersionName: String?, val device: String?, val androidCli: String?)

    /** What the runner does not know about the scenario: where it lives and who owns it. */
    data class Meta(
        /** Scenario directory relative to `.simul/scenarios/` ("" at the root). */
        val feature: String,
        /** Markdown file stem — the scenario's identity across runs (`billing/coupon.md` → `coupon`). */
        val name: String,
        val state: String?,
        /** `billing/coupon.md`, relative to `.simul/scenarios/`. */
        val file: String,
        val title: String,
        val owner: String?,
        val description: String,
        /** `YYYY-MM-DD` the trace was last recorded (git commit date, else file mtime); null without a trace. */
        val traceRecordedAt: String?,
        val env: Env,
    )

    fun write(r: ScenarioRunResult, mode: String, meta: Meta, dir: File) {
        dir.mkdirs()
        val layouts = r.steps.map { s -> s.layout?.let { LayoutJson.read(File(dir, it)) } ?: emptyList() }
        val videoStart = r.video?.startedAtMs
        fun videoMs(absMs: Long): Long? = videoStart?.let { (absMs - it).coerceAtLeast(0) }
        val obj: JsonObject = buildJsonObject {
            put("scenario", r.scenario)
            put("title", meta.title)
            put("feature", meta.feature)
            put("name", meta.name)
            put("state", meta.state?.let(::JsonPrimitive) ?: JsonNull)
            put("file", meta.file)
            put("owner", meta.owner?.let(::JsonPrimitive) ?: JsonNull)
            put("description", meta.description)
            put("trace_recorded_at", meta.traceRecordedAt?.let(::JsonPrimitive) ?: JsonNull)
            put("status", if (r.setupFailed) "SKIPPED_SETUP" else r.status.name)
            val failed = r.steps.firstOrNull { it.status == StepStatus.FAILED }
            put("failed_step", failed?.index?.let(::JsonPrimitive) ?: JsonNull)
            if (r.setupFailed) put("reason", r.steps.firstNotNullOfOrNull { it.reason } ?: "setup failed")
            put("mode", mode)
            put("started_at", RunSummaryJson.stamp(r.startedAtMs))
            put("started_at_ms", r.startedAtMs)
            put("duration_ms", r.durationMs)
            put("layout_hash", LayoutJson.hash(layouts))
            put("env", buildJsonObject {
                put("agent_version", AGENT_VERSION)
                put("app_version", meta.env.appVersionName?.let(::JsonPrimitive) ?: JsonNull)
                put("device", meta.env.device?.let(::JsonPrimitive) ?: JsonNull)
                put("android_cli", meta.env.androidCli?.let(::JsonPrimitive) ?: JsonNull)
            })
            put("video", r.video?.let { v ->
                buildJsonObject {
                    put("file", v.file.relativeToOrSelf(dir).path)
                    put("duration_ms", v.durationMs?.let(::JsonPrimitive) ?: JsonNull)
                }
            } ?: JsonNull)
            r.llm?.let { put("llm", llmJson(it)) }
            put("steps", buildJsonArray {
                r.steps.forEach { s ->
                    val spec = StepSpec.parse(s.text)
                    add(buildJsonObject {
                        put("n", s.index)
                        put("text", spec.command)
                        put("criterion", spec.criterion?.let(::JsonPrimitive) ?: JsonNull)
                        put("status", s.status.name)
                        put("mode", s.mode?.let(::JsonPrimitive) ?: JsonNull)
                        put("reason", s.reason?.let(::JsonPrimitive) ?: JsonNull)
                        put("started_ms", s.startedMs)
                        put("duration_ms", s.durationMs)
                        if (s.status != StepStatus.SKIPPED) put("video_ms", videoMs(r.startedAtMs + s.startedMs)?.let(::JsonPrimitive) ?: JsonNull)
                        put("evidence_expected", buildJsonArray { s.expectedEvidence.forEach { add(JsonPrimitive(it)) } })
                        put("evidence_found", buildJsonArray {
                            s.evidence.forEach { add(JsonPrimitive(it)) }
                            s.stateEvidence.forEach { add(JsonPrimitive(it.toString())) } // "label=state"
                        })
                        put("screenshot", s.screenshots.lastOrNull()?.let(::JsonPrimitive) ?: JsonNull)
                        put("layout", s.layout?.let(::JsonPrimitive) ?: JsonNull)
                        put("actions", buildJsonArray {
                            s.marks.forEach { m ->
                                add(buildJsonObject {
                                    put("tool", m.tool)
                                    put("target", m.target?.let(::JsonPrimitive) ?: JsonNull)
                                    put("at_ms", m.atMs - r.startedAtMs)
                                    put("video_ms", videoMs(m.atMs)?.let(::JsonPrimitive) ?: JsonNull)
                                })
                            }
                        })
                        s.llm?.let { put("llm", llmJson(it)) }
                        if (s.screenshots.size > 1) put("screenshots", buildJsonArray { s.screenshots.forEach { add(JsonPrimitive(it)) } })
                        if (s.screen.isNotEmpty()) put("screen", buildJsonArray { s.screen.forEach { add(JsonPrimitive(it)) } })
                    })
                }
            })
        }
        File(dir, FILE).writeText(prettyJson.encodeToString(JsonObject.serializer(), obj) + "\n")
    }

    // ------------------------------------------------------------------ read

    /** One scenario run as the report reads it. [dir] is where the file lies (its assets are relative to it). */
    data class Result(
        val dir: File,
        val scenario: String,
        val title: String,
        val feature: String,
        val name: String,
        val state: String?,
        val file: String,
        val owner: String?,
        val description: String,
        val traceRecordedAt: String?,
        /** PASSED | FAILED | SKIPPED_SETUP */
        val status: String,
        val failedStep: Int?,
        val reason: String?,
        val mode: String,
        val startedAt: String,
        val startedAtMs: Long,
        val durationMs: Long,
        val layoutHash: String,
        val appVersion: String?,
        val device: String?,
        val video: Video?,
        val llm: LlmStats?,
        val steps: List<Step>,
    ) {
        /** `coupon.member` / `coupon` — the directory a run of this scenario gets under a runs root (see ReportDirs). */
        val key: String get() = name + (state?.let { ".$it" } ?: "")
        /** `billing/coupon.member` — the identity an acknowledgement file keys on. */
        val id: String get() = (if (feature.isEmpty()) "" else "$feature/") + key
        val passed get() = status == "PASSED"
        val failed get() = status == "FAILED"
        val failedStepOf: Step? get() = failedStep?.let { n -> steps.firstOrNull { it.n == n } }
    }

    data class Video(val file: String, val durationMs: Long?)

    data class Step(
        val n: Int,
        val text: String,
        val criterion: String?,
        /** PASSED | FAILED | SKIPPED */
        val status: String,
        val mode: String?,
        val reason: String?,
        val startedMs: Long,
        val durationMs: Long,
        val videoMs: Long?,
        val expected: List<String>,
        val found: List<String>,
        val screenshot: String?,
        val layout: String?,
        val actions: List<Action>,
        val screen: List<String>,
        /** Every frame of the step in order — a recording's before/after of each action, then the step-end frame. */
        val screenshots: List<String> = listOfNotNull(screenshot),
    )

    data class Action(val tool: String, val target: String?, val atMs: Long, val videoMs: Long?)

    private fun parse(file: File): JsonObject? =
        runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()

    fun read(file: File): Result? {
        val o = parse(file) ?: return null
        val env = o.obj("env")
        return Result(
            dir = file.absoluteFile.parentFile,
            scenario = o.str("scenario") ?: return null,
            title = o.str("title") ?: o.str("scenario") ?: "",
            feature = o.str("feature") ?: "",
            name = o.str("name") ?: o.str("scenario") ?: "",
            state = o.str("state"),
            file = o.str("file") ?: "",
            owner = o.str("owner"),
            description = o.str("description") ?: "",
            traceRecordedAt = o.str("trace_recorded_at"),
            status = o.str("status") ?: "FAILED",
            failedStep = o.int("failed_step"),
            reason = o.str("reason"),
            mode = o.str("mode") ?: "replay",
            startedAt = o.str("started_at") ?: "",
            startedAtMs = o.long("started_at_ms") ?: 0,
            durationMs = o.long("duration_ms") ?: 0,
            layoutHash = o.str("layout_hash") ?: "",
            appVersion = env?.str("app_version"),
            device = env?.str("device"),
            video = o.obj("video")?.let { v -> v.str("file")?.let { Video(it, v.long("duration_ms")) } },
            llm = llmFromJson(o.obj("llm")),
            steps = o.arr("steps")?.mapNotNull { el ->
                val s = el as? JsonObject ?: return@mapNotNull null
                Step(
                    n = s.int("n") ?: 0,
                    text = s.str("text") ?: "",
                    criterion = s.str("criterion"),
                    status = s.str("status") ?: "FAILED",
                    mode = s.str("mode"),
                    reason = s.str("reason"),
                    startedMs = s.long("started_ms") ?: 0,
                    durationMs = s.long("duration_ms") ?: 0,
                    videoMs = s.long("video_ms"),
                    expected = s.strList("evidence_expected"),
                    found = s.strList("evidence_found"),
                    screenshot = s.str("screenshot"),
                    layout = s.str("layout"),
                    actions = s.arr("actions")?.mapNotNull { a ->
                        (a as? JsonObject)?.let { Action(it.str("tool") ?: "", it.str("target"), it.long("at_ms") ?: 0, it.long("video_ms")) }
                    } ?: emptyList(),
                    screen = s.strList("screen"),
                    screenshots = s.strList("screenshots").ifEmpty { listOfNotNull(s.str("screenshot")) },
                )
            } ?: emptyList(),
        )
    }

    /** The scenario-level verdict of a result, as `simul status` shows it; null when unreadable. */
    fun readLastRun(file: File): LastRun? {
        val r = read(file) ?: return null
        return LastRun(passed = r.passed, mode = r.mode, at = file.lastModified(), durationMs = r.durationMs)
    }
}
