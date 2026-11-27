package dev.nate.uiagent.cli

import java.io.File

/**
 * The HTML report of one run as pages (README "나이틀리 운영"): what the builder reads from a runs
 * directory and how it groups each scenario — by screen (its group directory), each scenario counted by
 * what it confirms ([Check]). Rendering lives in SitePages.kt.
 *
 * ```
 * .simul/reports/<stamp>/                  one `simul report`
 *   record.json                            the LLM re-recording that preceded the replay (RunSummary), when there was one
 *   record/<feature>/<name>.<state>/       that recording of one scenario (ResultJson, every step and action frame)
 *   run.json                               the replay batch (RunSummary)
 *   <feature>/<name>.<state>/result.json   one replayed scenario (ResultJson)
 * ```
 *
 * A failed recording is told by its own run: the steps the agent got through, the one it stopped on and what it did
 * there — not by the replay, which (when a trace was committed earlier) replays yesterday's trace and says nothing
 * about tonight's failure. record.json alone (one line per scenario, the failed step's frame) is the fallback when
 * the recording left no directory (a crash, an older layout).
 *
 * Every scenario of the batch is a row, not only those with a result.json: a scenario the replay skipped
 * (no trace, because tonight's recording failed and nothing was ever committed) has no directory, and it is
 * exactly the one the report must show (run 39395891887: 7 failed recordings, index empty).
 *
 * Nothing is judged here — a scenario failed when its recording failed (the agent could not perform it
 * on tonight's app) or its replay did (result.json says so).
 */
object Site {

    /** Where the recording's per-scenario directories sit under the report dir. */
    const val RECORD_DIR = "record"

    /** The file name of a screen's way in (`_entry.md`), as a result names it — one mode's is `_entry@<mode>`. */
    const val ENTRY_NAME = SimulProject.ENTRY_STEM

    /** How the reason of a scenario begins that was not run because its screen's way in had failed (see BatchSession.run). */
    const val WAY_IN_FAILED = "not run — the way in to"

    /**
     * How the reason of a scenario begins that was not run because the section of another scenario it starts
     * after ([Scenario.After]) had not passed (see BatchSession.run): the other scenario's failure is the one to read.
     */
    const val CAME_FROM_FAILED = "not run — it starts after"

    /** Where a scenario stands, in the order the report shows them. */
    enum class Kind(val label: String, val cls: String) {
        FAILED("실패", "failed"),
        SETUP("안 돌림", "setup"),
        PASSED("통과", "passed"),
    }

    /** One run directory, loaded. */
    class Run(
        val dir: File, val summary: RunSummary?, val results: List<ResultJson.Result>, val record: RunSummary? = null,
        /** The recording of each scenario (by id) that left a directory under `record/`. */
        val recordings: Map<String, ResultJson.Result> = emptyMap(),
    ) {
        /** `2026-10-19` — the directory name. */
        val date: String get() = dir.name
        val app: String? get() = summary?.app ?: record?.app
    }

    /**
     * One thing a scenario confirms — what the report counts and reads as a line of spec: a `## title` section with
     * its sentence, or the scenario itself (its prose) when it has no sections. [status] is PASSED, FAILED, SKIPPED
     * (an earlier one failed) or NONE (the scenario never ran).
     */
    class Check(
        val title: String?, val description: String, val status: String, val steps: List<ResultJson.Step>,
        /** The address the check's section was opened at instead of the scenario's entry. */
        val deeplink: String? = null,
        /** The screen whose way in the check's section started from instead of the scenario's entry. */
        val entry: String? = null,
        /** The account state the check's section ran as instead of the scenario's. */
        val appState: String? = null,
        /** The screens [description] mentions (`@화면`). */
        val mentions: List<String> = emptyList(),
    ) {
        /** The check started somewhere of its own: it went to look at a place rather than walk there. */
        val opened: Boolean get() = deeplink != null || entry != null
    }

    /**
     * A check that says something about another screen: its sentence — or its scenario's prose, for all the
     * scenario's checks ([check] null) — mentions [to] (`@화면`). It is written, run and counted on the screen
     * [from]; the report shows it on [to]'s page too. [look] tells the two kinds apart by how the check got there:
     * opened at a place of its own, it looked at what a change elsewhere left on [to]; on foot, it is a way to [to].
     * [mode] is the mode of [to] it was seen in, where [to] is a screen with modes: the one the look was opened in
     * (the `@mode` of its `setup.entry`, else the first the place has — what the runner opens), or for a walk the
     * mode the scenario is of. Null when it is of no one mode, or was opened at an address.
     */
    class Link(val from: Screen, val row: Row, val check: Check?, val to: String, val text: String, val status: String, val look: Boolean, val mode: String? = null)

