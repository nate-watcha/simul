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

    /** The file name of a screen's way in (`_entry.md`), as a result names it. */
    const val ENTRY_NAME = "_entry"

    /** How the reason of a scenario begins that was not run because its screen's way in had failed (see BatchSession.run). */
    const val WAY_IN_FAILED = "not run — the way in to"

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
    ) {
        /** The check started somewhere of its own: it went to look at a place rather than walk there. */
        val opened: Boolean get() = deeplink != null || entry != null
    }

    /**
     * A check that says something about another screen: its sentence — or its scenario's prose, for all the
     * scenario's checks ([check] null) — mentions [to] (`[[화면]]`). It is written, run and counted on the screen
     * [from]; the report shows it on [to]'s page too. [look] tells the two kinds apart by how the check got there:
     * opened at a place of its own, it looked at what a change elsewhere left on [to]; on foot, it is a way to [to].
     */
    class Link(val from: Screen, val row: Row, val check: Check?, val to: String, val text: String, val status: String, val look: Boolean)

    /** Every mention among [screens]' scenarios, in the screens' order. A screen's way in mentions nothing: it is that screen's. */
    fun links(screens: List<Screen>): List<Link> = screens.flatMap { screen ->
        screen.scenarios.flatMap { row ->
            val t = row.timeline
            val whole = when (row.kind) { Kind.FAILED -> "FAILED"; Kind.PASSED -> "PASSED"; Kind.SETUP -> "NONE" }
            Mentions.of(t.description).map { Link(screen, row, null, it, t.description, whole, look = false) } +
                row.checks.filter { it.title != null }.flatMap { c -> Mentions.of(c.description).map { Link(screen, row, c, it, c.description, c.status, c.opened) } }
        }
    }

    /** One way into a screen — a deeplink or `앱 실행` — with the account states its scenarios start from and how many each. */
    class Entry(val entry: String, val states: Map<String, Int>)

    /**
     * The scenarios of one group directory: a screen of the app (or an issue), which is what the report is organised
     * by — the first page lists screens, and each has a page that reads as its spec.
     */
    class Screen(val feature: String, val rows: List<Row>) {
        val name: String get() = feature.ifEmpty { "(root)" }
        /** The screen's way in (`_entry.md`), when its directory has one and it ran. */
        val entry: Row? get() = rows.firstOrNull { it.isEntry }
        /** The scenarios that test the screen: all but its way in. */
        val scenarios: List<Row> get() = rows.filterNot { it.isEntry }
        val checks: List<Check> get() = rows.flatMap { it.checks }
        /** The worst of its scenarios. */
        val kind: Kind get() = rows.minOf { it.kind }
        val durationMs: Long get() = rows.sumOf { it.timeline.durationMs }
        /** The ways in its scenarios declare (`setup:`), most used first; empty for results that recorded none. */
        val entries: List<Entry> get() = rows.mapNotNull { r -> r.result.setup?.let { s -> s.entry?.let { it to (s.appState ?: r.result.state ?: "") } } }
            .groupBy({ it.first }, { it.second })
            .map { (entry, states) -> Entry(entry, states.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()) }
            .sortedByDescending { it.states.values.sum() }
    }

    /** The screens of a run, those with a failure first, then by name. */
    fun screens(rows: List<Row>): List<Screen> = rows.groupBy { it.result.feature }.map { Screen(it.key, it.value) }
        .sortedWith(compareBy({ it.kind.ordinal }, { it.feature }))

    class Row(val result: ResultJson.Result, val kind: Kind, val record: ScenarioOutcome? = null, val recording: ResultJson.Result? = null) {
        val id get() = result.id
        /** This row is its screen's way in (`_entry.md`), not a scenario of it. */
        val isEntry get() = result.name == ENTRY_NAME
        /** What the pages call it: its title — `진입` for a way in that was given none. */
        val title: String get() = if (isEntry && (result.title == ENTRY_NAME || result.title == SimulProject.entryName(result.feature))) "진입" else result.title
        /** It was not run because its screen's way in had failed: the entry's failure is the one to read. */
        val waitsForEntry get() = kind == Kind.SETUP && result.reason?.startsWith(WAY_IN_FAILED) == true
        /** The run the pages tell this scenario by: tonight's recording when it failed and left its steps, else the replay. */
        val timeline: ResultJson.Result get() = recordTimeline ?: result
        /** What this scenario confirms, in file order (see [Check]). */
        val checks: List<Check> get() {
            val t = timeline
            if (t.tests.isNotEmpty()) return t.tests.map { sec ->
                Check(sec.title, sec.description, if (kind == Kind.SETUP) "NONE" else sec.status, t.steps.filter { it.n in sec.from..sec.to }, sec.deeplink, sec.entry)
            }
            return listOf(Check(null, t.description, when (kind) { Kind.FAILED -> "FAILED"; Kind.PASSED -> "PASSED"; Kind.SETUP -> "NONE" }, t.steps))
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
        val name = o.path.substringAfterLast('/').removeSuffix(".md")
        val state = o.name.substringAfter('@', "").ifEmpty { null }
        return (if (feature.isEmpty()) "" else "$feature/") + name + (state?.let { ".$it" } ?: "")
    }

    /** A result for a summary line the replay left no directory for: the summary's status and reason, no steps. */
    private fun placeholder(dir: File, summary: RunSummary, o: ScenarioOutcome): ResultJson.Result {
        val id = idOf(o)
        return ResultJson.Result(
            dir = File(dir, id), scenario = o.name, title = o.name,
            feature = areaOf(o.path), name = o.path.substringAfterLast('/').removeSuffix(".md"), state = o.name.substringAfter('@', "").ifEmpty { null },
            file = o.path, owner = null, description = o.description, traceRecordedAt = null,
            status = o.status, failedStep = null, reason = o.reason, mode = o.mode ?: summary.mode,
            startedAt = summary.startedAt, startedAtMs = 0, durationMs = o.durationMs, layoutHash = "",
            appVersion = summary.appVersionName, device = summary.device, video = null, llm = null, steps = emptyList(),
        )
    }

    /** Every result.json under [dir] (at most three levels deep, `record/` aside). */
    private fun resultsUnder(dir: File, skip: File? = null): List<ResultJson.Result> =
        dir.walkTopDown().onEnter { it != skip }.maxDepth(3)
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
        val results = (onDisk + missing).sortedWith(compareBy({ it.feature }, { it.name }, { it.state ?: "" }))
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
