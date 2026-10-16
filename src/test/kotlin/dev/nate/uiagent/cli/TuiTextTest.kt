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

}
