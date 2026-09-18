package dev.nate.uiagent.cli

/**
 * Minimal YAML-subset parser for `.simul/config.yaml` and scenario frontmatter — no external
 * dependency (the serialization compiler plugin is deliberately unavailable, see Core.kt).
 *
 * Supported: nested maps by indentation, `key: scalar`, `key: [a, b]` inline lists, quoted
 * scalars, full-line `#` comments, blank lines. Nothing else (anchors, multi-line strings,
 * block lists) — config and frontmatter stay inside this subset by convention.
 */
object MiniYaml {

    /** Values are String, List<String>, or Map<String, Any> (nested). */
    fun parse(text: String): Map<String, Any> {
        val lines = text.lines()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .map { it.trimEnd() }
        val (map, consumed) = parseBlock(lines, 0, indentOf(lines.getOrNull(0) ?: return emptyMap()))
        require(consumed == lines.size) { "yaml: unexpected outdent/content at '${lines[consumed]}'" }
        return map
    }

    private fun indentOf(line: String): Int = line.indexOfFirst { it != ' ' }

    private fun parseBlock(lines: List<String>, start: Int, indent: Int): Pair<Map<String, Any>, Int> {
        val map = linkedMapOf<String, Any>()
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            val ind = indentOf(line)
            if (ind < indent) break
            require(ind == indent) { "yaml: unexpected indent at '$line'" }
            val content = line.trim()
            val colon = content.indexOf(':')
            require(colon > 0) { "yaml: expected 'key: value' at '$line'" }
            val key = content.substring(0, colon).trim()
            val rest = stripTrailingComment(content.substring(colon + 1)).trim()
            if (rest.isEmpty()) {
                val childIndent = lines.getOrNull(i + 1)?.let(::indentOf) ?: -1
                require(childIndent > indent) { "yaml: '$key:' has no nested block" }
                val (child, next) = parseBlock(lines, i + 1, childIndent)
                map[key] = child
                i = next
            } else {
                map[key] = scalarOrList(rest)
                i++
            }
        }
        return map to i
    }

    /** Drop a trailing ` # comment`. `#` without a preceding space (e.g. in URLs) is kept. */
    private fun stripTrailingComment(s: String): String {
        val idx = s.indexOf(" #")
        return if (idx >= 0) s.substring(0, idx) else s
    }

    private fun scalarOrList(raw: String): Any =
        if (raw.startsWith("[") && raw.endsWith("]")) {
            raw.substring(1, raw.length - 1).split(',').map { unquote(it.trim()) }.filter { it.isNotEmpty() }
        } else {
            unquote(raw)
        }

    private fun unquote(s: String): String =
        if (s.length >= 2 && (s.first() == '"' && s.last() == '"' || s.first() == '\'' && s.last() == '\''))
            s.substring(1, s.length - 1)
        else s

    // -------------------------------------------------------------- typed access helpers

    fun string(map: Map<String, Any>, vararg path: String): String? {
        var cur: Any? = map
        for (p in path) cur = (cur as? Map<*, *>)?.get(p)
        return cur as? String
    }

    fun list(map: Map<String, Any>, vararg path: String): List<String> {
        var cur: Any? = map
        for (p in path) cur = (cur as? Map<*, *>)?.get(p)
        @Suppress("UNCHECKED_CAST")
        return when (cur) {
            is List<*> -> cur.filterIsInstance<String>()
            is String -> listOf(cur)
            else -> emptyList()
        }
    }
}
