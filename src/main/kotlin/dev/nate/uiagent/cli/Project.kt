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

    /** All scenario markdown files, sorted for stable ordering — a screen's way in (`_entry.md`, or one per mode) ahead of the scenarios that start from it. */
    fun scenarios(): List<File> =
        scenariosDir.walkTopDown()
            .filter { it.isFile && it.extension == "md" && it.name != ScreenMd.FILE }
            .sortedWith(compareBy({ it.parentFile.relativeTo(scenariosDir).path }, { !isEntry(it) }, { it.name }))
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
     * screen is open, so it has no way in to write and starts from the screen's. Where the ways in are one per
     * mode ([Modes]) it is the one of [md]'s mode, and for a file common to the modes the first mode's.
     */
    fun entryFileOf(md: File): File? {
        if (isEntry(md) || screenOf(md).isEmpty()) return null
        return entryFileIn(md.absoluteFile.parentFile, modeOf(md))
    }

    /**
     * The way in a scenario of [mode] in [dir] starts from: of the nearest directory, [dir] or above, that has one.
     * There a plain `_entry.md` serves every mode; where the ways in are one per mode it is [mode]'s — a place that
     * has none for it is not there in that mode, and a scenario of that mode cannot be under it — and without a
     * mode (a file common to the modes) the first of the screen's modes that has one.
     */
    private fun entryFileIn(dir: File, mode: String?): File? {
        val top = scenariosDir.absoluteFile
        for (d in generateSequence(dir.absoluteFile) { it.parentFile }.takeWhile { it != top }) {
            val plain = File(d, ENTRY_FILE).takeIf { it.isFile }
            val byMode = modeEntriesIn(d)
            if (plain == null && byMode.isEmpty()) continue
            val here = screenOfDir(d)
            if (mode != null) return byMode[mode] ?: plain
                ?: throw IllegalArgumentException("'$here' has no way in for the mode '$mode' (${byMode.values.joinToString(", ") { it.name }}) — a scenario of that mode cannot be under it")
            return plain ?: modesOf(here).firstNotNullOfOrNull { byMode[it] } ?: byMode.values.first()
        }
        return null
    }

    /** The ways in of [dir] that are one mode's (`_entry@타인.md`), by mode. */
    private fun modeEntriesIn(dir: File): Map<String, File> =
        dir.listFiles { f -> f.isFile && f.name != ENTRY_FILE && isEntry(f) }.orEmpty().sortedBy { it.name }
            .associateBy { Mentions.norm(Modes.of(it.nameWithoutExtension)!!) }

    private fun screenOfDir(dir: File): String = Mentions.norm(dir.absoluteFile.relativeTo(scenariosDir.absoluteFile).path)

    /**
     * The modes of [screen]: the `modes:` line of its `_screen.md`, or of the nearest screen above that has one —
     * a tab of a screen with modes is in those modes too. Empty for a screen that is one screen.
     */
    fun modesOf(screen: String): List<String> =
        generateSequence(Mentions.norm(screen).ifEmpty { null }) { at -> at.substringBeforeLast('/', "").ifEmpty { null } }
            .firstNotNullOfOrNull { at -> layoutOf(at)?.modes?.takeIf { it.isNotEmpty() } }.orEmpty()

    /** The values of each mode of [screen] ([ScreenLayout.data]), from the same `_screen.md` its modes are named in. */
    fun modeDataOf(screen: String): Map<String, Map<String, String>> =
        generateSequence(Mentions.norm(screen).ifEmpty { null }) { at -> at.substringBeforeLast('/', "").ifEmpty { null } }
            .firstNotNullOfOrNull { at -> layoutOf(at)?.takeIf { it.modes.isNotEmpty() } }?.data.orEmpty()

    /**
     * The mode [md]'s name says it is of (`헤더@타인.md` → `타인`), one of its screen's `modes:`; null for a file
     * common to the modes, and for every file of a screen without modes.
     */
    fun modeOf(md: File): String? {
        val mode = Modes.of(md.nameWithoutExtension)?.let(Mentions::norm) ?: return null
        val modes = modesOf(screenOf(md))
        val where = md.absoluteFile.relativeTo(scenariosDir.absoluteFile).path
        require(modes.isNotEmpty()) { "$where: `@$mode` in a file's name is a mode of its screen — no ${ScreenMd.FILE} at or above it has a `modes:` line" }
        require(mode in modes) { "$where: `@$mode` is not a mode of its screen (modes: ${modes.joinToString(", ")})" }
        return mode
    }

    /**
     * The mode of the place [md] is in when only one mode has that place: the nearest directory with a way in has
     * it for that mode alone (a tab only the owner sees). Null where the place is there in every mode.
     */
    private fun placeModeOf(md: File): String? {
        val top = scenariosDir.absoluteFile
        for (d in generateSequence(md.absoluteFile.parentFile) { it.parentFile }.takeWhile { it != top }) {
            val byMode = modeEntriesIn(d)
            if (File(d, ENTRY_FILE).isFile) return null
            if (byMode.isNotEmpty()) return byMode.keys.singleOrNull()
        }
        return null
    }

    /** What a scenario that starts from the way in [entry] names it by ([Modes.key]). */
    fun entryKeyOf(entry: File): String = Modes.key(screenOf(entry), Modes.of(entry.nameWithoutExtension)?.let(Mentions::norm))

    /** Every screen of the project: the directories that hold a scenario, in themselves or below, named as [screenOf] names them. */
    fun screens(): List<String> = scenarios().map(::screenOf).filter { it.isNotEmpty() }
        .flatMap { path -> path.split('/').let { seg -> seg.indices.map { i -> seg.subList(0, i + 1).joinToString("/") } } }.distinct()

    /**
     * What [screen] is made of, as its `_screen.md` says ([ScreenLayout]); null when its directory has none. A part
     * or a tab it names is a directory under the screen's: a directory is a place — a screen, a piece of one — and
     * a file in it a scenario. A piece with one check is still a directory, with that one scenario in it.
     */
    fun layoutOf(screen: String): ScreenLayout? {
        val dir = dirOf(screen) ?: return null
        val file = File(dir, ScreenMd.FILE).takeIf { it.isFile } ?: return null
        val where = file.relativeTo(scenariosDir).path
        val layout = ScreenMd.parse(file.readText(), where) ?: return null
        val under = dir.listFiles { f -> f.isDirectory }?.map { Mentions.norm(it.name) }.orEmpty().toSet()
        val missing = layout.names.toSet() - under
        if (missing.isNotEmpty()) {
            // a scenario file of that name is the likeliest slip: say where it goes
            val files = dir.listFiles { f -> f.isFile && f.extension == "md" }?.map { Mentions.norm(it.nameWithoutExtension) }.orEmpty().toSet()
            val hint = (missing intersect files).takeIf { it.isNotEmpty() }
                ?.let { " — ${it.joinToString(", ") { n -> "$n.md" }} is a scenario: a part is a directory, put it in one (${it.first()}/…)" } ?: ""
            throw IllegalArgumentException("$where: parts ${missing.joinToString(", ")} — a part or a tab is a directory under this screen's (there: ${under.joinToString(", ").ifEmpty { "none" }})$hint")
        }
        return layout
    }

    /** The layouts of [screen] and of every screen above it that has one, by screen — what a result carries for the report. */
    fun layoutsFor(screen: String): Map<String, ScreenLayout> =
        generateSequence(Mentions.norm(screen).ifEmpty { null }) { at -> at.substringBeforeLast('/', "").ifEmpty { null } }
            .mapNotNull { at -> layoutOf(at)?.let { at to it } }.toMap()

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
     * Its `@화면` mentions are resolved here, against the project's screens — a part of a screen is one, being a
     * directory: one that names none is refused.
     */
    fun load(md: File): Scenario {
        require(md.name != ScreenMd.FILE) { "${ScreenMd.FILE} says what a screen is made of — it is not a scenario and is not run" }
        layoutsFor(screenOf(md)) // read here so a line that names no directory is refused with the scenario, not found in the report
        val mode = modeOf(md)
        val data = modeDataOf(screenOf(md))
        val names = data.values.firstOrNull()?.keys.orEmpty()
        val parsed = mentioned(ScenarioMd.parse(md, names))
        // every screen's way in is the same file name: unnamed, it is called by its screen in logs, summaries and report directories
        if (isEntry(md)) return parsed.copy(mode = mode).also(::checkEntry).filled(mode?.let(data::get).orEmpty())
            .let { if (it.name == Modes.base(md.nameWithoutExtension)) it.copy(name = entryName(screenOf(md), mode)) else it }
        val scenario = parsed.copy(mode = mode ?: placeModeOf(md))
        val own = scenario.setup
        val key = own?.entry ?: (if (own?.deeplink == null && own?.launch != true) entryFileOf(md)?.let(::entryKeyOf) else null)
        val used = scenario.written.flatMap { ModeData.used(it, names) }.distinct()
        if (used.isEmpty()) return startedFrom(scenario, key)
        val name = "scenario '${scenario.name}'"
        val shared = scenario.values.keys intersect names
        require(shared.isEmpty()) { "$name: ${shared.joinToString(", ") { "\$$it" }} is a value of its screen's modes (${ScreenMd.FILE}) — its `values:` cannot name it too" }
        // of one mode — its file's, its place's, or the one its `setup.entry` names: that mode's values, one run
        (scenario.mode ?: own?.entry?.takeIf { dirOf(it) == null }?.let(Modes::modeOf))?.let { one ->
            return startedFrom(scenario, key).filled(data[one]?.filterKeys { it in used } ?: throw IllegalArgumentException("$name: setup.entry '${own?.entry}' — '$one' is not a mode of its screen (modes: ${data.keys.joinToString(", ")})"))
        }
        // common to the modes: the same behaviour on each mode's data — it runs in each, from that mode's way in
        val written = used.joinToString(", ") { "\$$it" }
        require(scenario.states.isEmpty()) { "$name: it names $written, so it runs once in each mode — `states:` would run each of those again; write the states as files of their own" }
        require(own?.deeplink == null && own?.launch != true) { "$name: it names $written, so it runs once in each mode, from that mode's way in — it cannot open the app itself (setup.deeplink, setup.launch)" }
        val dir = md.absoluteFile.parentFile
        val inModes = data.map { (m, its) ->
            val from = own?.entry?.let { inMode(it, m) } ?: entryFileIn(dir, m)?.let(::entryKeyOf)
                ?: throw IllegalArgumentException("$name: it names $written, so it runs once in each mode — its screen has no way in ($ENTRY_STEM@$m.md) to start the mode '$m' from")
            val started = startedFrom(scenario, from)
            Scenario.InMode(m, its.filterKeys { it in used }, started.via, started.setup, scenario.sections.map { sec -> sec.open?.screen?.let { sec.copy(open = sec.open.copy(screen = inMode(it, m))) } ?: sec })
        }
        return startedFrom(scenario, key).copy(inModes = inModes)
    }

    /** [scenario] as it starts from the way in [key] names ([Modes.key]) — the entry's address, and its account unless the scenario has its own; itself when [key] is null. */
    private fun startedFrom(scenario: Scenario, key: String?): Scenario {
        if (key == null) return scenario
        val own = scenario.setup
        val way = entryOf(key) ?: throw IllegalArgumentException("scenario '${scenario.name}': setup.entry '$key' — that screen has no $ENTRY_FILE, nor has a screen above it")
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

    /** The way in [key] names as a run in [mode] takes it: that mode's, where the screen has one per mode and [key] names none. */
    private fun inMode(key: String, mode: String): String {
        val dir = dirOf(key) ?: return key
        if (mode !in modesOf(key)) return key
        return runCatching { entryFileIn(dir, mode) }.getOrNull()?.let { Modes.key(Mentions.norm(key), mode) } ?: key
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

    private fun checkEntry(entry: Scenario) {
        val name = "$ENTRY_FILE '${entry.name}'"
        require(entry.setup?.deeplink != null || entry.setup?.launch == true) { "$name: a way in opens the app — give it `setup.deeplink` or `setup.launch: true`" }
        require(entry.sections.isEmpty() && entry.values.isEmpty() && entry.states.isEmpty()) { "$name: a way in is plain steps — no `##` sections, `values:` or `states:`" }
    }

    /**
     * The way in of [screen] with the trace that plays it (null until it is recorded): its own `_entry.md`, or — for a
     * part of a screen, which has none — that of the nearest screen above ([ScreenEntry.screen] is the one whose file
     * it is). Null when there is no such screen, or no way in on the way up. [screen] is a key ([Modes]): with a
     * mode after it (`프로필/홈@타인`) the way in is that mode's, without one the first mode's where there are several.
     */
    fun entryOf(screen: String): ScreenEntry? {
        val whole = dirOf(screen)
        val dir = whole ?: dirOf(Modes.screenOf(screen)) ?: return null
        val mode = if (whole != null) null else Modes.modeOf(screen)?.let(Mentions::norm)
        val md = entryFileIn(dir, mode) ?: return null
        val entry = load(md)
        val trace = traceFileFor(md).takeIf { it.isFile }?.let { runCatching { TraceJson.read(it) }.getOrNull() }
        return ScreenEntry(entryKeyOf(md), entry.setup!!, entry.steps, entry.precondition, trace, md.relativeTo(scenariosDir).path)
    }

    /**
     * The committed trace sits next to the scenario: `<name>.trace.json` — or, for a scenario
     * that declares `states:`, one per state: `<name>@<state>.trace.json`, and for one that runs in each mode
     * ([Scenario.inModes]) one per mode: `<name>@<mode>.trace.json`. A file of one mode carries the mode in
     * its own name, and so does its trace: `헤더@타인.trace.json`.
     */
    fun traceFileFor(md: File, run: String? = null): File =
        File(md.parentFile, md.nameWithoutExtension + (run?.let { "@$it" } ?: "") + TRACE_SUFFIX)

    fun traceFileFor(md: File, scenario: Scenario): File = traceFileFor(md, scenario.run)

    /** [traceFileFor] inverted: the scenario file a trace sits next to, and the run of it — a state, a mode — it is the trace of (null = the file's one run). */
    fun scenarioOfTrace(trace: File): Pair<File, String?> {
        val base = trace.name.removeSuffix(TRACE_SUFFIX)
        val dir = trace.absoluteFile.parentFile
        // `헤더@타인` is the file of a mode; a state comes after the file's name, whatever is in it
        File(dir, "$base.md").takeIf { it.isFile }?.let { return it to null }
        return File(dir, base.substringBeforeLast('@') + ".md") to base.substringAfterLast('@', "").ifEmpty { null }
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

        /** The stem of a way in's file name: `_entry.md`, and `_entry@타인.md` for one mode's. */
        const val ENTRY_STEM = "_entry"

        /** [file] is a screen's way in: `_entry.md`, or one mode's `_entry@<mode>.md`. */
        fun isEntry(file: File): Boolean = isEntryStem(file.name.removeSuffix(".md")) && file.name.endsWith(".md")

        /** `_entry` or `_entry@<mode>` — a way in's file name without `.md`, which is what a result calls it. */
        fun isEntryStem(stem: String): Boolean = stem == ENTRY_STEM || stem.startsWith("$ENTRY_STEM${Modes.SEP}")

        /** What an unnamed way in is called: `프로필 수정 진입`, and in a mode `프로필 타인 진입`. */
        fun entryName(screen: String, mode: String? = null): String =
            screen.replace('/', ' ').ifEmpty { "root" } + (mode?.let { " $it" } ?: "") + " 진입"

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
