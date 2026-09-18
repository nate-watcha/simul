package dev.nate.uiagent

import java.util.concurrent.TimeUnit

/** Run an external command, returning stdout. Throws on non-zero exit or timeout. */
fun runProcess(argv: List<String>, timeoutMs: Long = 20_000): String {
    val p = ProcessBuilder(argv).redirectErrorStream(false).start()
    val out = p.inputStream.bufferedReader().readText()
    val err = p.errorStream.bufferedReader().readText()
    if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        p.destroyForcibly()
        throw RuntimeException("timeout after ${timeoutMs}ms: ${argv.joinToString(" ")}")
    }
    if (p.exitValue() != 0) {
        throw RuntimeException("command failed (${p.exitValue()}): ${argv.joinToString(" ")}\n${err.trim()}")
    }
    return out
}
