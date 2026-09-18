package dev.nate.uiagent.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StepSpecTest {

    @Test
    fun `plain step has no criterion`() {
        val s = StepSpec.parse("Tap the \"보관함\" tab")
        assertEquals("Tap the \"보관함\" tab", s.command)
        assertNull(s.criterion)
        assertFalse(s.isVerify)
    }

    @Test
    fun `criterion splits on the double-colon separator`() {
        val s = StepSpec.parse("Tap the \"보관함\" tab :: the \"다운로드\" screen should be restored")
        assertEquals("Tap the \"보관함\" tab", s.command)
        assertEquals("the \"다운로드\" screen should be restored", s.criterion)
    }

    @Test
    fun `verify detection is case-insensitive and prefix-based`() {
        assertTrue(StepSpec.parse("Verify the \"다운로드\" title is shown").isVerify)
        assertTrue(StepSpec.parse("verify \"X\" is shown").isVerify)
        assertFalse(StepSpec.parse("Tap \"Verify\" button").isVerify)
    }

    // ------------------------------------------------------------ judgeVerify

    private val els = listOf(
        el("다운로드", resourceId = "download_screen_title"),
        el("보관함", state = listOf("selected")),
    )

    @Test
    fun `presence passes on label or resourceId`() {
        assertTrue(judgeVerify("Verify the \"다운로드\" title is shown", els)!!.passed)
        assertTrue(judgeVerify("Verify the \"download_screen_title\" is shown", els)!!.passed)
        assertFalse(judgeVerify("Verify the \"장르\" chip is shown", els)!!.passed)
    }

    @Test
    fun `absence inverts the check`() {
        assertTrue(judgeVerify("Verify the \"장르\" chip is not shown", els)!!.passed)
        assertFalse(judgeVerify("Verify the \"다운로드\" title is not shown", els)!!.passed)
    }

    @Test
    fun `state keyword outside quotes switches to statePresent`() {
        assertTrue(judgeVerify("Verify the \"보관함\" tab is selected", els)!!.passed)
        assertFalse(judgeVerify("Verify the \"다운로드\" title is selected", els)!!.passed)
        // the keyword inside a quoted label must NOT trigger the state check
        val labeled = listOf(el("selected items"))
        assertTrue(judgeVerify("Verify \"selected items\" is shown", labeled)!!.passed)
    }

    @Test
    fun `no quoted anchor means not machine-checkable`() {
        assertNull(judgeVerify("Verify the home screen looks right", els))
    }
}
