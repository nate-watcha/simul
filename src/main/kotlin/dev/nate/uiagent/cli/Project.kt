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

    /**
     * The screen a scenario file belongs to: its directory under `scenarios/`, as a path ("" for a file at the root).
     * Screens nest as directories do — `나의 왓챠/활동` is the 활동 of 나의 왓챠 — and that path is the screen's name
     * everywhere: in a mention, in `setup.entry`, in the report.
     */
    fun screenOf(md: File): String = Mentions.norm(areaOf(md.absoluteFile.relativeTo(scenariosDir.absoluteFile).path))

    /**
     * The file that says how [md]'s screen is entered, when [md] is not that file: the `_entry.md` of its own
     * directory, else of the nearest directory above that has one — a part of a screen is there as soon as the
     * screen is open, so it has no way in to write and starts from the screen's.
     */
    fun entryFileOf(md: File): File? {
        if (md.name == ENTRY_FILE || screenOf(md).isEmpty()) return null
        val top = scenariosDir.absoluteFile
        return generateSequence(md.absoluteFile.parentFile) { it.parentFile }.takeWhile { it != top }
            .map { File(it, ENTRY_FILE) }.firstOrNull { it.isFile }
    }

    /** Every screen of the project: the directories that hold a scenario, in themselves or below, named as [screenOf] names them. */
    fun screens(): List<String> = scenarios().map(::screenOf).filter { it.isNotEmpty() }
        .flatMap { path -> path.split('/').let { seg -> seg.indices.map { i -> seg.subList(0, i + 1).joinToString("/") } } }.distinct()

    /** The directory of [screen] (a path of directory names, compared in one Unicode form); null when there is none. */
    private fun dirOf(screen: String): File? = screen.split('/').fold(scenariosDir as File?) { dir, name ->
        dir?.listFiles { f -> f.isDirectory && Mentions.norm(f.name) == Mentions.norm(name) }?.firstOrNull()
    }

    /**
     * [md] as it runs. A screen's directory may hold `_entry.md` — where the app is opened to get to that
     * screen and the steps from there (none when the address opens it). A scenario starts from a screen's way
     * in when its `setup` says `entry: <화면>`, or when it opens nothing itself (no `setup.deeplink`, no
     * `setup.launch`) and its own directory has one: it takes the entry's setup — keeping an account of its
     * own when it names one — and the runner plays the entry's steps from the entry's trace before the
     * scenario's first step. A scenario with its own address runs as it always did.
     *
     * Its `@화면` mentions are resolved here, against the project's screens: one that names none is refused.
     */
    fun load(md: File): Scenario {
        val scenario = mentioned(ScenarioMd.parse(md))
        // every screen's way in is the same file name: unnamed, it is called by its screen in logs, summaries and report directories
        if (md.name == ENTRY_FILE) return scenario.also { checkEntry(it, md) }.let { if (it.name == md.nameWithoutExtension) it.copy(name = entryName(screenOf(md))) else it }
        require(scenario.tabs.isEmpty()) { "scenario '${scenario.name}': `tabs:` says which directories under a screen are its tabs — it goes in that screen's $ENTRY_FILE" }
        val own = scenario.setup
        val screen = own?.entry
            ?: entryFileOf(md)?.takeIf { own?.deeplink == null && own?.launch != true }?.let(::screenOf)
            ?: return scenario
        val way = entryOf(screen) ?: throw IllegalArgumentException("scenario '${scenario.name}': setup.entry '$screen' — that screen has no $ENTRY_FILE, nor has a screen above it")
        val entry = way.setup
        val ownAccount = own?.appState != null || own?.clearData == true || scenario.states.isNotEmpty()
        return scenario.copy(
            via = way.screen,
            setup = Scenario.Setup(
                deeplink = entry.deeplink, launch = entry.launch,
                clearData = if (ownAccount) own?.clearData == true else entry.clearData,
                appState = if (ownAccount) own?.appState else entry.appState,
                device = own?.device?.takeIf { it != DevicePins.NONE } ?: entry.device,
            ),
        )
    }

    /** [scenario] with the screens its prose and its sections' sentences mention. */
    private fun mentioned(scenario: Scenario): Scenario {
        val written = listOf(scenario.description) + scenario.sections.map { it.description }
        if (written.none(Mentions::has)) return scenario
        val screens = screens()
        val unknown = written.flatMap { Mentions.unknown(it, screens) }.distinct()
        require(unknown.isEmpty()) {
            "scenario '${scenario.name}': ${unknown.joinToString(", ") { "@$it" }} names no screen (screens: ${screens.joinToString(", ")})"
        }
        return scenario.copy(
            mentions = Mentions.of(scenario.description, screens),
            sections = scenario.sections.map { it.copy(mentions = Mentions.of(it.description, screens)) },
        )
    }

    private fun checkEntry(entry: Scenario, md: File) {
        val name = "$ENTRY_FILE '${entry.name}'"
        val under = md.absoluteFile.parentFile.listFiles { f -> f.isDirectory }?.map { Mentions.norm(it.name) }.orEmpty()
        (entry.tabs - under.toSet()).let { missing ->
            require(missing.isEmpty()) { "$name: tabs ${missing.joinToString(", ")} — a tab is a directory under this screen's (there: ${under.joinToString(", ").ifEmpty { "none" }})" }
        }
        require(entry.setup?.deeplink != null || entry.setup?.launch == true) { "$name: a way in opens the app — give it `setup.deeplink` or `setup.launch: true`" }
        require(entry.sections.isEmpty() && entry.values.isEmpty() && entry.states.isEmpty()) { "$name: a way in is plain steps — no `##` sections, `values:` or `states:`" }
    }

    /**
     * The way in of [screen] with the trace that plays it (null until it is recorded): its own `_entry.md`, or — for a
     * part of a screen, which has none — that of the nearest screen above ([ScreenEntry.screen] is the one whose file
     * it is). Null when there is no such screen, or no way in on the way up.
     */
    fun entryOf(screen: String): ScreenEntry? {
        if (dirOf(screen) == null) return null
        val owner = generateSequence(Mentions.norm(screen)) { at -> at.substringBeforeLast('/', "").ifEmpty { null } }
            .firstOrNull { at -> dirOf(at)?.let { File(it, ENTRY_FILE).isFile } == true } ?: return null
        val md = File(dirOf(owner)!!, ENTRY_FILE)
        val entry = load(md)
        val trace = traceFileFor(md).takeIf { it.isFile }?.let { runCatching { TraceJson.read(it) }.getOrNull() }
        return ScreenEntry(owner, entry.setup!!, entry.steps, entry.precondition, trace, md.relativeTo(scenariosDir).path)
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
