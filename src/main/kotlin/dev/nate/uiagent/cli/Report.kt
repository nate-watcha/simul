package dev.nate.uiagent.cli

import dev.nate.uiagent.humanDuration
import dev.nate.uiagent.agent.LlmStats
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * `simul report` has two inputs:
 *
 * `simul report --runs <runs/app/date> --out <site/app/date> [--format html|slack] [--acknowledged F]
 *               [--repo-url U [--repo-branch B]] [--site-url U]`
 *   The nightly site (README "나이틀리 운영") from one `simul run --runs` directory: the home page,
 *   one page per scenario and state, yesterday's run and the 7-day strips from the sibling date
 *   directories (see [Site], [SitePages]). `--format slack` prints the Block Kit summary instead.
 *   `--check` exits 1 when any scenario failed. Without `--out` it is only the gate (no site is written).
 *
 * `simul report <summary.json>... [--format md|slack|junit] [--title T] [--link Name=URL]... [--out FILE] [--check]`
 *   Joins the batch summaries of a pipeline into one report. The phases are recognised by
 *   mode and order, which is what the record/verify pipeline produces:
 *
 *     baseline  replay of the committed traces          — did the app change vs. the baseline?
 *     record    `--mode llm` re-recording                — can the agent still perform it?
 *     verify    replay of what `record` just wrote        — is the fresh trace deterministic?
 *
 *   Per scenario the three answers collapse into one verdict (see [VerdictKind]) so a reader
 *   sees "regression" / "screen changed, trace re-recorded" / "flaky trace" instead of three
 *   columns of PASSED/FAILED. A single-phase report (plain CI replay) degrades to pass/fail.
 *   Exit code: 0, or 1 with `--check` when any verdict is a failure — the CI gate.
 */
internal fun reportCommand(args: List<String>, cwd: File): Int {
    var format: String? = null
    var title = "simul report"
    var out: String? = null
    var runs: String? = null
    var acks: String? = null
    var repoUrl: String? = null
    var repoBranch = "main"
    var siteUrl: String? = null
    var check = false
    val links = mutableListOf<Pair<String, String>>()
    val files = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--format" -> format = args.getOrNull(++i)?.takeIf { it in setOf("md", "slack", "junit", "html") }
                ?: return err("invalid --format (use md|slack|junit|html)")
            "--title" -> title = args.getOrNull(++i) ?: return err("--title needs a value")
            "--out" -> out = args.getOrNull(++i) ?: return err("--out needs a path")
            "--runs" -> runs = args.getOrNull(++i) ?: return err("--runs needs a directory")
            "--acknowledged" -> acks = args.getOrNull(++i) ?: return err("--acknowledged needs a file")
            "--repo-url" -> repoUrl = args.getOrNull(++i)?.trimEnd('/') ?: return err("--repo-url needs a URL")
            "--repo-branch" -> repoBranch = args.getOrNull(++i) ?: return err("--repo-branch needs a branch name")
            "--site-url" -> siteUrl = args.getOrNull(++i)?.trimEnd('/') ?: return err("--site-url needs a URL")
            "--check" -> check = true
            "--link" -> {
                val v = args.getOrNull(++i) ?: return err("--link needs Name=URL")
                val eq = v.indexOf('=')
                if (eq <= 0) return err("--link needs Name=URL, got '$v'")
                links += v.substring(0, eq) to v.substring(eq + 1)
            }
            else -> if (a.startsWith("--")) return err("unknown option $a") else files += a
        }
        i++
    }
    if (runs != null) return runsReport(cwd, resolvePath(cwd, runs).absoluteFile, format ?: "html", out, acks, repoUrl, repoBranch, siteUrl, title.takeIf { it != "simul report" }, check)
    if (format == "html") return err("--format html builds the site from a runs directory: simul report --runs runs/<app>/<date> --out site/<app>/<date>")
    if (files.isEmpty()) return err("nothing to report: give summary.json files (from `simul run --summary`) or --runs <dir>")

    val summaries = files.map { p ->
        val f = resolvePath(cwd, p)
        if (!f.isFile) return err("summary not found: $p")
        runCatching { RunSummaryJson.read(f) }.getOrElse { return err("cannot parse $p: ${it.message}") }
    }
    val report = PipelineReport.build(title, summaries, links)
    val outFile = out?.let { resolvePath(cwd, it).absoluteFile }
    val text = when (format ?: "md") {
        "md" -> renderMarkdown(report)
        "slack" -> renderSlack(report)
        else -> renderJunit(report)
    }
    if (outFile != null) {
        outFile.parentFile?.mkdirs()
        outFile.writeText(text)
        System.err.println("report: ${outFile.relativeToOrSelf(cwd.absoluteFile).path} — ${report.headline()}")
    } else {
        print(text)
    }
    return if (check && report.overall == Severity.FAIL) 1 else 0
}

