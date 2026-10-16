package dev.nate.uiagent.cli

import org.jline.utils.WCWidth

/**
 * Terminal text geometry for the TUI, aware of two things `String.length` is not: SGR escape
 * sequences take no columns, and East-Asian characters (the scenario names here are Korean)
 * take two. Everything that lays out a row goes through these so columns stay aligned and no
 * line ever exceeds the terminal width — a wrapped line scrolls the alt screen and corrupts
 * the whole frame, which is what "the TUI scrolls weirdly" usually is.
 */
internal object TuiText {
    private const val ESC = '\u001B'

    fun bold(s: String) = "$ESC[1m$s$ESC[0m"
    fun dim(s: String) = "$ESC[90m$s$ESC[0m"
    fun green(s: String) = "$ESC[32m$s$ESC[0m"
    fun yellow(s: String) = "$ESC[33m$s$ESC[0m"
    fun red(s: String) = "$ESC[31m$s$ESC[0m"
    fun cyan(s: String) = "$ESC[36m$s$ESC[0m"

    /** Width of one code point in cells (0 for combining marks, 2 for wide CJK). */
    fun cellWidth(cp: Int): Int = WCWidth.wcwidth(cp).coerceAtLeast(0)

    /** Display columns of [s], ignoring escape sequences. */
    fun width(s: String): Int {
        var w = 0
        walk(s, { _, _ -> }, { cp -> w += cellWidth(cp) })
        return w
    }

    /** Strip every escape sequence. */
    fun plain(s: String): String {
        val sb = StringBuilder()
        walk(s, { _, _ -> }, { cp -> sb.appendCodePoint(cp) })
        return sb.toString()
    }

    /**
     * Clip [s] to at most [cols] columns, keeping escape sequences (so styling stays balanced)
     * and never splitting a wide character. When clipping drops text, the last column shows `…`.
     */
    fun clip(s: String, cols: Int): String {
        if (cols <= 0) return ""
        if (width(s) <= cols) return s
        val sb = StringBuilder()
        var w = 0
        val limit = cols - 1 // leave room for the ellipsis
        var cut = false
        walk(s, { start, end -> sb.append(s, start, end) }) { cp ->
            if (cut) return@walk
            val cw = cellWidth(cp)
            if (w + cw > limit) { cut = true; return@walk }
            sb.appendCodePoint(cp); w += cw
        }
        sb.append("…")
        if (w + 1 < cols) sb.append(" ".repeat(cols - w - 1)) // a wide char may have left a gap
        return sb.toString()
    }

    /** Pad with spaces on the right to exactly [cols] columns (clipping if longer). */
    fun pad(s: String, cols: Int): String {
        val w = width(s)
        return if (w > cols) clip(s, cols) else s + " ".repeat(cols - w)
    }

    /**
     * Word-wrap plain text to [cols] columns; continuation rows are indented by [indent]
     * columns. Breaks at spaces when one is in reach, otherwise mid-word (CJK has no spaces).
     */
    fun wrap(text: String, cols: Int, indent: Int = 0): List<String> {
        if (cols <= 0) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        var first = true
        while (true) {
            val avail = (if (first) cols else cols - indent).coerceAtLeast(1)
            if (width(rest) <= avail) { out += (if (first) "" else " ".repeat(indent)) + rest; return out }
            // longest prefix that fits
            var w = 0
            var i = 0
            while (i < rest.length) {
                val cp = rest.codePointAt(i)
                val cw = cellWidth(cp)
                if (w + cw > avail) break
                w += cw; i += Character.charCount(cp)
            }
            val space = rest.lastIndexOf(' ', i - 1)
            val cut = if (space > avail / 3) space else i
            out += (if (first) "" else " ".repeat(indent)) + rest.substring(0, cut).trimEnd()
            rest = rest.substring(cut).trimStart()
            first = false
            if (rest.isEmpty()) return out
        }
    }

    /**
     * A vertical scrollbar for a viewport of [height] rows over [total] rows starting at [top]:
     * one glyph per row (`┃` thumb, `│` track), or spaces when nothing scrolls.
     */
    fun scrollbar(total: Int, top: Int, height: Int): List<String> {
        if (height <= 0) return emptyList()
        if (total <= height) return List(height) { " " }
        val thumb = (height.toLong() * height / total).toInt().coerceIn(1, height)
        val maxTop = total - height
        val start = ((height - thumb).toLong() * top / maxTop).toInt().coerceIn(0, height - thumb)
        return List(height) { r -> if (r in start until start + thumb) "┃" else dim("│") }
    }

    /**
     * One full-screen frame: exactly [height] rows of at most [width] columns, painted in place
     * (home + erase-to-end-of-line per row, erase-below at the end) instead of clearing the
     * screen first — the clear/redraw cycle is what flickers. Wrapped in synchronized-output
     * markers for terminals that honor them.
     */
    class Frame(val width: Int, val height: Int) {
        private val rows = ArrayList<Pair<String, Int>>(height) // text, visible columns

        fun line(s: String = "") { if (rows.size < height) clip(s, width).let { rows += it to width(it) } }

        fun rule() = line(dim("─".repeat(width)))

        /** Left text and right text on one row, the right one flush with the edge. */
        fun split(left: String, right: String) {
            val rw = width(right)
            line(pad(clip(left, (width - rw - 1).coerceAtLeast(0)), (width - rw).coerceAtLeast(0)) + right)
        }

        /** Rows left for a body section given how many chrome rows follow it. */
        fun remaining(after: Int): Int = (height - rows.size - after).coerceAtLeast(1)

        fun render(): String {
            val sb = StringBuilder("$ESC[?2026h$ESC[H")
            rows.forEachIndexed { i, (text, cols) ->
                sb.append(text)
                // erase the rest of the row — but not after a full-width row: the cursor then sits
                // on the last column (wrap pending) and EL would wipe the character just written
                if (cols < width) sb.append("$ESC[K")
                if (i < height - 1) sb.append("\r\n")
            }
            if (rows.size < height) sb.append("$ESC[J")
            sb.append("$ESC[?2026l")
            return sb.toString()
        }
    }

    /** Walk [s]: escape sequences to [onEscape] (as a [start,end) range), code points to [onChar]. */
    private inline fun walk(s: String, onEscape: (Int, Int) -> Unit, onChar: (Int) -> Unit) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == ESC && i + 1 < s.length && s[i + 1] == '[') {
                var j = i + 2
                while (j < s.length && s[j] !in '@'..'~') j++
                val end = (j + 1).coerceAtMost(s.length)
                onEscape(i, end)
                i = end
                continue
            }
            val cp = s.codePointAt(i)
            onChar(cp)
            i += Character.charCount(cp)
        }
    }
}
