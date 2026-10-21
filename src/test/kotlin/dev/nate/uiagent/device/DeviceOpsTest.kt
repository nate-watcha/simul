package dev.nate.uiagent.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceOpsTest {
    @Test
    fun `unresolved deeplink is detected`() {
        val err = "Starting: Intent { act=android.intent.action.VIEW dat=myapp://nope pkg=com.x }\n" +
            "Error: Activity not started, unable to resolve Intent { ... }\n"
        assertTrue(AdbOps.deeplinkError(err)?.contains("unable to resolve Intent") == true)
    }

    @Test
    fun `fully drawn is read for the app under test only`() {
        val log = "I/ActivityTaskManager(  672): Displayed com.frograms.wplay/.activity.SchemeActivity for user 0: +2s364ms\n" +
            "I/ActivityTaskManager(  672): Fully drawn com.frograms.wplay.dev/.MainNavActivity: +1s1ms\n" +
            "I/ActivityTaskManager(  672): Fully drawn com.frograms.wplay/.MainNavActivity: +3s812ms\n"
        assertEquals("+3s812ms", AdbOps.fullyDrawn(log, "com.frograms.wplay"))
        assertNull(AdbOps.fullyDrawn(log.lines().first { ".dev/" in it }, "com.frograms.wplay"), "a package that merely extends ours is not ours")
    }

    @Test
    fun `brought-to-front warning is not an error`() {
        val warn = "Starting: Intent { ... }\n" +
            "Warning: Activity not started, its current task has been brought to the front\nStatus: ok\n"
        assertNull(AdbOps.deeplinkError(warn))
    }
}
