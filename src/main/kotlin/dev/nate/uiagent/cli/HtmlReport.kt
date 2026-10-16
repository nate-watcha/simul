package dev.nate.uiagent.cli

import dev.nate.uiagent.humanDuration
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/**
 * `simul report --format html`: two pages for two readers.
 *
 * [render] is the report everyone opens, and it answers "does the app work, and what
 * doesn't?": a headline, a tile per feature area (= scenario directory) with its counts, the
 * broken scenarios in full (gif, which step, reason, screenshot), the re-recorded ones to
 * review, then each area's scenarios as a checklist of one-line descriptions — how its
 * screens behave, ticked or not, the recording and the step grid a click away. No pipeline
 * phases, modes, durations, token counts or evidence lists here.
 *
 * [renderDebug] is the page for whoever has to find out why: every step of every phase with
 * mode, duration, model usage, harness reason, evidence, on-screen labels and the screenshots
 * of the failing moment — pipeline phases, modes, durations and evidence. The cards link to it per scenario.
 *
 * Both are self-contained apart from gifs: screenshots are inlined as JPEG data URIs so the
 * file opens anywhere the artifact lands; gifs are megabytes each, so with a [mediaDir] they
 * are copied there once and referenced as `media/<name>.gif` (ship the folder with the html);
 * without one the page links to the gif where it lies under the app root. Step detail comes
 * from each phase's `report.json`, resolved against the root; a missing report dir degrades
 * to what the summary carries (the failed step, the counts).
 */
object HtmlReport {

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
    fun gifOf(o: ScenarioOutcome, root: File): File? = o.gif?.let { resolvePath(root, it) }?.takeIf { it.isFile }

    /** `report.html` → `report.debug.html`: where the debug page goes next to the main one. */
    fun debugFileFor(out: File): File =
        File(out.parentFile, out.name.substringBeforeLast('.') + ".debug." + out.extension.ifEmpty { "html" })

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
        private val steps = HashMap<String, List<StepReport>>()

        fun resolve(path: String): File = resolvePath(root, path)
        fun reportDir(o: ScenarioOutcome): File? = o.reportDir?.let(::resolve)

        fun stepsOf(o: ScenarioOutcome): List<StepReport> = o.reportDir?.let { dir ->
            steps.getOrPut(dir) { File(resolve(dir), "report.json").takeIf { it.isFile }?.let(ReportJson::readSteps) ?: emptyList() }
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
        sb.append("<title>").append(escapeHtml(title)).append("</title><style>").append(CSS).append("</style></head><body>")
    }

    private fun envLine(r: PipelineReport): String = r.envParts().joinToString(" · ")

    private fun gifFigure(sb: StringBuilder, ctx: Ctx, row: ScenarioRow, phase: Phase, o: ScenarioOutcome, gif: File) {
        val src = ctx.gifSrc(gif, row.path, phase.label)
        sb.append("<figure class=gif><img src=\"").append(escapeHtml(src)).append("\" alt=\"").append(escapeHtml("${row.path} · ${phase.label}")).append("\" loading=\"lazy\">")
        sb.append("<figcaption><a href=\"").append(escapeHtml(src)).append("\">").append(escapeHtml(phase.label)).append(" · ").append(humanDuration(o.durationMs)).append("</a></figcaption></figure>")
    }

    private fun cardHead(sb: StringBuilder, row: ScenarioRow) {
        sb.append("<div class=head><span class=badge>").append(row.verdict.emoji).append(' ').append(escapeHtml(row.verdict.title)).append("</span>")
        sb.append("<h2>").append(escapeHtml(titleOf(row))).append("</h2>")
        sb.append("<p class=meta><code>").append(escapeHtml(row.path)).append("</code></p></div>")
    }

    // ------------------------------------------------------------------ the report