    /** Every mention among [screens]' scenarios, in the screens' order. A screen's way in mentions nothing: it is that screen's. */
    fun links(screens: List<Screen>): List<Link> {
        val by = screens.associateBy { Mentions.norm(it.feature) }
        // the mode a look was opened in: its entry says it (`프로필/홈@타인`), else it is the first mode that place has
        fun openedIn(c: Check): String? {
            val key = c.entry?.let(Mentions::norm) ?: return null
            val place = by[key] ?: return Modes.modeOf(key)
            return place.only ?: place.modes.firstOrNull()
        }
        return screens.flatMap { screen ->
            // the runs of one file in each mode say one thing about the other screen, once
            specs(screen.scenarios).flatMap { row ->
                val t = row.timeline
                val whole = when (row.kind) { Kind.FAILED -> "FAILED"; Kind.PASSED -> "PASSED"; Kind.SETUP -> "NONE" }
                t.mentions.map { Link(screen, row, null, it, t.description, whole, look = false, mode = row.mode) } +
                    row.checks.filter { it.title != null }.flatMap { c ->
                        c.mentions.map { Link(screen, row, c, it, c.description, c.status, c.opened, if (c.opened) openedIn(c) else row.mode) }
                    }
            }
        }
    }

    /**
     * One Firebase event a scenario sent, [count] times: [key] is what the report shows and finds it by — the app's
     * id for it (ResultJson.Event.id), or its [name] where the app sends none.
     */
    class Fired(val key: String, val name: String, val count: Int)

    /** One way into a screen — a deeplink or `앱 실행` — with the account states its scenarios start from and how many each. */
    class Entry(val entry: String, val states: Map<String, Int>)

    /**
     * What a nested screen is to the one its directory is in. The upper screen's `_screen.md` says what it is made of,
     * top to bottom ([ScreenLayout]): a name there is a [PART] of it, a name in the bracketed row a [TAB], and a
     * directory it does not name a screen that [OPENS] from it. Where the upper screen has no such file the files are
     * read instead: a screen with a way in of its own (`_entry.md`) opens from it, one without is a part of it, by
     * name. A screen with nothing above it is a [ROOT].
     */
    enum class Place { ROOT, PART, TAB, OPENS }

