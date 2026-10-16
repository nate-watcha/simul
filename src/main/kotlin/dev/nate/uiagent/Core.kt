package dev.nate.uiagent

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Shared JSON instances and core data types.
 *
 * We deliberately use the kotlinx-serialization *runtime* only (JsonElement tree),
 * NOT the @Serializable compiler plugin — the plugin version must match the Kotlin
 * compiler and is not always available offline. Manual JsonElement parsing keeps the
 * build down to the kotlinx-serialization-json runtime artifact alone.
 */
internal val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** The one pretty printer for committed/reported JSON (trace, summary, report): 2-space, fixed key order. */
@OptIn(ExperimentalSerializationApi::class)
internal val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

// Tolerant field readers for the hand-parsed JSON above: a missing or mistyped field is null, never a throw.
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.strList(key: String): List<String> = arr(key)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

/** Tunables config.yaml can override; the same numbers the init template documents. */
object Defaults {
    /** LLM turns a step may spend before it FAILS without a report(). */
    const val MAX_TURNS = 12
    /** scrollToFind rounds before NOT_FOUND. */
    const val SCROLL_LIMIT = 3
}

// -------------------------------------------------------------------- geometry

data class Point(val x: Int, val y: Int) {
    /** Squared distance — all proximity checks compare against a squared tolerance. */
    fun dist2(o: Point): Int {
        val dx = x - o.x
        val dy = y - o.y
        return dx * dx + dy * dy
    }
}

data class Bounds(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width get() = x1 - x0
    val height get() = y1 - y0
    val centerX get() = (x0 + x1) / 2
    val centerY get() = (y0 + y1) / 2

    operator fun contains(p: Point): Boolean = p.x in x0..x1 && p.y in y0..y1
}

// -------------------------------------------------------------------- raw layout

/**
 * A single node exactly as `android layout` emits it, after light normalization.
 * All fields except [center] are optional in the source.
 */
data class RawNode(
    val interactions: List<String>,
    val state: List<String>,
    val text: String?,
    val contentDesc: String?,
    val resourceId: String?,
    val center: Point,
    val bounds: Bounds?,
    /** Nested nodes (`children` in layout V2); empty in the legacy flat format. */
    val children: List<RawNode> = emptyList(),
) {
    val isInteractive get() = interactions.isNotEmpty()
    val ownLabel get() = combineLabel(text, contentDesc)
}

// -------------------------------------------------------------------- logical layout

/** Which observation channel produced an element. The model never sees this distinction. */
enum class Origin { NATIVE, WEB }

/**
 * A merged, model-facing element. Coordinates live here for the harness but are NEVER
 * serialized into the model prompt (see `renderFullLayout` in device/Layout.kt) — this
 * prevents the model from hallucinating tap coordinates.
 *
 * WEB elements carry screen-space coordinates already (CSS rect transformed at merge time),
 * so gestures go through the same `adb shell input` path as native elements. For them,
 * [resourceId] holds the DOM `id` attribute.
 */
data class LogicalElement(
    val id: Int,
    val label: String?,
    val resourceId: String?,
    val interactions: List<String>,
    val state: List<String>,
    /** "label" for an orphan text node that has no interactions of its own. */
    val kind: String?,
    val center: Point,
    val bounds: Bounds?,
    val origin: Origin = Origin.NATIVE,
    /** Id of the enclosing element (a list row's card, a card's button); null at the top level. */
    val parentId: Int? = null,
) {
    /**
     * Does [t] name this element exactly: the label, one `" / "`-joined part of it (text / content-desc),
     * or the resourceId — case-insensitive. The matching family grounding, evidence and replay share.
     */
    fun matchesExact(t: String): Boolean =
        label?.equals(t, ignoreCase = true) == true ||
            label?.split(" / ")?.any { it.equals(t, ignoreCase = true) } == true ||
            resourceId?.equals(t, ignoreCase = true) == true

    /** Looser fallback: [t] is a substring of the label. */
    fun matchesLoose(t: String): Boolean = label?.contains(t, ignoreCase = true) == true
}

/**
 * One observation. Hierarchy lives only in [LogicalElement.parentId]; [elements] is ALWAYS the
 * tree's pre-order (a parent precedes its children, `id` == index), so every flat consumer —
 * diff, evidence, replay grounding — keeps working and the model-facing renderer recovers the
 * nesting by indentation. Filter with [pruneReparent] so survivors keep a valid parent.
 */
class LogicalLayout(
    val elements: List<LogicalElement>,
    /**
     * Harness note about the web observation channel, appended to the model-facing tool result
     * (e.g. "WebView present but not inspectable"). Null when web observation is fine or N/A.
     */
    val webNote: String? = null,
) {
    /** True when web content was merged into this observation (diffing is disabled then). */
    val hasWeb: Boolean by lazy { elements.any { it.origin == Origin.WEB } }
}

/** Web page content beyond the viewport, in CSS px. Under a tenth of a viewport is "fits". */
data class WebOverflow(val belowPx: Int, val abovePx: Int, val viewportPx: Int) {
    val below: Boolean get() = belowPx > viewportPx / 10
    val above: Boolean get() = abovePx > viewportPx / 10
}

/** Nesting depth of every element (0 = top level), relying on the pre-order invariant. */
fun depthsOf(elements: List<LogicalElement>): IntArray {
    val depthById = HashMap<Int, Int>()
    return IntArray(elements.size) { i ->
        val e = elements[i]
        val d = e.parentId?.let { depthById[it] }?.plus(1) ?: 0
        depthById[e.id] = d
        d
    }
}

/**
 * Keep the elements matching [keep], in order; a survivor whose parent was dropped re-attaches
 * to its nearest surviving ancestor (or the top level).
 */
fun pruneReparent(elements: List<LogicalElement>, keep: (LogicalElement) -> Boolean): List<LogicalElement> {
    val byId = elements.associateBy { it.id }
    val kept = HashSet<Int>()
    val out = ArrayList<LogicalElement>()
    for (e in elements) {
        if (!keep(e)) continue
        var p = e.parentId
        while (p != null && p !in kept) p = byId[p]?.parentId
        kept += e.id
        out += if (p == e.parentId) e else e.copy(parentId = p)
    }
    return out
}

// -------------------------------------------------------------------- helpers

/** `\r\n`, tabs and repeated spaces collapse to a single space; empty -> null. */
internal fun normalizeText(s: String?): String? {
    if (s == null) return null
    val t = s.replace(Regex("\\s+"), " ").trim()
    return t.ifEmpty { null }
}

/** Combine visible text and accessibility description into one label (distinct, " / " joined). */
internal fun combineLabel(text: String?, desc: String?): String? {
    val parts = listOfNotNull(normalizeText(text), normalizeText(desc)).distinct()
    return if (parts.isEmpty()) null else parts.joinToString(" / ")
}
