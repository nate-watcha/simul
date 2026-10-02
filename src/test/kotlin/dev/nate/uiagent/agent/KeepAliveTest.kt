package dev.nate.uiagent.agent

import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.LogicalLayout
import dev.nate.uiagent.Point
import dev.nate.uiagent.device.Device
import dev.nate.uiagent.device.DeviceController
import org.apache.hc.core5.util.TimeValue
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * llama-server closes a keep-alive connection that idles 5s (cpp-httplib). The regression this
 * guards: a tool that runs about that long handed the next request a connection the server was
 * closing → "Connection closed by peer" → FAILED step. Two defenses, each with its own test:
 * the client drops idle connections before the server would, and a transport failure on a
 * reused connection is retried once on a fresh one.
 */
class KeepAliveTest {

    @Test
    fun `client keep-alive stays well under the server's`() {
        assertEquals(3_000, SimulLlm.keepAlive(null).toMilliseconds(), "no server hint → cap")
        assertEquals(3_000, SimulLlm.keepAlive(TimeValue.ofSeconds(5)).toMilliseconds(), "llama-server's 5s → cap")
        assertEquals(600, SimulLlm.keepAlive(TimeValue.ofSeconds(1)).toMilliseconds(), "short server timeout → 60%")
        assertEquals(3_000, SimulLlm.keepAlive(TimeValue.ofSeconds(60)).toMilliseconds())
        assertEquals(3_000, SimulLlm.keepAlive(TimeValue.ofMilliseconds(-1)).toMilliseconds(), "negative = indefinite → cap")
    }

    @Test
    fun `a connection the server kills on reuse is retried on a fresh one`() {
        StaleServer(killOnReuse = true, idleTimeoutMs = 10_000, advertisedTimeoutSec = 5).use { srv ->
            val v = runStep(srv.url, tapDelayMs = 0)
            assertTrue(v.passed, v.reason)
            assertEquals(1, srv.killed.get(), "the reused connection was killed once")
            assertEquals(2, srv.served.get(), "both LLM calls were eventually answered")
            assertEquals(2, srv.connections.get(), "the retry opened a fresh connection")
        }
    }

    @Test
    fun `a tool that outlasts the server's idle timeout does not break the step`() {
        // server idles out after 1s (advertised as Keep-Alive: timeout=1); the tool takes 1.5s
        StaleServer(killOnReuse = false, idleTimeoutMs = 1_000, advertisedTimeoutSec = 1).use { srv ->
            val v = runStep(srv.url, tapDelayMs = 1_500)
            assertTrue(v.passed, v.reason)
            assertEquals(2, srv.served.get())
            assertEquals(2, srv.connections.get(), "the idle connection was not reused")
        }
    }

    // ------------------------------------------------------------------ harness

    private class SlowDevice(private val tapDelayMs: Long) : Device {
        override fun observe() = LogicalLayout(listOf(
            LogicalElement(0, "웹툰", null, listOf("clickable"), emptyList(), null, Point(1, 1), null)))
        override fun tap(x: Int, y: Int) { Thread.sleep(tapDelayMs) }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
        override fun inputText(text: String) {}
        override fun keyBack() {}
        override fun sleep(ms: Long) {}
    }

    private fun runStep(url: String, tapDelayMs: Long): ScenarioSession.Verdict {
        val controller = DeviceController(SlowDevice(tapDelayMs), settleIntervalMs = 0)
        val session = ScenarioSession(SimulLlm.executor(url), controller, model = SimulLlm.model("qwen"))
        return session.runStep("Tap \"웹툰\"", controller.fullLayout())
    }

    /**
     * Minimal HTTP/1.1 server over raw sockets so connection behaviour is under test control:
     * closes a connection that idles [idleTimeoutMs]; with [killOnReuse], the second request on
     * any one connection is answered by closing the socket — the "closed by peer" race, made
     * deterministic. Replies: first served request → tap("웹툰") call, later ones → report(PASSED).
     */
    private class StaleServer(
        private val killOnReuse: Boolean,
        private val idleTimeoutMs: Int,
        private val advertisedTimeoutSec: Int,
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val url = "http://127.0.0.1:${socket.localPort}"
        val connections = AtomicInteger()
        val served = AtomicInteger()
        val killed = AtomicInteger()

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val s = runCatching { socket.accept() }.getOrNull() ?: break
                    connections.incrementAndGet()
                    thread(isDaemon = true) { handle(s) }
                }
            }
        }

        private fun handle(s: Socket) {
            s.soTimeout = idleTimeoutMs
            var onThisConnection = 0
            try {
                while (true) {
                    if (!readRequest(s.getInputStream())) break
                    onThisConnection++
                    if (killOnReuse && onThisConnection == 2) { killed.incrementAndGet(); break }
                    val n = served.incrementAndGet()
                    val call = if (n == 1) """{"id":"c1","type":"function","function":{"name":"tap","arguments":"{\"target\":\"웹툰\"}"}}"""
                               else """{"id":"c$n","type":"function","function":{"name":"report","arguments":"{\"status\":\"PASSED\",\"reason\":\"ok\"}"}}"""
                    val body = """{"id":"r","object":"chat.completion","created":1,"model":"qwen","choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[$call]}}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""".toByteArray()
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n" +
                        "Connection: keep-alive\r\nKeep-Alive: timeout=$advertisedTimeoutSec, max=100\r\n\r\n"
                    s.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
            } finally {
                runCatching { s.close() }
            }
        }

        /** Consumes one request (headers + body); false on EOF before any byte. */
        private fun readRequest(input: InputStream): Boolean {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) return head.isNotEmpty()
                head.append(b.toChar())
            }
            val headers = head.toString().lowercase()
            val length = Regex("content-length:\\s*(\\d+)").find(headers)?.groupValues?.get(1)?.toInt()
            if (length != null) {
                var left = length
                while (left > 0) { val r = input.read(ByteArray(left)); if (r < 0) break; left -= r }
            } else if ("transfer-encoding: chunked" in headers) {
                while (true) {
                    val line = StringBuilder()
                    while (!line.endsWith("\r\n")) line.append(input.read().toChar())
                    val size = line.trim().toString().toInt(16)
                    if (size == 0) { input.read(); input.read(); break }
                    var left = size + 2
                    while (left > 0) { val r = input.read(ByteArray(left)); if (r < 0) break; left -= r }
                }
            }
            return true
        }

        override fun close() { runCatching { socket.close() } }
    }
}
