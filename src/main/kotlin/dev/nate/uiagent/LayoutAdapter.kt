package dev.nate.uiagent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/**
 * Converts the flat, hierarchy-free `android layout` output into a merged list of logical
 * elements. Rationale is documented per step; thresholds are validated against real device data.
 */
object LayoutAdapter {

    // Label-to-target attachment window (center-to-center), validated on real app layout data.
    private const val MAX_DX = 60
    private const val MAX_DY = 45

    fun adapt(rawJson: String): LogicalLayout = LogicalLayout(build(parseRawLayout(rawJson)))

    // ---------------------------------------------------------------- parse

    fun parseRawLayout(rawJson: String): List<RawNode> {
        // malformed/truncated dump -> no elements, not a crash; callers poll again
        val parsed = runCatching { json.parseToJsonElement(rawJson) }.getOrNull()
        val arr = parsed as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val center = o["center"]?.jsonPrimitive?.contentOrNull?.let(::parsePoint) ?: return@mapNotNull null
            RawNode(
                interactions = o["interactions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                state = o["state"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                text = o["text"]?.jsonPrimitive?.contentOrNull,
                contentDesc = o["content-desc"]?.jsonPrimitive?.contentOrNull,
                resourceId = o["resource-id"]?.jsonPrimitive?.contentOrNull,
                center = center,
                bounds = o["bounds"]?.jsonPrimitive?.contentOrNull?.let(::parseBounds),
            )
        }
    }

    // ---------------------------------------------------------------- merge

    /** Mutable holder for an interactive node whose label may be filled in by a nearby text node. */
    private class Holder(val node: RawNode, var label: String?)

    private fun build(nodes: List<RawNode>): List<LogicalElement> {
        // Step 2: classify. Nodes that are neither interactive nor a label are dropped.
        val holders = nodes.filter { it.isInteractive }.map { Holder(it, it.ownLabel) }
        val labelNodes = nodes.filter { !it.isInteractive && (it.text != null || it.contentDesc != null) }

        // Step 3+4: attach each label to nearest interactive within threshold; keep orphans.
        val orphans = mutableListOf<RawNode>()
        for (lbl in labelNodes) {
            val candidate = holders
                .filter { abs(it.node.center.x - lbl.center.x) <= MAX_DX && abs(it.node.center.y - lbl.center.y) <= MAX_DY }
                .minByOrNull { dist2(it.node.center, lbl.center) }
            if (candidate != null) {
                if (candidate.label == null) candidate.label = lbl.ownLabel
                // else: same target already labeled (e.g. text + content-desc duplicate) -> consumed
            } else {
                orphans += lbl
            }
        }

        // Step 5: dedupe interactives sharing a label — keep the clickable one(s).
        val dropped = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Holder, Boolean>())
        holders.filter { it.label != null }.groupBy { it.label }.forEach { (_, group) ->
            if (group.size > 1 && group.any { "clickable" in it.node.interactions }) {
                group.filter { "clickable" !in it.node.interactions }.forEach { dropped.add(it) }
            }
        }
        val keptHolders = holders.filterNot { dropped.contains(it) }

        // Assemble logical elements: interactive first, then orphan labels.
        data class Pending(val label: String?, val resourceId: String?, val interactions: List<String>,
                           val state: List<String>, val kind: String?, val center: Point, val bounds: Bounds?)

        val pending = buildList {
            keptHolders.forEach {
                add(Pending(it.label, it.node.resourceId, it.node.interactions, it.node.state, null, it.node.center, it.node.bounds))
            }
            orphans.forEach {
                add(Pending(it.ownLabel, it.resourceId, emptyList(), it.state, "label", it.center, it.bounds))
            }
        }

        // Step 6: order top-to-bottom, left-to-right for natural reading, then assign sequential ids.
        return pending
            .sortedWith(compareBy({ it.center.y }, { it.center.x }))
            .mapIndexed { i, p ->
                LogicalElement(
                    id = i,
                    label = p.label,
                    resourceId = p.resourceId,
                    interactions = p.interactions,
                    state = p.state,
                    kind = p.kind,
                    center = p.center,
                    bounds = p.bounds,
                )
            }
    }

    // ---------------------------------------------------------------- geometry parsing

    /** "[648,1176]" -> Point(648, 1176). */
    internal fun parsePoint(s: String): Point? =
        ints(s).let { if (it.size >= 2) Point(it[0], it[1]) else null }

    /** "[0,260][720,1118]" -> Bounds(0,260,720,1118). */
    internal fun parseBounds(s: String): Bounds? =
        ints(s).let { if (it.size >= 4) Bounds(it[0], it[1], it[2], it[3]) else null }

    private fun ints(s: String): List<Int> =
        Regex("-?\\d+").findAll(s).map { it.value.toInt() }.toList()

    private fun dist2(a: Point, b: Point): Int {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }
}

