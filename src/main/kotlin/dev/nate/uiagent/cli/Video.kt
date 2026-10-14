package dev.nate.uiagent.cli

import dev.nate.uiagent.runProcess
import java.io.File
import java.util.concurrent.TimeUnit

/** What `--video` / `--gif` ask of a run: nothing, the raw screen recording, or that plus a GIF. */
enum class VideoOutput { NONE, MP4, GIF }

/** A running child process, narrowed to what the recorder needs so tests can script one. */
interface Spawned {
    /** True when the process exited within [timeoutMs]. */
    fun waitFor(timeoutMs: Long): Boolean
    fun exitCode(): Int
    fun destroy()
}

internal fun spawnProcess(argv: List<String>): Spawned {
    val p = ProcessBuilder(argv).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    return object : Spawned {
        override fun waitFor(timeoutMs: Long) = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        override fun exitCode() = p.exitValue()
        override fun destroy() { p.destroyForcibly() }
    }
}

/**
 * Records the device screen for the duration of a scenario with `adb shell screenrecord`.
 *
 * screenrecord caps one recording at 180s on most Android versions, so the recorder runs it
 * in segments on a background thread: when a segment ends by itself the next one starts, and
 * [stop] stitches the segments back together (ffmpeg concat — without ffmpeg the parts are
 * kept side by side). Stopping means SIGINT to the recorder *on the device*: killing the local
 * adb client would leave an mp4 without its moov atom, unreadable by anything.
 */
class ScreenRecorder(
    private val adbBin: String = "adb",
    private val ffmpegBin: String = "ffmpeg",
    private val segmentSec: Int = 180,
    private val bitRate: String = "4M",
    private val spawn: (List<String>) -> Spawned = ::spawnProcess,
    private val run: (List<String>, Long) -> String = { argv, timeout -> runProcess(argv, timeout) },
) {
    private val stamp = System.currentTimeMillis()
    private val remotes = mutableListOf<String>()
    @Volatile private var stopping = false
    @Volatile private var thread: Thread? = null
    @Volatile private var current: Spawned? = null

    /** Why recording stopped early or produced nothing, for the run log; null when all went well. */
    @Volatile var problem: String? = null
        private set

    fun start() {
        check(thread == null) { "recorder already started" }
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
     * Stop recording and pull the result into [dest] (`run.mp4`). Returns the local files:
     * normally just [dest]; the numbered parts when several segments could not be joined;
     * empty when nothing was recorded (see [problem]).
     */
    fun stop(dest: File): List<File> {
        val t = thread ?: return emptyList()
        stopping = true
        // The recorder may be between two segments, so keep signalling until the thread is
        // done; each attempt is cheap and the loop above exits right after the segment does.
        var tries = 0
        while (t.isAlive && tries++ < 15) {
            runCatching { run(listOf(adbBin, "shell", "kill -2 \$(pidof screenrecord)"), 5_000) }
            t.join(1000)
        }
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
            return emptyList()
        }
        if (parts.size == 1) {
            if (parts[0] != dest) parts[0].renameTo(dest)
            return listOf(dest)
        }
        return concat(parts, dest)
    }

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

/** mp4 → GIF via ffmpeg (two-pass palette, so UI text stays crisp at a small size). */
object Gif {
    const val DEFAULT_WIDTH = 360
    const val DEFAULT_FPS = 10

    fun filter(width: Int, fps: Int): String =
        "fps=$fps,scale=$width:-2:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];" +
            "[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle"

    /** Error text when conversion failed (ffmpeg missing, bad input); null on success. */
    fun convert(
        mp4: File,
        gif: File,
        width: Int = DEFAULT_WIDTH,
        fps: Int = DEFAULT_FPS,
        ffmpegBin: String = "ffmpeg",
        run: (List<String>, Long) -> String = { argv, timeout -> runProcess(argv, timeout) },
    ): String? {
        val argv = listOf(ffmpegBin, "-y", "-loglevel", "error", "-i", mp4.path, "-vf", filter(width, fps), "-loop", "0", gif.path)
        return try {
            run(argv, 600_000)
            if (gif.length() > 0) null else "ffmpeg wrote an empty gif"
        } catch (e: java.io.IOException) {
            "ffmpeg not found (install it to get a gif; the mp4 is kept)"
        } catch (e: Exception) {
            e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: e.toString()
        }
    }
}
