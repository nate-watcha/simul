package dev.nate.uiagent

import java.io.File
import java.util.concurrent.TimeUnit

/** A command exited non-zero; [stderr] carries the tool's own diagnosis (e.g. `android layout`'s idle report). */
class ProcessFailure(argv: List<String>, exitCode: Int, val stderr: String) :
    RuntimeException("command failed ($exitCode): ${argv.joinToString(" ")}\n${stderr.trim()}")

/** Run an external command, returning stdout. Throws [ProcessFailure] on non-zero exit, RuntimeException on timeout. */
fun runProcess(argv: List<String>, timeoutMs: Long = 20_000): String {
    val p = ProcessBuilder(argv).redirectErrorStream(false).start()
    val out = p.inputStream.bufferedReader().readText()
    val err = p.errorStream.bufferedReader().readText()
    if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        p.destroyForcibly()
        throw RuntimeException("timeout after ${timeoutMs}ms: ${argv.joinToString(" ")}")
    }
    if (p.exitValue() != 0) throw ProcessFailure(argv, p.exitValue(), err)
    return out
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
 * Returns null on success (exit 0 and a non-empty file), else the failure text; an empty or
 * failed output file is deleted.
 */
internal fun runToFile(argv: List<String>, dest: File): String? {
    dest.parentFile?.mkdirs()
    return try {
        val p = ProcessBuilder(argv).redirectOutput(dest).redirectErrorStream(false).start()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor()
        if (p.exitValue() == 0 && dest.length() > 0) null
        else { dest.delete(); err.trim().ifEmpty { "exit ${p.exitValue()}" } }
    } catch (e: Exception) {
        dest.delete()
        e.message ?: e.toString()
    }
}
