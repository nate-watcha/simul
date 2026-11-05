package dev.nate.uiagent.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun `a launch killed with the previous scenario's task is told apart from the force-stop before it`() {
        // stone owner-profile-stats 2026-11-05: the share sheet kept the old task alive past force-stop
        val stop = "I/ActivityManager(  831): Killing 30328:com.x.dev/u0a237 (adj 101): stop com.x.dev due to from pid 30898\n"
        val start = "I/ActivityTaskManager(  831): START u0 {act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.x.dev/.InitActivity} with LAUNCH_MULTIPLE from uid 2000\n" +
            "I/ActivityManager(  831): Start proc 30956:com.x.dev/u0a237 for next-top-activity {com.x.dev/.InitActivity}\n"
        val kill = "I/ActivityManager(  831): Killing 30956:com.x.dev/u0a237 (adj -10000): remove-package-task\n"
        assertTrue(AdbOps.killedWithOldTask(stop + start + kill, "com.x.dev"))
        assertFalse(AdbOps.killedWithOldTask(stop + start, "com.x.dev"))
        assertFalse(AdbOps.killedWithOldTask(stop + start + kill + start, "com.x.dev"), "the launch after the kill is a fresh one")
        assertFalse(AdbOps.killedWithOldTask(stop + start + kill, "com.x"), "a package that merely prefixes ours is not ours")
    }

    @Test
    fun `brought-to-front warning is not an error`() {
        val warn = "Starting: Intent { ... }\n" +
            "Warning: Activity not started, its current task has been brought to the front\nStatus: ok\n"
        assertNull(AdbOps.deeplinkError(warn))
    }

    @Test
    fun `app locale is read from cmd locale output, empty list means system, unknown package is an error`() {
        assertEquals("ko-KR,en-US", AdbOps.parseAppLocales("Locales for com.x for user 0 are [ko-KR,en-US]\n", "com.x"))
        assertNull(AdbOps.parseAppLocales("Locales for com.x for user 0 are []", "com.x"))
        assertFailsWith<RuntimeException> { AdbOps.parseAppLocales("Unknown package com.x for userId 0", "com.x") }
        assertFailsWith<RuntimeException> { AdbOps.parseAppLocales("Unknown command: locale", "com.x") }
    }
}