    /**
     * The scenarios of one group directory: a screen of the app (or an issue), which is what the report is organised
     * by — the first page lists screens, and each has a page that reads as its spec. Screens nest as their directories
     * do ([children], [place]): a part and a tab are drawn on the page of the screen they belong to, a screen that
     * opens from another has a page of its own under it.
     */
    class Screen(val feature: String, val rows: List<Row>) {
        val name: String get() = feature.ifEmpty { "(root)" }
        /** Its own name, without the screens above it: `활동` of `나의 왓챠/활동`. */
        val label: String get() = feature.substringAfterLast('/').ifEmpty { "(root)" }
        var parent: Screen? = null
            internal set
        var place: Place = Place.ROOT
            internal set
        /** The screens nested in this one: those drawn on it in the order they are drawn, then the ones that open from it by name. */
        var children: List<Screen> = emptyList()
            internal set
        /** What is drawn on this screen, top to bottom: a part, or the row of tabs ([Slot]). */
        var slots: List<Slot> = emptyList()
            internal set
        val parts: List<Screen> get() = children.filter { it.place == Place.PART }
        val tabs: List<Screen> get() = children.filter { it.place == Place.TAB }
        val opens: List<Screen> get() = children.filter { it.place == Place.OPENS }
        /** The screen whose page this one is read on: itself, or the one it is a part or a tab of. */
        val page: Screen get() = if (place == Place.ROOT || place == Place.OPENS) this else parent!!.page
        /** The screens drawn on this one's page: itself, its parts and tabs, and theirs. */
        val onPage: List<Screen> get() = listOf(this) + children.filter { it.place == Place.PART || it.place == Place.TAB }.flatMap { it.onPage }
        /** This screen and every one nested in it. */
        val subtree: List<Screen> get() = listOf(this) + children.flatMap { it.subtree }
        /** The topmost screen it is nested in (itself for a root). */
        val root: Screen get() = parent?.root ?: this
        /** The screen's way in (`_entry.md`), when its directory has one and it ran — the first mode's, where each mode has one. */
        val entry: Row? get() = ways.firstOrNull()
        /** The screen's ways in: the one, or one per mode in the modes' order. */
        val ways: List<Row> get() = rows.filter { it.isEntry }.sortedBy { r -> modes.indexOf(r.mode).let { if (it < 0) Int.MAX_VALUE else it } }
        /**
         * The modes of the screen ([ScreenLayout.modes]): its own, or those of the screen it is nested in — a tab of
         * a screen with modes is in those modes. Empty for a screen that is one screen.
         */
        var modes: List<String> = emptyList()
            internal set
        /**
         * The one mode that has this place — a tab only the owner sees: everything in it and under it is of that mode,
         * and it is so by where it is (its way in is that mode's alone, or a scenario in it says no mode of its own).
         * A place both modes have whose checks so far are all one mode's files is not one. Null for a place every
         * mode has, or a screen without modes.
         */
        val only: String? get() {
            if (modes.isEmpty()) return null
            val all = rowsOf(subtree)
            val mode = all.map { it.mode }.distinct().singleOrNull() ?: return null
            return mode.takeIf { ways.isNotEmpty() || all.any { Modes.of(it.result.name) == null } }
        }
        /** It is entered by a way of its own: its directory has one, or a scenario of it says it started from it. */
        val hasOwnWayIn: Boolean get() = entry != null || rows.any { r -> r.result.setup?.via?.let(Mentions::norm) == Mentions.norm(feature) }
        /** The scenarios that test the screen: all but its way in. */
        val scenarios: List<Row> get() = rows.filterNot { it.isEntry }
        val checks: List<Check> get() = rows.flatMap { it.checks }
        /** The worst of its scenarios. */
        val kind: Kind get() = kindOf(rows)
        val durationMs: Long get() = rows.sumOf { it.timeline.durationMs }
        /** The ways in its scenarios declare (`setup:`), most used first; empty for results that recorded none. */
        val entries: List<Entry> get() = rows.mapNotNull { r -> r.result.setup?.let { s -> s.entry?.let { it to (s.appState ?: r.result.state ?: "") } } }
            .groupBy({ it.first }, { it.second })
            .map { (entry, states) -> Entry(entry, states.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()) }
            .sortedByDescending { it.states.values.sum() }
    }

    /** One place down a screen's page: a [part] of it, or the row of its [tabs]. */
    class Slot(val part: Screen? = null, val tabs: List<Screen> = emptyList())

    /** The worst of [rows]; a screen that only holds other screens has none and nothing against it. */
    fun kindOf(rows: List<Row>): Kind = rows.minOfOrNull { it.kind } ?: Kind.PASSED

    /**
     * [rows] as the lines of spec they are: the runs of one file in each mode ([Row.ranIn]) are one line — told by
     * the worst of them — and every other row its own.
     */
    fun specs(rows: List<Row>): List<Row> = lines(rows).map { g -> g.minBy { it.kind } }

    /** [rows] with the runs of one file in each mode together, in the rows' order. */
    fun lines(rows: List<Row>): List<List<Row>> =
        rows.groupBy { if (it.ranIn != null) it.result.feature + "/" + it.result.name else it.id }.values.toList()

    /** The rows of [screens] together. */
    fun rowsOf(screens: List<Screen>): List<Row> = screens.flatMap { it.rows }

