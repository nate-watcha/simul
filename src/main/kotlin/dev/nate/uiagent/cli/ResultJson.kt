package dev.nate.uiagent.cli

import dev.nate.uiagent.agent.LlmStats
import dev.nate.uiagent.agent.llmFromJson
import dev.nate.uiagent.agent.llmJson
import dev.nate.uiagent.arr
import dev.nate.uiagent.bool
import dev.nate.uiagent.int
import dev.nate.uiagent.json
import dev.nate.uiagent.long
import dev.nate.uiagent.obj
import dev.nate.uiagent.prettyJson
import dev.nate.uiagent.str
import dev.nate.uiagent.strList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
 * logcat.txt           the device log over the scenario (threadtime + epoch stamps); the Firebase Analytics
 *                      events in it land on the step they fell in (`events`, AnalyticsEvents) — the setup's
 *                      (the app's launch) in `setup_events`
 * replay.mp4           the screen recording (--video); every step and action carries its
 *                      `video_ms`, so a page can seek the player to it
 * ```
 *
 * `setup` is how the scenario is entered, as its frontmatter says it: `deeplink` or `launch: true`, and the
 * account state it starts from (`app_state` — the `appState`, a `states:` variant, or `guest` for `clearData`).
 * The report groups scenarios by screen and shows this as the way in.
 *
 * A scenario with `## title` sections lists them under `sections` — title, prose, PASSED/FAILED/SKIPPED
 * (SKIPPED = not reached, an earlier section failed) and `preparation: true` on its `## 준비` section —
 * each owning the steps `from`..`to` of the one flat `steps` list (numbered through the whole scenario,
 * so `steps/NN.png` and `video_ms` mean what they always did). A preparation's steps have `mode: "llm"`
 * in a replay's result too, and their `reason` is what the model said it found or did.
 *
 * Times: `started_at_ms` is the scenario's epoch start; a step's `started_ms` counts from it,
 * as does an action's `at_ms`; `video_ms` is the same moment as an offset into replay.mp4.
 * Written once by the CLI after the recording is stopped (the runner only produces the
 * result); read back by `simul status` (the newest verdict) and the report builder.
 */
object ResultJson {
    const val FILE = "result.json"

    /** Environment facts stamped into every result. */
    data class Env(val appVersionName: String?, val device: String?, val androidCli: String?, val os: String? = null)

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
        /** OS of the device the trace was recorded on ("API 37"); null for a trace that does not say. */
        val traceOs: String? = null,
        /** How the scenario is entered (its `setup:`); null when it declares none. */
        val setup: Setup? = null,
        /** The screens [description] mentions (`@화면`). */
        val mentions: List<String> = emptyList(),
        /** What the scenario's screen and the screens above it are made of (their `_screen.md`), by screen. */
        val screens: Map<String, ScreenLayout> = emptyMap(),
        /** The mode of its screen the scenario is of ([Scenario.mode]); null when it is common to the modes. */
        val screenMode: String? = null,
        /** The values of that mode the scenario names ([Scenario.data]), by name; its prose keeps the names. */
        val modeData: Map<String, String> = emptyMap(),
        /** This is one of the runs of a file that runs in each mode ([Scenario.perMode]): [state] is the mode. */
        val perMode: Boolean = false,
        /** The scenario started where a section of another one ended ([Scenario.After]): where its data came from. */
        val after: After? = null,
    )

    /**
     * Where a scenario's data came from, when it started after another scenario's section (`setup.after`): that
     * scenario's [screen], what the report calls it ([title]), its file's [name] and the [section] — and the
     * [labels] it had put on the screen that this scenario acts on (`치이카와`).
     */
    data class After(val screen: String, val title: String, val section: String, val name: String, val labels: List<String> = emptyList())

    /**
     * A scenario's way in: the [deeplink] it opens or a plain [launch], from the account state [appState].
     * [via] is the screen whose way in (`_entry.md`) it started from: the address is that entry's, and the
     * entry's steps were played before the scenario's own — and [mode] the mode that way in is of, where the
     * screen has one per mode: the mode the scenario ran in.
     */
    data class Setup(val deeplink: String?, val launch: Boolean, val appState: String?, val via: String? = null, val mode: String? = null) {
        /** `watcha://my`, or `앱 실행` for a plain launch; null when the scenario names neither. */
        val entry: String? get() = deeplink ?: if (launch) "앱 실행" else null
    }

    fun write(r: ScenarioRunResult, mode: String, meta: Meta, dir: File) {
        dir.mkdirs()
        val layouts = r.steps.map { s -> s.layout?.let { LayoutJson.read(File(dir, it)) } ?: emptyList() }
        val videoStart = r.video?.startedAtMs
        fun videoMs(absMs: Long): Long? = videoStart?.let { (absMs - it).coerceAtLeast(0) }
        val events = AnalyticsEvents.bucket(
            AnalyticsEvents.read(File(dir, "logcat.txt")),
            r.steps.filter { it.status != StepStatus.SKIPPED }.map { it.index to r.startedAtMs + it.startedMs },
        )
        fun eventsJson(list: List<AnalyticsEvents.Logged>) = buildJsonArray {
            list.forEach { e ->
                add(buildJsonObject {
                    put("at_ms", e.atEpochMs - r.startedAtMs)
                    put("video_ms", videoMs(e.atEpochMs)?.let(::JsonPrimitive) ?: JsonNull)
                    put("origin", e.origin)
                    put("name", e.name)
                    put("params", buildJsonObject { e.params.forEach { (k, v) -> put(k, v) } })
                })
            }
        }
        val obj: JsonObject = buildJsonObject {
            put("scenario", r.scenario)
            put("title", meta.title)
            put("feature", meta.feature)
            put("name", meta.name)
            put("state", meta.state?.let(::JsonPrimitive) ?: JsonNull)
            put("file", meta.file)
            put("owner", meta.owner?.let(::JsonPrimitive) ?: JsonNull)
            put("description", meta.description)
            if (meta.mentions.isNotEmpty()) put("mentions", buildJsonArray { meta.mentions.forEach { add(JsonPrimitive(it)) } })
            // a screen's layout top to bottom: a name is a part, an array is the row of tabs
            if (meta.screens.isNotEmpty()) put("screens", buildJsonObject {
                meta.screens.forEach { (screen, layout) ->
                    put(screen, buildJsonArray {
                        layout.slots.forEach { slot -> if (slot.part != null) add(JsonPrimitive(slot.part)) else add(buildJsonArray { slot.tabs.forEach { add(JsonPrimitive(it)) } }) }
                    })
                }
            })
            // the modes of the screens that are one screen in several, by screen, and the one this scenario is of
            meta.screens.filterValues { it.modes.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { withModes ->
                put("modes", buildJsonObject { withModes.forEach { (screen, layout) -> put(screen, buildJsonArray { layout.modes.forEach { add(JsonPrimitive(it)) } }) } })
            }
            meta.screenMode?.let { put("screen_mode", it) }
            // the same behaviour on each mode's data: the values this run was filled with, and that the file runs in each mode
            if (meta.modeData.isNotEmpty()) put("mode_data", buildJsonObject { meta.modeData.forEach { (k, v) -> put(k, v) } })
            if (meta.perMode) put("per_mode", true)
            meta.after?.let { a ->
                put("after", buildJsonObject {
                    put("screen", a.screen); put("title", a.title); put("section", a.section); put("name", a.name)
                    if (a.labels.isNotEmpty()) put("labels", buildJsonArray { a.labels.forEach { add(JsonPrimitive(it)) } })
                })
            }
            meta.setup?.let { s ->
                put("setup", buildJsonObject {
                    s.deeplink?.let { put("deeplink", it) }
                    if (s.launch) put("launch", true)
                    s.appState?.let { put("app_state", it) }
                    s.via?.let { put("via", it) }
                    s.mode?.let { put("mode", it) }
                })
            }
            // the scenario's `values:` as this run picked them — absent when it declares none
            if (r.values.isNotEmpty()) put("values", buildJsonObject { r.values.forEach { (k, v) -> put(k, v) } })
            put("trace_recorded_at", meta.traceRecordedAt?.let(::JsonPrimitive) ?: JsonNull)
            meta.traceOs?.let { put("trace_os", it) }
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
                meta.env.os?.let { put("os", it) }
            })
            put("video", r.video?.let { v ->
                buildJsonObject {
                    put("file", v.file.relativeToOrSelf(dir).path)
                    put("duration_ms", v.durationMs?.let(::JsonPrimitive) ?: JsonNull)
                }
            } ?: JsonNull)
            r.llm?.let { put("llm", llmJson(it)) }
            events[0]?.let { put("setup_events", eventsJson(it)) }
            // the scenario's `## title` sections in the order they ran (file order) — absent without them
            if (r.sections.isNotEmpty()) put("sections", buildJsonArray {
                r.sections.forEach { sec ->
                    add(buildJsonObject {
                        put("title", sec.title)
                        put("description", ModeData.plain(fillValues(sec.description, r.values), meta.modeData.keys))
                        if (sec.preparation) put("preparation", true)
                        if (sec.mentions.isNotEmpty()) put("mentions", buildJsonArray { sec.mentions.forEach { add(JsonPrimitive(it)) } })
                        // the section's own `setup:` — where and as whom it started when that was not as the scenario does
                        sec.open?.deeplink?.let { put("deeplink", it) }
                        sec.open?.screen?.let { put("entry", it) }
                        sec.open?.appState?.let { put("app_state", it) }
                        put("status", sec.status.name)
                        put("from", sec.steps.first)
                        put("to", sec.steps.last)
                    })
                }
            })
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
                        events[s.index]?.let { put("events", eventsJson(it)) }
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
        /** The screens [description] mentions (`@화면`). */
        val mentions: List<String> = emptyList(),
        /** What the scenario's screen and the screens above it are made of (their `_screen.md`), by screen. */
        val screens: Map<String, ScreenLayout> = emptyMap(),
        /** The mode of its screen the scenario is of (`screen_mode`); null when it is common to the modes, or its screen has none. */
        val screenMode: String? = null,
        /** The values of that mode the scenario names (`mode_data`): what its steps were filled with. */
        val modeData: Map<String, String> = emptyMap(),
        /** One of the runs of a file that runs in each mode (`per_mode`), the one in [screenMode]: the same behaviour on that mode's data. */
        val perMode: Boolean = false,
        /** Where its data came from, when it started after another scenario's section (`after`). */
        val after: After? = null,
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
        /** The app's Firebase Analytics events during setup (its launch), before the first step. */
        val setupEvents: List<Event> = emptyList(),
        /** The scenario's `## title` sections in the order they ran; empty for a scenario without them. */
        val sections: List<Section> = emptyList(),
        /** How the scenario is entered; null in results written before `setup` was recorded. */
        val setup: Setup? = null,
        /** OS of the device this ran on ("API 35"), and of the one its trace was recorded on; null where not recorded. */
        val os: String? = null,
        val traceOs: String? = null,
    ) {
        /** "API 37" when this replayed a trace recorded on another OS version than the device it ran on; null otherwise. */
        val recordedOnOtherOs: String? get() = traceOs?.takeIf { mode == "replay" && os != null && it != os }
        /** `coupon.member` / `coupon` — the directory a run of this scenario gets under a runs root (see ReportDirs). */
        val key: String get() = name + (state?.let { ".$it" } ?: "")
        /** `billing/coupon.member` — the identity an acknowledgement file keys on. */
        val id: String get() = (if (feature.isEmpty()) "" else "$feature/") + key
        val passed get() = status == "PASSED"
        val failed get() = status == "FAILED"
        val failedStepOf: Step? get() = failedStep?.let { n -> steps.firstOrNull { it.n == n } }
        fun sectionOf(n: Int): Section? = sections.firstOrNull { n in it.from..it.to }
        /** The sections that test something — all but the preparation — in the order they ran. */
        val tests: List<Section> get() = sections.filterNot { it.preparation }
    }

    /**
     * One `## title` section of a run: [status] PASSED | FAILED | SKIPPED (not reached), owning the steps
     * [from]..[to]. [preparation] is the `## 준비` section: done by the model, a test of nothing.
     */
    data class Section(
        val title: String, val description: String, val preparation: Boolean, val status: String, val from: Int, val to: Int,
        /** The address the section was opened at instead of the scenario's entry (its `setup.deeplink`). */
        val deeplink: String? = null,
        /** The screen whose way in the section started from instead of the scenario's entry (its `setup.entry`). */
        val entry: String? = null,
        /** The account state the section ran as instead of the scenario's (its `setup.appState`). */
        val appState: String? = null,
        /** The screens its sentence mentions (`@화면`). */
        val mentions: List<String> = emptyList(),
    ) {
        /** The section started somewhere of its own: it went to look at another place rather than walk there. */
        val opened: Boolean get() = deeplink != null || entry != null
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
        /** The app's Firebase Analytics events that fell in this step, in log order. */
        val events: List<Event> = emptyList(),
    )

    data class Action(val tool: String, val target: String?, val atMs: Long, val videoMs: Long?)

    /** One Firebase Analytics event (AnalyticsEvents): [atMs] since the scenario started, [origin] `app`/`auto`. */
    data class Event(val atMs: Long, val videoMs: Long?, val origin: String, val name: String, val params: Map<String, String>) {
        /**
         * The app's own id for the event, when it sends one: its `event_id` parameter, under whatever prefix the app
         * gives its keys (`w_event_id=530`). It is what the app's event spec lists an event by — one name (`w_view`)
         * is many events there — so it is what the report lists and finds an event by ([Site.Fired]).
         */
        val id: String? get() = params.entries.firstOrNull { (k, _) -> !k.startsWith("ga_") && (k.equals("event_id", true) || k.endsWith("_event_id", true)) }
            ?.value?.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun events(o: JsonObject, key: String): List<Event> = o.arr(key)?.mapNotNull { el ->
        val e = el as? JsonObject ?: return@mapNotNull null
        Event(e.long("at_ms") ?: 0, e.long("video_ms"), e.str("origin") ?: "app", e.str("name") ?: return@mapNotNull null,
            e.obj("params")?.entries?.associate { (k, v) -> k to ((v as? JsonPrimitive)?.content ?: v.toString()) } ?: emptyMap())
    } ?: emptyList()

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
            mentions = o.strList("mentions"),
            screenMode = o.str("screen_mode"),
            modeData = o.obj("mode_data")?.entries?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty(),
            perMode = o.bool("per_mode") == true,
            after = o.obj("after")?.let { a -> After(a.str("screen") ?: "", a.str("title") ?: "", a.str("section") ?: "", a.str("name") ?: "", a.strList("labels")) },
            screens = o.obj("screens")?.mapNotNull { (screen, v) ->
                (v as? JsonArray)?.let { arr ->
                    screen to ScreenLayout(arr.mapNotNull { item ->
                        when (item) {
                            is JsonArray -> ScreenLayout.Slot(tabs = item.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
                            is JsonPrimitive -> item.contentOrNull?.let { ScreenLayout.Slot(part = it) }
                            else -> null
                        }
                    }, modes = o.obj("modes")?.get(screen)?.let { m -> (m as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }.orEmpty())
                }
            }?.toMap() ?: emptyMap(),
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
            os = env?.str("os"),
            traceOs = o.str("trace_os"),
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
                    events = events(s, "events"),
                )
            } ?: emptyList(),
            setupEvents = events(o, "setup_events"),
            setup = o.obj("setup")?.let { s -> Setup(s.str("deeplink"), s.bool("launch") == true, s.str("app_state"), s.str("via"), s.str("mode")) },
            sections = o.arr("sections")?.mapNotNull { el ->
                val sec = el as? JsonObject ?: return@mapNotNull null
                Section(sec.str("title") ?: return@mapNotNull null, sec.str("description") ?: "", sec.bool("preparation") == true,
                    sec.str("status") ?: "SKIPPED", sec.int("from") ?: 0, sec.int("to") ?: 0, sec.str("deeplink"), sec.str("entry"), sec.str("app_state"), sec.strList("mentions"))
            } ?: emptyList(),
        )
    }

    /** The scenario-level verdict of a result, as `simul status` shows it; null when unreadable. */
    fun readLastRun(file: File): LastRun? {
        val r = read(file) ?: return null
        return LastRun(passed = r.passed, mode = r.mode, at = file.lastModified(), durationMs = r.durationMs)
    }
}