/** `--runs`: the static site (html) or the Slack summary of one nightly run directory. */
private fun runsReport(cwd: File, runsDir: File, format: String, out: String?, acks: String?, repoUrl: String?, repoBranch: String, siteUrl: String?, appName: String?, check: Boolean): Int {
    if (!runsDir.isDirectory) return err("runs directory not found: $runsDir")
    val ackFile = acks?.let { resolvePath(cwd, it) }
        // the convention of the spec: report-state/<app>/acknowledged.json in the app repo
        ?: Site.load(runsDir).app?.let { File(cwd, "report-state/$it/acknowledged.json") }?.takeIf { it.isFile }
    val opts = SitePages.Options(repoUrl = repoUrl, repoBranch = repoBranch, siteUrl = siteUrl, appName = appName)
    return when (format) {
        "html" -> {
            if (out == null && check) {   // the gate alone: `simul report --runs <dir> --check`
                val run = Site.load(runsDir)
                val rows = Site.classify(run, Site.earlierRuns(runsDir).firstOrNull()?.let(Site::load), Site.readAcks(ackFile))
                return if (rows.any { it.kind == Site.Kind.FAILED }) 1 else 0
            }
            val outDir = out?.let { resolvePath(cwd, it).absoluteFile } ?: return err("--out <site dir> is required with --runs")
            val built = SitePages.build(runsDir, outDir, ackFile, opts)
            val counts = built.rows.groupingBy { it.kind }.eachCount()
            System.err.println("site: ${built.index.relativeToOrSelf(cwd.absoluteFile).path} (${built.pages.size} pages) — " +
                Site.Kind.entries.mapNotNull { k -> counts[k]?.let { "${k.label} $it" } }.joinToString(" · "))
            if (check && (counts[Site.Kind.FAILED] ?: 0) > 0) 1 else 0
        }
        "slack" -> {
            val run = Site.load(runsDir)
            val yesterday = Site.earlierRuns(runsDir).firstOrNull()?.let(Site::load)
            val rows = Site.classify(run, yesterday, Site.readAcks(ackFile))
            val text = SitePages.slack(run, rows, siteUrl?.let { "$it/index.html" }, yesterday?.let { y -> siteUrl?.let { "$it/../${y.date}/index.html" } }, appName)
            val outFile = out?.let { resolvePath(cwd, it).absoluteFile }
            if (outFile != null) { outFile.parentFile?.mkdirs(); outFile.writeText(text) } else print(text)
            if (check && rows.any { it.kind == Site.Kind.FAILED }) 1 else 0
        }
        else -> err("--runs supports --format html|slack")
    }
}

// ------------------------------------------------------------------------ model

enum class Severity { OK, WARN, FAIL }