    /** What a scenario is, for someone who asks "does the app work?" — the pipeline verdicts folded to five answers. */
    enum class State(val icon: String, val label: String, val cls: String) {
        BROKEN("❌", "안 됨", "broken"), FLAKY("⚠️", "불안정", "flaky"), CHANGED("🔁", "바뀜", "changed"),
        NEW("🆕", "새 녹화", "new"), OK("✅", "동작", "ok"), SKIPPED("⏭", "미실행", "skipped"),
    }

    fun stateOf(kind: VerdictKind): State = when (kind) {
        VerdictKind.STABLE, VerdictKind.PASS -> State.OK
        VerdictKind.REGRESSION, VerdictKind.FAIL, VerdictKind.CRASH, VerdictKind.TRACE_FLAKE -> State.BROKEN
        VerdictKind.RECORD_FLAKE -> State.FLAKY
        VerdictKind.CHANGED -> State.CHANGED
        VerdictKind.NEW -> State.NEW
        VerdictKind.SKIPPED -> State.SKIPPED
    }

    /** A feature area = a scenario directory; its display name is the first line of its README.md when there is one. */
    private class Group(val dir: String, val name: String, val rows: List<ScenarioRow>) {
        val states = rows.groupingBy { stateOf(it.verdict) }.eachCount()
        val worst: State = State.entries.first { it in states }
        val id get() = "g-" + dir.ifEmpty { "root" }.replace(Regex("[^A-Za-z0-9_-]+"), "_")
    }

    private fun groups(r: PipelineReport, root: File): List<Group> =
        r.rows.groupBy { areaOf(it.path) }.map { (dir, rows) ->
            val readme = File(root, ".simul/scenarios/$dir/README.md")
            val name = readme.takeIf { it.isFile }?.readLines()?.firstOrNull { it.isNotBlank() }?.trimStart('#', ' ')?.trim()
            Group(dir, name?.takeIf { it.isNotEmpty() } ?: dir.ifEmpty { "(root)" }, rows.sortedBy { stateOf(it.verdict).ordinal })
        }.sortedWith(compareBy({ it.worst.ordinal }, { it.dir.ifEmpty { "\uffff" } })) // root-level last

    /** `❌ 2 안 됨 · ✅ 7 동작` — the counts of a group or of the whole run, worst first. */
    private fun stateCounts(states: Map<State, Int>): String =
        State.entries.mapNotNull { s -> states[s]?.let { "${s.icon} $it ${s.label}" } }.joinToString(" · ")

