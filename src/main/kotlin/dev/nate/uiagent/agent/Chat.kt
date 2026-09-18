package dev.nate.uiagent.agent

import dev.nate.uiagent.json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Minimal OpenAI-compatible chat client for llama-server. We own the message list — koog's
 * AIAgent hid it, which made cross-step conversation reuse (and exact prompt logging)
 * impossible. Owning it means every step of a scenario APPENDS to one conversation, so
 * llama.cpp's prefix cache re-evaluates only the new tokens.
 *
 * Serialization is manual JsonElement (project rule: no @Serializable compiler plugin), with
 * stable field ordering — byte-identical prefixes are what the server cache keys on.
 */

data class ToolCall(val id: String, val name: String, val argumentsJson: String)

/** One chat message. role: system | user | assistant | tool. */
data class ChatMessage(
    val role: String,
    val content: String?,
    val toolCalls: List<ToolCall> = emptyList(),
    /** For role=tool: which call this result answers. */
    val toolCallId: String? = null,
)

data class ChatResponse(val message: ChatMessage)

interface ChatClient {
    fun complete(messages: List<ChatMessage>, tools: JsonArray, temperature: Double): ChatResponse
}

class HttpChatClient(
    private val baseUrl: String,
    private val model: String = "qwen",
    private val timeout: Duration = Duration.ofSeconds(300),
) : ChatClient {

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun complete(messages: List<ChatMessage>, tools: JsonArray, temperature: Double): ChatResponse {
        val body = encodeRequest(model, messages, tools, temperature)
        val req = HttpRequest.newBuilder(URI.create("$baseUrl/v1/chat/completions"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) {
            throw RuntimeException("llm http ${resp.statusCode()}: ${resp.body().take(300)}")
        }
        return ChatResponse(parseAssistantMessage(resp.body()))
    }

    companion object {
        fun encodeRequest(model: String, messages: List<ChatMessage>, tools: JsonArray, temperature: Double): String =
            buildJsonObject {
                put("model", model)
                put("temperature", temperature)
                // A deterministic test executor never wants thinking tokens. The template kwarg
                // is the only thing that actually suppresses them — Qwen3.8 ignores the old
                // /no_think soft switch (measured on-server), and reasoning_effort only shortens.
                put("chat_template_kwargs", buildJsonObject { put("enable_thinking", false) })
                put("messages", buildJsonArray { messages.forEach { add(messageJson(it)) } })
                put("tools", tools)
            }.toString()

        private fun messageJson(m: ChatMessage): JsonObject = buildJsonObject {
            put("role", m.role)
            put("content", m.content ?: "")
            if (m.toolCalls.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    m.toolCalls.forEach { tc ->
                        add(buildJsonObject {
                            put("id", tc.id)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tc.name)
                                put("arguments", tc.argumentsJson)
                            })
                        })
                    }
                })
            }
            m.toolCallId?.let { put("tool_call_id", it) }
        }

        /** Parse choices[0].message into a [ChatMessage]; malformed replies throw. */
        fun parseAssistantMessage(body: String): ChatMessage {
            val msg = json.parseToJsonElement(body).jsonObject["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject
                ?: throw RuntimeException("llm reply has no choices[0].message: ${body.take(200)}")
            val calls = (msg["tool_calls"] as? JsonArray)?.mapIndexed { i, el ->
                val o = el.jsonObject
                val fn = o["function"]?.jsonObject
                    ?: throw RuntimeException("tool_call without function: $o")
                ToolCall(
                    id = o["id"]?.jsonPrimitive?.contentOrNull ?: "call_$i",
                    name = fn["name"]?.jsonPrimitive?.contentOrNull
                        ?: throw RuntimeException("tool_call without name: $o"),
                    argumentsJson = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
                )
            } ?: emptyList()
            return ChatMessage(
                role = "assistant",
                content = msg["content"]?.jsonPrimitive?.contentOrNull,
                toolCalls = calls,
            )
        }
    }
}