/** What the phases together say about one scenario. Order = display order in "needs attention". */
enum class VerdictKind(val emoji: String, val severity: Severity, val title: String, val advice: String) {
    CRASH("💥", Severity.FAIL, "harness crash", "the runner died — see the crash log; not an app verdict"),
    REGRESSION("❌", Severity.FAIL, "regression suspected",
        "neither the committed trace nor a fresh recording completes the scenario — inspect the screenshots before touching the scenario"),
    TRACE_FLAKE("⚠️", Severity.FAIL, "fresh trace does not replay",
        "recorded fine but the replay of that recording failed — evidence is non-deterministic (dynamic label?) or the app is unstable"),
    FAIL("❌", Severity.FAIL, "failed", "replay broke — screen diverged from the committed trace: app regression or a legitimate change needing re-record"),
    RECORD_FLAKE("⚠️", Severity.WARN, "recording failed, baseline still replays",
        "the app behaves as committed but the agent could not redo the scenario — check the record log (wording, AMBIGUOUS, timeouts)"),
    CHANGED("🔁", Severity.WARN, "screen changed — trace re-recorded",
        "the committed trace broke but a fresh recording replays — review the trace diff and merge it if the change is legitimate"),
    NEW("🆕", Severity.WARN, "first trace recorded", "no committed trace yet — review and commit the recording"),
    SKIPPED("⏭", Severity.OK, "skipped", "nothing ran"),
    STABLE("✅", Severity.OK, "stable", ""),
    PASS("✅", Severity.OK, "passed", ""),
}

enum class Role { BASELINE, RECORD, VERIFY, OTHER }

data class Phase(val label: String, val mode: String, val role: Role, val summary: RunSummary) {
    /** Column heading: `record (llm)`. */
    val heading get() = "$label (${if (mode == "llm") "llm" else "replay"})"
}

data class ScenarioRow(
    val path: String,
    val name: String,
    /** Outcome per phase, aligned with [PipelineReport.phases]; null when absent from that phase. */
    val outcomes: List<ScenarioOutcome?>,
    val verdict: VerdictKind,
)

