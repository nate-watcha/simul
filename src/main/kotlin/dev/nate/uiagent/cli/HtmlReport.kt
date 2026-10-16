package dev.nate.uiagent.cli

import dev.nate.uiagent.agent.LlmStats
import dev.nate.uiagent.json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/**
 * `simul report --format html`: two pages for two readers.
 *
 * [render] is the report everyone opens — one table, a row per scenario grouped by directory:
 * the gif(s) of the replays, the scenario's one-line description (what the journey checks),
 * when something broke *which step* and the screenshot of that moment, one status cell per
 * phase, the verdict. The step list is folded under "N steps" as a status grid (one column
 * per phase), and that is all: no modes, durations, token counts or evidence lists.
 *
 * [renderDebug] is the page for whoever has to find out why: every step of every phase with
 * mode, duration, model usage, harness reason, evidence, on-screen labels and the screenshots
 * of the failing moment — what the first page used to be. The cards link to it per scenario.
 *
 * Both are self-contained apart from gifs: screenshots are inlined as JPEG data URIs so the
 * file opens anywhere the artifact lands; gifs are megabytes each, so with a [mediaDir] they
 * are copied there once and referenced as `media/<name>.gif` (ship the folder with the html);
 * without one the page links to the gif where it lies under the app root. Step detail comes
 * from each phase's `report.json`, resolved against the root; a missing report dir degrades
 * to what the summary carries (the failed step, the counts).
 */
object HtmlReport {

    /** One step as report.json records it. */
    data class Step(
        val index: Int, val text: String, val status: String, val mode: String?, val reason: String?,
        val durationMs: Long, val screenshots: List<String>, val screen: List<String>, val evidence: List<String>,
        val llm: LlmStats? = null,
    )

    fun readSteps(reportJson: File): List<Step> {
        val o = runCatching { json.parseToJsonElement(reportJson.readText()).jsonObject }.getOrNull() ?: return emptyList()
        fun strs(a: JsonArray?) = a?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        return (o["steps"] as? JsonArray)?.mapNotNull { el ->
            val s = el as? JsonObject ?: return@mapNotNull null
            Step(
                index = (s["step"] as? JsonPrimitive)?.intOrNull ?: 0,
                text = (s["text"] as? JsonPrimitive)?.contentOrNull ?: "",
                status = (s["status"] as? JsonPrimitive)?.contentOrNull ?: "",
                mode = (s["mode"] as? JsonPrimitive)?.contentOrNull,
                reason = (s["reason"] as? JsonPrimitive)?.contentOrNull,
                durationMs = (s["durationMs"] as? JsonPrimitive)?.longOrNull ?: 0,
                screenshots = strs(s["screenshots"] as? JsonArray),
                screen = strs(s["screen"] as? JsonArray),
                evidence = strs(s["evidence"] as? JsonArray),
                llm = llmFromJson(s["llm"] as? JsonObject),
            )
        } ?: emptyList()
    }

