package dev.nate.uiagent

import java.io.File
import java.util.concurrent.TimeUnit

/** An external command (adb, the android CLI, ffmpeg, git) that did not do its job; [brief] is the one line for a log or a step's reason. */
sealed class ProcessError(message: String) : RuntimeException(message) {
    abstract val brief: String
}

/** A command exited non-zero; [stderr] carries the tool's own diagnosis (e.g. `android layout`'s idle report). */
class ProcessFailure(argv: List<String>, exitCode: Int, val stderr: String) :
    ProcessError("command failed ($exitCode): ${argv.joinToString(" ")}\n${stderr.trim()}") {
    override val brief = "${argv.joinToString(" ")} exited $exitCode" +
        (stderr.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.let { ": $it" } ?: "")
}

/** A command was still running at its time limit and was killed — the device (or the tool) is not answering. */
class ProcessTimeout(argv: List<String>, val timeoutMs: Long) :
    ProcessError("timeout after ${timeoutMs}ms: ${argv.joinToString(" ")}") {
    override val brief = "no answer within ${timeoutMs / 1000}s: ${argv.joinToString(" ")}"
}

/**
 * Run an external command, returning stdout. Throws [ProcessFailure] on non-zero exit and
 * [ProcessTimeout] when it is still running after [timeoutMs].
 *
 * Both streams go to files, not pipes: reading a pipe to its end before waiting meant the time
 * limit was never reached for a command that hangs with its output open — an `adb exec-out
 * screencap` against an emulator whose display had stopped held a 39-scenario batch for 65
 * minutes, until the job was cancelled (stone run 42039775943, 2026-11-05).
 */
fun runProcess(argv: List<String>, timeoutMs: Long = 20_000): String {
    val out = File.createTempFile("simul-", ".out")
    val err = File.createTempFile("simul-", ".err")
    try {
        val p = ProcessBuilder(argv).redirectOutput(out).redirectError(err).start()
        awaitOrKill(p, argv, timeoutMs)
        if (p.exitValue() != 0) throw ProcessFailure(argv, p.exitValue(), err.readText())
        return out.readText()
    } finally {
        out.delete()
        err.delete()
    }
}

/** Wait for [p] up to [timeoutMs]; past that kill it with everything it started and throw [ProcessTimeout]. */
private fun awaitOrKill(p: Process, argv: List<String>, timeoutMs: Long) {
    if (p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) return
    p.descendants().forEach { it.destroyForcibly() }
    p.destroyForcibly()
    p.waitFor(2, TimeUnit.SECONDS)
    throw ProcessTimeout(argv, timeoutMs)
}

/** A running child process, narrowed to what callers need so tests can script one. */
interface Spawned {
    /** True when the process exited within [timeoutMs]. */
    fun waitFor(timeoutMs: Long): Boolean
    fun exitCode(): Int
    fun destroy()
}

/** Start [argv] detached from our streams (output discarded); the caller decides when it ends. */
internal fun spawnProcess(argv: List<String>): Spawned {
    val p = ProcessBuilder(argv).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    return object : Spawned {
        override fun waitFor(timeoutMs: Long) = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        override fun exitCode() = p.exitValue()
        override fun destroy() { p.destroyForcibly() }
    }
}

/**
 * Run [argv] with stdout streamed straight into [dest] (binary-safe: screenshots, tar streams).
 * Returns null on success (exit 0 and a non-empty file), else the failure text — a command still
 * running after [timeoutMs] is killed and is a failure like any other; an empty or failed output
 * file is deleted.
 */
internal fun runToFile(argv: List<String>, dest: File, timeoutMs: Long = 30_000): String? {
    dest.parentFile?.mkdirs()
    val err = File.createTempFile("simul-", ".err")
    return try {
        val p = ProcessBuilder(argv).redirectOutput(dest).redirectError(err).start()
        awaitOrKill(p, argv, timeoutMs)
        if (p.exitValue() == 0 && dest.length() > 0) null
        else { dest.delete(); err.readText().trim().ifEmpty { "exit ${p.exitValue()}" } }
    } catch (e: Exception) {
        dest.delete()
        (e as? ProcessError)?.brief ?: e.message ?: e.toString()
    } finally {
        err.delete()
    }
}