class PipelineReport(
    val title: String,
    val phases: List<Phase>,
    val rows: List<ScenarioRow>,
    val links: List<Pair<String, String>>,
) {
    val overall: Severity = rows.maxOfOrNull { it.verdict.severity } ?: Severity.OK
    val counts: Map<VerdictKind, Int> = rows.groupingBy { it.verdict }.eachCount()
    val attention: List<ScenarioRow> = rows.filter { it.verdict.severity != Severity.OK }
        .sortedBy { it.verdict.ordinal }

    val appVersionName get() = phases.firstNotNullOfOrNull { it.summary.appVersionName }
    val device get() = phases.firstNotNullOfOrNull { it.summary.device }
    val agentVersion get() = phases.firstNotNullOfOrNull { it.summary.agentVersion.ifEmpty { null } }
    val androidCli get() = phases.firstNotNullOfOrNull { it.summary.androidCli }
    val startedAt get() = phases.firstNotNullOfOrNull { it.summary.startedAt.ifEmpty { null } }

    /** `app 5.4.0 · device sdk_gphone64 · simul 0.2.0 · android CLI 1.0.1 · started …` — the environment line of every renderer. */
    fun envParts(withStart: Boolean = true): List<String> = listOfNotNull(
        appVersionName?.let { "app $it" }, device?.let { "device $it" }, agentVersion?.let { "simul $it" },
        androidCli?.let { "android CLI $it" }, startedAt?.takeIf { withStart }?.let { "started $it" },
    )

    /**
     * One line per phase that consulted the model: `record (llm): model 5m12s of 9m40s wall ·
     * 212 calls · prompt 1.2M tok (peak 9.1k) · completion 34k tok` — what the recording cost.
     */
    fun llmUsageLines(): List<String> = phases.mapNotNull { p ->
        val l = LlmStats.sum(p.summary.scenarios.map { it.llm }) ?: return@mapNotNull null
        val wall = p.summary.scenarios.sumOf { it.durationMs }
        "${p.heading}: model ${humanDuration(l.durationMs)} of ${humanDuration(wall)} wall · ${l.calls} calls" +
            (if (l.tokensReported) " · prompt ${LlmStats.tokens(l.promptTokens)} tok (peak ${LlmStats.tokens(l.peakPromptTokens)}) · completion ${LlmStats.tokens(l.completionTokens)} tok" else "")
    }

    /** `12 scenarios · ✅ 9 stable · 🔁 1 changed · ❌ 2 regression suspected` */
    fun countsLine(): String = buildString {
        append("${rows.size} scenario${if (rows.size == 1) "" else "s"}")
        VerdictKind.entries.sortedBy { it.severity.ordinal * -1 }.forEach { k ->
            counts[k]?.let { append(" · ${k.emoji} $it ${k.title}") }
        }
    }

    fun headline(): String = when (overall) {
        Severity.FAIL -> "${attention.count { it.verdict.severity == Severity.FAIL }} failing"
        Severity.WARN -> "${attention.size} need attention"
        Severity.OK -> "all ${rows.size} stable"
    }

    val overallEmoji get() = when (overall) { Severity.FAIL -> "❌"; Severity.WARN -> "⚠️"; Severity.OK -> "✅" }

    companion object {
        fun build(title: String, summaries: List<RunSummary>, links: List<Pair<String, String>> = emptyList()): PipelineReport {
            val phases = assignRoles(summaries)
            // scenario identity = path; keep first-seen order across phases
            val order = LinkedHashMap<String, String>() // path -> name
            phases.forEach { p -> p.summary.scenarios.forEach { order.putIfAbsent(it.path, it.name) } }
            val rows = order.map { (path, name) ->
                val outcomes = phases.map { p -> p.summary.scenarios.firstOrNull { it.path == path } }
                ScenarioRow(path, name, outcomes, judge(phases, outcomes))
            }
            return PipelineReport(title, phases, rows, links)
        }

        /** baseline = last replay before the (first) llm phase; verify = first replay after it. */
        fun assignRoles(summaries: List<RunSummary>): List<Phase> {
            val recordIdx = summaries.indexOfFirst { it.mode == "llm" }
            val baselineIdx = if (recordIdx < 0) -1 else (0 until recordIdx).lastOrNull { summaries[it].mode != "llm" } ?: -1
            val verifyIdx = if (recordIdx < 0) -1
                else (recordIdx + 1 until summaries.size).firstOrNull { summaries[it].mode != "llm" } ?: -1
            val seen = mutableMapOf<String, Int>()
            return summaries.mapIndexed { i, s ->
                val role = when (i) {
                    recordIdx -> Role.RECORD
                    baselineIdx -> Role.BASELINE
                    verifyIdx -> Role.VERIFY
                    else -> Role.OTHER
                }
                // duplicate labels would collapse columns — disambiguate with a counter
                val n = seen.merge(s.label, 1, Int::plus)!!
                Phase(if (n == 1) s.label else "${s.label}#$n", s.mode, role, s)
            }
        }

        fun judge(phases: List<Phase>, outcomes: List<ScenarioOutcome?>): VerdictKind {
            fun of(role: Role) = phases.indices.firstOrNull { phases[it].role == role }?.let { outcomes[it] }
            val present = outcomes.filterNotNull()
            if (present.any { it.status == "CRASH" }) return VerdictKind.CRASH
            if (present.all { it.status == "SKIPPED" }) return VerdictKind.SKIPPED

            val record = of(Role.RECORD)
            if (record == null || phases.none { it.role == Role.RECORD }) {
                // no recording phase: plain replay semantics over whatever ran
                return if (present.any { it.status == "FAILED" }) VerdictKind.FAIL else VerdictKind.PASS
            }
            val baseline = of(Role.BASELINE)
            val verify = of(Role.VERIFY)
            val baselinePhase = phases.any { it.role == Role.BASELINE }
            return when {
                record.status == "FAILED" ->
                    if (baseline?.status == "PASSED") VerdictKind.RECORD_FLAKE else VerdictKind.REGRESSION
                record.status == "SKIPPED" ->
                    if (present.any { it.status == "FAILED" }) VerdictKind.FAIL else VerdictKind.PASS
                verify?.status == "FAILED" -> VerdictKind.TRACE_FLAKE
                !baselinePhase -> VerdictKind.STABLE
                baseline == null || baseline.status == "SKIPPED" -> VerdictKind.NEW
                baseline.status == "FAILED" -> VerdictKind.CHANGED
                else -> VerdictKind.STABLE
            }
        }
    }
}

