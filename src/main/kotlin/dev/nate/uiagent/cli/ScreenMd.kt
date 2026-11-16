package dev.nate.uiagent.cli

/**
 * What a screen is made of, in the order it is drawn — the `parts:` line of the `_screen.md` in the screen's
 * directory:
 *
 * ```
 * ---
 * parts: [상단 바, 프로필 헤더, [홈, 활동, 좋아요, 캘린더]]
 * ---
 * ```
 *
 * Top to bottom as the screen is: a name is a part of it (the directory of that name under the screen's), a
 * bracketed row is its tabs. A directory under the screen that the line does not name is a screen that opens from
 * it. The file says what the screen is; how it is entered is `_entry.md`'s. It is not a scenario and is never run —
 * only the report reads it, to draw the screen's page in that order ([Site.Place]).
 */
data class ScreenLayout(val slots: List<Slot>) {
    /** One place down the screen: a [part], or the row of [tabs]. */
    data class Slot(val part: String? = null, val tabs: List<String> = emptyList())

    val parts: List<String> get() = slots.mapNotNull { it.part }
    val tabs: List<String> get() = slots.flatMap { it.tabs }
    /** Every directory the line names. */
    val names: List<String> get() = slots.flatMap { listOfNotNull(it.part) + it.tabs }
}

object ScreenMd {
    const val FILE = "_screen.md"

    /** The layout [text] declares; null when it has no `parts:` line. [where] names the file in an error. */
    fun parse(text: String, where: String): ScreenLayout? {
        val lines = text.lines()
        val front = if (lines.firstOrNull()?.trim() == "---") lines.drop(1).takeWhile { it.trim() != "---" } else lines
        val at = front.indexOfFirst { it.startsWith("parts:") }.takeIf { it >= 0 } ?: return null
        val inline = front[at].removePrefix("parts:").trim()
        // `parts: [a, b, [c, d]]` on the line, or one `- item` a line under it
        val items = if (inline.isNotEmpty()) {
            require(inline.startsWith("[") && inline.endsWith("]")) { "$where: `parts:` is a list — `parts: [상단 바, 프로필 헤더, [홈, 활동]]`" }
            split(inline.substring(1, inline.length - 1), where)
        } else front.drop(at + 1).takeWhile { it.isBlank() || it.startsWith(" ") || it.startsWith("-") }.filter { it.isNotBlank() }.map { line ->
            require(line.trim().startsWith("-")) { "$where: under `parts:` each line is `- name` or `- [tab, tab]` (got `${line.trim()}`)" }
            line.trim().removePrefix("-").trim()
        }
        val slots = items.map { item ->
            if (item.startsWith("[")) {
                require(item.endsWith("]")) { "$where: `parts:` has an unclosed `[`" }
                ScreenLayout.Slot(tabs = split(item.substring(1, item.length - 1), where).map(::name))
            } else ScreenLayout.Slot(part = name(item))
        }
        val layout = ScreenLayout(slots)
        require(layout.names.none { it.isEmpty() || '[' in it || ']' in it }) { "$where: `parts:` has an empty name or a row inside a row" }
        require(slots.count { it.tabs.isNotEmpty() } <= 1) { "$where: `parts:` has two bracketed rows — a screen has one row of tabs" }
        layout.names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.let { twice ->
            require(twice.isEmpty()) { "$where: `parts:` names ${twice.joinToString(", ")} twice" }
        }
        return layout
    }

    private fun name(raw: String) = Mentions.norm(raw.trim().removeSurrounding("\"").removeSurrounding("'"))

    /** [inner] cut at the commas that are not inside a bracketed row. */
    private fun split(inner: String, where: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        val cur = StringBuilder()
        for (c in inner) {
            when {
                c == '[' -> { depth++; cur.append(c) }
                c == ']' -> { depth--; require(depth >= 0) { "$where: `parts:` has a `]` too many" }; cur.append(c) }
                c == ',' && depth == 0 -> { out += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
        }
        require(depth == 0) { "$where: `parts:` has an unclosed `[`" }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }
}
