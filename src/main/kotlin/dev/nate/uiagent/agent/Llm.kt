package dev.nate.uiagent.agent

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.retry.RetryConfig
import ai.koog.prompt.executor.clients.retry.RetryablePattern
import ai.koog.prompt.executor.clients.retry.toRetryingClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache5.Apache5
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.apache.hc.client5.http.ConnectionKeepAliveStrategy
import org.apache.hc.client5.http.impl.DefaultConnectionKeepAliveStrategy
import org.apache.hc.core5.util.TimeValue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Koog wiring for the OpenAI-compatible chat-completions server simul talks to (a local
 * llama-server by default; any hosted OpenAI-compatible endpoint with `llm.apiKey`).
 *
 * The request shape is Koog's (`OpenAILLMClient`); what simul pins on top of it is in [PARAMS]:
 * temperature 0 and `chat_template_kwargs {"enable_thinking": false}` — the only switch that
 * actually suppresses Qwen3.8 thinking tokens (`/no_think` is ignored, measured on-server).
 * Serialization is kotlinx with a fixed field order, so an append-only conversation keeps a
 * byte-identical prefix across requests, which is what llama.cpp's prefix cache keys on.
 */
object SimulLlm {
    init {
        // koog logs through kotlin-logging, whose first logger prints "kotlin-logging: initializing…"
        // to stdout — right into the runner's live log / TUI. Every koog entry point goes through
        // this object, so flipping it here precedes the first logger.
        KotlinLoggingConfiguration.logStartupMessage = false
    }

    const val DEFAULT_MODEL = "qwen"
    const val DEFAULT_TIMEOUT_MS = 300_000L

    /**
     * Any OpenAI-compatible chat-completions model: tools + temperature, never the Responses API.
     *
     * The provider is referenced through `LLMProvider.OpenAI`, never as `OpenAILLMProvider` directly:
     * touching the subclass object first runs into Koog's class-init cycle (LLMProvider's companion
     * reads the subclass while it is still initializing) and leaves `LLMProvider.OpenAI` null for the
     * JVM's lifetime — the OpenAI client then reports a null provider and the executor finds no
     * client ("No client found for provider").
     */
    fun model(id: String = DEFAULT_MODEL): LLModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = id,
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Tools,
            LLMCapability.Temperature,
            LLMCapability.OpenAIEndpoint.Completions,
        ),
    )

    /** Prompt params every request carries. `additionalProperties` are flattened into the request body. */
    val PARAMS: OpenAIChatParams = OpenAIChatParams(
        temperature = 0.0,
        additionalProperties = mapOf(
            "chat_template_kwargs" to buildJsonObject { put("enable_thinking", JsonPrimitive(false)) },
        ),
    )

    /** Longest a pooled connection may sit idle before we drop it ourselves (see [keepAlive]). */
    val KEEP_ALIVE_CAP: TimeValue = TimeValue.ofSeconds(3)

    /**
     * How long to keep a connection alive after a response: the server's `Keep-Alive: timeout=N`
     * scaled down (60%), capped at [KEEP_ALIVE_CAP]; the cap alone when the server says nothing.
     *
     * llama-server (cpp-httplib) closes a connection that idles 5s. Koog's default Ktor/Apache5
     * engine keeps pooled connections for as long as the server allows, so a tool that runs ~5s
     * (two observations) hands the next request a connection the server is closing at that very
     * moment → "Connection closed by peer". The JDK client that preceded Koog here retried that
     * case transparently; Apache5 does not. Dropping idle connections well before the server does
     * removes the race; [RETRY] covers whatever is left.
     */
    fun keepAlive(server: TimeValue?): TimeValue {
        if (server == null || server.duration <= 0) return KEEP_ALIVE_CAP
        val scaled = server.toMilliseconds() * 6 / 10
        return if (scaled < KEEP_ALIVE_CAP.toMilliseconds()) TimeValue.ofMilliseconds(scaled) else KEEP_ALIVE_CAP
    }

    /**
     * One retry, only for transport-level failures that mean the request never reached the model
     * (Koog's defaults cover timeouts/429/5xx but not a closed pooled connection). A chat
     * completion has no side effects, so re-sending it is safe; a model-level error is not retried.
     */
    val RETRY: RetryConfig = RetryConfig(
        maxAttempts = 2,
        initialDelay = 200.milliseconds,
        maxDelay = 2.seconds,
        retryablePatterns = RetryConfig.DEFAULT_PATTERNS + listOf(
            RetryablePattern.Keyword("connection closed"),
            RetryablePattern.Keyword("closed by peer"),
            RetryablePattern.Keyword("connection reset"),
            RetryablePattern.Keyword("broken pipe"),
            RetryablePattern.Keyword("end of stream"),
        ),
    )

    /**
     * @param apiKey sent as `Authorization: Bearer <key>`. A local llama-server ignores the header
     *   (Koog always sends it, empty when there is no key); hosted endpoints require it. The key
     *   never appears in logs, traces or error messages.
     */
    fun executor(baseUrl: String, apiKey: String? = null, timeoutMs: Long = DEFAULT_TIMEOUT_MS): PromptExecutor {
        val client = OpenAILLMClient(
            apiKey = apiKey ?: "",
            settings = OpenAIClientSettings(
                baseUrl = baseUrl.trimEnd('/'),
            )
        )
        return MultiLLMPromptExecutor(client)
    }
}