// --------------------------------------------------------------------- shared bits

/** One table cell for a phase outcome: `✅ 12s`, `❌ step 3`, `❌ setup`, `⏭ no trace`, `💥 crash`, `–`. */
fun cell(o: ScenarioOutcome?): String = when {
    o == null -> "–"
    o.status == "PASSED" -> "✅ ${humanDuration(o.durationMs)}"
    o.status == "CRASH" -> "💥 crash"
    o.status == "SKIPPED" -> "⏭ ${o.reason ?: "skipped"}"
    else -> "❌ " + (o.failedStep?.let { f -> if (f.isSetup) "setup" else "step ${f.index}" } ?: "failed")
}

/** The failure narrative of one outcome, or null when it passed / did not run. */
private fun failureLine(o: ScenarioOutcome): String? = when (o.status) {
    "FAILED" -> o.failedStep?.let { f ->
        val where = if (f.isSetup) "setup" else "step ${f.index} `${f.text}`"
        "$where — ${f.reason ?: "failed"}"
    } ?: "failed"
    "CRASH" -> "crash — ${o.reason ?: ""}".trim()
    else -> null
}

// --------------------------------------------------------------------------- md

/** GitHub-flavoured markdown: job summary, PR body, issue comment. */
fun renderMarkdown(r: PipelineReport): String = buildString {
    appendLine("## ${r.overallEmoji} ${r.title}")
    appendLine()
    appendLine("**${r.countsLine()}**")
    val env = r.envParts()
    if (env.isNotEmpty()) appendLine("<sub>${env.joinToString(" · ")}</sub>")
    if (r.links.isNotEmpty()) {
        appendLine()
        appendLine(r.links.joinToString(" · ") { (n, u) -> "[$n]($u)" })
    }
    appendLine()
    appendLine("| Scenario | " + r.phases.joinToString(" | ") { it.heading } + " | Verdict |")
    appendLine("|---|" + r.phases.joinToString("") { "---|" } + "---|")
    for (row in r.rows) {
        appendLine("| `${row.path}` | " + row.outcomes.joinToString(" | ") { cell(it) } +
            " | ${row.verdict.emoji} ${row.verdict.title} |")
    }
    r.llmUsageLines().forEach { appendLine(); appendLine("<sub>$it</sub>") }
    if (r.attention.isNotEmpty()) {
        appendLine()
        appendLine("### Needs attention")
        for (row in r.attention) {
            appendLine()
            appendLine("#### ${row.verdict.emoji} `${row.path}` — ${row.verdict.title}")
            if (row.verdict.advice.isNotEmpty()) appendLine(row.verdict.advice)
            for ((i, o) in row.outcomes.withIndex()) {
                if (o == null) continue
                val line = failureLine(o) ?: continue
                appendLine("- **${r.phases[i].label}** $line")
                o.failedStep?.screenshot?.let { appendLine("  - screenshot: `$it`") }
                o.failedStep?.screen?.takeIf { it.isNotEmpty() }?.let { appendLine("  - on screen: " + it.joinToString(" · ") { l -> "`$l`" }) }
                o.reportDir?.let { appendLine("  - report: `$it`") }
                o.video?.let { appendLine("  - video: `$it`") }
            }
            row.outcomes.filterNotNull().firstOrNull { it.traceUpdated }?.let {
                appendLine("- trace updated by **${r.phases[row.outcomes.indexOf(it)].label}** — see the diff of `.simul/scenarios/${traceRelPath(it.path)}`")
            }
        }
    }
}

// ------------------------------------------------------------------------ slack

private fun slackEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private const val SLACK_NAMES_PER_KIND = 8

