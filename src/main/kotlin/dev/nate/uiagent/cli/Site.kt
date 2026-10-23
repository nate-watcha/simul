package dev.nate.uiagent.cli

import java.io.File

/**
 * The HTML report of one run as pages (README "나이틀리 운영"): what the builder reads from a runs
 * directory and how it groups each scenario. Rendering lives in SitePages.kt.
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

    class Row(val result: ResultJson.Result, val kind: Kind, val record: ScenarioOutcome? = null, val recording: ResultJson.Result? = null) {
        val id get() = result.id
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