    /**
     * The page for everyone: "does the app work, and what doesn't?". A headline, one tile per
     * feature area (scenario directory) with its counts, the broken scenarios with where they
     * broke (gif, step, reason, screenshot), the re-recorded ones to review, then every area's
     * scenarios as a checklist — each description is a sentence about how a screen behaves,
     * ticked or not, with its recording a click away. Pipeline phases stay on the debug page.
     * [debugHref] (relative to the page) links each scenario to its section there; null = none.
     */
    fun render(r: PipelineReport, root: File, mediaDir: File? = null, debugHref: String? = null): String {
        val ctx = Ctx(root, mediaDir)
        val groups = groups(r, root)
        val states = r.rows.groupingBy { stateOf(it.verdict) }.eachCount()
        val broken = r.rows.filter { stateOf(it.verdict) == State.BROKEN }
        val flaky = r.rows.filter { stateOf(it.verdict) == State.FLAKY }
        val review = r.rows.filter { stateOf(it.verdict) in setOf(State.CHANGED, State.NEW) }
        val sb = StringBuilder()
        head(sb, r.title)

        // ---- headline: the one answer
        val headline = when {
            broken.isNotEmpty() -> "❌ ${broken.size}개 안 됨"
            flaky.isNotEmpty() || review.isNotEmpty() -> "⚠️ 앱 정상, 확인할 게 ${flaky.size + review.size}개"
            states.keys.all { it == State.SKIPPED } -> "⏭ 실행된 시나리오 없음"
            else -> "✅ 앱 정상"
        }
        sb.append("<header><h1>").append(escapeHtml(r.title)).append("</h1>")
        sb.append("<p class=\"headline ").append((if (broken.isNotEmpty()) State.BROKEN else if (flaky.isNotEmpty() || review.isNotEmpty()) State.CHANGED else State.OK).cls).append("\">").append(escapeHtml(headline)).append("</p>")
        sb.append("<p class=counts>").append(r.rows.size).append("개 시나리오 · ").append(escapeHtml(stateCounts(states))).append("</p>")
        envLine(r).takeIf { it.isNotEmpty() }?.let { sb.append("<p class=env>").append(escapeHtml(it)).append("</p>") }
        val links = r.links.map { (n, u) -> "<a href=\"${escapeHtml(u)}\">${escapeHtml(n)}</a>" } +
            listOfNotNull(debugHref?.let { "<a href=\"${escapeHtml(it)}\">debug</a>" })
        if (links.isNotEmpty()) sb.append("<p class=links>").append(links.joinToString(" · ")).append("</p>")
        sb.append("</header><main>")

        // ---- feature tiles
        sb.append("<nav class=tiles>")
        for (g in groups) {
            sb.append("<a class=\"tile ").append(g.worst.cls).append("\" href=\"#").append(g.id).append("\"><span class=name>").append(escapeHtml(g.name)).append("</span>")
            if (g.name != g.dir) sb.append("<span class=dir>").append(escapeHtml(g.dir.ifEmpty { "." })).append("/</span>")
            sb.append("<span class=state>").append(escapeHtml(stateCounts(g.states))).append("</span></a>")
        }
        sb.append("</nav>")

        // ---- what does not work
        if (broken.isNotEmpty() || flaky.isNotEmpty()) {
            sb.append("<section class=broken><h2>안 되는 것</h2>")
            for (row in broken + flaky) brokenItem(sb, r, row, ctx, debugHref)
            sb.append("</section>")
        }
        // ---- what changed: re-recorded or first recorded, for a human to review
        if (review.isNotEmpty()) {
            sb.append("<section class=review><h2>확인 필요 <span class=dim>화면이 바뀌어 다시 녹화됐거나 처음 녹화된 것 — trace diff를 보고 머지</span></h2><ul>")
            for (row in review) {
                val i = r.rows.indexOf(row)
                val st = stateOf(row.verdict)
                sb.append("<li>").append(st.icon).append(" <a href=\"#s").append(i).append("\">").append(escapeHtml(titleOf(row))).append("</a> <span class=dim>").append(escapeHtml(row.path))
                row.outcomes.filterNotNull().firstOrNull { it.traceUpdated }?.let { sb.append(" · .simul/scenarios/").append(escapeHtml(traceRelPath(it.path))) }
                sb.append("</span></li>")
            }
            sb.append("</ul></section>")
        }

        // ---- every area, as a checklist of how its screens behave
        for (g in groups) {
            sb.append("<section class=area id=\"").append(g.id).append("\"><h2>").append(escapeHtml(g.name))
            if (g.name != g.dir) sb.append(" <span class=dir>").append(escapeHtml(g.dir.ifEmpty { "." })).append("/</span>")
            sb.append(" <span class=\"dim counts\">").append(escapeHtml(stateCounts(g.states))).append("</span></h2><ul class=list>")
            for (row in g.rows) listItem(sb, r, row, ctx, debugHref)
            sb.append("</ul></section>")
        }
        sb.append("</main><footer class=dim>generated by simul ").append(escapeHtml(r.agentVersion ?: "")).append("</footer></body></html>\n")
        return sb.toString()
    }

