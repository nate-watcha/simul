package dev.nate.uiagent.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TuiTextTest {
    private val esc = "\u001B"

    @Test
    fun `width ignores escapes and counts CJK as two columns`() {
        assertEquals(5, TuiText.width("hello"))
        assertEquals(5, TuiText.width(TuiText.green("hello")))
        assertEquals(6, TuiText.width("보관함"))
        assertEquals(9, TuiText.width(TuiText.bold("보관함") + " ok"))
    }

    @Test
    fun `pad aligns Korean and styled text to the same column`() {
        val a = TuiText.pad("보관함", 10)
        val b = TuiText.pad(TuiText.red("sign_in"), 10)
        assertEquals(10, TuiText.width(a))
        assertEquals(10, TuiText.width(b))
        assertEquals("보관함    ", a)
    }

    @Test
    fun `clip keeps escapes balanced, never splits a wide char, and ends with an ellipsis`() {
        val s = TuiText.red("보관함 탭을 눌러")
        val c = TuiText.clip(s, 6)
        assertEquals(6, TuiText.width(c))
        assertTrue(c.endsWith("$esc[0m…") || c.endsWith("$esc[0m… "), c)
        assertTrue(c.startsWith("$esc[31m"))
        // an odd column budget on wide chars leaves a one-column gap that is padded, not overflowed
        assertEquals(5, TuiText.width(TuiText.clip("가나다라", 5)))
        assertEquals("abc", TuiText.clip("abc", 3))
    }

    @Test
    fun `wrap breaks at spaces with an indented continuation and mid-word for CJK`() {
        assertEquals(listOf("step 1/3: tap the", "    archive tab"), TuiText.wrap("step 1/3: tap the archive tab", 18, indent = 4))
        val rows = TuiText.wrap("가나다라마바사아자차", 8, indent = 2)
        assertEquals(listOf("가나다라", "  마바사", "  아자차"), rows)
        assertTrue(rows.all { TuiText.width(it) <= 8 })
        assertEquals(listOf("short"), TuiText.wrap("short", 40))
    }

    @Test
    fun `scrollbar thumb tracks the viewport position`() {
        assertEquals(List(5) { " " }, TuiText.scrollbar(total = 3, top = 0, height = 5))
        val top = TuiText.scrollbar(total = 100, top = 0, height = 10)
        val bottom = TuiText.scrollbar(total = 100, top = 90, height = 10)
        assertEquals("┃", top[0]); assertEquals("┃", bottom[9])
        assertTrue(top.count { it == "┃" } == 1)
    }

    @Test
    fun `frame is exactly height rows, clipped to width, with no EL after a full-width row`() {
        val f = TuiText.Frame(width = 10, height = 3)
        f.line("1234567890")        // full width
        f.line("short")
        f.line("this is far too long for ten columns")
        f.line("dropped")           // beyond height
        val out = f.render()
        val body = out.removePrefix("$esc[?2026h$esc[H").removeSuffix("$esc[?2026l")
        val rows = body.split("\r\n")
        assertEquals(3, rows.size)
        assertEquals("1234567890", rows[0])              // no ESC[K after a full-width row
        assertEquals("short$esc[K", rows[1])
        assertEquals(10, TuiText.width(rows[2].removeSuffix("$esc[K")))
        assertTrue(rows[2].contains("…"))
        assertTrue(!out.endsWith("\r\n"))                 // the last row must not scroll the screen
    }

    @Test
    fun `frame split puts the right text flush with the edge`() {
        val f = TuiText.Frame(width = 20, height = 1)
        f.split(TuiText.bold("left"), "right")
        val row = TuiText.plain(f.render().substringAfter("$esc[H").substringBefore("$esc[K"))
        assertEquals("left           right", row)
    }
}
