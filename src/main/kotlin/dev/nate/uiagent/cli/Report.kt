package dev.nate.uiagent.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * `simul report <summary.json>... [--format md|slack|junit] [--title T] [--link Name=URL]...
 *                [--out FILE] [--check]`
 *
 * Joins the batch summaries of a pipeline into one report. The phases are recognised by
 * mode and order, which is what the nightly pipeline produces:
 *
 *   baseline  replay of the committed traces          — did the app change vs. the baseline?
 *   record    `--mode llm` re-recording                — can the agent still perform it?
 *   verify    replay of what `record` just wrote        — is the fresh trace deterministic?
 *
 * Per scenario the three answers collapse into one verdict (see [VerdictKind]) so a reader
 * sees "regression" / "screen changed, trace re-recorded" / "flaky trace" instead of three
 * columns of PASSED/FAILED. A single-phase report (plain CI replay) degrades to pass/fail.
 *
 * Exit code: 0, or 1 with `--check` when any verdict is a failure — the CI gate.
 */
internal fun reportCommand(args: List<String>, cwd: File): Int {
    var format = "md"
    var title = "simul report"
    var out: String? = null
    var check = false
    val links = mutableListOf<Pair<String, String>>()
    val files = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--format" -> format = args.getOrNull(++i)?.takeIf { it in setOf("md", "slack", "junit") }
                ?: return reportErr("invalid --format (use md|slack|junit)")
            "--title" -> title = args.getOrNull(++i) ?: return reportErr("--title needs a value")
            "--out" -> out = args.getOrNull(++i) ?: return reportErr("--out needs a file path")
            "--check" -> check = true
            "--link" -> {
                val v = args.getOrNull(++i) ?: return reportErr("--link needs Name=URL")
                val eq = v.indexOf('=')
                if (eq <= 0) return reportErr("--link needs Name=URL, got '$v'")
                links += v.substring(0, eq) to v.substring(eq + 1)
            }
            else -> if (a.startsWith("--")) return reportErr("unknown option $a") else files += a
        }
        i++
    }
    if (files.isEmpty()) return reportErr("nothing to report: give one or more summary.json files (from `simul run --summary`)")

    val summaries = files.map { p ->
        val f = File(p).takeIf { it.isAbsolute } ?: File(cwd, p)
        if (!f.isFile) return reportErr("summary not found: $p")
        runCatching { RunSummaryJson.read(f) }.getOrElse { return reportErr("cannot parse $p: ${it.message}") }
    }
    val report = PipelineReport.build(title, summaries, links)
    val text = when (format) {
        "md" -> renderMarkdown(report)
        "slack" -> renderSlack(report)
        else -> renderJunit(report)
    }
    if (out != null) {
        val f = File(out).takeIf { it.isAbsolute } ?: File(cwd, out)
        f.absoluteFile.parentFile?.mkdirs()
        f.writeText(text)
        System.err.println("report: ${f.relativeToOrSelf(cwd).path} — ${report.headline()}")
    } else {
        print(text)
    }
    return if (check && report.overall == Severity.FAIL) 1 else 0
}

private fun reportErr(msg: String): Int {
    System.err.println("error: $msg")
    return 2
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

data class Verdict(val kind: VerdictKind) {
    val severity get() = kind.severity
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
    val verdict: Verdict,
)

class PipelineReport(
    val title: String,
    val phases: List<Phase>,
    val rows: List<ScenarioRow>,
    val links: List<Pair<String, String>>,
) {
    val overall: Severity = rows.maxOfOrNull { it.verdict.severity } ?: Severity.OK
    val counts: Map<VerdictKind, Int> = rows.groupingBy { it.verdict.kind }.eachCount()
    val attention: List<ScenarioRow> = rows.filter { it.verdict.severity != Severity.OK }
        .sortedBy { it.verdict.kind.ordinal }

    val appVersionName get() = phases.firstNotNullOfOrNull { it.summary.appVersionName }
    val device get() = phases.firstNotNullOfOrNull { it.summary.device }
    val agentVersion get() = phases.firstNotNullOfOrNull { it.summary.agentVersion.ifEmpty { null } }
    val startedAt get() = phases.firstNotNullOfOrNull { it.summary.startedAt.ifEmpty { null } }

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

        fun judge(phases: List<Phase>, outcomes: List<ScenarioOutcome?>): Verdict {
            fun of(role: Role) = phases.indices.firstOrNull { phases[it].role == role }?.let { outcomes[it] }
            val present = outcomes.filterNotNull()
            if (present.any { it.status == "CRASH" }) return Verdict(VerdictKind.CRASH)
            if (present.all { it.status == "SKIPPED" }) return Verdict(VerdictKind.SKIPPED)

            val record = of(Role.RECORD)
            if (record == null || phases.none { it.role == Role.RECORD }) {
                // no recording phase: plain replay semantics over whatever ran
                return Verdict(if (present.any { it.status == "FAILED" }) VerdictKind.FAIL else VerdictKind.PASS)
            }
            val baseline = of(Role.BASELINE)
            val verify = of(Role.VERIFY)
            val baselinePhase = phases.any { it.role == Role.BASELINE }
            return Verdict(
                when {
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
            )
        }
    }
}

// --------------------------------------------------------------------- shared bits

/** `12s`, `1m24s`, `2h05m`. */
fun humanDuration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m${"%02d".format(s % 60)}s"
        else -> "${s / 3600}h${"%02d".format((s % 3600) / 60)}m"
    }
}