    /** The failure of one outcome: phase, step, reason, on-screen labels, the screenshot of that moment. */
    private fun failureBlock(sb: StringBuilder, phase: Phase, o: ScenarioOutcome, ctx: Ctx) {
        when (o.status) {
            "FAILED" -> {
                sb.append("<div class=fail>")
                val f = o.failedStep
                if (f == null) sb.append("<p class=where>❌ <b>").append(escapeHtml(phase.label)).append("</b> failed</p>")
                else {
                    // the failing moment: the summary's last screenshot, else the step's last one from
                    // report.json — first in the markup so it floats beside the text, not under it
                    val shot = f.screenshot?.let(ctx::resolve)
                        ?: ctx.stepsOf(o).firstOrNull { it.index == f.index }?.screenshots?.lastOrNull()?.let { s -> ctx.reportDir(o)?.let { File(it, s) } }
                    val uri = shot?.let(ctx::thumb)
                    if (shot != null && uri != null) {
                        sb.append("<figure class=shot><img src=\"").append(uri).append("\" alt=\"").append(escapeHtml(shot.name)).append("\"><figcaption>").append(escapeHtml(shot.name)).append("</figcaption></figure>")
                    }
                    sb.append("<p class=where>❌ <b>").append(escapeHtml(phase.label)).append("</b> · ")
                    if (f.isSetup) sb.append("setup")
                    else sb.append("step ").append(f.index).append(" <q>").append(escapeHtml(f.text)).append("</q>")
                    sb.append("</p>")
                    f.reason?.let { sb.append("<p class=reason>").append(escapeHtml(it)).append("</p>") }
                    if (f.screen.isNotEmpty()) sb.append("<p class=screen>on screen: ").append(f.screen.joinToString(" · ") { "<code>${escapeHtml(it)}</code>" }).append("</p>")
                }
                sb.append("</div>")
            }
            "CRASH" -> sb.append("<div class=fail><p class=where>💥 <b>").append(escapeHtml(phase.label)).append("</b> · harness crash</p><p class=reason>").append(escapeHtml(o.reason ?: "")).append("</p></div>")
        }
    }

    /** The folded step grid: one line per step, one status mark per phase that ran. Nothing when no report.json is around. */
    private fun stepsGrid(sb: StringBuilder, r: PipelineReport, row: ScenarioRow, ctx: Ctx) {
        val stepsByPhase = row.outcomes.map { o -> o?.let(ctx::stepsOf) ?: emptyList() }
        val n = stepsByPhase.maxOf { it.size }
        if (n == 0) return
        val ran = row.outcomes.indices.filter { stepsByPhase[it].isNotEmpty() }
        sb.append("<details class=steps><summary>").append(n).append(" steps</summary><table><thead><tr><th></th><th>step</th>")
        ran.forEach { sb.append("<th>").append(escapeHtml(r.phases[it].label)).append("</th>") }
        sb.append("</tr></thead><tbody>")
        for (k in 0 until n) {
            val text = stepsByPhase.firstNotNullOfOrNull { it.getOrNull(k)?.text } ?: ""
            val statuses = ran.map { stepsByPhase[it].getOrNull(k)?.status?.name }
            val worst = when {
                "FAILED" in statuses -> "failed"
                statuses.all { it == "SKIPPED" || it == null } -> "skipped"
                else -> "passed"
            }
            sb.append("<tr class=\"st-").append(worst).append("\"><td class=n>").append(k + 1).append("</td><td class=t>").append(escapeHtml(text)).append("</td>")
            ran.forEach { sb.append("<td class=m>").append(mark(stepsByPhase[it].getOrNull(k)?.status?.name)).append("</td>") }
            sb.append("</tr>")
        }
        sb.append("</tbody></table></details>")
    }

    private fun gifs(sb: StringBuilder, r: PipelineReport, row: ScenarioRow, ctx: Ctx): Boolean {
        val gifs = row.outcomes.mapIndexedNotNull { pi, o -> o?.let { gifOf(it, ctx.root) }?.let { Triple(r.phases[pi], o, it) } }
        if (gifs.isEmpty()) return false
        sb.append("<div class=gifs>")
        for ((phase, o, gif) in gifs) gifFigure(sb, ctx, row, phase, o, gif)
        sb.append("</div>")
        return true
    }

