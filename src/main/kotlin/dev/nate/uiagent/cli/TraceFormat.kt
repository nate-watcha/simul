package dev.nate.uiagent.cli

import dev.nate.uiagent.Point
import dev.nate.uiagent.arr
import dev.nate.uiagent.json
import dev.nate.uiagent.obj
import dev.nate.uiagent.prettyJson
import dev.nate.uiagent.str
import dev.nate.uiagent.strList
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * The committed replay trace (`<scenario>.trace.json`). Serialization discipline for PR-diff
 * readability: 2-space pretty print, fixed key order (insertion order of buildJsonObject),
 * and NO volatile values — no timestamps, durations, or raw responses. lastCoords is the one
 * coordinate exception (replay hint) and is only rewritten when grounding actually fell back
 * to coordinates.
 */
data class TraceFile(
    val scenario: String,
    val agentVersion: String,
    val appVersionName: String?,
    val steps: List<TraceStep>,
    /** Display the recording ran under ("720x1280@320") — replay warns on mismatch. */
    val display: String? = null,
    /** `android` CLI version the recording observed with — replay warns on mismatch. */
    val androidCli: String? = null,
)

data class TraceStep(
    val text: String,
    val actions: List<TraceAction>,
    /** Labels that newly appeared when this step succeeded — the deterministic pass gate. */
    val evidence: List<String>,
    /**
     * Structural evidence: elements that gained a stable state (selected/checked) during the
     * step, e.g. the bottom-nav tab that became selected. Immune to dynamic content churn,
     * so it is the strongest replay proof for navigation/toggle steps.
     */
    val stateEvidence: List<StateEvidence> = emptyList(),
)

/** [ref] is the element's resourceId when it has one, else its label. */
data class StateEvidence(val ref: String, val state: String) {
    override fun toString() = "$ref=$state"
}

/**
 * One recorded tool execution. tap/type carry a grounding [target]; scroll/scrollToFind carry
 * their verbatim [args]; back/waitForChange carry nothing.
 */
data class TraceAction(
    val tool: String,
    val args: Map<String, String> = emptyMap(),
    val target: TraceTarget? = null,
    val text: String? = null,
)

/**
 * [origin] is "web" for elements grounded inside a WebView (resourceId then holds the DOM id
 * attribute); null — and omitted from JSON — for native elements, keeping old traces compatible.
 */
data class TraceTarget(
    val resourceId: String?,
    val label: String?,
    val lastCoords: Point?,
    val origin: String? = null,
)

object TraceJson {

    // ------------------------------------------------------------------ write

    fun render(t: TraceFile): String = prettyJson.encodeToString(JsonObject.serializer(), toJson(t)) + "\n"

    fun write(t: TraceFile, file: File) {
        file.parentFile?.mkdirs()
        file.writeText(render(t))
    }

    fun toJson(t: TraceFile): JsonObject = buildJsonObject {
        put("scenario", t.scenario)
        put("recordedWith", buildJsonObject {
            put("agentVersion", t.agentVersion)
            put("appVersionName", t.appVersionName?.let(::JsonPrimitive) ?: JsonNull)
            t.display?.let { put("display", it) }
            t.androidCli?.let { put("androidCli", it) }
        })
        put("steps", buildJsonArray { t.steps.forEach { add(stepJson(it)) } })
    }

    private fun stepJson(s: TraceStep): JsonObject = buildJsonObject {
        put("text", s.text)
        put("actions", buildJsonArray { s.actions.forEach { add(actionJson(it)) } })
        put("evidence", buildJsonObject {
            put("appeared", buildJsonArray { s.evidence.forEach { add(JsonPrimitive(it)) } })
            if (s.stateEvidence.isNotEmpty()) {
                put("states", buildJsonArray {
                    s.stateEvidence.forEach { se ->
                        add(buildJsonObject { put("ref", se.ref); put("state", se.state) })
                    }
                })
            }
        })
    }

    fun actionJson(a: TraceAction): JsonObject = buildJsonObject {
        put("tool", a.tool)
        a.target?.let { t ->
            put("target", buildJsonObject {
                put("resourceId", t.resourceId?.let(::JsonPrimitive) ?: JsonNull)
                put("label", t.label?.let(::JsonPrimitive) ?: JsonNull)
                put("lastCoords", t.lastCoords?.let { c ->
                    buildJsonArray { add(JsonPrimitive(c.x)); add(JsonPrimitive(c.y)) }
                } ?: JsonNull)
                t.origin?.let { put("origin", it) }
            })
        }
        a.text?.let { put("text", it) }
        if (a.args.isNotEmpty()) {
            put("args", buildJsonObject { a.args.forEach { (k, v) -> put(k, v) } })
        }
    }

    // ------------------------------------------------------------------ read

    fun read(file: File): TraceFile = parse(file.readText())

    fun parse(text: String): TraceFile {
        val o = json.parseToJsonElement(text).jsonObject
        val recorded = o.obj("recordedWith")
        return TraceFile(
            scenario = o.str("scenario") ?: "",
            agentVersion = recorded?.str("agentVersion") ?: "",
            appVersionName = recorded?.str("appVersionName"),
            steps = o.arr("steps")?.map { parseStep(it.jsonObject) } ?: emptyList(),
            display = recorded?.str("display"),
            androidCli = recorded?.str("androidCli"),
        )
    }

    private fun parseStep(o: JsonObject): TraceStep {
        val evidence = o.obj("evidence")
        return TraceStep(
            text = o.str("text") ?: "",
            actions = o.arr("actions")?.map { parseAction(it.jsonObject) } ?: emptyList(),
            evidence = evidence?.strList("appeared") ?: emptyList(),
            stateEvidence = evidence?.arr("states")?.mapNotNull { el ->
                val se = el as? JsonObject ?: return@mapNotNull null
                StateEvidence(se.str("ref") ?: return@mapNotNull null, se.str("state") ?: return@mapNotNull null)
            } ?: emptyList(),
        )
    }

    private fun parseAction(o: JsonObject): TraceAction {
        val target = o.obj("target")?.let { t ->
            TraceTarget(
                resourceId = t.str("resourceId"),
                label = t.str("label"),
                lastCoords = t.arr("lastCoords")?.takeIf { it.size >= 2 }
                    ?.let { Point(it[0].jsonPrimitive.int, it[1].jsonPrimitive.int) },
                origin = t.str("origin"),
            )
        }
        return TraceAction(
            tool = o.str("tool") ?: "",
            args = o.obj("args")?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
                ?.toMap(LinkedHashMap()) ?: emptyMap(),
            target = target,
            text = o.str("text"),
        )
    }
}
