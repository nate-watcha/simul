package dev.nate.uiagent.agent

import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

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

    /**
     * @param apiKey sent as `Authorization: Bearer <key>`. A local llama-server ignores the header
     *   (Koog always sends it, empty when there is no key); hosted endpoints require it. The key
     *   never appears in logs, traces or error messages.
     */
    fun executor(baseUrl: String, apiKey: String? = null): PromptExecutor {
        val client = OpenAILLMClient(
            apiKey = apiKey ?: "",
            settings = OpenAIClientSettings(
                baseUrl = baseUrl.trimEnd('/'),
            )
        )
        return MultiLLMPromptExecutor(client)
    }
}