    /** A broken scenario, in full: gifs beside the description, every failing phase's block, the advice, the steps. */
    private fun brokenItem(sb: StringBuilder, r: PipelineReport, row: ScenarioRow, ctx: Ctx, debugHref: String?) {
        val i = r.rows.indexOf(row)
        val st = stateOf(row.verdict)
        sb.append("<article id=\"b").append(i).append("\" class=\"item ").append(st.cls).append("\">")
        gifs(sb, r, row, ctx)
        sb.append("<div class=main><div class=desc>").append(st.icon).append(' ').append(escapeHtml(titleOf(row))).append("</div>")
        sb.append("<div class=path><code>").append(escapeHtml(row.path)).append("</code> · ").append(escapeHtml(row.verdict.title)).append("</div>")
        for ((pi, o) in row.outcomes.withIndex()) if (o != null) failureBlock(sb, r.phases[pi], o, ctx)
        if (row.verdict.advice.isNotEmpty()) sb.append("<p class=advice>").append(escapeHtml(row.verdict.advice)).append("</p>")
        stepsGrid(sb, r, row, ctx)
        sb.append("<p class=foot><a href=\"#s").append(i).append("\">목록에서 보기</a>")
        if (debugHref != null) sb.append(" · <a href=\"").append(escapeHtml(debugHref)).append("#s").append(i).append("\">debug</a>")
        sb.append("</p></div></article>")
    }

