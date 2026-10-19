package dev.nate.uiagent.cli

import dev.nate.uiagent.Bounds
import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.Point
import dev.nate.uiagent.arr
import dev.nate.uiagent.int
import dev.nate.uiagent.json
import dev.nate.uiagent.obj
import dev.nate.uiagent.str
import dev.nate.uiagent.strList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * `layout/NN.json` — the layout tree as it stood when step NN finished, one element per line
 * (pre-order, `id` == index, `parentId` links the tree). This is what the report compares day
 * to day: a screen "changed" when its resourceId set changes or an element moved, never when
 * its text did. Coordinates are kept here on purpose — this file is for the report, not the
 * model.
 */
object LayoutJson {

    /** One element as the report reads it back. */
    data class Node(
        val id: Int,
        val parentId: Int?,
        val resourceId: String?,
        val label: String?,
        val bounds: Bounds?,
        val center: Point,
        val state: List<String>,
        val interactions: List<String>,
        val kind: String?,
    )

    fun write(elements: List<LogicalElement>, file: File) {
        file.parentFile?.mkdirs()
        file.writeText(elements.joinToString(",\n", prefix = "[\n", postfix = "\n]\n") { "  " + elementLine(it) })
    }

    /** The in-memory form of what [write] stores, for comparing a live layout without a file. */
    fun nodes(elements: List<LogicalElement>): List<Node> =
        elements.map { Node(it.id, it.parentId, it.resourceId, it.label, it.bounds, it.center, it.state, it.interactions, it.kind) }

    private fun elementLine(e: LogicalElement): String = buildJsonObject {
        put("id", e.id)
        e.parentId?.let { put("parentId", it) }
        e.resourceId?.let { put("resourceId", it) }
        e.label?.let { put("label", it) }
        e.bounds?.let { b -> put("bounds", buildJsonArray { add(JsonPrimitive(b.x0)); add(JsonPrimitive(b.y0)); add(JsonPrimitive(b.x1)); add(JsonPrimitive(b.y1)) }) }
        put("center", buildJsonArray { add(JsonPrimitive(e.center.x)); add(JsonPrimitive(e.center.y)) })
        if (e.state.isNotEmpty()) put("state", buildJsonArray { e.state.forEach { add(JsonPrimitive(it)) } })
        if (e.interactions.isNotEmpty()) put("interactions", buildJsonArray { e.interactions.forEach { add(JsonPrimitive(it)) } })
        e.kind?.let { put("kind", it) }
        if (e.origin == dev.nate.uiagent.Origin.WEB) put("origin", "web")
    }.toString()

    fun read(file: File): List<Node>? = runCatching {
        json.parseToJsonElement(file.readText()).jsonArray.map { el ->
            val o = el.jsonObject
            fun ints(key: String): List<Int>? = (o[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            val b = ints("bounds")?.takeIf { it.size == 4 }
            val c = ints("center")?.takeIf { it.size == 2 } ?: listOf(0, 0)
            Node(
                id = o.int("id") ?: 0,
                parentId = o.int("parentId"),
                resourceId = o.str("resourceId"),
                label = o.str("label"),
                bounds = b?.let { Bounds(it[0], it[1], it[2], it[3]) },
                center = Point(c[0], c[1]),
                state = o.strList("state"),
                interactions = o.strList("interactions"),
                kind = o.str("kind"),
            )
        }
    }.getOrNull()

    /**
     * A short, stable fingerprint of a run's screens: the resourceIds and bounds of every
     * step's layout, in order. Text and content are left out, so the hash names a screen
     * *structure* — the thing an "intended change" acknowledgement refers to.
     */
    fun hash(stepLayouts: List<List<Node>>): String {
        val md = MessageDigest.getInstance("SHA-1")
        for (nodes in stepLayouts) {
            for (n in nodes) {
                val b = n.bounds
                md.update("${n.resourceId ?: ""}@${b?.x0 ?: n.center.x},${b?.y0 ?: n.center.y},${b?.x1 ?: ""},${b?.y1 ?: ""}\n".toByteArray())
            }
            md.update("--\n".toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(12)
    }

    /** The structural difference of one step's screen between two runs; null when nothing structural moved. */
    data class ScreenChange(val step: Int, val added: List<String>, val removed: List<String>, val moved: List<String>) {
        /** `새 resourceId 2개: price_badge, cta_secondary · 사라짐 1개: empty_cta · 이동 4개` */
        fun summary(): String = listOfNotNull(
            added.takeIf { it.isNotEmpty() }?.let { "새 resourceId ${it.size}개: ${it.take(4).joinToString(", ")}" },
            removed.takeIf { it.isNotEmpty() }?.let { "사라짐 ${it.size}개: ${it.take(4).joinToString(", ")}" },
            moved.takeIf { it.isNotEmpty() }?.let { "이동 ${it.size}개" + if (it.size <= 3) ": ${it.joinToString(", ")}" else "" },
        ).joinToString(" · ")
    }

    /**
     * Compare one step's layout across two runs the way the report classifies "screen changed":
     * the set of resourceIds differs, or an element with the same (unique) resourceId moved by
     * [threshold] px or more on any edge. Labels, text and states are ignored.
     */
    fun compare(step: Int, before: List<Node>, after: List<Node>, threshold: Int = 8): ScreenChange? {
        val idsBefore = before.mapNotNull { it.resourceId }.toSet()
        val idsAfter = after.mapNotNull { it.resourceId }.toSet()
        val added = (idsAfter - idsBefore).sorted()
        val removed = (idsBefore - idsAfter).sorted()
        fun unique(nodes: List<Node>) = nodes.filter { it.resourceId != null }.groupBy { it.resourceId!! }.filterValues { it.size == 1 }.mapValues { it.value.single() }
        val ub = unique(before)
        val ua = unique(after)
        val moved = (ub.keys intersect ua.keys).filter { id ->
            val a = ub.getValue(id).bounds
            val b = ua.getValue(id).bounds
            if (a == null || b == null) ub.getValue(id).center.let { c -> val d = ua.getValue(id).center; kotlin.math.abs(c.x - d.x) >= threshold || kotlin.math.abs(c.y - d.y) >= threshold }
            else listOf(a.x0 - b.x0, a.y0 - b.y0, a.x1 - b.x1, a.y1 - b.y1).any { kotlin.math.abs(it) >= threshold }
        }.sorted()
        return if (added.isEmpty() && removed.isEmpty() && moved.isEmpty()) null else ScreenChange(step, added, removed, moved)
    }
}