/** One table cell for a phase outcome: `✅ 12s`, `❌ step 3`, `❌ setup`, `⏭ no trace`, `💥 crash`, `–`. */
fun cell(o: ScenarioOutcome?): String = when {
    o == null -> "–"
    o.status == "PASSED" -> "✅ ${humanDuration(o.durationMs)}"
    o.status == "CRASH" -> "💥 crash"
    o.status == "SKIPPED" -> "⏭ ${o.reason ?: "skipped"}"
    else -> "❌ " + (o.failedStep?.let { f -> if (f.reason?.startsWith("setup failed") == true) "setup" else "step ${f.index}" } ?: "failed")
}

/** The failure narrative of one outcome, or null when it passed / did not run. */
private fun failureLine(o: ScenarioOutcome): String? = when (o.status) {
    "FAILED" -> o.failedStep?.let { f ->
        val where = if (f.reason?.startsWith("setup failed") == true) "setup" else "step ${f.index} `${f.text}`"
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
    val env = listOfNotNull(
        r.appVersionName?.let { "app $it" }, r.device?.let { "device $it" },
        r.agentVersion?.let { "simul $it" }, r.startedAt?.let { "started $it" },
    )
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
            " | ${row.verdict.kind.emoji} ${row.verdict.kind.title} |")
    }
    if (r.attention.isNotEmpty()) {
        appendLine()
        appendLine("### Needs attention")
        for (row in r.attention) {
            appendLine()
            appendLine("#### ${row.verdict.kind.emoji} `${row.path}` — ${row.verdict.kind.title}")
            if (row.verdict.kind.advice.isNotEmpty()) appendLine(row.verdict.kind.advice)
            for ((i, o) in row.outcomes.withIndex()) {
                if (o == null) continue
                val line = failureLine(o) ?: continue
                appendLine("- **${r.phases[i].label}** $line")
                o.failedStep?.screenshot?.let { appendLine("  - screenshot: `$it`") }
                o.failedStep?.screen?.takeIf { it.isNotEmpty() }?.let { appendLine("  - on screen: " + it.joinToString(" · ") { l -> "`$l`" }) }
                o.reportDir?.let { appendLine("  - report: `$it`") }
            }
            row.outcomes.filterNotNull().firstOrNull { it.traceUpdated }?.let {
                appendLine("- trace updated by **${r.phases[row.outcomes.indexOf(it)].label}** — see the diff of `.simul/scenarios/${it.path.removeSuffix(".md")}.trace.json`")
            }
        }
    }
}

// ------------------------------------------------------------------------ slack

private fun slackEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Incoming-webhook payload (Block Kit) — `curl -d @slack.json $SLACK_WEBHOOK_URL`. */
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
    val env = listOfNotNull(r.appVersionName?.let { "app $it" }, r.device?.let { "device $it" }, r.agentVersion?.let { "simul $it" })
    blocks += section("*${slackEscape(r.countsLine())}*" + (if (env.isEmpty()) "" else "\n_${slackEscape(env.joinToString(" · "))}_"))
    // one block per scenario needing attention, capped — Slack allows 50 blocks per message
    val shown = r.attention.take(20)
    for (row in shown) {
        val lines = mutableListOf("*${row.verdict.kind.emoji} `${slackEscape(row.path)}`* — ${row.verdict.kind.title}")
        for ((i, o) in row.outcomes.withIndex()) {
            val line = o?.let(::failureLine) ?: continue
            lines += "> _${slackEscape(r.phases[i].label)}_ ${slackEscape(line)}"
            o.failedStep?.screen?.takeIf { it.isNotEmpty() }?.let { sc ->
                lines += "> on screen: " + slackEscape(sc.take(12).joinToString(" · ")) + (if (sc.size > 12) " …" else "")
            }
        }
        blocks += section(lines.joinToString("\n"))
    }
    if (r.attention.size > shown.size) blocks += section("_… and ${r.attention.size - shown.size} more — see the full report_")
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

private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** JUnit XML: one testsuite per phase, one testcase per scenario — for CI test reporters. */
fun renderJunit(r: PipelineReport): String = buildString {
    appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    appendLine("""<testsuites name="${xml(r.title)}">""")
    for ((i, p) in r.phases.withIndex()) {
        val outs = r.rows.mapNotNull { it.outcomes[i] }
        val failures = outs.count { it.status == "FAILED" || it.status == "CRASH" }
        val skipped = outs.count { it.status == "SKIPPED" }
        appendLine("""  <testsuite name="${xml(p.heading)}" tests="${outs.size}" failures="$failures" errors="0" skipped="$skipped" time="${p.summary.durationMs / 1000.0}">""")
        for (o in outs) {
            append("""    <testcase classname="${xml(p.label)}" name="${xml(o.path)}" time="${o.durationMs / 1000.0}"""")
            when (o.status) {
                "PASSED" -> appendLine("/>")
                "SKIPPED" -> appendLine("><skipped message=\"${xml(o.reason ?: "")}\"/></testcase>")
                else -> {
                    val msg = failureLine(o) ?: o.status
                    appendLine(">")
                    appendLine("      <failure message=\"${xml(msg.take(200))}\">${xml(msg)}${o.reportDir?.let { "\nreport: $it" } ?: ""}</failure>")
                    appendLine("    </testcase>")
                }
            }
        }
        appendLine("  </testsuite>")
    }
    appendLine("</testsuites>")
}
