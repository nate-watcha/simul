package dev.nate.uiagent.cli

import dev.nate.uiagent.Spawned
import dev.nate.uiagent.runProcess
import dev.nate.uiagent.spawnProcess
import java.io.File

/**
 * Records the device screen for the duration of a scenario with `adb shell screenrecord`.
 *
 * screenrecord caps one recording at 180s on most Android versions, so the recorder runs it
 * in segments on a background thread: when a segment ends by itself the next one starts, and
 * [stop] stitches the segments back together (ffmpeg concat — without ffmpeg the parts are
 * kept side by side). Stopping means SIGINT to the recorder *on the device*: killing the local
 * adb client would leave an mp4 without its moov atom, unreadable by anything.
 *
 * The recording is a timeline the report seeks into (click a step → the video jumps there),
 * so [stop] also returns where the mp4's t=0 sits on the wall clock: screenrecord takes a
 * moment to start, but it ends exactly when it is interrupted, so the start is anchored from
 * the end — `stoppedAt − duration` when ffprobe can read the duration, the spawn time otherwise.
 */
class ScreenRecorder(
    private val adbBin: String = "adb",
    private val ffmpegBin: String = "ffmpeg",
    private val ffprobeBin: String = "ffprobe",
    private val segmentSec: Int = 180,
    private val bitRate: String = "4M",
    private val spawn: (List<String>) -> Spawned = ::spawnProcess,
    private val run: (List<String>, Long) -> String = { argv, timeout -> runProcess(argv, timeout) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val stamp = System.currentTimeMillis()
    private val remotes = mutableListOf<String>()
    @Volatile private var stopping = false
    @Volatile private var thread: Thread? = null
    @Volatile private var current: Spawned? = null
    @Volatile private var spawnedAt = 0L

    /** Why recording stopped early or produced nothing, for the run log; null when all went well. */
    @Volatile var problem: String? = null
        private set

    /** What a finished recording is: the files, and where its t=0 lies on the wall clock. */
    class Recording(val files: List<File>, val startedAtMs: Long, val durationMs: Long?) {
        /** The one playable file when the segments were joined (or there was only one). */
        val file: File? get() = files.singleOrNull()
    }

    fun start() {
        check(thread == null) { "recorder already started" }
        spawnedAt = clock()
        thread = Thread(::loop, "simul-screenrecord").apply { isDaemon = true; start() }
    }

    private fun loop() {
        var n = 0
        while (!stopping) {
            val remote = "/sdcard/simul-rec-$stamp-${n++}.mp4"
            val p = try {
                spawn(listOf(adbBin, "shell", "screenrecord", "--time-limit", "$segmentSec", "--bit-rate", bitRate, remote))
            } catch (e: Exception) {
                problem = "could not start screenrecord: ${e.message}"
                return
            }
            synchronized(remotes) { remotes += remote }
            current = p
            while (!p.waitFor(1000)) { /* a segment runs until its time limit or our SIGINT */ }
            if (p.exitCode() != 0 && !stopping) {
                problem = "screenrecord exited with ${p.exitCode()} (not supported on this device?)"
                return
            }
        }
    }

    /**
     * Stop recording and pull the result into [dest] (`replay.mp4`). The recording's files are
     * normally just [dest]; the numbered parts when several segments could not be joined; empty
     * when nothing was recorded (see [problem]).
     */
    fun stop(dest: File): Recording {
        val t = thread ?: return Recording(emptyList(), spawnedAt, null)
        stopping = true
        // The recorder may be between two segments, so keep signalling until the thread is
        // done; each attempt is cheap and the loop above exits right after the segment does.
        var tries = 0
        while (t.isAlive && tries++ < 15) {
            runCatching { run(listOf(adbBin, "shell", "kill -2 \$(pidof screenrecord)"), 5_000) }
            t.join(1000)
        }
        val stoppedAt = clock()
        if (t.isAlive) {
            current?.destroy()
            t.join(3000)
        }

        dest.parentFile?.mkdirs()
        val segments = synchronized(remotes) { remotes.toList() }
        val parts = mutableListOf<File>()
        for ((i, remote) in segments.withIndex()) {
            val local = if (segments.size == 1) dest else File(dest.parentFile, "${dest.nameWithoutExtension}-${i + 1}.mp4")
            val pulled = runCatching { run(listOf(adbBin, "pull", remote, local.path), 120_000) }.isSuccess && local.length() > 0
            runCatching { run(listOf(adbBin, "shell", "rm", "-f", remote), 5_000) }
            if (pulled) parts += local else local.delete()
        }
        if (parts.isEmpty()) {
            if (problem == null) problem = "nothing was recorded"
            return Recording(emptyList(), spawnedAt, null)
        }
        val files = when {
            parts.size == 1 -> { if (parts[0] != dest) parts[0].renameTo(dest); listOf(dest) }
            else -> concat(parts, dest)
        }
        val duration = files.singleOrNull()?.let(::probeDurationMs)
        // the end of the recording is the moment it was interrupted; the start is derived from it
        val startedAt = if (duration != null) stoppedAt - duration else spawnedAt
        return Recording(files, startedAt, duration)
    }

    /** The playable length of [mp4] via ffprobe; null without ffprobe or when it cannot read the file. */
    fun probeDurationMs(mp4: File): Long? = runCatching {
        run(listOf(ffprobeBin, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", mp4.path), 30_000)
            .trim().toDoubleOrNull()?.let { (it * 1000).toLong() }
    }.getOrNull()

    /** Join [parts] into [dest] losslessly (same encoder settings); keep the parts if ffmpeg is unavailable. */
    private fun concat(parts: List<File>, dest: File): List<File> {
        val list = File(dest.parentFile, "${dest.nameWithoutExtension}-parts.txt")
        list.writeText(parts.joinToString("\n") { "file '${it.absolutePath.replace("'", "'\\''")}'" } + "\n")
        val joined = runCatching {
            run(listOf(ffmpegBin, "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", list.path, "-c", "copy", dest.path), 120_000)
        }.isSuccess && dest.length() > 0
        list.delete()
        if (!joined) {
            problem = "ffmpeg could not join ${parts.size} segments — kept as parts"
            dest.delete()
            return parts
        }
        parts.forEach { it.delete() }
        return listOf(dest)
    }
}
