package dev.nate.uiagent.web

import dev.nate.uiagent.Bounds
import dev.nate.uiagent.json
import dev.nate.uiagent.runProcess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Minimal Chrome DevTools Protocol client for Android WebViews, deliberately library-free:
 * discovery via `adb shell` + HTTP GET /json, evaluation via the JDK 11 HttpClient WebSocket.
 *
 * Error policy (plan M1): observation failures must never kill the agent. [discover] returns a
 * [Discovery] whose `failure` explains why no target is available; [evaluate] returns
 * [Eval.Failed] instead of throwing. Callers degrade to native-only observation.
 */
open class CdpClient(private val adbBin: String = "adb") : AutoCloseable {

    /** One debuggable page inside a WebView, as listed by GET /json. */
    data class Target(
        val id: String,
        val url: String,
        val title: String,
        /** Full ws:// url through the forwarded local port. */
        val webSocketUrl: String,
        /** On-screen rect of the hosting WebView in physical px (from the description field). */
        val rect: Bounds?,
        val visible: Boolean?,
        val attached: Boolean?,
    )

    data class Discovery(val targets: List<Target>, val failure: String? = null)

    sealed interface Eval {
        data class Ok(val value: String) : Eval
        data class Failed(val reason: String) : Eval
    }

    /** socket name (e.g. webview_devtools_remote_8716) -> forwarded local port */
    private val forwards = mutableMapOf<String, Int>()

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(HTTP_TIMEOUT_MS))
        .build()

    // ---------------------------------------------------------------- discover

    /**
     * Find debuggable WebView pages. When [appId] is given, only sockets whose pid matches a
     * live process of that package are considered (socket names embed the app pid).
     */
    open fun discover(appId: String?): Discovery {
        val unixDump = runCatching { runProcess(listOf(adbBin, "shell", "cat", "/proc/net/unix")) }
            .getOrElse { return Discovery(emptyList(), "adb failed: ${it.message?.lineSequence()?.first()}") }
        var sockets = parseWebViewSockets(unixDump)
        if (sockets.isEmpty()) {
            return Discovery(emptyList(), "no webview_devtools socket on the device")
        }
        if (appId != null) {
            val pids = runCatching { runProcess(listOf(adbBin, "shell", "pidof", appId)) }
                .getOrDefault("").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            sockets = sockets.filter { socketPid(it) in pids }
            if (sockets.isEmpty()) return Discovery(emptyList(), "no webview_devtools socket for $appId")
        }
        val targets = mutableListOf<Target>()
        var lastError: String? = null
        for (socket in sockets) {
            var port = forwardPort(socket)
            if (port == null) {
                lastError = "adb forward failed for $socket"
                continue
            }
            var body = httpGet("http://localhost:$port/json")
            if (body == null) {
                // stale forward (adb server restarted): re-forward once and retry
                forwards.remove(socket)
                port = forwardPort(socket)
                body = port?.let { httpGet("http://localhost:$it/json") }
            }
            if (port == null || body == null) {
                lastError = "GET /json failed for $socket"
                continue
            }
            targets += parseTargets(body, port)
        }
        if (targets.isEmpty()) {
            return Discovery(emptyList(), lastError ?: "webview socket found but no debuggable page")
        }
        return Discovery(targets)
    }

    private fun forwardPort(socket: String): Int? {
        forwards[socket]?.let { return it }
        val out = runCatching {
            runProcess(listOf(adbBin, "forward", "tcp:0", "localabstract:$socket"))
        }.getOrNull() ?: return null
        val port = out.trim().toIntOrNull() ?: return null
        forwards[socket] = port
        return port
    }

    private fun httpGet(url: String): String? = runCatching {
        val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(HTTP_TIMEOUT_MS)).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() == 200) resp.body() else null
    }.getOrNull()

    // ---------------------------------------------------------------- evaluate

    /** One-shot Runtime.evaluate with returnByValue. The result value is returned as a string. */
    open fun evaluate(target: Target, js: String, timeoutMs: Long = EVAL_TIMEOUT_MS): Eval {
        val request = buildJsonObject {
            put("id", 1)
            put("method", "Runtime.evaluate")
            putJsonObject("params") {
                put("expression", js)
                put("returnByValue", true)
            }
        }.toString()

        val messages = LinkedBlockingQueue<String>()
        val ws = runCatching {
            http.newWebSocketBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .buildAsync(URI.create(target.webSocketUrl), QueueListener(messages))
                .get(timeoutMs, TimeUnit.MILLISECONDS)
        }.getOrElse { return Eval.Failed("websocket connect failed: ${it.message?.lineSequence()?.first()}") }

        try {
            runCatching { ws.sendText(request, true).get(timeoutMs, TimeUnit.MILLISECONDS) }
                .getOrElse { return Eval.Failed("websocket send failed: ${it.message}") }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return Eval.Failed("evaluate timed out after ${timeoutMs}ms")
                val msg = messages.poll(remaining, TimeUnit.MILLISECONDS)
                    ?: return Eval.Failed("evaluate timed out after ${timeoutMs}ms")
                val parsed = runCatching { json.parseToJsonElement(msg).jsonObject }.getOrNull() ?: continue
                if (parsed["id"]?.jsonPrimitive?.intOrNull != 1) continue // event, not our reply
                return parseEvalReply(parsed)
            }
        } finally {
            runCatching { ws.abort() }
        }
    }

    /** `document.readyState`, or null when it cannot be evaluated. */
    open fun readyState(target: Target): String? =
        (evaluate(target, "document.readyState") as? Eval.Ok)?.value

    override fun close() {
        forwards.values.forEach { port ->
            runCatching { runProcess(listOf(adbBin, "forward", "--remove", "tcp:$port")) }
        }
        forwards.clear()
    }

    /** Accumulates (possibly fragmented) text frames into complete messages on a queue. */
    private class QueueListener(private val queue: LinkedBlockingQueue<String>) : WebSocket.Listener {
        private val buf = StringBuilder()
        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buf.append(data)
            if (last) {
                queue.put(buf.toString())
                buf.setLength(0)
            }
            ws.request(1)
            return null
        }

        override fun onError(ws: WebSocket, error: Throwable) {}
        override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? =
            CompletableFuture.completedFuture(null)
    }

    companion object {
        const val HTTP_TIMEOUT_MS = 3_000L
        const val EVAL_TIMEOUT_MS = 3_000L

        private val SOCKET_REGEX = Regex("@(webview_devtools_remote_(\\d+))")

        /** Socket names ("webview_devtools_remote_<pid>") from a /proc/net/unix dump. */
        fun parseWebViewSockets(procNetUnix: String): List<String> =
            SOCKET_REGEX.findAll(procNetUnix).map { it.groupValues[1] }.distinct().toList()

        /** The pid embedded in a webview devtools socket name, or null. */
        fun socketPid(socket: String): String? =
            Regex("webview_devtools_remote_(\\d+)").find(socket)?.groupValues?.get(1)

        /**
         * Parse a GET /json response into page targets. Non-page targets are dropped. The
         * `description` field is itself a JSON string carrying the WebView's on-screen rect
         * (physical px) and visibility — validated in the M0 spike.
         */
        fun parseTargets(jsonBody: String, localPort: Int): List<Target> {
            val arr = runCatching { json.parseToJsonElement(jsonBody) }.getOrNull() as? JsonArray
                ?: return emptyList()
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                if (o["type"]?.jsonPrimitive?.contentOrNull != "page") return@mapNotNull null
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val desc = o["description"]?.jsonPrimitive?.contentOrNull
                    ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
                val rect = desc?.let {
                    val x = it["screenX"]?.jsonPrimitive?.intOrNull
                    val y = it["screenY"]?.jsonPrimitive?.intOrNull
                    val w = it["width"]?.jsonPrimitive?.intOrNull
                    val h = it["height"]?.jsonPrimitive?.intOrNull
                    if (x != null && y != null && w != null && h != null && w > 0 && h > 0)
                        Bounds(x, y, x + w, y + h) else null
                }
                Target(
                    id = id,
                    url = o["url"]?.jsonPrimitive?.contentOrNull ?: "",
                    title = o["title"]?.jsonPrimitive?.contentOrNull ?: "",
                    // rebuild against our forwarded port; the advertised url may embed another
                    webSocketUrl = "ws://localhost:$localPort/devtools/page/$id",
                    rect = rect,
                    visible = desc?.get("visible")?.jsonPrimitive?.booleanOrNull,
                    attached = desc?.get("attached")?.jsonPrimitive?.booleanOrNull,
                )
            }
        }

        /** Extract the Runtime.evaluate reply value; exceptions and non-string results fail. */
        fun parseEvalReply(reply: JsonObject): Eval {
            val result = reply["result"] as? JsonObject
                ?: return Eval.Failed("malformed evaluate reply")
            (result["exceptionDetails"] as? JsonObject)?.let { ex ->
                val text = ex["text"]?.jsonPrimitive?.contentOrNull ?: "JS exception"
                return Eval.Failed("evaluate threw: $text")
            }
            val inner = result["result"] as? JsonObject
                ?: return Eval.Failed("malformed evaluate reply")
            val value = inner["value"]?.jsonPrimitive?.contentOrNull
                ?: return Eval.Failed("evaluate returned no value (type=${inner["type"]?.jsonPrimitive?.contentOrNull})")
            return Eval.Ok(value)
        }
    }
}