    /**
     * Every screen of a run, those with a failure first, then by name — each knowing the screen it is nested in and
     * what it is to it ([Place]). A directory that holds only other screens' directories is a screen too: the one
     * they are parts of.
     */
    fun screens(rows: List<Row>): List<Screen> {
        val by = LinkedHashMap<String, Screen>()
        rows.groupBy { it.result.feature }.forEach { (feature, its) -> by[Mentions.norm(feature)] = Screen(feature, its) }
        for (feature in by.values.map { it.feature }) {
            var up = feature
            while ('/' in up) { up = up.substringBeforeLast('/'); by.getOrPut(Mentions.norm(up)) { Screen(up, emptyList()) } }
        }
        // what each screen is made of, as any of the run's results carries it
        val layouts = rows.flatMap { it.result.screens.entries }.associate { (screen, layout) -> Mentions.norm(screen) to layout }
        // a screen's modes are those of every screen nested in it: shortest paths first, so an upper screen's are set before a lower one asks
        for (screen in by.values.sortedBy { it.feature.count { c -> c == '/' } }) {
            screen.modes = layouts[Mentions.norm(screen.feature)]?.modes?.takeIf { it.isNotEmpty() }
                ?: by[Mentions.norm(screen.feature.substringBeforeLast('/', ""))]?.modes.orEmpty()
        }
        val kids = by.values.filter { '/' in it.feature }.groupBy { by.getValue(Mentions.norm(it.feature.substringBeforeLast('/'))) }
        for (screen in by.values) {
            val children = kids[screen].orEmpty()
            // a file that only names the screen's modes says nothing of what it is made of
            val layout = layouts[Mentions.norm(screen.feature)]?.takeIf { it.slots.isNotEmpty() }
            if (children.isEmpty() && layout == null) continue
            val under = children.associateBy { Mentions.norm(it.label) }
            for (c in children) {
                c.parent = screen
                val name = Mentions.norm(c.label)
                c.place = when {
                    layout != null -> if (name in layout.tabs) Place.TAB else if (name in layout.parts) Place.PART else Place.OPENS
                    c.hasOwnWayIn -> Place.OPENS
                    else -> Place.PART
                }
            }
            // a named directory none of whose scenarios ran tonight is not there to draw
            screen.slots = layout?.slots?.mapNotNull { slot ->
                if (slot.part != null) under[slot.part]?.let { Slot(part = it) }
                else slot.tabs.mapNotNull(under::get).takeIf { it.isNotEmpty() }?.let { Slot(tabs = it) }
            } ?: children.filter { it.place == Place.PART }.sortedBy { it.feature }.map { Slot(part = it) }
            screen.children = screen.slots.flatMap { listOfNotNull(it.part) + it.tabs } + children.filter { it.place == Place.OPENS }.sortedBy { it.feature }
        }
        return by.values.sortedWith(compareBy({ it.kind.ordinal }, { it.feature }))
    }

    /**
     * The screens that have a page, in the order the report lists them: the roots — one with a failure anywhere in it
     * first, then by name — each followed by the screens that open from it, and from those.
     */
    fun pages(screens: List<Screen>): List<Screen> {
        fun under(s: Screen): List<Screen> = listOf(s) + s.onPage.flatMap { it.opens }.flatMap(::under)
        return screens.filter { it.parent == null }
            .sortedWith(compareBy({ kindOf(rowsOf(it.subtree)).ordinal }, { it.feature }))
            .flatMap(::under)
    }

    /** [row] was not run because the way in of [screen] had failed — the one of [mode], where the screen has one per mode. */
    fun waitsFor(row: Row, screen: String, mode: String? = null): Boolean =
        row.waitsForEntry && row.result.reason?.let(Mentions::norm)?.contains("'${Mentions.norm(Modes.key(screen, mode))}'") == true

