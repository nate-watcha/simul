package dev.nate.uiagent

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessTest {

    private fun elapsed(block: () -> Unit): Long = System.currentTimeMillis().let { t0 -> block(); System.currentTimeMillis() - t0 }

    @Test
    fun `a command that never finishes is killed at its time limit`() {
        val took = elapsed {
            val ex = assertFailsWith<ProcessTimeout> { runProcess(listOf("sleep", "30"), timeoutMs = 300) }
            assertEquals("no answer within 0s: sleep 30", ex.brief)
        }
        assertTrue(took < 10_000, "returned after ${took}ms")
    }

    // the shape that held stone run 42039775943 for 65 minutes: the output stays open and nothing ever closes it
    @Test
    fun `a command whose output is held open by something it started still returns`() {
        val took = elapsed { assertEquals("hi", runProcess(listOf("sh", "-c", "sleep 30 & echo hi"), timeoutMs = 5_000).trim()) }
        assertTrue(took < 10_000, "returned after ${took}ms")
    }

    @Test
    fun `a capture that never finishes is a failure with no file left behind`() {
        val dest = File(tempDir(), "shot.png")
        val took = elapsed {
            val why = runToFile(listOf("sh", "-c", "printf partial; sleep 30"), dest, timeoutMs = 300)
            assertEquals("no answer within 0s: sh -c printf partial; sleep 30", why)
        }
        assertFalse(dest.exists(), "a half-written capture must not pass for a screenshot")
        assertTrue(took < 10_000, "returned after ${took}ms")
    }
}
