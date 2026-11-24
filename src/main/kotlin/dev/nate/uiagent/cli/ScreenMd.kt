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
 * the report reads it, to draw the screen's page in that order ([Site.Place]).
 *
 * A screen that is one screen in several modes — the profile as its owner sees it and as a visitor does — names
 * them on a `modes:` line, in the order the report shows them:
 *
 * ```
 * ---
 * modes: [본인, 타인]
 * parts: [상단 바, 프로필 헤더, [취향 매칭, 홈, 활동, 좋아요, 캘린더]]
 * ---
 * ```
 *
 * A scenario file of the screen, or of anything under it, that is of one mode says so in its name
 * (`헤더@타인.md`), and each mode has its way in (`_entry@타인.md`) — see [Modes].
 *
 * What a mode shows is often the same behaviour on other data — whose profile it is. That data is written here,
 * once, under each mode ([data]), and a scenario names it (`$주인`) where it goes — see [ModeData]:
 *
 * ```
 * ---
 * modes:
 *   본인:
 *     주인: 구름
 *   타인:
 *     주인: WATCHA
 * parts: [상단 바, 프로필 헤더, [취향 매칭, 홈, 활동, 좋아요, 캘린더]]
 * ---
 * ```
 */
data class ScreenLayout(
    val slots: List<Slot>, val modes: List<String> = emptyList(),
    /** Each mode's values, by mode and then by name — the same names in every mode; empty where the modes are only named. */
    val data: Map<String, Map<String, String>> = emptyMap(),
) {
    /** One place down the screen: a [part], or the row of [tabs]. */
    data class Slot(val part: String? = null, val tabs: List<String> = emptyList())

    val parts: List<String> get() = slots.mapNotNull { it.part }
    val tabs: List<String> get() = slots.flatMap { it.tabs }
    /** Every directory the line names. */
    val names: List<String> get() = slots.flatMap { listOfNotNull(it.part) + it.tabs }
}

object ScreenMd {
    const val FILE = "_screen.md"

    /** The layout [text] declares; null when it has neither a `parts:` nor a `modes:` line. [where] names the file in an error. */
    fun parse(text: String, where: String): ScreenLayout? {
        val lines = text.lines()
        val front = if (lines.firstOrNull()?.trim() == "---") lines.drop(1).takeWhile { it.trim() != "---" } else lines
        val data = modeData(front, where)
        val modes = data?.keys?.toList() ?: modes(front, where)
        val at = front.indexOfFirst { it.startsWith("parts:") }.takeIf { it >= 0 }
            ?: return modes.takeIf { it.isNotEmpty() }?.let { ScreenLayout(emptyList(), it, data.orEmpty()) }
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
        val layout = ScreenLayout(slots, modes, data.orEmpty())
        require(layout.names.none { it.isEmpty() || '[' in it || ']' in it }) { "$where: `parts:` has an empty name or a row inside a row" }
        require(slots.count { it.tabs.isNotEmpty() } <= 1) { "$where: `parts:` has two bracketed rows — a screen has one row of tabs" }
        layout.names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.let { twice ->
            require(twice.isEmpty()) { "$where: `parts:` names ${twice.joinToString(", ")} twice" }
        }
        return layout
    }

    /** The `modes: [본인, 타인]` line of [front]: the screen's modes, in order; empty without the line. */
    private fun modes(front: List<String>, where: String): List<String> {
        val line = front.firstOrNull { it.startsWith("modes:") } ?: return emptyList()
        val inline = line.removePrefix("modes:").trim()
        require(inline.startsWith("[") && inline.endsWith("]")) { "$where: `modes:` is a list on its line — `modes: [본인, 타인]`" }
        val names = inline.substring(1, inline.length - 1).split(',').map(::name)
        require(names.size >= 2 && names.none { it.isEmpty() || Modes.SEP in it || '/' in it || '[' in it }) {
            "$where: `modes:` names two or more modes, plain names — `modes: [본인, 타인]`"
        }
        require(names.distinct().size == names.size) { "$where: `modes:` names a mode twice" }
        return names
    }

