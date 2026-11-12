package dev.nate.uiagent.cli

import dev.nate.uiagent.device.DevicePins
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

    /** All scenario markdown files, sorted for stable ordering — a screen's way in (`_entry.md`) ahead of the scenarios that start from it. */
    fun scenarios(): List<File> =
        scenariosDir.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .sortedWith(compareBy({ it.parentFile.relativeTo(scenariosDir).path }, { it.name != ENTRY_FILE }, { it.name }))
            .toList()

    /** The screen a scenario file belongs to: its group directory under `scenarios/` ("" for a file at the root). */
    fun screenOf(md: File): String = Mentions.norm(areaOf(md.absoluteFile.relativeTo(scenariosDir.absoluteFile).path))

    /** The file that says how [md]'s screen is entered, when its directory has one and [md] is not that file. */
    fun entryFileOf(md: File): File? =
        File(md.absoluteFile.parentFile, ENTRY_FILE).takeIf { it.isFile && md.name != ENTRY_FILE && screenOf(md).isNotEmpty() }

    /**
     * [md] as it runs. A screen's directory may hold `_entry.md` — where the app is opened to get to that
     * screen and the steps from there (none when the address opens it). A scenario beside it that opens
     * nothing itself (no `setup.deeplink`, no `setup.launch`) starts from that way in: it takes the entry's
     * setup — keeping an account of its own when it names one — and the runner plays the entry's steps from
     * the entry's trace before the scenario's first step. A scenario with its own address runs as it always did.
     */
    fun load(md: File): Scenario {
        val scenario = ScenarioMd.parse(md)
        // every screen's way in is the same file name: unnamed, it is called by its screen in logs, summaries and report directories
        if (md.name == ENTRY_FILE) return scenario.also(::checkEntry).let { if (it.name == md.nameWithoutExtension) it.copy(name = entryName(screenOf(md))) else it }
        val own = scenario.setup
        val entryFile = entryFileOf(md)?.takeIf { own?.deeplink == null && own?.launch != true } ?: return scenario
        val entry = ScenarioMd.parse(entryFile).also(::checkEntry).setup!!
        val ownAccount = own?.appState != null || own?.clearData == true || scenario.states.isNotEmpty()
        return scenario.copy(
            via = screenOf(md),
            setup = Scenario.Setup(
                deeplink = entry.deeplink, launch = entry.launch,
                clearData = if (ownAccount) own?.clearData == true else entry.clearData,
                appState = if (ownAccount) own?.appState else entry.appState,
                device = own?.device?.takeIf { it != DevicePins.NONE } ?: entry.device,
            ),
        )
    }

    private fun checkEntry(entry: Scenario) {
        val name = "$ENTRY_FILE '${entry.name}'"
        require(entry.setup?.deeplink != null || entry.setup?.launch == true) { "$name: a way in opens the app — give it `setup.deeplink` or `setup.launch: true`" }
        require(entry.sections.isEmpty() && entry.values.isEmpty() && entry.states.isEmpty()) { "$name: a way in is plain steps — no `##` sections, `values:` or `states:`" }
    }

    /** The way in of [screen] with the trace that plays it (null until it is recorded); null when the screen has no `_entry.md`. */
    fun entryOf(screen: String): ScreenEntry? {
        val dir = scenariosDir.listFiles { f -> f.isDirectory && Mentions.norm(f.name) == Mentions.norm(screen) }?.firstOrNull() ?: return null
        val md = File(dir, ENTRY_FILE).takeIf { it.isFile } ?: return null
        val entry = load(md)
        val trace = traceFileFor(md).takeIf { it.isFile }?.let { runCatching { TraceJson.read(it) }.getOrNull() }
        return ScreenEntry(Mentions.norm(screen), entry.setup!!, entry.steps, entry.precondition, trace, md.relativeTo(scenariosDir).path)
    }

    /**
     * The committed trace sits next to the scenario: `<name>.trace.json` — or, for a scenario
     * that declares `states:`, one per state: `<name>@<state>.trace.json`.
     */
    fun traceFileFor(md: File, state: String? = null): File =
        File(md.parentFile, md.nameWithoutExtension + (state?.let { "@$it" } ?: "") + TRACE_SUFFIX)

    fun traceFileFor(md: File, scenario: Scenario): File = traceFileFor(md, scenario.state)

    /** [traceFileFor] inverted: the scenario file a trace sits next to, and the state it is the trace of (null = stateless). */
    fun scenarioOfTrace(trace: File): Pair<File, String?> {
        val base = trace.name.removeSuffix(TRACE_SUFFIX)
        return File(trace.absoluteFile.parentFile, base.substringBefore('@') + ".md") to base.substringAfter('@', "").ifEmpty { null }
    }

    /**
     * Every runnable scenario — each `states:` scenario expanded to its variants — paired with
     * its markdown file. A file that fails to parse is reported, not thrown, so one authoring
     * mistake cannot take a whole `--all` batch down with it.
     */
    fun scenarioVariants(onError: (File, String) -> Unit = { _, _ -> }): List<Pair<File, Scenario>> =
        scenarios().flatMap { md ->
            runCatching { load(md).variants().map { md to it } }
                .getOrElse { onError(md, it.message ?: it.toString()); emptyList() }
        }

    /** Warning if this binary does not satisfy the config's `agentVersion` requirement. */
    fun versionWarning(): String? {
        val spec = config.agentVersion ?: return null
        val min = spec.removePrefix(">=").trim()
        if (compareVersions(AGENT_VERSION, min) < 0)
            return "warning: config requires agentVersion $spec but this is $AGENT_VERSION"
        return null
    }

    companion object {
        const val TRACE_SUFFIX = ".trace.json"

        /** A screen's way in, in its directory (see [load]). */
        const val ENTRY_FILE = "_entry.md"

        /** What an unnamed way in is called: `프로필 수정 진입`. */
        fun entryName(screen: String): String = screen.replace('/', ' ').ifEmpty { "root" } + " 진입"

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

/** [path] as given when absolute, else relative to [base] — how every CLI argument names a file. */
fun resolvePath(base: File, path: String): File = File(path).takeIf { it.isAbsolute } ?: File(base, path)

/**
 * A screen's way in as the runner plays it: open the app as [setup] says, then the [steps] from [trace] —
 * without the model. [precondition] is the account it was recorded in: under another one the labels a step
 * saw are that account's data and are not asked for again.
 */
class ScreenEntry(
    val screen: String,
    val setup: Scenario.Setup,
    val steps: List<String>,
    val precondition: String?,
    val trace: TraceFile?,
    /** The entry's file under `scenarios/`, for the message that says what to record. */
    val file: String,
)
