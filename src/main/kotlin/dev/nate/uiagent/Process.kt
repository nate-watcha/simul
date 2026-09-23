package dev.nate.uiagent

import java.util.concurrent.TimeUnit

/** A command exited non-zero; [stderr] carries the tool's own diagnosis (e.g. `android layout`'s idle report). */
class ProcessFailure(val argv: List<String>, val exitCode: Int, val stderr: String) :
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