/**
 * Block Kit payload for `chat.postMessage` / an incoming webhook — deliberately short: the
 * channel gets the headline, the counts, and the names of what needs attention; reasons,
 * screenshots and recordings live in the HTML site, which the `--link`s point at.
 */
fun renderSlack(r: PipelineReport): String {
    val blocks = mutableListOf<JsonObject>()
    fun section(mrkdwn: String) = buildJsonObject {
        put("type", "section")
        put("text", buildJsonObject { put("type", "mrkdwn"); put("text", mrkdwn.take(2900)) })
    }
    blocks += buildJsonObject {
        put("type", "header")
        put("text", buildJsonObject { put("type", "plain_text"); put("text", "${r.overallEmoji} ${r.title} — ${r.headline()}".take(150)) })
    }
    val env = r.envParts(withStart = false)
    blocks += section("*${slackEscape(r.countsLine())}*" + (if (env.isEmpty()) "" else "\n_${slackEscape(env.joinToString(" · "))}_") +
        r.llmUsageLines().joinToString("") { "\n_${slackEscape(it)}_" })
    // what to look at, names only — one line per verdict kind, capped
    val byKind = r.attention.groupBy { it.verdict }
    if (byKind.isNotEmpty()) {
        blocks += section(byKind.entries.joinToString("\n") { (kind, rows) ->
            val names = rows.take(SLACK_NAMES_PER_KIND).joinToString(", ") { "`${slackEscape(it.path)}`" }
            val more = rows.size - SLACK_NAMES_PER_KIND
            "${kind.emoji} ${kind.title}: $names" + (if (more > 0) " +$more more" else "")
        })
    }
    if (r.links.isNotEmpty()) {
        blocks += buildJsonObject {
            put("type", "context")
            put("elements", buildJsonArray {
                r.links.forEach { (n, u) ->
                    add(buildJsonObject { put("type", "mrkdwn"); put("text", "<$u|${slackEscape(n)}>") })
                }
            })
        }
    }
    val payload = buildJsonObject {
        put("text", "${r.overallEmoji} ${r.title}: ${r.headline()} — ${r.countsLine()}")
        put("blocks", buildJsonArray { blocks.forEach { add(it) } })
    }
    return payload.toString() + "\n"
}

// ------------------------------------------------------------------------ junit

/** Escape for an HTML/XML text node or attribute — the html report and the junit renderer share it. */
internal fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** JUnit XML: one testsuite per phase, one testcase per scenario — for CI test reporters. */
fun renderJunit(r: PipelineReport): String = buildString {
    appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    appendLine("""<testsuites name="${escapeHtml(r.title)}">""")
    for ((i, p) in r.phases.withIndex()) {
        val outs = r.rows.mapNotNull { it.outcomes[i] }
        val failures = outs.count { it.status == "FAILED" || it.status == "CRASH" }
        val skipped = outs.count { it.status == "SKIPPED" }
        appendLine("""  <testsuite name="${escapeHtml(p.heading)}" tests="${outs.size}" failures="$failures" errors="0" skipped="$skipped" time="${p.summary.durationMs / 1000.0}">""")
        for (o in outs) {
            append("""    <testcase classname="${escapeHtml(p.label)}" name="${escapeHtml(o.path)}" time="${o.durationMs / 1000.0}"""")
            when (o.status) {
                "PASSED" -> appendLine("/>")
                "SKIPPED" -> appendLine("><skipped message=\"${escapeHtml(o.reason ?: "")}\"/></testcase>")
                else -> {
                    val msg = failureLine(o) ?: o.status
                    appendLine(">")
                    appendLine("      <failure message=\"${escapeHtml(msg.take(200))}\">${escapeHtml(msg)}${o.reportDir?.let { "\nreport: $it" } ?: ""}</failure>")
                    appendLine("    </testcase>")
                }
            }
        }
        appendLine("  </testsuite>")
    }
    appendLine("</testsuites>")
}
