package dev.nate.uiagent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/**
 * Converts the `android layout` output (flat list or tree — see [parseRawLayout]) into the
 * logical element tree the model sees: tree in → logical tree out. Labels are attached to
 * their interactive owner structurally first (a tappable row absorbs its single text child),
 * geometrically as a fallback (the legacy flat format has no structure), and interactives
 * sharing a label are deduped. The result is emitted in pre-order with `parentId` links —
 * see [LogicalLayout]. Thresholds are validated against real device data.
 */
object LayoutAdapter {

    // Label-to-target attachment window (center-to-center), validated on real app layout data.
    private const val MAX_DX = 60
    private const val MAX_DY = 45

    fun adapt(rawJson: String): LogicalLayout = LogicalLayout(build(parseRawLayout(rawJson)))

    // ---------------------------------------------------------------- parse

    /**
     * Parses the dump into a forest of [RawNode]s (the roots; nesting in [RawNode.children]).
     * Both `android layout` output generations are accepted:
     *  - ≤ 1.0.15498356: a flat JSON array, lowercase `interactions`/`state`, short `resource-id`
     *    — every node is a root.
     *  - ≥ 1.0.16251017 (layout V2, first seen 1.0.16261425): status lines first ("Unpacking
     *    embedded installation...", "Installing layout instrumentation server..."), then a tree —
     *    every node may carry `children` — with UPPERCASE `interactions`/`state` and full
     *    `resource-id`s (`com.example:id/name`). Seen on the nightly runner: the top level alone
     *    parsed to 15 nodes and the whole bottom nav was in the children. 1.0.16406183 kept this
     *    shape unchanged (verified node-for-node against 1.0.16261425 on the same screen).
     * The default dump already drops pure containers; `--full` adds `hidden`/`off-screen`
     * booleans, and nodes flagged with either are skipped here so a full dump grounds the same
     * as a default one. A node without a `center` contributes only its children (hoisted to its
     * parent). Text after the array (the CLI's "A new version ... is available" notice when it
     * lands on stdout) is ignored.
     * Everything is normalised to the old vocabulary so traces and evidence stay valid.
     */
    fun parseRawLayout(rawJson: String): List<RawNode> {
        // skip any non-JSON preamble / postamble the CLI prints on stdout
        val start = rawJson.indexOf('[').takeIf { it >= 0 } ?: return emptyList()
        val end = rawJson.lastIndexOf(']').takeIf { it > start } ?: return emptyList()
        // malformed/truncated dump -> no elements, not a crash; callers poll again
        val parsed = runCatching { json.parseToJsonElement(rawJson.substring(start, end + 1)) }.getOrNull()
        val arr = parsed as? JsonArray ?: return emptyList()
        fun visit(el: JsonElement, into: MutableList<RawNode>) {
            val o = el as? JsonObject ?: return
            // `--full` only: invisible subtrees must not become tap targets
            if (o["hidden"].isTrue() || o["off-screen"].isTrue()) return
            val children = mutableListOf<RawNode>()
            (o["children"] as? JsonArray)?.forEach { visit(it, children) }
            val center = o["center"]?.jsonPrimitive?.contentOrNull?.let(::parsePoint)
            if (center == null) {
                into += children
                return
            }
            into += RawNode(
                interactions = o["interactions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() } ?: emptyList(),
                state = o["state"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() } ?: emptyList(),
                text = o["text"]?.jsonPrimitive?.contentOrNull,
                contentDesc = o["content-desc"]?.jsonPrimitive?.contentOrNull,
                resourceId = o["resource-id"]?.jsonPrimitive?.contentOrNull?.let(::shortResourceId),
                center = center,
                bounds = o["bounds"]?.jsonPrimitive?.contentOrNull?.let(::parseBounds),
                children = children,
            )
        }
        val roots = mutableListOf<RawNode>()
        arr.forEach { visit(it, roots) }
        return roots
    }

    /** Pre-order flattening of a parsed forest (tests and debugging). */
    fun List<RawNode>.flatten(): List<RawNode> = flatMap { listOf(it) + it.children.flatten() }

    private fun JsonElement?.isTrue(): Boolean = (this as? JsonPrimitive)?.contentOrNull == "true"

    private val fullIdPrefix = Regex("^[A-Za-z0-9_.]+:id/")

    /** `com.example.app:id/recycler_view` -> `recycler_view` (what traces and scenarios use). */
    fun shortResourceId(id: String): String = fullIdPrefix.replace(id, "")

    // ---------------------------------------------------------------- merge

    /** Mutable tree node; [label] of an interactive may be filled in by a text node. */
    private class Node(val raw: RawNode, var label: String?, val children: MutableList<Node>) {
        val isInteractive get() = raw.isInteractive
        /** A non-interactive node carrying text: a label owned by some interactive, or an orphan. */
        val isLabel get() = !raw.isInteractive && label != null
    }

    private val sentinel = RawNode(emptyList(), emptyList(), null, null, null, Point(0, 0), null)

    private fun build(roots: List<RawNode>): List<LogicalElement> {
        fun toNode(r: RawNode): Node = Node(r, r.ownLabel, r.children.map(::toNode).toMutableList())
        val root = Node(sentinel, null, roots.map(::toNode).toMutableList())

        // Pass 0: prune. A node that is neither interactive nor a label is dropped and its
        // children take its place (document order kept). Rare in the default dump.
        fun prune(n: Node) {
            val out = mutableListOf<Node>()
            for (c in n.children) {
                prune(c)
                if (c.isInteractive || c.isLabel) out += c else out += c.children
            }
            n.children.clear(); n.children += out
        }
        prune(root)

        // Pass A: structural absorption. An unlabeled interactive with exactly one label child,
        // itself a leaf, takes that label (bottom nav item, chip, list row). Several text
        // children (a card: title, subtitle, ...) stay nested — the hierarchy says they belong to
        // the card, and picking one would be arbitrary and unstable between observations.
        // A text node that carries a resourceId stays nested even when absorbed: scenarios anchor
        // on it and traces record it as evidence (tvDeleteHistory, tv_row_title on kloud).
        fun absorb(n: Node) {
            n.children.forEach(::absorb)
            if (n.isInteractive && n.label == null) {
                val only = n.children.singleOrNull { it.isLabel }?.takeIf { it.children.isEmpty() } ?: return
                n.label = only.label
                if (only.raw.resourceId == null) n.children.remove(only)
            }
        }
        absorb(root)

        // Pass B: geometric fallback (the legacy flat format has no structure; siblings). A text
        // node within the window of an unlabeled interactive labels it; one that repeats the
        // nearest interactive's label (text + content-desc duplicates) is consumed; any other text
        // stays as a label — never lose a label an author or a trace may refer to.
        val interactives = mutableListOf<Node>()
        fun collect(n: Node) { n.children.forEach { if (it.isInteractive) interactives += it; collect(it) } }
        collect(root)
        fun attach(n: Node) {
            val out = mutableListOf<Node>()
            for (c in n.children) {
                val nearest = if (!c.isLabel) null else interactives
                    .filter { abs(it.raw.center.x - c.raw.center.x) <= MAX_DX && abs(it.raw.center.y - c.raw.center.y) <= MAX_DY }
                    .minByOrNull { dist2(it.raw.center, c.raw.center) }
                val consumed = when {
                    nearest == null -> false
                    nearest.label == null -> { nearest.label = c.label; c.raw.resourceId == null }
                    else -> nearest.label == c.label && c.raw.resourceId == null
                }
                attach(c)
                if (consumed) out += c.children else out += c
            }
            n.children.clear(); n.children += out
        }
        attach(root)

        // Pass C: dedupe interactives sharing a label — keep the clickable one(s); a dropped
        // node's children move up to its parent (toolbar FrameLayout > ImageView duplicates).
        val dropped = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Node, Boolean>())
        interactives.filter { it.label != null }.groupBy { it.label }.forEach { (_, group) ->
            if (group.size > 1 && group.any { "clickable" in it.raw.interactions }) {
                group.filter { "clickable" !in it.raw.interactions }.forEach { dropped.add(it) }
            }
        }
        fun dedupe(n: Node) {
            val out = mutableListOf<Node>()
            for (c in n.children) {
                dedupe(c)
                if (dropped.contains(c)) out += c.children else out += c
            }
            n.children.clear(); n.children += out
        }
        dedupe(root)

        // Emit in pre-order: id = index, parentId = the enclosing element's id.
        val out = ArrayList<LogicalElement>()
        fun emit(n: Node, parentId: Int?) {
            for (c in n.children) {
                val id = out.size
                out += LogicalElement(
                    id = id,
                    label = c.label,
                    resourceId = c.raw.resourceId,
                    interactions = c.raw.interactions,
                    state = c.raw.state,
                    kind = if (c.isInteractive) null else "label",
                    center = c.raw.center,
                    bounds = c.raw.bounds,
                    parentId = parentId,
                )
                emit(c, id)
            }
        }
        emit(root, null)
        return out
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
