package dev.nate.uiagent.cli

import java.io.File

/**
 * The HTML report of one run as pages (README "나이틀리 운영"): what the builder reads from a runs
 * directory and how it groups each scenario. Rendering lives in SitePages.kt.
 *
 * ```
 * .simul/reports/<stamp>/                  one `simul report`
 *   record.json                            the LLM re-recording that preceded the replay (RunSummary), when there was one
 *   run.json                               the replay batch (RunSummary)
 *   <feature>/<name>.<state>/result.json   one replayed scenario (ResultJson)
 * ```
 *
 * Nothing is judged here — a scenario failed when its recording failed (the agent could not perform it
 * on tonight's app) or its replay did (result.json says so).
 */
object Site {

    /** Where a scenario stands, in the order the report shows them. */
    enum class Kind(val label: String, val cls: String) {
        FAILED("실패", "failed"),
        SETUP("안 돌림", "setup"),
        PASSED("통과", "passed"),
    }

    /** One run directory, loaded. */
    class Run(val dir: File, val summary: RunSummary?, val results: List<ResultJson.Result>, val record: RunSummary? = null) {
        /** `2026-10-19` — the directory name. */
        val date: String get() = dir.name
        val app: String? get() = summary?.app ?: record?.app
    }

    class Row(val result: ResultJson.Result, val kind: Kind, val record: ScenarioOutcome? = null) {
        val id get() = result.id
        /** The LLM could not re-record this scenario tonight (the replay then ran the committed trace). */
        val recordFailed get() = record != null && record.status in setOf("FAILED", "CRASH")
        /** `step 2 `Tap …` — agent error: …` / `crash — OutOfMemoryError` */
        val recordFailure: String? get() = record?.takeIf { recordFailed }?.let { o ->
            o.failedStep?.let { f -> "step ${f.index} `${f.text}` — ${f.reason ?: "failed"}" } ?: "${o.status.lowercase()} — ${o.reason ?: ""}".trim()
        }
    }

    /** Every result.json under [dir] (at most three levels deep), plus run.json when present. */
    fun load(dir: File): Run {
        val summary = File(dir, "run.json").takeIf { it.isFile }?.let { runCatching { RunSummaryJson.read(it) }.getOrNull() }
        val record = File(dir, "record.json").takeIf { it.isFile }?.let { runCatching { RunSummaryJson.read(it) }.getOrNull() }
        val results = dir.walkTopDown().maxDepth(3)
            .filter { it.isFile && it.name == ResultJson.FILE }
            .mapNotNull(ResultJson::read)
            .sortedWith(compareBy({ it.feature }, { it.name }, { it.state ?: "" }))
            .toList()
        return Run(dir, summary, results, record)
    }

    /** FAILED when the recording or the replay failed, SKIPPED_SETUP straight from result.json; the rest passed. */
    fun classify(run: Run): List<Row> = run.results.map { r ->
        val key = r.name + (r.state?.let { "@$it" } ?: "")
        val rec = run.record?.scenarios?.firstOrNull { it.name == key }
        val recFailed = rec != null && rec.status in setOf("FAILED", "CRASH")
        Row(r, when {
            r.status == "FAILED" || recFailed -> Kind.FAILED
            r.status == "SKIPPED_SETUP" -> Kind.SETUP
            else -> Kind.PASSED
        }, rec)
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