    /**
     * The block under a bare `modes:` line — a mode a line, its values indented under it — as mode → name → value,
     * in the order written; null when the line is not there or names the modes on itself (`modes: [본인, 타인]`).
     */
    private fun modeData(front: List<String>, where: String): Map<String, Map<String, String>>? {
        val at = front.indexOfFirst { it.startsWith("modes:") }.takeIf { it >= 0 } ?: return null
        if (front[at].removePrefix("modes:").trim().isNotEmpty()) return null
        val how = "$where: under `modes:` each mode is a line and its values the lines indented under it — `  타인:` then `    주인: WATCHA`"
        val block = front.drop(at + 1).takeWhile { it.isBlank() || it.startsWith(" ") }
        val parsed = try { MiniYaml.parse((listOf("modes:") + block).joinToString("\n"))["modes"] } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("$how (${e.message})")
        }
        val byMode = (parsed as? Map<*, *>)?.entries?.associate { (mode, values) ->
            val its = (values as? Map<*, *>)?.entries?.associate { (k, v) ->
                require(v is String && v.isNotBlank()) { how }
                require(v.none { it in "\"“”‘’'$" }) { "$where: `modes:` $mode.$k — a value cannot contain quotes or \$" }
                Mentions.norm(k as String) to v.trim()
            }
            Mentions.norm(mode as String) to (its ?: throw IllegalArgumentException(how))
        } ?: throw IllegalArgumentException(how)
        require(byMode.size >= 2 && byMode.keys.none { it.isEmpty() || Modes.SEP in it || '/' in it || '[' in it }) {
            "$where: `modes:` names two or more modes, plain names"
        }
        val names = byMode.values.first().keys
        require(names.none { n -> n.isEmpty() || n.any { it.isWhitespace() || it in "$@\"'" } }) { "$where: `modes:` — a value's name is one word (it is written `\$name` in a scenario)" }
        byMode.forEach { (mode, its) ->
            require(its.keys == names) { "$where: `modes:` — every mode has the same values (${names.joinToString(", ")}); $mode has ${its.keys.joinToString(", ")}" }
        }
        return byMode
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

/**
 * A screen's modes in file names and in what a scenario starts from.
 *
 * A screen with `modes: [본인, 타인]` ([ScreenLayout.modes]) is one screen that behaves in two ways. What differs
 * between them is written as files of their own, the mode after an `@` in the name: `헤더@본인.md` and
 * `헤더@타인.md` are the same check in each mode, `차단@타인.md` one only that mode has. A file without a mode is
 * common to the modes and runs once, in the first of them. The way in is a file per mode too — `_entry@본인.md`,
 * `_entry@타인.md` — and a place that only one mode has (a tab) has only that mode's.
 *
 * What a scenario starts from is named by a key: the screen whose way in it is, and after an `@` the mode when that
 * way in is one mode's (`프로필/홈@타인`). [Scenario.via], a section's `setup.entry` and the runner's look-up of an
 * entry all speak in keys; a screen without modes is its own key.
 */
object Modes {
    const val SEP = '@'

    /** `헤더@타인` → `타인`; null for a name without a mode. */
    fun of(stem: String): String? = stem.substringAfterLast(SEP, "").ifEmpty { null }

    /** `헤더@타인` → `헤더`: the name the modes' files of one check share. */
    fun base(stem: String): String = if (SEP in stem) stem.substringBeforeLast(SEP) else stem

    fun key(screen: String, mode: String?): String = if (mode == null) screen else "$screen$SEP$mode"
    fun screenOf(key: String): String = base(key)
    fun modeOf(key: String): String? = of(key)
}

/**
 * A mode's values in a scenario. A screen whose modes differ in data — whose profile it is — says that data once, in
 * its `_screen.md` ([ScreenLayout.data]), and a scenario names a value where it goes: `$주인의 팔로워 목록으로 간다`,
 * `"$주인님의 취향분석"이 보이는지 확인한다`.
 *
 * That is all a scenario does to say its behaviour is the same in every mode on other data: a file common to the
 * modes that names a value runs once in each mode, from that mode's way in, with that mode's value in place, and
 * the report tells it as one line with the values it was run on. A file that names none runs once, as before; a
 * file of one mode (`헤더@타인.md`) is filled with that mode's.
 *
 * The names are the screen's, so where one ends is not in the text — a Korean particle follows unspaced: after a
 * `$` it is the longest of the names the text goes on with, as a mention is ([Mentions]). A name that ends in a
 * Latin letter or a digit ends where such letters do, as a `values:` name does.
 */
object ModeData {
    private fun word(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_'

    /** [text] cut at its `$name`s of [names]: `(text, null)` and `(name, name)` in turn. */
    fun parts(text: String, names: Collection<String>): List<Pair<String, String?>> = buildList {
        if (names.isEmpty() || '$' !in text) { if (text.isNotEmpty()) add(text to null); return@buildList }
        val t = Mentions.norm(text)
        val longest = names.sortedByDescending { it.length }
        var from = 0
        var i = t.indexOf('$')
        while (i >= 0) {
            val name = longest.firstOrNull { n -> t.startsWith(n, i + 1) && !(word(n.last()) && t.getOrNull(i + 1 + n.length)?.let(::word) == true) }
            if (name == null) { i = t.indexOf('$', i + 1); continue }
            if (i > from) add(t.substring(from, i) to null)
            add(name to name)
            from = i + 1 + name.length
            i = t.indexOf('$', from)
        }
        if (from < t.length) add(t.substring(from) to null)
    }

    /** The [names] that [text] names, in order, each once. */
    fun used(text: String, names: Collection<String>): List<String> = parts(text, names).mapNotNull { it.second }.distinct()

    /** [text] with each `$name` replaced by its value in [data]. */
    fun fill(text: String, data: Map<String, String>): String =
        if (data.isEmpty() || '$' !in text) text else parts(text, data.keys).joinToString("") { (t, name) -> name?.let(data::getValue) ?: t }

    /** [text] as a line of spec reads: the name where the value goes — `주인의 팔로워 목록으로 간다`. */
    fun plain(text: String, names: Collection<String>): String =
        if (names.isEmpty() || '$' !in text) text else parts(text, names).joinToString("") { it.first }
}
