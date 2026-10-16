package dev.nate.uiagent.cli

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
    fun `a segment that hits its time limit is followed by the next one, and stop joins them`() {
        val shell = FakeShell()
        val rec = recorder(shell)
        rec.start()
        waitForSegments(shell, 1)
        shell.segments[0].finish(0) // screenrecord's own time limit
        waitForSegments(shell, 2)
        val dir = kotlin.io.path.createTempDirectory("simul-video").toFile()
        val dest = File(dir, "run.mp4")

        val files = rec.stop(dest)

        assertEquals(listOf(dest), files)
        assertNull(rec.problem)
        assertEquals("joined", dest.readText())
        val concat = shell.ran.single { it[0] == "ffmpeg" }
        assertEquals(listOf("-f", "concat", "-safe", "0"), concat.subList(4, 8))
        assertEquals(dest.path, concat.last())
        // the parts and the list file are gone once joined
        assertEquals(listOf("run.mp4"), dir.list()!!.toList())
        assertEquals(2, shell.ran.count { it[0] == "adb" && it[1] == "pull" })
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
    fun `screenrecord that fails to start is reported, not thrown`() {
        val shell = FakeShell()
        val rec = recorder(shell)
        rec.start()
        waitForSegments(shell, 1)
        shell.segments[0].finish(1) // e.g. "Unable to get output buffers" on a device without an encoder
        Thread.sleep(50)
        val dir = kotlin.io.path.createTempDirectory("simul-video").toFile()

        val files = rec.stop(File(dir, "run.mp4"))

        // the fake pull still writes a file, so the segment counts as recorded; the problem text is what matters
        assertEquals(1, files.size)
        assertTrue(rec.problem!!.contains("exited with 1"), rec.problem)
        assertEquals(1, shell.spawned.size, "no further segment after a failure")
        dir.deleteRecursively()
    }

    @Test
    fun `gif conversion is a two-pass palette ffmpeg call at the asked size`() {
        val ran = mutableListOf<List<String>>()
        val dir = kotlin.io.path.createTempDirectory("simul-video").toFile()
        val mp4 = File(dir, "run.mp4").apply { writeText("x") }
        val gif = File(dir, "run.gif")

        val failure = Gif.convert(mp4, gif, width = 300, fps = 8, run = { argv, _ -> ran += argv; gif.writeText("GIF89a"); "" })

        assertNull(failure)
        val argv = ran.single()
        assertEquals("ffmpeg", argv[0])
        assertEquals(mp4.path, argv[argv.indexOf("-i") + 1])
        val vf = argv[argv.indexOf("-vf") + 1]
        assertTrue(vf.startsWith("setpts=PTS/1.5,fps=8,scale=300:-2") && "palettegen" in vf && "paletteuse" in vf, "1.5× by default: $vf")
        assertEquals(gif.path, argv.last())
        assertTrue(Gif.filter(300, 8, speed = 1.0).startsWith("fps=8,"), "real time → no setpts")
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