    class Row(val result: ResultJson.Result, val kind: Kind, val record: ScenarioOutcome? = null, val recording: ResultJson.Result? = null) {
        val id get() = result.id
        /** This row is its screen's way in (`_entry.md`, or one mode's `_entry@<mode>.md`), not a scenario of it. */
        val isEntry get() = SimulProject.isEntryStem(result.name)
        /**
         * The mode of its screen the scenario is of ([Scenario.mode]); null when it is common to the modes — one that
         * runs in each mode on that mode's data ([ranIn]) is common: the same behaviour, told as one line.
         */
        val mode: String? get() = result.screenMode.takeUnless { result.perMode }
        /** The mode this run was in, for one of the runs of a file that runs in each ([Scenario.perMode]); null otherwise. */
        val ranIn: String? get() = result.screenMode.takeIf { result.perMode }
        /** The values of its mode the scenario names, by name ([Scenario.data]). */
        val data: Map<String, String> get() = result.modeData
        /** What tells this run from the file's other ones in a word: the value it ran on (`WATCHA`), the mode where it names several. */
        val runLabel: String? get() = ranIn?.let { data.values.singleOrNull() ?: it }
        /** The name the modes' files of one check share: `헤더` of `헤더@타인`. */
        val base: String get() = Modes.base(result.name)
        /** What the pages call it: its title — `진입` for a way in that was given none. */
        val title: String get() = if (isEntry && (result.title == result.name || result.title == ENTRY_NAME ||
                result.title == SimulProject.entryName(result.feature) || result.title == SimulProject.entryName(result.feature, mode))) "진입" else result.title
        /** It was not run because the scenario it starts after had not passed there: what it says of that scenario (`'평가하고 지우기 › 평가 뒤 활동 탭', which did not pass`), else null. */
        val waitsForAfter: String? get() = result.reason?.takeIf { kind == Kind.SETUP && it.startsWith(CAME_FROM_FAILED) }?.removePrefix(CAME_FROM_FAILED)?.trim()
        /** It was not run because its screen's way in had failed: the entry's failure is the one to read. */
        val waitsForEntry get() = kind == Kind.SETUP && result.reason?.startsWith(WAY_IN_FAILED) == true
        /** The run the pages tell this scenario by: tonight's recording when it failed and left its steps, else the replay. */
        val timeline: ResultJson.Result get() = recordTimeline ?: result
        /** What this scenario confirms, in file order (see [Check]). */
        val checks: List<Check> get() {
            val t = timeline
            if (t.tests.isNotEmpty()) return t.tests.map { sec ->
                Check(sec.title, sec.description, if (kind == Kind.SETUP) "NONE" else sec.status, t.steps.filter { it.n in sec.from..sec.to }, sec.deeplink, sec.entry, sec.appState, sec.mentions)
            }
            return listOf(Check(null, t.description, when (kind) { Kind.FAILED -> "FAILED"; Kind.PASSED -> "PASSED"; Kind.SETUP -> "NONE" }, t.steps, mentions = t.mentions))
        }
        /**
         * The Firebase events the app sent over this scenario — its launch and its steps — one per event ([Fired]), in
         * the order each was first sent. Firebase's own (`auto`: screen_view, user_engagement) are not the app's.
         */
        val events: List<Fired> by lazy {
            timeline.let { t -> t.setupEvents + t.steps.flatMap { it.events } }.filter { it.origin == "app" }
                .groupBy { it.id ?: it.name }
                .map { (key, sent) -> Fired(key, sent.map { it.name }.distinct().joinToString(" / "), sent.size) }
        }
        /** The replay never ran this scenario (no trace, no steps) — there is nothing of it but the summary line. */
        val replayed get() = result.steps.isNotEmpty()
        /** The LLM could not re-record this scenario tonight (the replay then ran the committed trace, if any). */
        val recordFailed get() = (record?.status ?: recording?.status) in setOf("FAILED", "CRASH")
        /** The recording's steps when it failed and left them — the timeline the page shows instead of the replay's. */
        val recordTimeline: ResultJson.Result? get() = recording?.takeIf { recordFailed && it.steps.isNotEmpty() }
        /** `step 2 `Tap …` — agent error: …` / `crash — OutOfMemoryError` */
        val recordFailure: String? get() = when {
            !recordFailed -> null
            recordTimeline?.failedStepOf != null -> recordTimeline!!.failedStepOf!!.let { f -> "step ${f.n} `${f.text}` — ${f.reason ?: "failed"}" }
            else -> record?.let { o ->
                o.failedStep?.let { f -> "step ${f.index} `${f.text}` — ${f.reason ?: "failed"}" } ?: "${o.status.lowercase()} — ${o.reason ?: ""}".trim()
            } ?: recording?.reason ?: "failed"
        }
    }

    /**
     * `billing/coupon.member` — a summary line's identity in the run directory's terms (ResultJson.Result.id):
     * feature and name from the md path, state from the `name@state` variant name. Not the scenario's `name`
     * (frontmatter, e.g. `auth-email-login`), which result.json keeps apart from its file name.
     */
    fun idOf(o: ScenarioOutcome): String {
        val feature = areaOf(o.path)
        val (name, state) = runOf(o)
        return (if (feature.isEmpty()) "" else "$feature/") + name + (state?.let { ".$it" } ?: "")
    }

    /**
     * The file's name and the run of it a summary line is: its path says both — `play.md@member`, the run after the
     * file ([scenarioPath]) — and a line written without the run in its path says it in its `name@state`.
     */
    private fun runOf(o: ScenarioOutcome): Pair<String, String?> {
        val file = o.path.substringAfterLast('/')
        val at = file.indexOf(".md@")
        return if (at >= 0) file.substring(0, at) to file.substring(at + ".md@".length)
        else file.removeSuffix(".md") to o.name.substringAfter('@', "").ifEmpty { null }
    }

