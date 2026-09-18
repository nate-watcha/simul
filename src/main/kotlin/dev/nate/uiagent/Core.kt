package dev.nate.uiagent

import kotlinx.serialization.json.Json

/**
 * Shared JSON instances and core data types.
 *
 * We deliberately use the kotlinx-serialization *runtime* only (JsonElement tree),
 * NOT the @Serializable compiler plugin — the plugin version must match the Kotlin
 * compiler and is not always available offline. Manual JsonElement parsing keeps the
 * build dependency-free beyond what koog already pulls in.
 */
internal val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal val prettyJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
}

// -------------------------------------------------------------------- geometry

data class Point(val x: Int, val y: Int)

data class Bounds(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width get() = x1 - x0
    val height get() = y1 - y0
    val centerX get() = (x0 + x1) / 2
    val centerY get() = (y0 + y1) / 2
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
) {
    val isInteractive get() = interactions.isNotEmpty()
    val ownLabel get() = combineLabel(text, contentDesc)
}

// -------------------------------------------------------------------- logical layout

/** Which observation channel produced an element. The model never sees this distinction. */
enum class Origin { NATIVE, WEB }

/**
 * A merged, model-facing element. Coordinates live here for the harness but are NEVER
 * serialized into the model prompt (see [renderModelJson]) — this prevents the model
 * from hallucinating tap coordinates.
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
)

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
