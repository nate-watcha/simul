package dev.nate.uiagent.device

import dev.nate.uiagent.LogicalElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import dev.nate.uiagent.prettyJson

/**
 * Model-facing rendering and diffing. Elements are referenced by LABEL, not by a
 * numeric id: ids are re-assigned every observation, so they drift in the conversation
 * history, while labels stay meaningful. Elements without any label get a synthetic
 * positional handle ("@x360y1176") that doubles as the tap target.
 */

/** The stable, model-facing handle of an element: label, else resourceId, else "@x..y..". */
fun handleOf(e: LogicalElement): String =
    e.label ?: e.resourceId ?: syntheticHandle(e)

fun syntheticHandle(e: LogicalElement): String = "@x${e.center.x}y${e.center.y}"

/**
 * Identity key for diffing. Label/resourceId identify an element across observations;
 * unlabeled elements fall back to their position, which makes them churn under scrolling —
 * an accepted limitation (rendering is capped, see [renderDiff]).
 */
fun diffKey(e: LogicalElement): String =
    listOfNotNull(e.label, e.resourceId).joinToString("|").ifEmpty { syntheticHandle(e) }

/** One rendered element as shown to the model (no coordinates — hallucination guard). */
internal fun elementJson(e: LogicalElement): String = buildJsonObject {
    put("label", handleOf(e))
    e.resourceId?.let { put("resourceId", it) }
    if (e.interactions.isNotEmpty()) putJsonArray("interactions") { e.interactions.forEach { add(JsonPrimitive(it)) } }
    if (e.state.isNotEmpty()) putJsonArray("state") { e.state.forEach { add(JsonPrimitive(it)) } }
    e.kind?.let { put("kind", it) }
}.toString()

/** The full layout as shown to the model (initial message and the layout() tool). */
fun renderFullLayout(elements: List<LogicalElement>): String {
    val arr: JsonArray = buildJsonArray {
        elements.forEach { e ->
            add(buildJsonObject {
                put("label", handleOf(e))
                e.resourceId?.let { put("resourceId", it) }
                if (e.interactions.isNotEmpty()) putJsonArray("interactions") { e.interactions.forEach { add(JsonPrimitive(it)) } }
                if (e.state.isNotEmpty()) putJsonArray("state") { e.state.forEach { add(JsonPrimitive(it)) } }
                e.kind?.let { put("kind", it) }
            })
        }
    }
    return prettyJson.encodeToString(JsonArray.serializer(), arr)
}

// -------------------------------------------------------------------- diff

data class LayoutDiff(
    val added: List<LogicalElement>,
    val removed: List<LogicalElement>,
    /** Same key present on both sides but with a different state (e.g. selected flipped). */
    val changed: List<LogicalElement>,
    /** Number of elements suppressed because their key is known to auto-change (carousels). */
    val volatileSuppressed: Int,
) {
    val isEmpty get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}

/**
 * Multiset diff by [diffKey]. Elements whose key is in [volatileKeys] are counted but not
 * reported — they change on their own (auto-rotating banners) and are not evidence of anything.
 */
fun computeDiff(
    old: List<LogicalElement>,
    new: List<LogicalElement>,
    volatileKeys: Set<String> = emptySet(),
): LayoutDiff {
    val oldByKey = old.groupBy(::diffKey)
    val newByKey = new.groupBy(::diffKey)

    val added = mutableListOf<LogicalElement>()
    val removed = mutableListOf<LogicalElement>()
    val changed = mutableListOf<LogicalElement>()
    var volatileSuppressed = 0

    for ((key, newElems) in newByKey) {
        val oldElems = oldByKey[key] ?: emptyList()
        if (newElems.size > oldElems.size) {
            val extra = newElems.size - oldElems.size
            if (key in volatileKeys) volatileSuppressed += extra
            else added += newElems.take(extra)
        }
        // state change: same cardinality but the state multiset differs
        if (oldElems.isNotEmpty()) {
            val oldStates = oldElems.map { it.state.sorted() }.sorted(compareStates)
            val newStates = newElems.map { it.state.sorted() }.sorted(compareStates)
            if (oldStates != newStates) {
                if (key in volatileKeys) volatileSuppressed++
                else changed += newElems.first()
            }
        }
    }
    for ((key, oldElems) in oldByKey) {
        val newElems = newByKey[key] ?: emptyList()
        if (oldElems.size > newElems.size) {
            val extra = oldElems.size - newElems.size
            if (key in volatileKeys) volatileSuppressed += extra
            else removed += oldElems.take(extra)
        }
    }
    return LayoutDiff(added, removed, changed, volatileSuppressed)
}

private val compareStates = Comparator<List<String>> { a, b -> a.joinToString().compareTo(b.joinToString()) }
private fun List<List<String>>.sorted(c: Comparator<List<String>>) = sortedWith(c)

/** How many entries of each kind (+/-/~) the model gets to see before truncation. */
private const val MAX_DIFF_ENTRIES = 25

/**
 * Render a diff as the tool result body. An empty diff renders the NO CHANGE sentinel so the
 * model immediately sees that its action had no effect.
 */
fun renderDiff(diff: LayoutDiff): String {
    if (diff.isEmpty) {
        val suffix = if (diff.volatileSuppressed > 0) " (only auto-rotating content changed)" else ""
        return "NO CHANGE — the action had no visible effect.$suffix"
    }
    val sb = StringBuilder("LAYOUT DIFF:\n")
    fun section(sign: String, elems: List<LogicalElement>) {
        elems.take(MAX_DIFF_ENTRIES).forEach { sb.append(sign).append(' ').append(elementJson(it)).append('\n') }
        if (elems.size > MAX_DIFF_ENTRIES) sb.append("$sign … and ${elems.size - MAX_DIFF_ENTRIES} more\n")
    }
    section("+", diff.added)
    section("-", diff.removed)
    diff.changed.take(MAX_DIFF_ENTRIES).forEach { sb.append("~ ").append(elementJson(it)).append(" (state changed)\n") }
    if (diff.volatileSuppressed > 0) sb.append("(${diff.volatileSuppressed} auto-rotating elements omitted)\n")
    return sb.toString().trimEnd()
}

// -------------------------------------------------------------------- stability signature

/**
 * Observation signature for wait-for-stable: two observations with equal signatures are
 * considered "settled". Includes centers so scroll/settle animation is detected, and states
 * so selection flips are too.
 */
fun signature(elements: List<LogicalElement>): Set<String> =
    elements.map { "${diffKey(it)}@${it.center.x},${it.center.y}|${it.state.sorted().joinToString(",")}" }.toSet()
