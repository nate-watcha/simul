package dev.nate.uiagent.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StepSpecTest {

    @Test
    fun `criterion splits on the double-colon separator`() {
        val s = StepSpec.parse("Tap the \"보관함\" tab :: the \"다운로드\" screen should be restored")
        assertEquals("Tap the \"보관함\" tab", s.command)
        assertEquals("the \"다운로드\" screen should be restored", s.criterion)
    }

    @Test
    fun `a step without the separator has no criterion`() {
        val s = StepSpec.parse("Verify the \"다운로드\" title is shown")
        assertEquals("Verify the \"다운로드\" title is shown", s.command)
        assertNull(s.criterion)
    }
}
