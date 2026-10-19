package dev.nate.uiagent.cli

import dev.nate.uiagent.json
import dev.nate.uiagent.obj
import dev.nate.uiagent.str
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * The nightly report as a static site (docs/report/SPEC.md): what the report builder reads from a
 * runs directory and how it classifies each scenario. Rendering lives in SitePages.kt.
 *
 * ```
 * runs/<app>/<YYYY-MM-DD>/                 one nightly run (simul run --runs …)
 *   run.json                               the batch (RunSummary)
 *   <feature>/<name>.<state>/result.json   one scenario (ResultJson)
 * ```
 *
 * Yesterday is the nearest earlier date directory beside today's; the 7-day strips come from
 * the same siblings. Nothing is judged again here — PASS/FAIL is result.json's — only
 * "screen changed" (layout structure vs. yesterday) and "needs re-recording" (acknowledged as
 * intended, trace not re-recorded since) are derived.
 */
object Site {

    /** Where a scenario stands today, in the order the report shows them. */
    enum class Kind(val label: String, val cls: String) {
        FAILED("실패", "failed"),
        CHANGED("화면 바뀜", "changed"),
        RERECORD("재녹화 필요", "rerecord"),
        SETUP("안 돌림", "setup"),
        PASSED("통과", "passed"),
    }

    /** One nightly run directory, loaded. */
    class Run(val dir: File, val summary: RunSummary?, val results: List<ResultJson.Result>) {
        /** `2026-10-19` — the directory name. */
        val date: String get() = dir.name
        val byId: Map<String, ResultJson.Result> = results.associateBy { it.id }
        val app: String? get() = summary?.app
    }

    /** `report-state/<app>/acknowledged.json` entry: this scenario's screen change is intended. */
    data class Ack(val at: String, val by: String?, val layoutHash: String?)

    /** A scenario with today's classification and what it was derived from. */
    class Row(
        val result: ResultJson.Result,
        val kind: Kind,
        /** The first step whose screen structure differs from yesterday's, when [kind] is CHANGED/RERECORD. */
        val change: LayoutJson.ScreenChange?,
        val yesterday: ResultJson.Result?,
        val ack: Ack?,
    ) {
        val id get() = result.id
    }

    private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

    fun isDateDir(f: File) = f.isDirectory && DATE.matches(f.name)

    /** Every result.json under [dir] (at most three levels deep), plus run.json when present. */
    fun load(dir: File): Run {
        val summary = File(dir, "run.json").takeIf { it.isFile }?.let { runCatching { RunSummaryJson.read(it) }.getOrNull() }
        val results = dir.walkTopDown().maxDepth(3)
            .filter { it.isFile && it.name == ResultJson.FILE }
            .mapNotNull(ResultJson::read)
            .sortedWith(compareBy({ it.feature }, { it.name }, { it.state ?: "" }))
            .toList()
        return Run(dir, summary, results)
    }

    /** Date directories beside [today], newest first, [today] excluded, only earlier dates. */
    fun earlierRuns(today: File): List<File> =
        today.absoluteFile.parentFile?.listFiles { f -> isDateDir(f) && f.name < today.name }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun readAcks(file: File?): Map<String, Ack> {
        if (file == null || !file.isFile) return emptyMap()
        return runCatching {
            json.parseToJsonElement(file.readText()).jsonObject.mapNotNull { (k, v) ->
                val o = v as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                k to Ack(o.str("at") ?: return@mapNotNull null, o.str("by"), o.str("layout_hash"))
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** The first step whose layout structure differs between [a] (earlier) and [b] (today); null when none does. */
    fun screenChange(a: ResultJson.Result, b: ResultJson.Result): LayoutJson.ScreenChange? {
        for (step in b.steps) {
            val prev = a.steps.firstOrNull { it.n == step.n } ?: continue
            val la = prev.layout?.let { LayoutJson.read(File(a.dir, it)) } ?: continue
            val lb = step.layout?.let { LayoutJson.read(File(b.dir, it)) } ?: continue
            LayoutJson.compare(step.n, la, lb)?.let { return it }
        }
        return null
    }

    /**
     * Classify every scenario of [today]: FAILED and SKIPPED_SETUP come straight from result.json;
     * a PASSED scenario that was acknowledged as an intended change and whose trace has not been
     * re-recorded since the acknowledgement needs re-recording; a PASSED one whose screen structure
     * differs from yesterday's has changed; the rest passed. Without a yesterday nothing is "changed".
     */
    fun classify(today: Run, yesterday: Run?, acks: Map<String, Ack> = emptyMap()): List<Row> = today.results.map { r ->
        val prev = yesterday?.byId?.get(r.id)
        val ack = acks[r.id]
        val change = if (r.passed && prev != null && prev.passed) screenChange(prev, r) else null
        val kind = when {
            r.status == "FAILED" -> Kind.FAILED
            r.status == "SKIPPED_SETUP" -> Kind.SETUP
            ack != null && (r.traceRecordedAt == null || r.traceRecordedAt <= ack.at) -> Kind.RERECORD
            change != null -> Kind.CHANGED
            else -> Kind.PASSED
        }
        Row(r, kind, change, prev, ack)
    }

    /** One day of a scenario's or a feature's history strip. */
    enum class Day(val cls: String, val label: String) { PASSED("passed", "통과"), CHANGED("changed", "화면 바뀜"), FAILED("failed", "실패"), NONE("none", "안 돌림") }

    /**
     * The last [days] dates ending at [today] (missing dates included as NONE) with, per scenario id,
     * what happened that day. "Changed" on a past day is judged against the day before it.
     */
    class History(val dates: List<String>, val days: Map<String, Map<String, Day>>) {
        fun of(id: String): List<Day> = dates.map { days[it]?.get(id) ?: Day.NONE }

        /** The worst day over several scenarios (a feature card). */
        fun worst(ids: Collection<String>): List<Day> = dates.map { d ->
            ids.mapNotNull { days[d]?.get(it) }.maxByOrNull { it.ordinal } ?: Day.NONE
        }
    }

    fun history(today: Run, todayRows: List<Row>, earlier: List<File>, days: Int = 7): History {
        val runs = LinkedHashMap<String, Run>()
        runs[today.date] = today
        earlier.take(days + 1).forEach { runs[it.name] = load(it) }
        val dates = generateSequence(java.time.LocalDate.parse(today.date)) { it.minusDays(1) }.take(days).map { it.toString() }.toList().reversed()
        val byDate = HashMap<String, Map<String, Day>>()
        for (d in dates) {
            val run = runs[d] ?: continue
            val prevDate = runs.keys.filter { it < d }.maxOrNull()
            val rows = if (d == today.date) todayRows else classify(run, prevDate?.let(runs::get))
            byDate[d] = rows.associate { it.id to when (it.kind) {
                Kind.FAILED -> Day.FAILED
                Kind.CHANGED, Kind.RERECORD -> Day.CHANGED
                Kind.SETUP -> Day.NONE
                Kind.PASSED -> Day.PASSED
            } }
        }
        return History(dates, byDate)
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
