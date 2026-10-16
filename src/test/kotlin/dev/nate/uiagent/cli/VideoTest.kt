package dev.nate.uiagent.cli

import dev.nate.uiagent.Spawned

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A scripted `adb shell screenrecord`: blocks until the fake device gets SIGINT (or the test ends it). */
private class FakeSegment : Spawned {
    val done = CountDownLatch(1)
    var exit = 0
    override fun waitFor(timeoutMs: Long) = done.await(timeoutMs, TimeUnit.MILLISECONDS)
    override fun exitCode() = exit
    override fun destroy() { done.countDown() }
    fun finish(code: Int = 0) { exit = code; done.countDown() }
}

/** adb + ffmpeg as the recorder sees them: every argv is logged; pulls and joins create files. */
private class FakeShell {
    val spawned = mutableListOf<List<String>>()
    val segments = mutableListOf<FakeSegment>()
    val ran = mutableListOf<List<String>>()
    var ffmpeg = true

    fun spawn(argv: List<String>): Spawned {
        spawned += argv
        return FakeSegment().also { segments += it }
    }

    fun run(argv: List<String>, @Suppress("UNUSED_PARAMETER") timeout: Long): String {
        ran += argv
        when {
            argv[0] == "adb" && argv[1] == "shell" && argv[2].startsWith("kill -2") ->
                segments.lastOrNull()?.takeIf { it.done.count > 0 }?.finish(0)
            argv[0] == "adb" && argv[1] == "pull" -> File(argv[3]).writeText("mp4:" + argv[2])
            argv[0] == "ffmpeg" -> {
                if (!ffmpeg) throw java.io.IOException("ffmpeg: No such file or directory")
                File(argv.last()).writeText("joined")
            }
        }
        return ""
    }
}

class VideoTest {

    private fun recorder(shell: FakeShell, segmentSec: Int = 180) =
        ScreenRecorder(spawn = shell::spawn, run = shell::run, segmentSec = segmentSec)

    private fun waitForSegments(shell: FakeShell, n: Int) {
        val deadline = System.currentTimeMillis() + 5000
        while (shell.segments.size < n && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(n, shell.segments.size, "segments started")
    }

    @Test
    fun `records one segment, stops it with SIGINT on the device, pulls and cleans up`() {
        val shell = FakeShell()
        val rec = recorder(shell)
        rec.start()
        waitForSegments(shell, 1)
        val dir = kotlin.io.path.createTempDirectory("simul-video").toFile()
        val dest = File(dir, "run.mp4")

        val files = rec.stop(dest)

        assertEquals(listOf(dest), files)
        assertNull(rec.problem)
        val argv = shell.spawned.single()
        assertEquals(listOf("adb", "shell", "screenrecord", "--time-limit", "180", "--bit-rate", "4M"), argv.dropLast(1))
        val remote = argv.last()
        assertTrue(remote.startsWith("/sdcard/simul-rec-") && remote.endsWith("-0.mp4"), remote)
        // the recorder on the device is interrupted (so the mp4 is finalised), not the adb client
        assertTrue(shell.ran.any { it == listOf("adb", "shell", "kill -2 \$(pidof screenrecord)") }, "SIGINT sent")
        assertTrue(shell.ran.any { it == listOf("adb", "pull", remote, dest.path) }, "pulled")
        assertTrue(shell.ran.any { it == listOf("adb", "shell", "rm", "-f", remote) }, "remote removed")
        assertEquals("mp4:$remote", dest.readText())
        dir.deleteRecursively()
    }

    @Test
    fun `without ffmpeg the segments are kept as numbered parts`() {
        val shell = FakeShell().apply { ffmpeg = false }
        val rec = recorder(shell)
        rec.start()
        waitForSegments(shell, 1)
        shell.segments[0].finish(0)
        waitForSegments(shell, 2)
        val dir = kotlin.io.path.createTempDirectory("simul-video").toFile()

        val files = rec.stop(File(dir, "run.mp4"))

        assertEquals(listOf("run-1.mp4", "run-2.mp4"), files.map { it.name })
        assertTrue(rec.problem!!.contains("join"), rec.problem)
        assertEquals(setOf("run-1.mp4", "run-2.mp4"), dir.list()!!.toSet())
        dir.deleteRecursively()
    }

    @Test
    fun `gif conversion without ffmpeg says so instead of failing the run`() {
        val dir = kotlin.io.path.createTempDirectory("simul-video").toFile()
        val mp4 = File(dir, "run.mp4").apply { writeText("x") }

        val failure = Gif.convert(mp4, File(dir, "run.gif"), run = { _, _ -> throw java.io.IOException("Cannot run program \"ffmpeg\"") })

        assertTrue(failure!!.contains("ffmpeg not found"), failure)
        dir.deleteRecursively()
    }
}
