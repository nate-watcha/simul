package dev.nate.uiagent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/**
 * Converts the `android layout` output (flat list or tree — see [parseRawLayout]) into a
 * merged list of logical elements. Rationale is documented per step; thresholds are validated against real device data.
 */
object LayoutAdapter {

    // Label-to-target attachment window (center-to-center), validated on real app layout data.
    private const val MAX_DX = 60
    private const val MAX_DY = 45

    fun adapt(rawJson: String): LogicalLayout = LogicalLayout(build(parseRawLayout(rawJson)))

    // ---------------------------------------------------------------- parse

    /**
     * Both `android layout` output generations are accepted:
     *  - ≤ 1.0.15498356: a flat JSON array, lowercase `interactions`/`state`, short `resource-id`
     *  - ≥ 1.0.16261425: a status line first ("Installing layout instrumentation server..."),
     *    then a tree — every node may carry `children` — with UPPERCASE `interactions`/`state`
     *    and full `resource-id`s (`com.example:id/name`). Seen on the nightly runner: the
     *    top level alone parsed to 15 nodes and the whole bottom nav was in the children.
     * Everything is normalised to the old vocabulary so traces and evidence stay valid.
     */
    fun parseRawLayout(rawJson: String): List<RawNode> {
        // skip any non-JSON preamble the CLI prints on stdout
        val start = rawJson.indexOf('[').takeIf { it >= 0 } ?: return emptyList()
        // malformed/truncated dump -> no elements, not a crash; callers poll again
        val parsed = runCatching { json.parseToJsonElement(rawJson.substring(start)) }.getOrNull()
        val arr = parsed as? JsonArray ?: return emptyList()
        val out = mutableListOf<RawNode>()
        fun visit(el: kotlinx.serialization.json.JsonElement) {
            val o = el as? JsonObject ?: return
            val center = o["center"]?.jsonPrimitive?.contentOrNull?.let(::parsePoint)
            if (center != null) {
                out += RawNode(
                    interactions = o["interactions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() } ?: emptyList(),
                    state = o["state"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() } ?: emptyList(),
                    text = o["text"]?.jsonPrimitive?.contentOrNull,
                    contentDesc = o["content-desc"]?.jsonPrimitive?.contentOrNull,
                    resourceId = o["resource-id"]?.jsonPrimitive?.contentOrNull?.let(::shortResourceId),
                    center = center,
                    bounds = o["bounds"]?.jsonPrimitive?.contentOrNull?.let(::parseBounds),
                )
            }
            (o["children"] as? JsonArray)?.forEach(::visit)
        }
        arr.forEach(::visit)
        return out
    }

    private val fullIdPrefix = Regex("^[A-Za-z0-9_.]+:id/")

    /** `com.example.app:id/recycler_view` -> `recycler_view` (what traces and scenarios use). */
    fun shortResourceId(id: String): String = fullIdPrefix.replace(id, "")

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