    /** One line of an area's checklist: state, description, where it broke; the recording and steps fold under ▶. */
    private fun listItem(sb: StringBuilder, r: PipelineReport, row: ScenarioRow, ctx: Ctx, debugHref: String?) {
        val i = r.rows.indexOf(row)
        val st = stateOf(row.verdict)
        sb.append("<li id=\"s").append(i).append("\" class=\"").append(st.cls).append("\"><details><summary><span class=icon>").append(st.icon).append("</span><span class=desc>").append(escapeHtml(titleOf(row))).append("</span>")
        val where = row.outcomes.filterNotNull().firstOrNull { it.status == "FAILED" || it.status == "CRASH" }
            ?.let { o -> o.failedStep?.let { f -> if (f.isSetup) "setup" else "step ${f.index}" } ?: o.status.lowercase() }
        // the tag: where it broke for a broken one; the state (and where the old trace broke) otherwise
        val tag = when {
            st == State.BROKEN || st == State.FLAKY -> where ?: st.label
            st != State.OK -> st.label + (where?.let { " · $it" } ?: "")
            else -> null
        }
        tag?.let { sb.append("<span class=\"tag ").append(st.cls).append("\">").append(escapeHtml(it)).append("</span>") }
        sb.append("<span class=path>").append(escapeHtml(row.path)).append("</span></summary><div class=body>")
        val hadGifs = gifs(sb, r, row, ctx)
        sb.append("<div class=main>")
        if (st == State.BROKEN || st == State.FLAKY) sb.append("<p><a href=\"#b").append(i).append("\">↑ 안 되는 것에서 보기</a></p>")
        for ((pi, o) in row.outcomes.withIndex()) if (o != null && st != State.BROKEN && st != State.FLAKY) failureBlock(sb, r.phases[pi], o, ctx)
        stepsGrid(sb, r, row, ctx)
        if (!hadGifs && row.outcomes.all { it == null || it.gif == null }) sb.append("<p class=dim>녹화 없음</p>")
        if (debugHref != null) sb.append("<p class=foot><a href=\"").append(escapeHtml(debugHref)).append("#s").append(i).append("\">debug</a></p>")
        sb.append("</div></div></details></li>")
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
        sb.append("<header><h1>").append(r.overallEmoji).append(' ').append(escapeHtml(r.title)).append(" <span class=dim>debug</span></h1>")
        sb.append("<p class=counts>").append(escapeHtml(r.countsLine())).append("</p>")
        envLine(r).takeIf { it.isNotEmpty() }?.let { sb.append("<p class=env>").append(escapeHtml(it)).append("</p>") }
        r.llmUsageLines().forEach { sb.append("<p class=env>").append(escapeHtml(it)).append("</p>") }
        val links = listOfNotNull(reportHref?.let { "<a href=\"${escapeHtml(it)}\">report</a>" }) + r.links.map { (n, u) -> "<a href=\"${escapeHtml(u)}\">${escapeHtml(n)}</a>" }
        if (links.isNotEmpty()) sb.append("<p class=links>").append(links.joinToString(" · ")).append("</p>")
        sb.append("</header><main>")
        for (row in r.attention + r.rows.filter { it.verdict.severity == Severity.OK }) {
            val i = r.rows.indexOf(row)
            sb.append("<article id=\"s").append(i).append("\" class=\"card sev-").append(row.verdict.severity.name.lowercase()).append("\">")
            cardHead(sb, row)
            if (reportHref != null) sb.append("<p class=foot><a href=\"").append(escapeHtml(reportHref)).append("#s").append(i).append("\">report</a></p>")
            for ((pi, o) in row.outcomes.withIndex()) {
                if (o == null) continue
                val phase = r.phases[pi]
                sb.append("<section class=phase><h3>").append(escapeHtml(phase.heading)).append(" · ").append(escapeHtml(cell(o)))
                o.reportDir?.let { sb.append(" <span class=dim>").append(escapeHtml(it)).append("</span>") }
                o.llm?.let { sb.append(" <span class=dim>").append(escapeHtml(it.summary(totalMs = o.durationMs))).append("</span>") }
                sb.append("</h3>")
                if (o.status == "SKIPPED" || o.status == "CRASH") {
                    sb.append("<p class=reason>").append(escapeHtml(o.reason ?: o.status)).append("</p></section>")
                    continue
                }
                val reportDir = ctx.reportDir(o)
                val steps = ctx.stepsOf(o)
                if (steps.isEmpty()) {
                    o.failedStep?.let { f ->
                        sb.append("<p class=reason>step ").append(f.index).append(" <code>").append(escapeHtml(f.text)).append("</code> — ").append(escapeHtml(f.reason ?: "failed")).append("</p>")
                    }
                    sb.append("</section>")
                    continue
                }
                sb.append("<div class=body><ol class=steps>")
                for (st in steps) {
                    sb.append("<li class=\"st-").append(st.status.name.lowercase()).append("\"><span class=st>").append(st.status.name).append("</span> ")
                    sb.append("<code>").append(escapeHtml(st.text)).append("</code>")
                    st.mode?.let { sb.append(" <span class=dim>(").append(escapeHtml(it)).append(", ").append(humanDuration(st.durationMs)).append(")</span>") }
                    st.llm?.let { sb.append(" <span class=dim>").append(escapeHtml(it.summary(totalMs = st.durationMs))).append("</span>") }
                    st.reason?.let { sb.append("<div class=reason>").append(escapeHtml(it)).append("</div>") }
                    if (st.status == StepStatus.FAILED) {
                        if (st.screen.isNotEmpty()) sb.append("<div class=screen>on screen: ").append(st.screen.joinToString(" · ") { "<code>${escapeHtml(it)}</code>" }).append("</div>")
                        // the failing moment: the step's last two screenshots (before/after, or the -failed shot)
                        val shots = st.screenshots.takeLast(2)
                        if (shots.isNotEmpty() && reportDir != null) {
                            sb.append("<div class=shots>")
                            for (shot in shots) {
                                val uri = ctx.thumb(File(reportDir, shot))
                                if (uri != null) sb.append("<figure class=shot><img src=\"").append(uri).append("\" alt=\"").append(escapeHtml(shot)).append("\"><figcaption>").append(escapeHtml(shot.substringAfterLast('/'))).append("</figcaption></figure>")
                                else sb.append("<span class=dim>").append(escapeHtml(shot)).append("</span> ")
                            }
                            sb.append("</div>")
                        }
                    } else if (st.evidence.isNotEmpty()) {
                        sb.append("<div class=dim>evidence: ").append(escapeHtml(st.evidence.joinToString(", "))).append("</div>")
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
        sb.append("</main><footer class=dim>generated by simul ").append(escapeHtml(r.agentVersion ?: "")).append("</footer></body></html>\n")
        return sb.toString()
    }

    private val CSS = """
        :root{--bg:#f6f6f7;--card:#fff;--fg:#1b1b1b;--dim:#6b6b6b;--line:#e3e3e3;--ok:#2e7d32;--warn:#b26a00;--fail:#c62828;--info:#1565c0;--okbg:#e8f5e9;--warnbg:#fff4d6;--failbg:#fdecea;--infobg:#e3f2fd}
        @media(prefers-color-scheme:dark){:root{--bg:#111;--card:#1c1c1e;--fg:#e6e6e6;--dim:#9a9a9a;--line:#333;--ok:#6fcf7a;--warn:#f0b443;--fail:#ef6a5f;--info:#6fa8e8;--okbg:#15301b;--warnbg:#3a2f10;--failbg:#3d1c1a;--infobg:#15263a}}
        body{margin:0;padding:24px 16px;font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Apple SD Gothic Neo",sans-serif;background:var(--bg);color:var(--fg)}
        header,main,footer{max-width:1100px;margin-inline:auto}
        h1{font-size:20px;margin:0 0 6px;color:var(--dim);font-weight:600}h2{font-size:18px;margin:0 0 10px}h3{font-size:14px;margin:12px 0 4px}
        .headline{font-size:30px;font-weight:800;margin:0;line-height:1.2}.headline.broken{color:var(--fail)}.headline.changed{color:var(--warn)}.headline.ok{color:var(--ok)}
        .counts{margin:4px 0 0}.env,.dim,.foot{color:var(--dim);font-size:13px}.links{margin:6px 0 0}.links a{margin-right:4px}
        code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12.5px}
        .dir{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;color:var(--dim);font-weight:400}
        nav.tiles{display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:10px;margin:20px 0 28px}
        .tile{display:block;padding:12px 14px;border-radius:10px;background:var(--card);border:1px solid var(--line);border-left:5px solid var(--ok);color:inherit;text-decoration:none}
        .tile .name{display:block;font-weight:700;font-size:16px}.tile .dir{display:block}.tile .state{display:block;font-size:13px;margin-top:4px;color:var(--dim)}
        .tile.broken{border-left-color:var(--fail);background:var(--failbg)}.tile.broken .state{color:var(--fail)}
        .tile.flaky,.tile.changed,.tile.new{border-left-color:var(--warn)}.tile.changed .state,.tile.flaky .state,.tile.new .state{color:var(--warn)}
        .tile.skipped{border-left-color:var(--line)}
        section{margin:0 0 28px}section.broken h2{color:var(--fail)}section.review h2 .dim{font-size:13px;font-weight:400}
        section.review ul{margin:0;padding-left:20px}section.review li{margin:3px 0}
        .item{display:flex;gap:18px;align-items:flex-start;background:var(--card);border:1px solid var(--line);border-left:5px solid var(--fail);border-radius:10px;padding:14px 16px;margin:10px 0}
        .item.flaky{border-left-color:var(--warn)}.item .main{flex:1;min-width:0}
        .desc{font-size:16px;font-weight:600;line-height:1.35}.path{color:var(--dim);font-size:12px;margin-bottom:4px}
        .fail{background:var(--failbg);border-radius:8px;padding:8px 12px;margin:8px 0;display:flow-root}
        .fail figure.shot{float:right;margin:0 0 4px 12px}.fail figure.shot img{width:110px}
        .where{margin:0;font-weight:600}.where q{quotes:none;font-weight:500}
        .reason{margin:2px 0 0;color:var(--fail)}.screen{margin:2px 0 0;font-size:13px}
        .advice{margin:6px 0;color:var(--dim);font-size:13px}
        figure{margin:0}figure.shot{margin-top:8px}figure.shot img{display:block;width:170px;max-width:40vw;border:1px solid var(--line);border-radius:4px}
        figcaption{font-size:11px;color:var(--dim);max-width:170px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
        details.steps{margin-top:8px}details.steps summary{cursor:pointer;color:var(--dim);font-size:13px}
        details.steps table{border-collapse:collapse;margin-top:6px;width:100%}details.steps td,details.steps th{padding:3px 8px;border-bottom:1px solid var(--line);vertical-align:top;text-align:left}
        details.steps th{font-size:11px;color:var(--dim);font-weight:600}details.steps td.n{color:var(--dim);width:1%}details.steps td.m{white-space:nowrap;width:1%}
        details.steps tr.st-failed td.t{color:var(--fail);font-weight:600}details.steps tr.st-skipped td.t{color:var(--dim)}
        .gifs{display:flex;gap:10px;flex:0 0 auto;flex-wrap:wrap}
        figure.gif img{width:150px;max-width:40vw;border:1px solid var(--line);border-radius:10px;background:#000}
        figure.gif figcaption{text-align:center;max-width:150px}figure.gif figcaption a{color:var(--dim)}
        section.area{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px 16px}section.area h2 .counts{font-size:13px;font-weight:400;margin-left:6px}
        ul.list{list-style:none;margin:0;padding:0}ul.list>li{border-top:1px solid var(--line)}ul.list>li>details>summary{list-style:none;cursor:pointer;display:flex;gap:8px;align-items:baseline;padding:7px 4px;flex-wrap:wrap}
        ul.list>li>details>summary::-webkit-details-marker{display:none}ul.list>li>details>summary::before{content:"▶";font-size:10px;color:var(--dim);width:12px}ul.list>li>details[open]>summary::before{content:"▼"}
        ul.list .desc{font-size:15px;font-weight:500;flex:1;min-width:200px}ul.list>li.broken .desc{font-weight:600}
        ul.list .path{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;margin:0;white-space:nowrap}
        .tag{font-size:12px;font-weight:700;padding:0 8px;border-radius:999px;background:var(--warnbg);color:var(--warn);white-space:nowrap}
        .tag.broken{background:var(--failbg);color:var(--fail)}.tag.new{background:var(--infobg);color:var(--info)}.tag.skipped{background:var(--bg);color:var(--dim)}
        ul.list .body{display:flex;gap:18px;align-items:flex-start;padding:6px 4px 12px 20px}ul.list .body .main{flex:1;min-width:0}
        section.phase{margin:6px 0 10px}ol.steps{margin:4px 0;padding-left:22px;flex:1;min-width:0}ol.steps li{margin:3px 0}
        .st{display:inline-block;min-width:58px;font-size:11px;font-weight:700;letter-spacing:.02em}
        li.st-passed .st{color:var(--ok)}li.st-failed .st{color:var(--fail)}li.st-skipped .st{color:var(--dim)}
        .shots{display:flex;gap:10px;flex-wrap:wrap;margin:6px 0}
        .card{background:var(--card);border:1px solid var(--line);border-left:5px solid var(--line);border-radius:10px;padding:14px 16px;margin:12px 0}
        .card.sev-fail{border-left-color:var(--fail)}.card.sev-warn{border-left-color:var(--warn)}.card.sev-ok{border-left-color:var(--ok)}
        .badge{display:inline-block;font-size:12px;font-weight:700;padding:1px 8px;border-radius:999px;background:var(--okbg)}
        .sev-fail .badge{background:var(--failbg);color:var(--fail)}.sev-warn .badge{background:var(--warnbg);color:var(--warn)}.sev-ok .badge{background:var(--okbg);color:var(--ok)}
        .meta{margin:0;color:var(--dim);font-size:13px}.body{display:flex;gap:20px;align-items:flex-start;margin-top:8px}.body>ol.steps{flex:1}
        @media(max-width:700px){.item,ul.list .body,.body{flex-direction:column}figure.gif img{max-width:100%}}
        @media(max-width:600px){body{padding:16px 12px}details.steps{overflow-x:auto}}
    """.trimIndent()
}
