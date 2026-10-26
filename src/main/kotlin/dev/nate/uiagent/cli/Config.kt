package dev.nate.uiagent.cli

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import dev.nate.uiagent.Defaults
import dev.nate.uiagent.agent.SimulLlm
import dev.nate.uiagent.device.DevicePins
import dev.nate.uiagent.device.DisplayProfile
import java.io.File

/**
 * The `llm:` block of config.yaml — an OpenAI-compatible endpoint. A local llama-server
 * needs only [url]; a hosted API also needs [model] and an API key.
 *
 * Keys never live in config.yaml literally (the file is committed): `apiKey: ${VAR}` reads the
 * environment at load time, and with no `apiKey` at all `SIMUL_LLM_API_KEY` is consulted. A
 * `${VAR}` that is unset is remembered in [missingKeyEnv] and only becomes an error when a
 * client is actually built ([client]) — replay runs never touch the LLM and must not need
 * the secret.
 */
data class LlmConfig(
    val url: String = DEFAULT_URL,
    val model: String = SimulLlm.DEFAULT_MODEL,
    val apiKey: String? = null,
    /** Name of the env var `apiKey: ${VAR}` pointed at, when it resolved to nothing. */
    val missingKeyEnv: String? = null,
) {
    /** Human-readable problem that would make [client] throw, or null. */
    fun keyProblem(): String? = missingKeyEnv?.let {
        "llm.apiKey references \$$it but it is not set in the environment"
    }

    /** The Koog executor for this endpoint. Throws (never silently drops the key) when `${VAR}` is unset. */
    fun client(): PromptExecutor {
        keyProblem()?.let { throw IllegalStateException(it) }
        return SimulLlm.executor(url, apiKey)
    }

    fun llmModel(): LLModel = SimulLlm.model(model)

    companion object {
        const val DEFAULT_URL = "http://localhost:8080"
        const val DEFAULT_KEY_ENV = "SIMUL_LLM_API_KEY"
        private val ENV_REF = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}""")

        fun fromYaml(y: Map<String, Any>, env: (String) -> String? = System::getenv): LlmConfig {
            val rawKey = MiniYaml.string(y, "llm", "apiKey")?.trim()?.ifEmpty { null }
            val ref = rawKey?.let { ENV_REF.matchEntire(it)?.groupValues?.get(1) }
            val key = when {
                rawKey == null -> env(DEFAULT_KEY_ENV)?.ifEmpty { null }
                ref != null -> env(ref)?.ifEmpty { null }
                else -> rawKey
            }
            return LlmConfig(
                url = MiniYaml.string(y, "llm", "url") ?: DEFAULT_URL,
                model = MiniYaml.string(y, "llm", "model") ?: SimulLlm.DEFAULT_MODEL,
                apiKey = key,
                missingKeyEnv = ref.takeIf { it != null && key == null },
            )
        }
    }
}

/** Parsed `.simul/config.yaml` with defaults for every field. */
data class SimulConfig(
    val agentVersion: String?,
    val app: String?,
    val llm: LlmConfig,
    val maxTurns: Int,
    val scrollLimit: Int,
    /** Emulator display to force for the whole run (trace portability across devices). */
    val display: DisplayProfile? = null,
    /** App locale + emulator `settings` pinned for the whole run — the baseline every scenario assumes (see DevicePins). */
    val device: DevicePins = DevicePins.NONE,
) {
    companion object {
        fun load(file: File, env: (String) -> String? = System::getenv): SimulConfig {
            val y = if (file.isFile) MiniYaml.parse(file.readText()) else emptyMap()
            return SimulConfig(
                agentVersion = MiniYaml.string(y, "agentVersion"),
                app = MiniYaml.string(y, "app"),
                llm = LlmConfig.fromYaml(y, env),
                maxTurns = MiniYaml.string(y, "defaults", "maxTurns")?.toIntOrNull() ?: Defaults.MAX_TURNS,
                scrollLimit = MiniYaml.string(y, "defaults", "scrollLimit")?.toIntOrNull() ?: Defaults.SCROLL_LIMIT,
                display = DisplayProfile.fromConfig(
                    MiniYaml.string(y, "display", "sizeClass"),
                    MiniYaml.string(y, "display", "size"),
                    MiniYaml.string(y, "display", "density"),
                ),
                device = DevicePins.parse(y["device"], "config.yaml"),
            )
        }
    }
}