    /** Downscaled JPEG data URI of a PNG screenshot, or null when unreadable. */
    fun thumbnail(png: File, width: Int = 260): String? = runCatching {
        val src = ImageIO.read(png) ?: return null
        val h = (src.height.toLong() * width / src.width).toInt().coerceAtLeast(1)
        val dst = BufferedImage(width, h, BufferedImage.TYPE_INT_RGB)
        dst.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(src, 0, 0, width, h, null)
            dispose()
        }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(dst, "jpg", it) }.toByteArray()
        "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)
    }.getOrNull()

    /** The gif a phase outcome recorded (`--gif`), when the summary names one and the file is still there. */
    fun gifOf(o: ScenarioOutcome, root: File): File? =
        o.gif?.let { File(it).takeIf { f -> f.isAbsolute } ?: File(root, it) }?.takeIf { it.isFile }

    /** `report.html` → `report.debug.html`: where the debug page goes next to the main one. */
    fun debugFileFor(out: File): File =
        File(out.parentFile, out.name.substringBeforeLast('.') + ".debug." + out.extension.ifEmpty { "html" })

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val MAX_IMAGES = 200

    /** What a card calls the scenario: its prose description, else its name. */
    private fun titleOf(row: ScenarioRow): String =
        row.outcomes.firstNotNullOfOrNull { o -> o?.description?.takeIf { it.isNotBlank() } } ?: row.name

    private fun mark(status: String?): String = when (status) {
        null -> "·"; "PASSED" -> "✅"; "FAILED" -> "❌"; "SKIPPED" -> "⏭"; "CRASH" -> "💥"; else -> status
    }

    /**
     * One render's state: where each gif was copied (both pages reference the same copy) and how
     * many thumbnails have been inlined (a 56-scenario night with a broken login must not become
     * a 100 MB page).
     */
    private class Ctx(val root: File, val mediaDir: File?) {
        private var images = 0
        private val gifs = HashMap<String, String>()
        private val steps = HashMap<String, List<Step>>()

        fun resolve(path: String): File = File(path).takeIf { it.isAbsolute } ?: File(root, path)
        fun reportDir(o: ScenarioOutcome): File? = o.reportDir?.let(::resolve)

        fun stepsOf(o: ScenarioOutcome): List<Step> = o.reportDir?.let { dir ->
            steps.getOrPut(dir) { File(resolve(dir), "report.json").takeIf { it.isFile }?.let(::readSteps) ?: emptyList() }
        } ?: emptyList()

        fun thumb(file: File): String? = if (images < MAX_IMAGES) thumbnail(file)?.also { images++ } else null

        /** Where the html should point at a gif: a copy under [mediaDir] (named after scenario + phase), else the file itself. */
        fun gifSrc(gif: File, scenarioPath: String, phase: String): String = gifs.getOrPut("$scenarioPath|$phase") {
            if (mediaDir == null) gif.relativeToOrSelf(root).path
            else {
                val name = "$scenarioPath-$phase".replace(Regex("[^A-Za-z0-9._@-]+"), "_") + ".gif"
                mediaDir.mkdirs()
                gif.copyTo(File(mediaDir, name), overwrite = true)
                "${mediaDir.name}/$name"
            }
        }
    }

    private fun head(sb: StringBuilder, title: String) {
        sb.append("<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>").append(esc(title)).append("</title><style>").append(CSS).append("</style></head><body>")
    }

    private fun envLine(r: PipelineReport): String = listOfNotNull(
        r.appVersionName?.let { "app $it" }, r.device?.let { "device $it" }, r.agentVersion?.let { "simul $it" },
        r.androidCli?.let { "android CLI $it" }, r.startedAt?.let { "started $it" },
    ).joinToString(" · ")

    private fun gifFigure(sb: StringBuilder, ctx: Ctx, row: ScenarioRow, phase: Phase, o: ScenarioOutcome, gif: File) {
        val src = ctx.gifSrc(gif, row.path, phase.label)
        sb.append("<figure class=gif><img src=\"").append(esc(src)).append("\" alt=\"").append(esc("${row.path} · ${phase.label}")).append("\" loading=\"lazy\">")
        sb.append("<figcaption><a href=\"").append(esc(src)).append("\">").append(esc(phase.label)).append(" · ").append(humanDuration(o.durationMs)).append("</a></figcaption></figure>")
    }

    private fun cardHead(sb: StringBuilder, r: PipelineReport, row: ScenarioRow, phases: Boolean) {
        sb.append("<div class=head><span class=badge>").append(row.verdict.kind.emoji).append(' ').append(esc(row.verdict.kind.title)).append("</span>")
        sb.append("<h2>").append(esc(titleOf(row))).append("</h2>")
        sb.append("<p class=meta><code>").append(esc(row.path)).append("</code>")
        if (phases) for ((pi, o) in row.outcomes.withIndex()) {
            if (o == null) continue
            sb.append(" <span class=\"ph ph-").append(o.status.lowercase()).append("\">").append(esc(r.phases[pi].label)).append(' ').append(esc(cell(o))).append("</span>")
        }
        sb.append("</p></div>")
    }

    // ------------------------------------------------------------------ the report

    /** `billing/play/x.md@member` → `billing/play`; root-level scenarios group under "". */
    fun groupOf(path: String): String = path.substringBeforeLast('/', "")

    /**
     * The page for everyone: one table. Rows are the scenarios, grouped by the directory they
     * live in (`auth/`, `billing/` …, a header row each), with the replay gif(s) in the first
     * column, the description and, when something broke, *which step* in the second, then one
     * status cell per phase and the verdict. [debugHref] (relative to the page) links each row
     * to its section on the debug page; null when there is none (stdout).
     */
    fun render(r: PipelineReport, root: File, mediaDir: File? = null, debugHref: String? = null): String {
        val ctx = Ctx(root, mediaDir)
        val sb = StringBuilder()
        head(sb, r.title)
        sb.append("<header><h1>").append(r.overallEmoji).append(' ').append(esc(r.title)).append("</h1>")
        sb.append("<p class=headline>").append(esc(r.headline())).append("</p>")
        sb.append("<p class=counts>").append(esc(r.countsLine())).append("</p>")
        envLine(r).takeIf { it.isNotEmpty() }?.let { sb.append("<p class=env>").append(esc(it)).append("</p>") }
        val links = r.links.map { (n, u) -> "<a href=\"${esc(u)}\">${esc(n)}</a>" } +
            listOfNotNull(debugHref?.let { "<a href=\"${esc(it)}\">debug</a>" })
        if (links.isNotEmpty()) sb.append("<p class=links>").append(links.joinToString(" · ")).append("</p>")
        sb.append("</header>")

        // what to look at first — jump chips into the table
        if (r.attention.isNotEmpty()) {
            sb.append("<nav class=attention><ul>")
            for (row in r.attention) {
                sb.append("<li><a href=\"#s").append(r.rows.indexOf(row)).append("\">").append(row.verdict.kind.emoji).append(' ').append(esc(titleOf(row))).append("</a></li>")
            }
            sb.append("</ul></nav>")
        }

        val hasGifs = r.rows.any { row -> row.outcomes.any { o -> o != null && gifOf(o, root) != null } }
        val cols = 2 + (if (hasGifs) 1 else 0) + r.phases.size
        sb.append("<main><div class=scroll><table class=grid><thead><tr>")
        if (hasGifs) sb.append("<th></th>")
        sb.append("<th>scenario</th>")
        r.phases.forEach { sb.append("<th>").append(esc(it.heading)).append("</th>") }
        sb.append("<th>verdict</th></tr></thead><tbody>")
        val groups = r.rows.groupBy { groupOf(it.path) } // LinkedHashMap: first-seen order
        for ((dir, rows) in groups) {
            if (groups.size > 1 || dir.isNotEmpty()) {
                val bad = rows.filter { it.verdict.severity != Severity.OK }.groupingBy { it.verdict.kind.emoji }.eachCount()
                sb.append("<tr class=group><th colspan=").append(cols).append("><span class=dir>").append(esc(dir.ifEmpty { "." })).append("/</span> <span class=dim>")
                sb.append(rows.size).append(" scenario").append(if (rows.size == 1) "" else "s")
                bad.forEach { (emoji, n) -> sb.append(" · ").append(emoji).append(' ').append(n) }
                sb.append("</span></th></tr>")
            }
            for (row in rows) tableRow(sb, r, row, ctx, debugHref, hasGifs)
        }
        sb.append("</tbody></table></div></main>")
        sb.append("<footer class=dim>generated by simul ").append(esc(r.agentVersion ?: "")).append("</footer></body></html>\n")
        return sb.toString()
    }

    private fun tableRow(sb: StringBuilder, r: PipelineReport, row: ScenarioRow, ctx: Ctx, debugHref: String?, gifColumn: Boolean) {
        val i = r.rows.indexOf(row)
        sb.append("<tr id=\"s").append(i).append("\" class=\"row sev-").append(row.verdict.severity.name.lowercase()).append("\">")
        // ---- the recordings, one per phase that has one
        if (gifColumn) {
            sb.append("<td class=preview>")
            for ((pi, o) in row.outcomes.withIndex()) {
                val gif = o?.let { gifOf(it, ctx.root) } ?: continue
                gifFigure(sb, ctx, row, r.phases[pi], o, gif)
            }
            sb.append("</td>")
        }
        // ---- what it is, and where it broke
        sb.append("<td class=scenario><div class=desc>").append(esc(titleOf(row))).append("</div>")
        sb.append("<div class=path><code>").append(esc(row.path)).append("</code></div>")
        for ((pi, o) in row.outcomes.withIndex()) {
            if (o == null) continue
            val phase = r.phases[pi]
            when (o.status) {
                "FAILED" -> {
                    sb.append("<div class=fail>")
                    val f = o.failedStep
                    if (f == null) sb.append("<p class=where>❌ <b>").append(esc(phase.label)).append("</b> failed</p>")
                    else {
                        // the failing moment: the summary's last screenshot, else the step's last one from
                        // report.json — first in the markup so it floats beside the text, not under it
                        val shot = f.screenshot?.let(ctx::resolve)
                            ?: ctx.stepsOf(o).firstOrNull { it.index == f.index }?.screenshots?.lastOrNull()?.let { s -> ctx.reportDir(o)?.let { File(it, s) } }
                        val uri = shot?.let(ctx::thumb)
                        if (shot != null && uri != null) {
                            sb.append("<figure class=shot><img src=\"").append(uri).append("\" alt=\"").append(esc(shot.name)).append("\"><figcaption>").append(esc(shot.name)).append("</figcaption></figure>")
                        }
                        sb.append("<p class=where>❌ <b>").append(esc(phase.label)).append("</b> · ")
                        if (f.reason?.startsWith("setup failed") == true) sb.append("setup")
                        else sb.append("step ").append(f.index).append(" <q>").append(esc(f.text)).append("</q>")
                        sb.append("</p>")
                        f.reason?.let { sb.append("<p class=reason>").append(esc(it)).append("</p>") }
                        if (f.screen.isNotEmpty()) sb.append("<p class=screen>on screen: ").append(f.screen.joinToString(" · ") { "<code>${esc(it)}</code>" }).append("</p>")
                    }
                    sb.append("</div>")
                }
                "CRASH" -> sb.append("<div class=fail><p class=where>💥 <b>").append(esc(phase.label)).append("</b> · harness crash</p><p class=reason>").append(esc(o.reason ?: "")).append("</p></div>")
                "SKIPPED" -> if (row.verdict.severity != Severity.OK) sb.append("<p class=dim>⏭ <b>").append(esc(phase.label)).append("</b> · ").append(esc(o.reason ?: "skipped")).append("</p>")
            }
        }
        if (row.verdict.kind.advice.isNotEmpty()) sb.append("<p class=advice>").append(esc(row.verdict.kind.advice)).append("</p>")
        row.outcomes.withIndex().firstOrNull { it.value?.traceUpdated == true }?.let { (pi, o) ->
            sb.append("<p class=dim>trace re-recorded by <b>").append(esc(r.phases[pi].label)).append("</b> — <code>.simul/scenarios/").append(esc(traceRelPath(o!!.path))).append("</code></p>")
        }
        // ---- the steps, folded: one line per step, one status mark per phase that ran
        val stepsByPhase = row.outcomes.map { o -> o?.let(ctx::stepsOf) ?: emptyList() }
        val n = stepsByPhase.maxOf { it.size }
        if (n > 0) {
            val ran = row.outcomes.indices.filter { stepsByPhase[it].isNotEmpty() }
            sb.append("<details class=steps><summary>").append(n).append(" step").append(if (n == 1) "" else "s").append("</summary><table><thead><tr><th></th><th>step</th>")
            ran.forEach { sb.append("<th>").append(esc(r.phases[it].label)).append("</th>") }
            sb.append("</tr></thead><tbody>")
            for (k in 0 until n) {
                val text = stepsByPhase.firstNotNullOfOrNull { it.getOrNull(k)?.text } ?: ""
                val statuses = ran.map { stepsByPhase[it].getOrNull(k)?.status }
                val worst = when {
                    "FAILED" in statuses -> "failed"
                    statuses.all { it == "SKIPPED" || it == null } -> "skipped"
                    else -> "passed"
                }
                sb.append("<tr class=\"st-").append(worst).append("\"><td class=n>").append(k + 1).append("</td><td class=t>").append(esc(text)).append("</td>")
                ran.forEach { sb.append("<td class=m>").append(mark(stepsByPhase[it].getOrNull(k)?.status)).append("</td>") }
                sb.append("</tr>")
            }
            sb.append("</tbody></table></details>")
        }
        if (debugHref != null) sb.append("<p class=foot><a href=\"").append(esc(debugHref)).append("#s").append(i).append("\">debug</a></p>")
        sb.append("</td>")
        // ---- one cell per phase, then the verdict
        for (o in row.outcomes) {
            sb.append("<td class=\"ph ph-").append(o?.status?.lowercase() ?: "none").append("\">").append(esc(cell(o))).append("</td>")
        }
        sb.append("<td class=verdict><span class=badge>").append(row.verdict.kind.emoji).append(' ').append(esc(row.verdict.kind.title)).append("</span></td>")
        sb.append("</tr>")
    }

    // ------------------------------------------------------------------ the debug page

    /**
     * Every step of every phase with what the harness knew — the page a card's "debug" link
     * opens. Same scenario ids (`#s<i>`) and order as [render]; gifs share the media copies.
     */
    fun renderDebug(r: PipelineReport, root: File, mediaDir: File? = null, reportHref: String? = null): String {
        val ctx = Ctx(root, mediaDir)
        val sb = StringBuilder()
        head(sb, "${r.title} — debug")
        sb.append("<header><h1>").append(r.overallEmoji).append(' ').append(esc(r.title)).append(" <span class=dim>debug</span></h1>")
        sb.append("<p class=counts>").append(esc(r.countsLine())).append("</p>")
        envLine(r).takeIf { it.isNotEmpty() }?.let { sb.append("<p class=env>").append(esc(it)).append("</p>") }
        r.llmUsageLines().forEach { sb.append("<p class=env>").append(esc(it)).append("</p>") }
        val links = listOfNotNull(reportHref?.let { "<a href=\"${esc(it)}\">report</a>" }) + r.links.map { (n, u) -> "<a href=\"${esc(u)}\">${esc(n)}</a>" }
        if (links.isNotEmpty()) sb.append("<p class=links>").append(links.joinToString(" · ")).append("</p>")
        sb.append("</header><main>")
        for (row in r.attention + r.rows.filter { it.verdict.severity == Severity.OK }) {
            val i = r.rows.indexOf(row)
            sb.append("<article id=\"s").append(i).append("\" class=\"card sev-").append(row.verdict.severity.name.lowercase()).append("\">")
            cardHead(sb, r, row, phases = false)
            if (reportHref != null) sb.append("<p class=foot><a href=\"").append(esc(reportHref)).append("#s").append(i).append("\">report</a></p>")
            for ((pi, o) in row.outcomes.withIndex()) {
                if (o == null) continue
                val phase = r.phases[pi]
                sb.append("<section class=phase><h3>").append(esc(phase.heading)).append(" · ").append(esc(cell(o)))
                o.reportDir?.let { sb.append(" <span class=dim>").append(esc(it)).append("</span>") }
                o.llm?.let { sb.append(" <span class=dim>").append(esc(it.summary(totalMs = o.durationMs))).append("</span>") }
                sb.append("</h3>")
                if (o.status == "SKIPPED" || o.status == "CRASH") {
                    sb.append("<p class=reason>").append(esc(o.reason ?: o.status)).append("</p></section>")
                    continue
                }
                val reportDir = ctx.reportDir(o)
                val steps = ctx.stepsOf(o)
                if (steps.isEmpty()) {
                    o.failedStep?.let { f ->
                        sb.append("<p class=reason>step ").append(f.index).append(" <code>").append(esc(f.text)).append("</code> — ").append(esc(f.reason ?: "failed")).append("</p>")
                    }
                    sb.append("</section>")
                    continue
                }
                sb.append("<div class=body><ol class=steps>")
                for (st in steps) {
                    sb.append("<li class=\"st-").append(st.status.lowercase()).append("\"><span class=st>").append(esc(st.status)).append("</span> ")
                    sb.append("<code>").append(esc(st.text)).append("</code>")
                    st.mode?.let { sb.append(" <span class=dim>(").append(esc(it)).append(", ").append(humanDuration(st.durationMs)).append(")</span>") }
                    st.llm?.let { sb.append(" <span class=dim>").append(esc(it.summary(totalMs = st.durationMs))).append("</span>") }
                    st.reason?.let { sb.append("<div class=reason>").append(esc(it)).append("</div>") }
                    if (st.status == "FAILED") {
                        if (st.screen.isNotEmpty()) sb.append("<div class=screen>on screen: ").append(st.screen.joinToString(" · ") { "<code>${esc(it)}</code>" }).append("</div>")
                        // the failing moment: the step's last two screenshots (before/after, or the -failed shot)
                        val shots = st.screenshots.takeLast(2)
                        if (shots.isNotEmpty() && reportDir != null) {
                            sb.append("<div class=shots>")
                            for (shot in shots) {
                                val uri = ctx.thumb(File(reportDir, shot))
                                if (uri != null) sb.append("<figure class=shot><img src=\"").append(uri).append("\" alt=\"").append(esc(shot)).append("\"><figcaption>").append(esc(shot.substringAfterLast('/'))).append("</figcaption></figure>")
                                else sb.append("<span class=dim>").append(esc(shot)).append("</span> ")
                            }
                            sb.append("</div>")
                        }
                    } else if (st.evidence.isNotEmpty()) {
                        sb.append("<div class=dim>evidence: ").append(esc(st.evidence.joinToString(", "))).append("</div>")
                    }
                    sb.append("</li>")
                }
                sb.append("</ol>")
                gifOf(o, ctx.root)?.let { gif ->
                    sb.append("<div class=gifs>")
                    gifFigure(sb, ctx, row, phase, o, gif)
                    sb.append("</div>")
                }
                sb.append("</div></section>")
            }
            sb.append("</article>")
        }
        sb.append("</main><footer class=dim>generated by simul ").append(esc(r.agentVersion ?: "")).append("</footer></body></html>\n")
        return sb.toString()
    }

    private val CSS = """
        :root{--bg:#f6f6f7;--card:#fff;--fg:#1b1b1b;--dim:#6b6b6b;--line:#e3e3e3;--ok:#2e7d32;--warn:#b26a00;--fail:#c62828;--okbg:#e8f5e9;--warnbg:#fff4d6;--failbg:#fdecea}
        @media(prefers-color-scheme:dark){:root{--bg:#111;--card:#1c1c1e;--fg:#e6e6e6;--dim:#9a9a9a;--line:#333;--ok:#6fcf7a;--warn:#f0b443;--fail:#ef6a5f;--okbg:#15301b;--warnbg:#3a2f10;--failbg:#3d1c1a}}
        body{margin:0;padding:24px 16px;font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Apple SD Gothic Neo",sans-serif;background:var(--bg);color:var(--fg)}
        header,main,nav,footer{max-width:1360px;margin-inline:auto}
        h1{font-size:22px;margin:0 0 2px}h2{font-size:18px;margin:4px 0 2px;line-height:1.35}h3{font-size:14px;margin:12px 0 4px}
        .headline{font-size:17px;font-weight:600;margin:0}.counts{margin:2px 0 0}.env,.dim,.foot{color:var(--dim);font-size:13px}.links{margin:6px 0 0}.links a{margin-right:4px}
        code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12.5px}
        nav.attention{margin:16px auto 0}nav.attention ul{list-style:none;margin:0;padding:0;display:flex;flex-wrap:wrap;gap:6px}
        nav.attention a{display:inline-block;padding:4px 10px;border:1px solid var(--line);border-radius:999px;background:var(--card);color:inherit;text-decoration:none;font-size:13px}
        main{margin-top:16px}
        .card{background:var(--card);border:1px solid var(--line);border-left:5px solid var(--line);border-radius:10px;padding:14px 16px;margin:12px 0}
        .card.sev-fail{border-left-color:var(--fail)}.card.sev-warn{border-left-color:var(--warn)}.card.sev-ok{border-left-color:var(--ok)}
        .badge{display:inline-block;font-size:12px;font-weight:700;padding:1px 8px;border-radius:999px;background:var(--okbg)}
        .sev-fail .badge{background:var(--failbg);color:var(--fail)}.sev-warn .badge{background:var(--warnbg);color:var(--warn)}.sev-ok .badge{background:var(--okbg);color:var(--ok)}
        .meta{margin:0;color:var(--dim);font-size:13px}
        .body{display:flex;gap:20px;align-items:flex-start;margin-top:8px}.body .main{flex:1;min-width:0}
        .fail{background:var(--failbg);border-radius:8px;padding:8px 12px;margin:8px 0;display:flow-root}
        .fail figure.shot{float:right;margin:0 0 4px 12px;margin-top:0}.fail figure.shot img{width:110px}
        .where{margin:0;font-weight:600}.where q{quotes:none;font-weight:500}
        .reason{margin:2px 0 0;color:var(--fail)}.screen{margin:2px 0 0;font-size:13px}
        .advice{margin:6px 0;color:var(--dim);font-size:13px}
        figure{margin:0}figure.shot{margin-top:8px}figure.shot img{display:block;width:170px;max-width:40vw;border:1px solid var(--line);border-radius:4px}
        figcaption{font-size:11px;color:var(--dim);max-width:170px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
        details.steps{margin-top:8px}details.steps summary{cursor:pointer;color:var(--dim);font-size:13px}
        details.steps table{border-collapse:collapse;margin-top:6px;width:100%}details.steps td,details.steps th{padding:3px 8px;border-bottom:1px solid var(--line);vertical-align:top;text-align:left}
        details.steps th{font-size:11px;color:var(--dim);font-weight:600}details.steps td.n{color:var(--dim);width:1%}details.steps td.m{white-space:nowrap;width:1%}
        details.steps tr.st-failed td.t{color:var(--fail);font-weight:600}details.steps tr.st-skipped td.t{color:var(--dim)}
        .scroll{overflow-x:auto}
        table.grid{border-collapse:collapse;width:100%;background:var(--card);border:1px solid var(--line);border-radius:10px}
        .grid>thead th{font-size:11px;color:var(--dim);font-weight:600;text-align:left;padding:8px 10px;border-bottom:1px solid var(--line);white-space:nowrap}
        .grid>tbody>tr.group>th{text-align:left;padding:10px 10px 6px;background:var(--bg);border-top:1px solid var(--line);border-bottom:1px solid var(--line);font-size:13px}
        .grid .dir{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-weight:700;font-size:13px}
        .grid>tbody>tr.row>td{padding:10px;border-bottom:1px solid var(--line);vertical-align:top}
        .grid>tbody>tr.row:last-child>td{border-bottom:0}
        tr.row.sev-fail>td:first-child{box-shadow:inset 4px 0 var(--fail)}tr.row.sev-warn>td:first-child{box-shadow:inset 4px 0 var(--warn)}
        td.preview{width:1%;white-space:nowrap}td.preview figure.gif{display:inline-block;vertical-align:top;margin-right:8px}td.preview figure.gif:last-child{margin-right:0}
        td.scenario{min-width:320px}.desc{font-size:16px;font-weight:600;line-height:1.35}.path{color:var(--dim);font-size:12px;margin-bottom:4px}
        td.ph{white-space:nowrap;font-size:13px;width:1%}td.ph-failed,td.ph-crash{color:var(--fail);font-weight:600}td.ph-none,td.ph-skipped{color:var(--dim)}
        td.verdict{white-space:nowrap;width:1%}
        .gifs{display:flex;gap:10px;flex:0 0 auto;position:sticky;top:12px}
        figure.gif img{width:150px;max-width:40vw;border:1px solid var(--line);border-radius:10px;background:#000}
        figure.gif figcaption{text-align:center;max-width:150px}figure.gif figcaption a{color:var(--dim)}
        section.phase{margin:6px 0 10px}ol.steps{margin:4px 0;padding-left:22px;flex:1;min-width:0}ol.steps li{margin:3px 0}
        .st{display:inline-block;min-width:58px;font-size:11px;font-weight:700;letter-spacing:.02em}
        li.st-passed .st{color:var(--ok)}li.st-failed .st{color:var(--fail)}li.st-skipped .st{color:var(--dim)}
        .shots{display:flex;gap:10px;flex-wrap:wrap;margin:6px 0}
        @media(max-width:700px){.body{flex-direction:column}.gifs{position:static;flex-wrap:wrap}figure.gif img{max-width:100%}}
        @media(max-width:600px){body{padding:16px 12px}details.steps{overflow-x:auto}}
    """.trimIndent()
}
