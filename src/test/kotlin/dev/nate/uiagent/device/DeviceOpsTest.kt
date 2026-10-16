package dev.nate.uiagent.device

import kotlin.test.Test
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
    fun `brought-to-front warning is not an error`() {
        val warn = "Starting: Intent { ... }\n" +
            "Warning: Activity not started, its current task has been brought to the front\nStatus: ok\n"
        assertNull(AdbOps.deeplinkError(warn))
    }
}