    /** A result for a summary line the replay left no directory for: the summary's status and reason, no steps. */
    private fun placeholder(dir: File, summary: RunSummary, o: ScenarioOutcome): ResultJson.Result {
        val id = idOf(o)
        return ResultJson.Result(
            dir = File(dir, id), scenario = o.name, title = o.name,
            feature = areaOf(o.path), name = runOf(o).first, state = runOf(o).second,
            file = o.path, owner = null, description = o.description, traceRecordedAt = null,
            status = o.status, failedStep = null, reason = o.reason, mode = o.mode ?: summary.mode,
            startedAt = summary.startedAt, startedAtMs = 0, durationMs = o.durationMs, layoutHash = "",
            appVersion = summary.appVersionName, device = summary.device, video = null, llm = null, steps = emptyList(),
        )
    }

    /** Every result.json under [dir] (`record/` aside): `<screen>/<scenario>/result.json`, the screen a path of nested directories. */
    private fun resultsUnder(dir: File, skip: File? = null): List<ResultJson.Result> =
        dir.walkTopDown().onEnter { it != skip && !File(it.parentFile, ResultJson.FILE).isFile }
            .filter { it.isFile && it.name == ResultJson.FILE }
            .mapNotNull(ResultJson::read)
            .toList()

    /** The replay's results plus a placeholder for each run.json scenario without one, and the recordings under `record/`. */
    fun load(dir: File): Run {
        val summary = File(dir, "run.json").takeIf { it.isFile }?.let { runCatching { RunSummaryJson.read(it) }.getOrNull() }
        val record = File(dir, "record.json").takeIf { it.isFile }?.let { runCatching { RunSummaryJson.read(it) }.getOrNull() }
        val recordDir = File(dir, RECORD_DIR)
        val onDisk = resultsUnder(dir, skip = recordDir)
        val ids = onDisk.map { it.id }.toSet()
        val missing = summary?.scenarios.orEmpty().filter { idOf(it) !in ids }.map { placeholder(dir, summary!!, it) }
        // a run the replay left no directory for knows only its name: it is one of a file's runs in each mode when another of them says so
        val perMode = onDisk.filter { it.perMode }.associateBy { it.feature to it.name }
        val results = (onDisk + missing.map { m -> perMode[m.feature to m.name]?.let { m.copy(title = it.title, description = it.description, screenMode = m.state, perMode = true, screens = it.screens) } ?: m })
            .sortedWith(compareBy({ it.feature }, { it.name }, { it.state ?: "" }))
        val recordings = if (recordDir.isDirectory) resultsUnder(recordDir).associateBy { it.id } else emptyMap()
        return Run(dir, summary, results, record, recordings)
    }

    /** FAILED when the recording or the replay failed; PASSED when the replay passed; anything else never ran (setup, no trace). */
    fun classify(run: Run): List<Row> = run.results.map { r ->
        val rec = run.record?.scenarios?.firstOrNull { idOf(it) == r.id }
        val row = Row(r, Kind.PASSED, rec, run.recordings[r.id])
        Row(r, when {
            r.status == "FAILED" || r.status == "CRASH" || row.recordFailed -> Kind.FAILED
            r.status == "PASSED" -> Kind.PASSED
            else -> Kind.SETUP
        }, rec, row.recording)
    }

    /** The lines of `logcat.txt` (threadtime+epoch) within [fromMs, toMs] of the device clock, capped. */
    fun logcatWindow(logcat: File, fromMs: Long, toMs: Long, cap: Int = 60): List<String> {
        if (!logcat.isFile) return emptyList()
        val stamp = Regex("^\\s*(\\d+)\\.(\\d{1,3})\\s")
        val lines = ArrayList<String>()
        logcat.useLines { seq ->
            for (line in seq) {
                val m = stamp.find(line) ?: continue
                val t = m.groupValues[1].toLong() * 1000 + m.groupValues[2].padEnd(3, '0').toLong()
                if (t < fromMs) continue
                if (t > toMs) break
                lines += line.trim()
            }
        }
        return if (lines.size <= cap) lines else lines.takeLast(cap)
    }
}
