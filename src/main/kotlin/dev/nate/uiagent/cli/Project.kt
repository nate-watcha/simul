package dev.nate.uiagent.cli

import dev.nate.uiagent.agent.HttpChatClient
import java.io.File

/** Version of this tool, written into traces and checked against config `agentVersion`. */
const val AGENT_VERSION = "0.2.0"

/**
 * The `.simul/` convention: an app repo opts in by carrying config + scenarios; the agent
 * code itself lives in this repo and is distributed as an installDist binary. The agent never
 * writes outside `.simul/`.
 */
class SimulProject(val appRoot: File) {
    val dir = File(appRoot, ".simul")
    val scenariosDir = File(dir, "scenarios")
    val reportsDir = File(dir, "reports")
    /** App-data snapshots for `setup.appState` (created by `simul state save <name>`). */
    val statesDir = File(dir, "states")
    val config: SimulConfig = SimulConfig.load(File(dir, "config.yaml"))

    /** All scenario markdown files, sorted for stable ordering. */
    fun scenarios(): List<File> =
        scenariosDir.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .sortedBy { it.relativeTo(scenariosDir).path }
            .toList()

    /** The committed trace sits next to the scenario: `<name>.trace.json`. */
    fun traceFileFor(md: File): File = File(md.parentFile, md.nameWithoutExtension + ".trace.json")

    /** Warning if this binary does not satisfy the config's `agentVersion` requirement. */
    fun versionWarning(): String? {
        val spec = config.agentVersion ?: return null
        val min = spec.removePrefix(">=").trim()
        if (compareVersions(AGENT_VERSION, min) < 0)
            return "warning: config requires agentVersion $spec but this is $AGENT_VERSION"
        return null
    }

    companion object {
        /** Walk up from [from] looking for a `.simul/` directory. */
        fun find(from: File): SimulProject? {
            var dir: File? = from.absoluteFile
            while (dir != null) {
                if (File(dir, ".simul").isDirectory) return SimulProject(dir)
                dir = dir.parentFile
            }
            return null
        }

        fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
            val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val d = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0)
                if (d != 0) return d
            }
            return 0
        }
    }
}

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
    val model: String = HttpChatClient.DEFAULT_MODEL,
    val apiKey: String? = null,
    /** Name of the env var `apiKey: ${VAR}` pointed at, when it resolved to nothing. */
    val missingKeyEnv: String? = null,
) {
    /** Human-readable problem that would make [client] throw, or null. */
    fun keyProblem(): String? = missingKeyEnv?.let {
        "llm.apiKey references \$$it but it is not set in the environment"
    }

    fun client(): HttpChatClient {
        keyProblem()?.let { throw IllegalStateException(it) }
        return HttpChatClient(url, model, apiKey)
    }

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
                model = MiniYaml.string(y, "llm", "model") ?: HttpChatClient.DEFAULT_MODEL,
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
) {
    companion object {
        fun load(file: File, env: (String) -> String? = System::getenv): SimulConfig {
            val y = if (file.isFile) MiniYaml.parse(file.readText()) else emptyMap()
            return SimulConfig(
                agentVersion = MiniYaml.string(y, "agentVersion"),
                app = MiniYaml.string(y, "app"),
                llm = LlmConfig.fromYaml(y, env),
                maxTurns = MiniYaml.string(y, "defaults", "maxTurns")?.toIntOrNull() ?: 12,
                scrollLimit = MiniYaml.string(y, "defaults", "scrollLimit")?.toIntOrNull() ?: 3,
                display = DisplayProfile.fromConfig(
                    MiniYaml.string(y, "display", "sizeClass"),
                    MiniYaml.string(y, "display", "size"),
                    MiniYaml.string(y, "display", "density"),
                ),
            )
        }
    }
}
