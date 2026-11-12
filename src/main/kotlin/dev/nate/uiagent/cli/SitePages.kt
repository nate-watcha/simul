package dev.nate.uiagent.cli

import dev.nate.uiagent.humanDuration
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders a loaded run (see [Site]) as pages — the HTML report of README "나이틀리 운영":
 *
 *  1. Home: `index.html` — a checklist: the verdict with every check as a cell, the failures as rows (where it
 *     is, the step it stopped on, why, its frame), then the run as a table of screens that opens to each screen's
 *     scenarios and what they confirm. A screen is a group directory; a check is a `## title` section, or the
 *     scenario itself without sections ([Site.Check]).
 *  2. Screen: `<feature>/_screen.html` — the screen's spec: the frame it is entered on (`_screen.jpg`, cut from a
 *     recording) and its ways in (`setup`), then each scenario's prose and its checks as sentences with a verdict,
 *     opening to their steps.
 *  3. Scenario detail: `<feature>/<name>.<state>.html` — the md prose, the states, the steps (the failed
 *     one expanded with expected/found evidence and logcat) beside the replay: a `<video>` of replay.mp4
 *     with a step-coloured progress bar; clicking a step or a segment seeks the video to that step's
 *     `video_ms`. Without a recording the frame shows the step's screenshot instead. When tonight's LLM
 *     recording failed, the page is about *that* timeline (the agent's steps, the failed one with its
 *     actions and their frames, `record/NN.jpg`) with the recording's own mp4, and the replay of the committed
 *     trace is folded under it. A scenario with `## title` sections shows its steps under them — one foldable,
 *     numbered block per section in the order they ran, with its prose and status, the `## 준비` section ahead
 *     of them with what the model found or did; each block has a player of its own, playing that section's part
 *     of the run's one recording.
 *
 * Pages are self-contained apart from their assets beside them: `<name>.<state>/replay.mp4`,
 * `steps/NN.jpg` and `record/NN.jpg` (downscaled screenshots). Built inside the run directory itself
 * (`simul run --runs` does this; the page's asset directory *is* the scenario's directory) nothing is copied but the JPEGs.
 */
object SitePages {

    class Options(
        /** What the pages call the app (`--title`); default the package name from run.json. */
        val appName: String? = null,
        val thumbWidth: Int = 360,
        /** Cuts a screen's entry frame out of a recording; the pages do without when it is not installed. */
        val ffmpeg: String = "ffmpeg",
    )

    /** [pages] are the scenarios' own pages, one per row; [screens] the page of each screen. */
    class Built(val index: File, val pages: List<File>, val rows: List<Site.Row>, val screens: List<File> = emptyList()) {
        val failed get() = rows.count { it.kind == Site.Kind.FAILED }
        val recordFailed get() = rows.count { it.recordFailed }
        /** `실패 2 (녹화 실패 1) · 안 돌림 1 · 통과 7` */
        fun counts(): String = Site.Kind.entries.mapNotNull { k ->
            rows.count { it.kind == k }.let { n ->
                if (n == 0 && k != Site.Kind.PASSED) null
                else "${k.label} $n" + (if (k == Site.Kind.FAILED && recordFailed > 0) " (녹화 실패 $recordFailed)" else "")
            }
        }.joinToString(" · ")
    }

    /** One picture of a recording beside its page, with what to call it. */
    class Frame(val caption: String, val src: String)

    /**
     * A failed recording's pictures, copied as `<id>/record/…jpg`: the frame each step ended on (by step number) and,
     * for the step it stopped on, the frame after each action the agent took there.
     */
    class RecordFrames(val steps: Map<Int, String>, val actions: List<Frame>) {
        companion object { val NONE = RecordFrames(emptyMap(), emptyList()) }
    }

    /** The screens a mention in this run's prose can name: the run's own and those its results say they mention (where a mention ends is not in the text, see [Mentions]). */
    private var known: Set<String> = emptySet()

    fun build(runDir: File, out: File, opts: Options = Options()): Built {
        val run = Site.load(runDir)
        val rows = Site.classify(run)
        known = rows.flatMap { r -> listOf(Mentions.norm(r.result.feature)) + r.timeline.mentions + r.timeline.tests.flatMap { it.mentions } }.toSet()
        out.mkdirs()
        val frames = rows.associate { it.id to (it.recordTimeline?.let { rec -> copyRecordFrames(rec, out, opts) } ?: RecordFrames.NONE) }
        val pages = rows.map { row -> renderDetail(run, row, rows, out, opts, frames.getValue(row.id)) }
        val screens = Site.screens(rows)
        val links = Site.links(screens)
        val screenPages = screens.map { s -> renderScreen(run, s, screens, links, out, opts, entryFrame(s, out, opts)) }
        val index = File(out, "index.html").apply { writeText(renderHome(run, rows, opts, frames)) }
        return Built(index, pages, rows, screenPages)
    }

    // ------------------------------------------------------------------ helpers

    private fun esc(s: String) = escapeHtml(s)
    private fun attr(s: String) = esc(s).replace("'", "&#39;")

    /**
     * A step's sentence with what it quotes set apart: `"작품" 탭을 누른다` — the quotes are the words on the screen the step
     * taps, types or looks for, and read on their own they are the path the scenario takes. Steps only: a description's
     * quote is a sentence's quote, and the spec reads as prose.
     */
    private fun stepText(text: String): String {
        val sb = StringBuilder()
        var at = 0
        for (m in QUOTED.findAll(text)) {
            sb.append(esc(text.substring(at, m.range.first))).append("<span class=q>").append(esc(m.value)).append("</span>")
            at = m.range.last + 1
        }
        return sb.append(esc(text.substring(at))).toString()
    }
    private val QUOTED = Regex("\"[^\"]+\"")

    /** `02:14` from an ISO stamp. */
    private fun hm(iso: String?): String = iso?.takeIf { it.length >= 16 }?.substring(11, 16) ?: ""
    private fun clock(ms: Long): String = "${ms / 60000}:${"%02d".format((ms / 1000) % 60)}"
    private fun up(feature: String) = if (feature.isEmpty()) "" else "../".repeat(feature.count { it == '/' } + 1)
    private fun pageOf(r: ResultJson.Result) = r.id + ".html"
    /** The scenario's own page under [out]: `<feature>/<name>.<state>.html`. */
    fun pageFile(out: File, r: ResultJson.Result) = File(out, pageOf(r))
    private fun chip(kind: Site.Kind, text: String = kind.label) = "<span class=\"chip ${kind.cls}\">${esc(text)}</span>"
    private fun avatar(owner: String?) = owner?.let { "<span class=owner><span class=avatar>${esc(it.take(1).uppercase())}</span>${esc(it)}</span>" } ?: ""
    private fun nn(n: Int) = n.toString().padStart(2, '0')
    private fun cls(status: String) = when (status) { "PASSED" -> "passed"; "FAILED" -> "failed"; else -> "skipped" }
    /** One cell per step, coloured by its status — the scenario's shape at a glance. */
    private fun strip(steps: List<ResultJson.Step>) =
        "<span class=\"strip wide\">" + steps.joinToString("") { "<i class=\"${cls(it.status)}\" title=\"${it.n}. ${attr(it.text)}\"></i>" } + "</span>"
    /** A step's number as the scenario writes it: counted within its section, through the whole scenario without sections. */
    private fun noOf(r: ResultJson.Result, s: ResultJson.Step) = r.sectionOf(s.n)?.let { s.n - it.from + 1 } ?: s.n
    /** How many steps [s] is counted among: its section's, or the scenario's. */
    private fun outOf(r: ResultJson.Result, s: ResultJson.Step) = r.sectionOf(s.n)?.let { it.to - it.from + 1 } ?: r.steps.size
    /** `3/7`, or `팔로우 추가 1/2` in a scenario with sections — where a step stands. */
    private fun at(r: ResultJson.Result, s: ResultJson.Step) = (r.sectionOf(s.n)?.let { "${it.title} " } ?: "") + "${noOf(r, s)}/${outOf(r, s)}"
    /** `7스텝 중 3에서 멈춤` / `팔로우 추가 1/2에서 멈춤` */
    private fun stoppedAt(r: ResultJson.Result) = r.failedStepOf?.takeIf { r.sections.isNotEmpty() }?.let { "${at(r, it)}에서 멈춤" }
        ?: "${r.steps.size}스텝 중 ${r.failedStep ?: r.steps.size}에서 멈춤"
    /** `PASSED · 4/4`, `FAILED · 3/7` — by sections when the scenario has them: `PASSED · 구간 1/2`, `FAILED · 팔로우 추가 1/2`. */
    private fun verdict(r: ResultJson.Result): String = when (r.status) {
        "PASSED" -> if (r.sections.isEmpty()) "PASSED · ${r.steps.count { it.status == "PASSED" }}/${r.steps.size}"
                    else "PASSED · 구간 ${r.tests.count { it.status == "PASSED" }}/${r.tests.size}"
        "FAILED" -> "FAILED · ${r.failedStepOf?.let { at(r, it) } ?: "${r.failedStep}/${r.steps.size}"}"
        "SKIPPED_SETUP" -> "SETUP · 안 돌림"
        else -> "안 돌림 · ${r.reason ?: r.status}"
    }
    /** Downscale a PNG screenshot to a JPEG of [width] px; false when unreadable. */
    fun writeJpeg(png: File, dest: File, width: Int): Boolean = runCatching {
        val src = ImageIO.read(png) ?: return false
        val h = (src.height.toLong() * width / src.width).toInt().coerceAtLeast(1)
        val dst = BufferedImage(width, h, BufferedImage.TYPE_INT_RGB)
        dst.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(src, 0, 0, width, h, null)
            dispose()
        }
        dest.parentFile?.mkdirs()
        ImageIO.write(dst, "jpg", dest)
    }.getOrDefault(false)

    /** Copy a result's step frames next to its page as `<out>/<id>/<sub>/NN.jpg`; returns the relative thumbnail per step n. */
    private fun copyAssets(r: ResultJson.Result, out: File, opts: Options, sub: String = "steps"): Map<Int, String> {
        val dir = File(out, r.id)
        val thumbs = LinkedHashMap<Int, String>()
        for (s in r.steps) {
            val png = s.screenshot?.let { File(r.dir, it) }?.takeIf { it.isFile } ?: continue
            val rel = "${r.key}/$sub/${nn(s.n)}.jpg"
            if (writeJpeg(png, File(dir, "$sub/${nn(s.n)}.jpg"), opts.thumbWidth)) thumbs[s.n] = rel
        }
        return thumbs
    }

    private fun copyVideo(r: ResultJson.Result, out: File): String? {
        val src = r.video?.let { File(r.dir, it.file) }?.takeIf { it.isFile } ?: return null
        val dest = File(out, "${r.id}/replay.mp4")
        if (dest.absoluteFile != src.absoluteFile) {   // built inside the run directory: already there
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
        }
        return "${r.key}/replay.mp4"
    }

    /**
     * A failed recording's mp4 for its page: left where it lies and linked relatively when the pages are built inside the
     * run directory (`../record/<id>/replay.mp4` — minutes of LLM run are not copied twice into the artifact), copied to
     * `<id>/record/replay.mp4` otherwise.
     */
    private fun recordVideo(rec: ResultJson.Result, out: File): String? {
        val src = rec.video?.let { File(rec.dir, it.file) }?.takeIf { it.isFile } ?: return null
        val pageDir = File(out, rec.feature).absoluteFile
        if (src.absoluteFile.startsWith(out.absoluteFile)) return pageDir.toPath().relativize(src.absoluteFile.toPath()).toString().replace(File.separatorChar, '/')
        val dest = File(out, "${rec.id}/record/replay.mp4")
        dest.parentFile?.mkdirs()
        src.copyTo(dest, overwrite = true)
        return "${rec.key}/record/replay.mp4"
    }

    private val ACTION_FRAME = Regex("step\\d+-(\\d+)-(\\w+)-after\\.png$")

    /** The frames of a failed recording (see [RecordFrames]): `record/NN.jpg` per step, `record/NN-k.jpg` per action of the failed step. */
    private fun copyRecordFrames(rec: ResultJson.Result, out: File, opts: Options): RecordFrames {
        val steps = copyAssets(rec, out, opts, sub = "record")
        val failed = rec.failedStepOf ?: return RecordFrames(steps, emptyList())
        val actions = failed.screenshots.mapNotNull { shot ->
            val m = ACTION_FRAME.find(shot) ?: return@mapNotNull null
            val png = File(rec.dir, shot).takeIf { it.isFile } ?: return@mapNotNull null
            val name = "record/${nn(failed.n)}-${m.groupValues[1]}.jpg"
            if (writeJpeg(png, File(out, "${rec.id}/$name"), opts.thumbWidth)) Frame("${m.groupValues[1]} ${m.groupValues[2]}", "${rec.key}/$name") else null
        }
        return RecordFrames(steps, actions)
    }

    // ------------------------------------------------------------------ what the home and the screen pages share

    /** A replay's `replay broken: evidence not on screen: toast` in words; any other reason as it is. */
    private fun humanReason(reason: String?): String = reason?.let {
        Regex("^replay broken: evidence not on screen: (.+)$").find(it)?.let { m -> "녹화 때 있던 것이 화면에 없음: ${m.groupValues[1]}" } ?: it
    } ?: "failed"
    private fun icon(status: String) = "<i class=\"ic ${cls(status)}\">${when (status) { "PASSED" -> "✓"; "FAILED" -> "✕"; else -> "–" }}</i>"
    /** One cell per check, a scenario's together — the shape of a screen (or of the run) at a glance. */
    private fun cells(rows: List<Site.Row>) = "<span class=cells>" + rows.joinToString("") { row ->
        "<span title=\"${attr(row.result.title)}\">" + row.checks.joinToString("") { "<i class=\"${cls(it.status)}\"></i>" } + "</span>"
    } + "</span>"
    private fun screenPageOf(feature: String) = (if (feature.isEmpty()) "" else "$feature/") + "_screen.html"
    /** A screen's own page under [out]: `<feature>/_screen.html`. */
    fun screenFile(out: File, feature: String) = File(out, screenPageOf(feature))
    /** The account state a scenario starts from: its `setup`'s, else its `states:` variant. */
    private fun stateOf(r: ResultJson.Result) = r.setup?.appState ?: r.state ?: ""
    private fun entryLine(e: Site.Entry) = "<span class=entry><code class=e>${esc(e.entry)}</code>" +
        e.states.entries.joinToString("") { (state, n) -> "<span>${esc(state)} $n</span>" } + "</span>"
    /** `통과 24/25`: the checks of [rows] that passed, of all. */
    private fun passedOf(rows: List<Site.Row>) = rows.flatMap { it.checks }.let { c -> c.count { it.status == "PASSED" } to c.size }
    /** Why a failed scenario none of whose checks failed did: its preparation, or a recording that left no steps. */
    private fun orphanFailure(row: Site.Row): String? {
        if (row.kind != Site.Kind.FAILED || row.checks.any { it.status == "FAILED" }) return null
        val t = row.timeline
        return t.failedStepOf?.let { f -> "${t.sectionOf(f.n)?.title ?: "실패"} — ${noOf(t, f)}. ${f.text} · ${humanReason(f.reason)}" } ?: row.recordFailure ?: t.reason ?: "failed"
    }
    /** Why a check is not a pass: its failed step's reason (a scenario may have more than one failed check), or why the scenario never ran. */
    private fun whyNot(row: Site.Row, c: Site.Check): String? = when (c.status) {
        "FAILED" -> c.steps.firstOrNull { it.status == "FAILED" }?.let { humanReason(it.reason) } ?: row.recordFailure ?: row.timeline.reason
        "NONE" -> row.result.reason
        else -> null
    }
    /** What a check says: its title and sentence — a scenario without sections is its own prose (its name without any). */
    private fun checkText(row: Site.Row, c: Site.Check) = (c.title?.let { "<b>${esc(it)}</b> " } ?: "") + esc(plain(c.description).ifEmpty { if (c.title == null) row.title else "" })
    /** A step that checks is what the scenario is there to confirm; the others get there. */
    private fun verifies(text: String) = text.endsWith("확인한다") || text.startsWith("Verify", ignoreCase = true)
    /** Prose with each `@화면` mention as the screen's name alone, where it is not drawn as a link. */
    private fun plain(text: String): String = Mentions.parts(text, known).joinToString("") { it.first }
    /** Prose with its `@화면` mentions drawn: a link to that screen's page when the run has it ([pages]: screen → its feature), the name alone otherwise. */
    private fun mentions(text: String, u: String, pages: Map<String, String>, here: String? = null): String =
        Mentions.parts(text, known).joinToString("") { (t, screen) ->
            when {
                screen == null -> esc(t)
                screen == here -> "<b class=\"mn here\">${esc(t)}</b>"
                pages[screen] != null -> "<a class=mn href=\"${attr(u + screenPageOf(pages.getValue(screen)))}\">${esc(t)}</a>"
                else -> "<span class=mn>${esc(t)}</span>"
            }
        }

    // ------------------------------------------------------------------ home

    /**
     * The first page is a checklist: the verdict with every check as a cell, what has to be looked at (each failure with
     * the step it stopped on and why, each scenario that never ran), then the whole run as a table of screens — a row a
     * screen, opening to its scenarios and what each confirms. However many scenarios, it is as long as there are screens.
     */
    fun renderHome(run: Site.Run, rows: List<Site.Row>, opts: Options, frames: Map<String, RecordFrames> = emptyMap()): String {
        val app = opts.appName ?: run.app ?: "app"
        val build = run.results.firstNotNullOfOrNull { it.appVersion } ?: run.summary?.appVersionName
        val failed = rows.filter { it.kind == Site.Kind.FAILED }
        val setup = rows.filter { it.kind == Site.Kind.SETUP }
        val passed = rows.filter { it.kind == Site.Kind.PASSED }
        val screens = Site.screens(rows)
        val links = Site.links(screens)
        val (ok, total) = passedOf(rows)
        val wall = run.summary?.durationMs ?: rows.sumOf { it.result.durationMs }
        val sb = StringBuilder()
        head(sb, "$app ${run.date}", "")
        sb.append("<div class=topbar><div class=inner><span class=brand>simul</span><nav class=apps><a class=on href=\"index.html\">${esc(app)}</a></nav></div></div><main>")

        // ---- the verdict
        val (tone, verdict) = when {
            failed.isNotEmpty() -> "failed" to "실패 ${failed.size}"
            setup.isNotEmpty() -> "setup" to "안 돌림 ${setup.size}"
            else -> "passed" to "전부 통과"
        }
        sb.append("<header class=\"gate $tone\"><div class=kicker>${esc(app)}${build?.let { " ${esc(it)}" } ?: ""} · ${esc(run.date)} ${esc(hm(run.summary?.startedAt ?: run.results.firstOrNull()?.startedAt))}</div>")
        sb.append("<div class=grow><h1>$verdict</h1><div class=chips>")
        if (failed.isNotEmpty() && setup.isNotEmpty()) sb.append("<span class=\"chip big setup\">안 돌림 ${setup.size}</span>")
        sb.append("<span class=\"chip big passed\">통과 ${passed.size}</span>")
        val recFailed = rows.count { it.recordFailed }
        if (recFailed > 0) sb.append("<span class=\"chip big failed\">녹화 실패 $recFailed</span>")
        sb.append("</div></div>")
        sb.append("<div class=gsum>확인 ${total}개 중 ${ok}개 통과 · 시나리오 ${rows.count { !it.isEntry }} · 화면 ${screens.size} · 총 소요 ${esc(humanDuration(wall))}</div>")
        sb.append("<div class=gcells>${screens.joinToString("") { cells(it.rows) }}</div></header>")
        run.record?.let { rec ->
            val l = dev.nate.uiagent.agent.LlmStats.sum(rec.scenarios.map { it.llm })
            sb.append("<p class=dim>LLM 녹화 ${esc(hm(rec.startedAt))} · ${esc(humanDuration(rec.durationMs))} · ${rec.scenarios.count { it.status == "PASSED" }}/${rec.scenarios.size} 녹화됨" +
                (l?.let { " · 모델 ${it.calls} calls ${esc(humanDuration(it.durationMs))}" } ?: "") + " → 그 trace를 LLM 없이 재생한 결과가 아래다.</p>")
        }

        // ---- what has to be looked at
        // a scenario that was not run because its screen's way in failed is told by that failure, not as a line of its own
        val todo = failed + setup.filterNot { it.waitsForEntry }
        sb.append("<section><div class=sh><h2>봐야 할 것</h2><span class=dim>${todo.size}건${if (failed.isNotEmpty()) " · 멈춘 스텝과 이유" else ""}</span></div>")
        if (todo.isEmpty()) sb.append("<p class=dim>전부 통과 ${passed.size} — ${if (run.record != null) "오늘 녹화되고 " else ""}끝까지 재생됨.</p>")
        else {
            sb.append("<div class=todo>")
            for (row in failed) failureRow(sb, row, frames[row.id] ?: RecordFrames.NONE, if (row.isEntry) rows.count { it.waitsForEntry && it.result.feature == row.result.feature } else 0)
            for (row in setup.filterNot { it.waitsForEntry }) setupRow(sb, row)
            sb.append("</div>")
        }
        sb.append("</section>")

        // ---- the whole run, by screen
        sb.append("<section><div class=sh><h2>전체</h2><span class=dim>화면 ${screens.size} · 시나리오 ${rows.count { !it.isEntry }} · 화면을 누르면 시나리오와 확인이 펼쳐진다</span>")
        sb.append("<span class=tools><input type=search id=q placeholder=\"화면·시나리오 찾기\" aria-label=\"화면·시나리오 찾기\">")
        if (failed.isNotEmpty()) sb.append("<button type=button id=onlyfail class=tog aria-pressed=false>실패만</button>")
        sb.append("</span></div><div class=tw><table class=scr><thead><tr><th>화면</th><th>진입</th><th class=r>시나리오</th><th class=r>확인</th><th>상태</th><th class=r title=\"다른 화면의 확인이 이 화면을 말한 줄\">다른 화면에서</th><th class=r title=\"이 화면의 확인이 다른 화면을 말한 줄\">다른 화면으로</th><th class=r>소요</th></tr></thead>")
        for (s in screens) {
            val (p, n) = passedOf(s.rows)
            val q = (s.name + " " + s.rows.joinToString(" ") { it.result.title + " " + it.result.name }).lowercase()
            // a run of a few screens (one scenario, a feature branch) is read open
            sb.append("<tbody class=\"g ${s.kind.cls}${if (screens.size <= 3) " open" else ""}\" data-q=\"${attr(q)}\"><tr class=srow><td><a href=\"${attr(screenPageOf(s.feature))}\">${esc(s.name)}</a></td>")
            // the screen's own way in when it has one — the address, and that there are steps after it — else where its scenarios start
            val way = s.entry?.let { e -> e.result.setup?.entry?.let { a -> "<code class=e>${esc(a)}</code>" + if (e.timeline.steps.any { st -> !verifies(st.text) }) " <span class=dim>→ 진입</span>" else "" } }
                ?: s.entries.firstOrNull()?.let { e -> "<code class=e>${esc(e.entry)}</code>" + if (s.entries.size > 1) " <span class=dim>외 ${s.entries.size - 1}</span>" else "" }
            sb.append("<td>${way ?: "<span class=dim>–</span>"}</td>")
            sb.append("<td class=r>${s.scenarios.size}</td><td class=r>${if (p < n) "<b class=\"${if (s.kind == Site.Kind.FAILED) "bad" else "dim"}\">$p/$n</b>" else "$n"}</td>")
            val here = Mentions.norm(s.feature)
            val from = links.filter { it.to == here && it.from !== s }
            val to = links.count { it.from === s && it.to != here }
            val fromFailed = from.count { it.status == "FAILED" }
            sb.append("<td>${cells(s.rows)}</td><td class=r>${if (from.isEmpty()) "<span class=dim>–</span>" else "${from.size}" + if (fromFailed > 0) " <b class=bad>· 실패 $fromFailed</b>" else ""}</td>")
            sb.append("<td class=r>${if (to == 0) "<span class=dim>–</span>" else "$to"}</td><td class=\"r dim\">${esc(humanDuration(s.durationMs))}</td></tr>")
            for (row in s.rows.sortedBy { it.kind.ordinal }) scenarioRow(sb, row)
            sb.append("</tbody>")
        }
        sb.append("</table></div></section>")
        sb.append("<script>").append(HOME_JS).append("</script>")
        sb.append("</main><footer class=dim>generated by simul $AGENT_VERSION</footer></body></html>\n")
        return sb.toString()
    }

    /**
     * A failed scenario, as a row: the frame it stopped on, where it is (screen › scenario › section), the step and why.
     * When tonight's recording failed the row is about that — how far the agent got, the step it gave up on — and the
     * replay (of yesterday's trace, if any) is one dim line.
     */
    private fun failureRow(sb: StringBuilder, row: Site.Row, frames: RecordFrames, waiting: Int = 0) {
        val r = row.result
        val rec = row.recordTimeline
        val t = row.timeline
        val step = t.failedStepOf
        val prefix = r.feature.let { f -> if (f.isEmpty()) "" else "$f/" }
        val thumb = rec?.failedStep?.let { frames.steps[it] }?.let { prefix + it }
            ?: step?.n?.takeIf { rec == null }?.let { "${r.id}/steps/${nn(it)}.jpg" }?.takeIf { step.screenshot != null }
        sb.append("<article class=frow>")
        if (thumb != null) sb.append("<a class=shot href=\"${attr(pageOf(r))}\"><img src=\"${attr(thumb)}\" alt=\"실패 시점 스크린샷\" loading=lazy></a>")
        else sb.append("<div class=\"shot empty\"></div>")
        sb.append("<div class=body><div class=path>${chip(Site.Kind.FAILED, if (row.recordFailed) "녹화 실패" else "FAILED")}<a href=\"${attr(screenPageOf(r.feature))}#${attr(r.key)}\">${esc(r.feature.ifEmpty { "(root)" })}</a><span>›</span><a class=title href=\"${attr(pageOf(r))}\">${esc(row.title)}</a>")
        step?.let { t.sectionOf(it.n) }?.let { sec ->
            sb.append("<span>›</span><span>${esc(sec.title)}</span>")
            // the screens the failed check is about: the failure shows on their pages too
            sec.mentions.forEach { sb.append("<span class=to>→ ${esc(it)}</span>") }
        }
        sb.append("</div>")
        if (waiting > 0) sb.append("<div class=reason>이 화면의 진입이 실패해 시나리오 ${waiting}개를 돌리지 못했다.</div>")
        val replayLine = if (row.replayed) "커밋 trace 재생 · ${esc(if (r.failed) verdict(r).replace(" · ", " ") else r.status)}" else "재생 없음 · ${esc(r.reason ?: "no trace")}"
        when {
            rec != null -> {
                sb.append("<div class=progress>${strip(rec.steps)}<span class=dim>${esc(stoppedAt(rec))}</span></div>")
                if (step != null) sb.append("<div class=mono>${noOf(rec, step)}. ${stepText(step.text)}</div><div class=reason>${esc(humanReason(step.reason))}</div>")
                sb.append("<div class=dim>$replayLine</div>")
            }
            row.recordFailed -> sb.append("<div class=reason>LLM 녹화: ${esc(row.recordFailure ?: "failed")}</div><div class=dim>$replayLine</div>")
            step != null -> sb.append("<div class=mono>${noOf(r, step)}. ${stepText(step.text)}</div><div class=reason>${esc(humanReason(step.reason))}</div>")
        }
        val when_ = if (rec != null) "녹화 ${esc(hm(t.startedAt))} · ${esc(humanDuration(t.durationMs))}"
                    else if (row.replayed || row.record == null) "${esc(hm(r.startedAt))} · ${esc(humanDuration(r.durationMs))}"
                    else "녹화 ${esc(humanDuration(row.record.durationMs))}"
        sb.append("</div><div class=side>${r.setup?.entry?.let { "<code class=e>${esc(it)}</code>" } ?: ""}<span>${esc(listOf(stateOf(r), when_).filter { it.isNotEmpty() }.joinToString(" · "))}</span>${avatar(r.owner)}<a class=more href=\"${attr(pageOf(r))}\">스텝·영상 ›</a></div></article>")
    }

    /** A scenario that never ran (its setup failed, or it has no trace): not a verdict on the app. */
    private fun setupRow(sb: StringBuilder, row: Site.Row) {
        val r = row.result
        sb.append("<article class=frow><div class=\"shot none\"></div><div class=body><div class=path>${chip(Site.Kind.SETUP)}<a href=\"${attr(screenPageOf(r.feature))}#${attr(r.key)}\">${esc(r.feature.ifEmpty { "(root)" })}</a><span>›</span><a class=title href=\"${attr(pageOf(r))}\">${esc(row.title)}</a></div>")
        sb.append("<div class=note>${esc(r.reason ?: "setup failed")} — 앱 판정이 아니라 환경 문제입니다.</div></div><div class=side><span>${esc(stateOf(r))}</span></div></article>")
    }

    /** One scenario under its screen's row: its name, the state it starts from, what it confirms — each check with its sentence. */
    private fun scenarioRow(sb: StringBuilder, row: Site.Row) {
        val r = row.result
        sb.append("<tr class=\"sub ${row.kind.cls}\"><td><i class=\"dot ${row.kind.cls}\"></i><a href=\"${attr(pageOf(r))}\">${esc(row.title)}</a>")
        if (row.recordFailed) sb.append(" ${chip(Site.Kind.FAILED, "녹화 실패")}") else if (row.kind == Site.Kind.SETUP) sb.append(" ${chip(Site.Kind.SETUP)}")
        sb.append("</td><td class=dim>${esc(stateOf(r))}</td><td colspan=3><span class=cl>")
        orphanFailure(row)?.let { sb.append("<span class=failed>${icon("FAILED")}<span class=reason>${esc(it)}</span></span>") }
        for (c in row.checks) {
            sb.append("<span class=\"${cls(c.status)}\">${icon(c.status)}<span>${checkText(row, c)}")
            whyNot(row, c)?.let { sb.append("<br><span class=reason>${esc(it)}</span>") }
            sb.append("</span></span>")
        }
        sb.append("</span></td><td class=\"r dim\">${esc(humanDuration(row.timeline.durationMs))}</td></tr>")
    }

    // ------------------------------------------------------------------ screen

    /**
     * A screen's page reads as its spec, in the order it is read every day: how one gets here — its way in
     * (`_entry.md`) and the checks of other screens that walk here — then what other screens change that shows
     * here, then the screen's own scenarios, a line each, those of another account under that account. The lines
     * from other screens are shown here and counted where they are written. A scenario opens to its prose and
     * checks, a check to its steps (the `…확인한다` ones set apart); steps and video are a click away, on the
     * scenario's own page.
     */
    private fun renderScreen(run: Site.Run, screen: Site.Screen, screens: List<Site.Screen>, links: List<Site.Link>, out: File, opts: Options, frame: String?): File {
        val app = opts.appName ?: run.app ?: "app"
        val u = up(screen.feature)
        val (p, n) = passedOf(screen.rows)
        val pages = screens.associate { Mentions.norm(it.feature) to it.feature }
        val here = Mentions.norm(screen.feature)
        val sb = StringBuilder()
        head(sb, "${screen.name} · $app", u)
        sb.append("<div class=topbar><div class=inner><span class=brand>simul</span><nav class=crumbs><a href=\"${u}index.html\">${esc(app)}</a><span>/</span><b>${esc(screen.name)}</b></nav><span class=right>${esc(run.date)}</span></div></div><main class=screen>")

        // ---- the screens of the run
        sb.append("<nav class=snav aria-label=\"화면\"><input type=search id=q placeholder=\"화면·시나리오 찾기\" aria-label=\"화면·시나리오 찾기\">")
        for (s in screens) {
            val (sp, sn) = passedOf(s.rows)
            val q = (s.name + " " + s.rows.joinToString(" ") { it.title }).lowercase()
            sb.append("<a${if (s.feature == screen.feature) " class=on" else ""} href=\"${attr(u + screenPageOf(s.feature))}\" data-q=\"${attr(q)}\"><i class=\"dot ${s.kind.cls}\"></i><span class=nm>${esc(s.name)}</span><span class=\"c${if (s.kind == Site.Kind.FAILED) " bad" else ""}\">$sp/$sn</span></a>")
        }
        sb.append("</nav><div class=doc>")

        // ---- the screen: what it looks like when entered
        sb.append("<header class=shead>")
        if (frame != null) sb.append("<img src=\"${attr(frame)}\" alt=\"진입 직후 화면\">")
        sb.append("<div class=b><span class=eyebrow>화면</span><h1>${esc(screen.name)}</h1>")
        // without a way in of its own, where its scenarios start is all there is to say about getting here
        if (screen.entry == null && screen.entries.isNotEmpty()) sb.append("<div class=ents>${screen.entries.joinToString("") { entryLine(it) }}</div>")
        val failedN = screen.rows.count { it.kind == Site.Kind.FAILED }
        sb.append("<span class=dim>시나리오 ${screen.scenarios.size} · 확인 $n · ${if (failedN > 0) "<b class=bad>실패 $failedN</b>" else if (p == n) "전부 통과" else "통과 $p"} · ${esc(humanDuration(screen.durationMs))}</span></div></header>")

        // ---- how one gets here, and what other screens change that shows here
        val incoming = links.filter { it.to == here && it.from !== screen }
        val ways = incoming.filterNot { it.look }
        val looks = incoming.filter { it.look }
        if (screen.entry != null || ways.isNotEmpty()) {
            sb.append("<section class=blk>${blockHead("들어오는 길", ways)}<div class=ways>")
            screen.entry?.let { entryRows(sb, screen, it, u) }
            for (l in ways) linkRow(sb, l, u, pages, here)
            sb.append("</div></section>")
        }
        if (looks.isNotEmpty()) {
            sb.append("<section class=blk>${blockHead("다른 화면에서 바꾸면", looks, "쓰인 화면에서 센다")}<div class=ways>")
            for (l in looks) linkRow(sb, l, u, pages, here)
            sb.append("</div></section>")
        }

        // ---- the screen's own scenarios: those of the account most of them run in, then each other account's
        val byState = screen.scenarios.groupBy { stateOf(it.result) }
        val main = screen.entry?.let { stateOf(it.result) }?.takeIf { it in byState } ?: byState.maxByOrNull { it.value.size }?.key
        for ((state, rows) in byState.entries.sortedBy { it.key != main }) {
            val title = if (state == main) "이 화면에서" else "다른 계정으로 보면 <code class=e>${esc(state.ifEmpty { "계정 없음" })}</code>"
            sb.append("<section class=blk><h3>$title</h3><div class=fl>")
            for (row in rows) scenarioLine(sb, row, u, pages)
            sb.append("</div></section>")
        }

        sb.append("</div><script>").append(SCREEN_JS).append("</script>")
        sb.append("</main><footer class=dim>generated by simul $AGENT_VERSION</footer></body></html>\n")
        val file = screenFile(out, screen.feature)
        file.parentFile?.mkdirs()
        file.writeText(sb.toString())
        return file
    }

    /** A block's heading on a screen's page: how many of the lines from other screens failed, and a note. */
    private fun blockHead(title: String, links: List<Site.Link>, note: String? = null): String {
        val failed = links.count { it.status == "FAILED" }
        val hn = listOfNotNull(if (failed > 0) "<b class=bad>실패 $failed</b>" else null, note).joinToString(" · ")
        return "<h3>$title${if (hn.isNotEmpty()) " <span class=hn>$hn</span>" else ""}</h3>"
    }

    /**
     * The screen's way in, as the first lines of its page. When its steps only check — the address opens the screen —
     * it is the screen's deeplink; with steps that move, the screen has no deeplink of its own and the way in is the
     * address and those steps. Either way it says how many of the screen's scenarios start from it, and when it failed,
     * that they were not run.
     */
    private fun entryRows(sb: StringBuilder, screen: Site.Screen, row: Site.Row, u: String) {
        val r = row.result
        val t = row.timeline
        val addr = r.setup?.entry?.let { "<code class=e>${esc(it)}</code>" } ?: ""
        val more = " <a class=more href=\"${attr(u + pageOf(r))}\">스텝·영상 ›</a>"
        if (t.steps.isEmpty()) {
            sb.append("<div class=\"in skipped\">${icon("NONE")}<span class=ip>진입</span><span class=it>$addr <span class=st2>${esc(r.reason ?: "돌지 않음")}</span></span></div>")
            return
        }
        val status = if (row.kind == Site.Kind.PASSED) "PASSED" else "FAILED"
        val from = screen.scenarios.count { it.result.setup?.via != null || it.waitsForEntry }
        val waiting = screen.scenarios.count { it.waitsForEntry }
        val note = if (from > 0) "<span class=st2>이 화면의 시나리오 ${from}개가 여기서 시작한다</span>" else ""
        val why = if (status != "FAILED") "" else
            "<br><span class=reason>${esc(t.failedStepOf?.let { f -> "${noOf(t, f)}. ${f.text} · ${humanReason(f.reason)}" } ?: row.recordFailure ?: t.reason ?: "failed")}" +
                (if (waiting > 0) " — 시나리오 ${waiting}개를 돌리지 못했다" else "") + "</span>"
        val moves = t.steps.filterNot { verifies(it.text) }
        if (moves.isEmpty()) {
            sb.append("<div class=\"in ${cls(status)}\">${icon(status)}<span class=ip>딥링크</span><span class=it>$addr<span class=st2>이 주소가 바로 연다</span>$note$more$why</span></div>")
        } else {
            sb.append("<div class=\"in skipped\"><i class=\"ic none\">–</i><span class=ip>딥링크</span><span class=it>없음</span></div>")
            sb.append("<div class=\"in ${cls(status)}\">${icon(status)}<span class=ip>진입</span><span class=it>$addr 를 열고 ${moves.joinToString(" / ") { stepText(it.text) }}$note$more$why</span></div>")
        }
    }

    /** A check of another screen that mentions this one, as a line here: where it is written (a link to it), and what it says. */
    private fun linkRow(sb: StringBuilder, l: Site.Link, u: String, pages: Map<String, String>, here: String) {
        val path = listOfNotNull(l.from.name, l.row.title, l.check?.title).joinToString(" › ") { esc(it) }
        sb.append("<div class=\"in ${cls(l.status)}\">${icon(l.status)}<a class=ip href=\"${attr(u + screenPageOf(l.from.feature))}#${attr(l.row.result.key)}\">$path</a><span class=it>${mentions(l.text, u, pages, here)}")
        if (l.status == "FAILED") (l.check?.let { whyNot(l.row, it) } ?: orphanFailure(l.row))?.let { sb.append("<br><span class=reason>${esc(it)}</span>") }
        sb.append("</span></div>")
    }

    /**
     * One scenario on its screen's page, as a line that opens: its name, then its sentence when it confirms one thing,
     * or its sections' titles and the screens they mention when it confirms several. Opened: its prose, its
     * preparation, each check with its sentence and — a click further — its steps. A scenario that did not pass is open.
     */
    private fun scenarioLine(sb: StringBuilder, row: Site.Row, u: String, pages: Map<String, String>) {
        val r = row.result
        val t = row.timeline
        val checks = row.checks
        val status = when (row.kind) { Site.Kind.FAILED -> "FAILED"; Site.Kind.PASSED -> "PASSED"; Site.Kind.SETUP -> "NONE" }
        val mark = if (row.recordFailed) chip(Site.Kind.FAILED, "녹화 실패") else if (row.kind == Site.Kind.SETUP) chip(Site.Kind.SETUP) else ""
        val single = checks.size == 1 && checks[0].title == null
        sb.append("<details class=\"ft ${row.kind.cls}\" id=\"${attr(r.key)}\"${if (row.kind != Site.Kind.PASSED) " open" else ""}><summary>${icon(status)}<b class=fn>${esc(row.title)}</b>")
        if (single) {
            val c = checks[0]
            sb.append("<span class=fs>${mentions(c.description, u, pages)}$mark</span><span class=ckn>${if (c.steps.isEmpty()) "" else "${c.steps.size}스텝"}</span></summary><div class=fb>")
        } else {
            val to = (t.mentions + checks.flatMap { it.mentions }).distinct()
            sb.append("<span class=\"fs titles\">${checks.joinToString(" · ") { "<span class=${cls(it.status)}>${esc(it.title ?: "")}</span>" }}${to.joinToString("") { "<span class=to>→ ${esc(it)}</span>" }}$mark</span>")
            sb.append("<span class=ckn>확인 ${checks.size}</span></summary><div class=fb>")
            if (t.description.isNotEmpty()) sb.append("<p class=spd>${mentions(t.description, u, pages)}</p>")
        }
        t.sections.firstOrNull { it.preparation }?.let { prep ->
            sb.append("<p class=prep>준비 · ${t.steps.filter { it.n in prep.from..prep.to }.joinToString(" / ") { stepText(it.text) }}</p>")
        }
        if (row.kind == Site.Kind.SETUP) sb.append("<p class=dim>${esc(r.reason ?: "돌지 않음")}</p>")
        orphanFailure(row)?.let { sb.append("<p class=reason>${esc(it)}</p>") }
        if (single) { if (checks[0].steps.isNotEmpty()) stepList(sb, checks[0].steps) }
        else for (c in checks) checkLine(sb, row, c, u, pages)
        sb.append("<span class=fm>${esc(listOf(stateOf(r), humanDuration(t.durationMs)).filter { it.isNotEmpty() }.joinToString(" · "))} · <a class=more href=\"${attr(u + pageOf(r))}\">스텝·영상 ›</a></span></div></details>")
    }

    /** One check of a scenario with sections: its title and sentence, how it started when that was not as the scenario does (its place, its account), and its steps on demand. */
    private fun checkLine(sb: StringBuilder, row: Site.Row, c: Site.Check, u: String, pages: Map<String, String>) {
        val at = (c.deeplink?.let { " <code class=\"e dl\">${esc(it)}</code>" } ?: c.entry?.let { " <span class=to>${esc(it)}의 진입으로 연다</span>" } ?: "") +
            (c.appState?.let { " <span class=to>${esc(it)} 계정으로</span>" } ?: "")
        val why = whyNot(row, c)?.takeIf { c.status == "FAILED" && c.steps.none { s -> s.status == "FAILED" } }?.let { "<br><span class=reason>${esc(it)}</span>" } ?: ""
        val head = "${icon(c.status)}<span class=ckt>${c.title?.let { "<b>${esc(it)}</b>" } ?: ""}${mentions(c.description, u, pages)}$at$why</span>"
        if (c.steps.isEmpty()) { sb.append("<div class=\"ck flat ${cls(c.status)}\">$head</div>"); return }
        sb.append("<details class=\"ck ${cls(c.status)}\"${if (c.status == "FAILED") " open" else ""}><summary>$head<span class=ckn>${c.steps.size}스텝</span></summary>")
        stepList(sb, c.steps)
        sb.append("</details>")
    }

    /** A check's steps: the ones that check set apart from the ones that get there, the failed one with why. */
    private fun stepList(sb: StringBuilder, steps: List<ResultJson.Step>) {
        sb.append("<ol class=st>")
        for (s in steps) {
            sb.append("<li class=\"${cls(s.status)}${if (s.status == "PASSED" && verifies(s.text)) " v" else ""}\">${stepText(s.text)}")
            if (s.status == "FAILED") sb.append("<br><span class=why>${esc(humanReason(s.reason))}</span>")
            sb.append("</li>")
        }
        sb.append("</ol>")
    }

    /**
     * The frame a screen is entered on — cut from a recording of one of its scenarios at the moment its first step
     * began (the recording starts when setup is done) — as `<feature>/_screen.jpg`; null without a recording or ffmpeg.
     */
    private fun entryFrame(screen: Site.Screen, out: File, opts: Options): String? {
        // a scenario's recording starts on the screen; the way in's starts where the app was opened, before it got there
        for (row in screen.scenarios + listOfNotNull(screen.entry)) {
            val t = row.timeline
            val mp4 = t.video?.let { File(t.dir, it.file) }?.takeIf { it.isFile } ?: continue
            val at = t.steps.firstOrNull { it.videoMs != null }?.videoMs ?: continue
            val dest = File(out, (if (screen.feature.isEmpty()) "" else "${screen.feature}/") + "_screen.jpg")
            dest.parentFile?.mkdirs()
            // one try: without ffmpeg, or with a file it cannot read, the other scenarios would fail the same way
            return runCatching {
                dev.nate.uiagent.runProcess(listOf(opts.ffmpeg, "-y", "-loglevel", "error", "-ss", "%.3f".format(at / 1000.0), "-i", mp4.path,
                    "-frames:v", "1", "-vf", "scale=${opts.thumbWidth}:-2", "-q:v", "4", dest.path), 30_000)
                "_screen.jpg".takeIf { dest.isFile && dest.length() > 0 }
            }.getOrNull()
        }
        return null
    }

    // ------------------------------------------------------------------ detail

    /**
     * The scenario's page is about one timeline: the recording's when it failed tonight (every step the agent did,
     * the one it stopped on expanded with its actions and their frames), else the replay's with its mp4. A failed
     * recording's page keeps the replay of the committed trace folded under it — it says whether yesterday's path still
     * works, nothing about tonight's failure.
     */
    private fun renderDetail(run: Site.Run, row: Site.Row, all: List<Site.Row>, out: File, opts: Options, frames: RecordFrames): File {
        val r = row.result
        val rec = row.recordTimeline
        val t = rec ?: r
        val app = opts.appName ?: run.app ?: "app"
        val replayVideo = copyVideo(r, out)
        val thumbs = if (rec != null) frames.steps else copyAssets(r, out, opts)
        val video = if (rec != null) recordVideo(rec, out) else replayVideo
        val u = up(r.feature)
        val sb = StringBuilder()
        head(sb, r.title, u)
        sb.append("<div class=topbar><div class=inner><span class=brand>simul</span><nav class=crumbs><a href=\"${u}index.html\">${esc(app)}</a><span>/</span><a href=\"${attr(u + screenPageOf(r.feature))}#${attr(r.key)}\">${esc(r.feature.ifEmpty { "(root)" })}</a><span>/</span><b>${esc(r.name)}</b></nav><span class=right>${esc(run.date)}</span></div></div><main class=detail>")

        // ---- header
        val replayStatus = verdict(r)
        sb.append("<header><div class=row><h1>${esc(r.title)}</h1>")
        if (rec != null) sb.append(chip(Site.Kind.FAILED, "녹화 실패 · ${rec.failedStepOf?.let { at(rec, it) } ?: "${rec.failedStep ?: rec.steps.size}/${rec.steps.size}"}"))
        else {
            sb.append(chip(row.kind, replayStatus))
            if (row.recordFailed) sb.append(chip(Site.Kind.FAILED, "녹화 실패"))
        }

        sb.append("<span class=right>${avatar(r.owner)}${if (r.owner != null) " · " else ""}${esc(hm(t.startedAt))} · ${esc(humanDuration(t.durationMs))}</span></div>")
        if (r.description.isNotEmpty()) sb.append("<p class=desc>${esc(plain(r.description))}</p>")
        if (rec != null) {
            val f = rec.failedStepOf
            sb.append("<div class=reason>LLM 녹화 ${esc(hm(rec.startedAt))} · ${esc(humanDuration(rec.durationMs))} — ${esc(stoppedAt(rec))}${f?.reason?.let { " · ${esc(it)}" } ?: ""} — 아래가 그 타임라인</div>")
        } else row.record?.let { recSummary ->
            val then = when {
                !row.recordFailed -> " — 아래는 그 trace의 재생"
                row.replayed -> " — trace는 커밋본 그대로, 아래는 그 재생"
                else -> " — 커밋된 trace가 없어 재생하지 않음"
            }
            sb.append("<div class=\"${if (row.recordFailed) "reason" else "dim"}\">LLM 녹화: ${esc(row.recordFailure ?: "${recSummary.status} · ${humanDuration(recSummary.durationMs)}")}$then</div>")
            // the recording left no directory: what record.json kept of the step the agent gave up on
            recSummary.failedStep?.takeIf { row.recordFailed }?.let { f ->
                sb.append("<div class=\"step failed static\"><div class=line><span class=icon>✕</span><div class=text><div class=mono><span class=n>녹화 ${f.index}.</span> ${stepText(f.text)}</div><div class=meta>${esc(f.reason ?: "failed")}</div></div></div>")
                if (f.screen.isNotEmpty()) sb.append("<div class=fail><div><h4>그때 화면</h4><div class=mono>${f.screen.take(12).joinToString("<br>") { "<span class=dim>· ${esc(it)}</span>" }}</div></div></div>")
                sb.append("</div>")
            }
        }
        sb.append("<div class=row>")
        val siblings = all.filter { it.result.feature == r.feature && it.result.name == r.name }
        if (siblings.size > 1 || r.state != null) {
            sb.append("<span class=tabs role=tablist>")
            for (s in siblings) sb.append("<a role=tab aria-selected=\"${s.id == r.id}\" href=\"${attr(u + pageOf(s.result))}\"><i class=\"dot ${s.kind.cls}\"></i>${esc(s.result.state ?: "default")}</a>")
            sb.append("</span>")
        }
        sb.append("<span class=right><span class=dim>${esc(".simul/scenarios/${r.file}")}</span></span></div></header>")

        // ---- the timeline and what plays it: one player beside the steps, or — a scenario with sections — one in every
        // section's block, so each of its scenarios is read with its own video
        val heading = if (rec != null) "오늘 녹화 (LLM)" else "STEPS"
        val hint = if (video != null) "스텝이나 막대를 클릭하면 영상이 그 스텝으로 이동" else "스텝을 클릭하면 그 스텝이 끝난 화면"
        val download = if (video != null) "<div class=btns><a class=btn href=\"${attr(video)}\" download>mp4 다운로드</a></div>" else ""
        if (t.sections.isEmpty()) {
            sb.append("<div class=cols data-scope><section class=steps><h2>$heading</h2>")
            if (t.steps.isEmpty()) sb.append("<p class=dim>재생 없음 — ${esc(t.reason ?: t.status)}</p>")
            setupRow(sb, t)
            timeline(sb, t, filmAt = rec?.failedStep, film = frames.actions)
            sb.append("</section><aside class=replay><h2>${if (rec != null) "녹화" else "REPLAY"} · ${esc(hm(t.startedAt))}</h2>")
            player(sb, t, t.steps, video, thumbs, footer = "<div class=dim>$hint</div>$download")
            sb.append("</aside></div>")
        } else {
            sb.append("<section class=\"steps grouped\"><h2>$heading<span class=hint>구간마다 그 구간의 영상 · $hint</span></h2>")
            setupRow(sb, t)
            timeline(sb, t, filmAt = rec?.failedStep, film = frames.actions, players = Players(video, thumbs))
            if (video != null) sb.append("<div class=whole><span class=dim>영상 파일은 하나입니다 — 구간마다 그 구간만 재생합니다.</span><a class=btn href=\"${attr(video)}\" download>전체 mp4 다운로드</a></div>")
            sb.append("</section>")
        }

        // ---- under a failed recording: what the replay of the committed trace did, folded
        if (rec != null) {
            if (!row.replayed) sb.append("<p class=dim>커밋된 trace가 없어 재생하지 않음 — ${esc(r.reason ?: "no trace")}</p>")
            else {
                sb.append("<details class=replaysec><summary>커밋된 trace의 재생 · ${esc(replayStatus)}<span class=dim> — 오늘 실패와 별개 (trace는 커밋본 그대로)</span></summary><div class=steps>")
                timeline(sb, r, interactive = false)
                if (replayVideo != null) sb.append("<div class=btns><a class=btn href=\"${attr(replayVideo)}\" download>재생 mp4 다운로드</a></div>")
                sb.append("</div></details>")
            }
        }

        // ---- the step timeline for the player
        sb.append("<script>const STEPS=").append(stepsJson(t, thumbs)).append(";").append(JS).append("</script>")
        sb.append("</main><footer class=dim>generated by simul $AGENT_VERSION</footer></body></html>\n")
        val file = pageFile(out, r)
        file.parentFile?.mkdirs()
        file.writeText(sb.toString())
        return file
    }

    /** What the players of a page play: the run's mp4 (null without a recording — then each step's frame, [thumbs]). */
    private class Players(val video: String?, val thumbs: Map<Int, String>)

    /**
     * A player for [steps]: the recording with a bar of the steps under it (a click on a step or its part of the bar
     * seeks there), or without a recording the frame each step ended on. [clip] makes it one section's: it plays only
     * the part of the run's one mp4 its steps span ([clipOf]) and counts its time from there.
     */
    private fun player(sb: StringBuilder, t: ResultJson.Result, steps: List<ResultJson.Step>, video: String?, thumbs: Map<Int, String>, clip: Boolean = false, footer: String = "") {
        val executed = steps.filter { it.status != "SKIPPED" }
        val window = if (clip && video != null) clipOf(t, executed) else null
        sb.append("<div class=player${window?.let { " data-from=${it.first} data-to=${it.second}" } ?: ""}><div class=frame>")
        if (video != null) sb.append("<video src=\"${attr(video)}\" preload=metadata playsinline></video>")
        else {
            val first = executed.firstOrNull()?.let { thumbs[it.n] }
            sb.append(if (first != null) "<img class=still src=\"${attr(first)}\" alt=\"스텝 화면\">" else "<span class=dim>녹화 없음 (--video)</span>")
        }
        // the current step's line sits under the frame, not over it: laid over the video it hid the app's bottom bar
        sb.append("</div><div class=caption>${executed.firstOrNull()?.let { "${noOf(t, it)}/${outOf(t, it)} · ${esc(it.text)}" } ?: ""}</div>")
        sb.append("<div class=controls>")
        if (video != null) sb.append("<button type=button class=play aria-label=\"재생\">▶</button>")
        // one part per step, all the same width: sized by duration, a one-second step was a sliver next to a long one
        sb.append("<span class=segments>")
        for (s in steps) sb.append("<i class=\"${cls(s.status)}\" data-step=${s.n} title=\"${noOf(t, s)}. ${attr(s.text)}\"></i>")
        sb.append("</span>")
        if (video != null) sb.append("<span class=time>0:00 / ${clock(window?.let { it.second - it.first } ?: t.video?.durationMs ?: 0)}</span>")
        sb.append("</div>$footer</div>")
    }

    /**
     * Where a section's steps lie in the run's one recording, in ms: from just ahead of its first step to a moment
     * past the end of its last — short of the next section's entry, which stops the app.
     */
    private fun clipOf(t: ResultJson.Result, executed: List<ResultJson.Step>): Pair<Long, Long>? {
        val first = executed.firstOrNull { it.videoMs != null } ?: return null
        val last = executed.last { it.videoMs != null }
        val end = last.videoMs!! + last.durationMs + 300
        return (first.videoMs!! - 300).coerceAtLeast(0) to (t.video?.durationMs?.let { minOf(end, it) } ?: end)
    }

    /**
     * The steps of a run. A scenario with sections gets one block per section, in the order they ran: its number
     * in that order, title, status, how long, its prose, then its steps numbered as the scenario numbers them.
     * The `## 준비` section stands ahead of them unnumbered — it tests nothing — with what the model found or
     * did, folded when there was nothing to do. A section an earlier failure kept from running is folded too.
     */
    private fun timeline(sb: StringBuilder, r: ResultJson.Result, interactive: Boolean = true, filmAt: Int? = null, film: List<Frame> = emptyList(), players: Players? = null) {
        if (r.sections.isEmpty()) { r.steps.forEach { s -> stepRow(sb, r, s, interactive, if (s.n == filmAt) film else emptyList()) }; return }
        var order = 0
        // With players the sections are cards side by side — video on top, steps under it — so the scenarios of the
        // file are seen together; a failed one takes the full width (its detail needs it), one that is folded is a bar.
        if (players != null) sb.append("<div class=secgrid>")
        for (sec in r.sections) {
            val steps = r.steps.filter { it.n in sec.from..sec.to }
            val ran = sec.status != "SKIPPED"
            val did = steps.sumOf { it.actions.size }
            val (kind, label) = when {
                sec.status == "FAILED" -> Site.Kind.FAILED to if (sec.preparation) "준비 실패" else "실패"
                !ran -> Site.Kind.SETUP to "안 돌림"
                sec.preparation -> Site.Kind.SETUP to "준비됨"
                else -> Site.Kind.PASSED to "통과"
            }
            val took = "%.1fs".format(steps.sumOf { it.durationMs } / 1000.0)
            val open = ran && !(sec.preparation && sec.status == "PASSED" && did == 0)
            val wide = players != null && (!open || sec.status == "FAILED")
            sb.append("<details class=\"sec ${cls(sec.status)}${if (sec.preparation) " prep" else ""}${if (wide) " wide" else ""}\"${if (players != null) " data-scope" else ""}${if (open) " open" else ""}><summary>")
            sb.append("<span class=ord>${if (sec.preparation) "⚙" else (++order).toString()}</span><span class=stitle>${esc(sec.title)}</span>${chip(kind, label)}")
            sb.append("<span class=sinfo>" + when {
                !ran -> "앞에서 실패해 돌지 않음"
                sec.preparation -> "모델이 준비 · ${if (did == 0) "한 일 없음" else "조작 ${did}번"} · $took"
                else -> "${steps.size}스텝 · $took"
            } + "</span></summary><div class=sbody>")
            if (sec.description.isNotEmpty()) sb.append("<p class=sdesc>${esc(plain(sec.description))}</p>")
            if (sec.preparation) sb.append("<div class=snote>테스트가 아니라 준비입니다. 지난 실행이 남긴 상태가 무엇이든 아래 구간이 같은 상태에서 시작하도록, 재생에서도 모델이 화면을 보고 필요한 만큼만 조작합니다.</div>")
            // the section's own player with its steps; a section that did not run has nothing to play
            val playing = players?.takeIf { ran }
            if (playing != null) sb.append("<div class=scols><div class=ssteps>")
            for (s in steps) stepRow(sb, r, s, interactive, if (s.n == filmAt) film else emptyList(), prep = sec.preparation)
            if (playing != null) {
                sb.append("</div>")
                player(sb, r, steps, playing.video, playing.thumbs, clip = true)
                sb.append("</div>")
            }
            sb.append("</div></details>")
        }
        if (players != null) sb.append("</div>")
    }

    /**
     * One step. A failed one is expanded: expected vs actual evidence, what the agent did there (a recording: the
     * actions in order and the frame after each, [film]) and logcat around the failing moment. [interactive] rows
     * drive the player; the folded replay under a failed recording is static.
     */
    private fun stepRow(sb: StringBuilder, r: ResultJson.Result, s: ResultJson.Step, interactive: Boolean = true, film: List<Frame> = emptyList(), prep: Boolean = false) {
        val icon = when (s.status) { "PASSED" -> "✓"; "FAILED" -> "✕"; else -> "–" }
        val target = s.actions.lastOrNull()?.let { it.target ?: it.tool }?.let { "→ ${esc(it)} · " } ?: ""
        val attrs = if (interactive) " data-step=${s.n}${s.videoMs?.let { " data-video-ms=$it" } ?: ""}" else ""
        sb.append("<div class=\"step ${cls(s.status)}${if (interactive) "" else " static"}\"$attrs><div class=line><span class=icon>$icon</span><div class=text><div class=mono><span class=n>${noOf(r, s)}.</span> ${stepText(s.text)}")
        s.criterion?.let { sb.append("<br><span class=criterion>:: ${esc(it)}</span>") }
        sb.append("</div><div class=meta>")
        sb.append(when {
            // a preparation step has no evidence to show: what the model did, and what it said of the state
            prep && s.status == "PASSED" -> (s.actions.takeIf { it.isNotEmpty() }?.joinToString(" → ") { esc(it.target?.let { t -> "${it.tool} $t" } ?: it.tool) } ?: "한 일 없음") +
                (s.reason?.let { " · ${esc(it)}" } ?: "") + " · " + "%.1fs".format(s.durationMs / 1000.0)
            else -> when (s.status) {
            "PASSED" -> target + (s.found.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "+ ") ?: "evidence 없음") + " · " + "%.1fs".format(s.durationMs / 1000.0)
            "FAILED" -> target + "FAILED · ${esc(s.reason ?: "")}"
            else -> "SKIPPED"
            }
        })
        sb.append("</div></div></div>")
        eventsBlock(sb, s.events, s.startedMs)
        if (s.status == "FAILED") {
            val missing = s.expected - s.found.toSet()
            if (prep) sb.append("<div class=fail><div><h4>그때 화면</h4><div class=\"mono screen\">${s.screen.take(8).joinToString("<br>") { "<span class=dim>· ${esc(it)}</span>" }.ifEmpty { "<span class=dim>-</span>" }}</div></div>")
            else {
            sb.append("<div class=fail><div class=cols2><div><h4>기대 (${if (s.mode == "llm") "시나리오" else "trace"})</h4><div class=mono>")
            sb.append(if (s.expected.isEmpty()) "<span class=dim>evidence 없음</span>" else s.expected.joinToString("<br>") { "+ ${esc(it)}" })
            sb.append("</div></div><div><h4>실제</h4><div class=\"mono actual\">")
            val actual = s.found.map { "+ ${esc(it)}" } + missing.map { "<span class=miss>· ${esc(it)} 없음</span>" } +
                s.screen.take(6).map { "<span class=dim>· ${esc(it)}</span>" }
            sb.append(if (actual.isEmpty()) "<span class=dim>-</span>" else actual.joinToString("<br>"))
            sb.append("</div></div></div>")
            }
            // a recording: the gestures the agent tried on this step, in order, and what each left on screen
            if (s.actions.isNotEmpty() || film.isNotEmpty()) {
                sb.append("<div><h4>에이전트가 한 일</h4>")
                if (s.actions.isNotEmpty()) sb.append("<div class=mono>${s.actions.joinToString(" → ") { esc(it.target?.let { t -> "${it.tool} ${t}" } ?: it.tool) }}</div>")
                if (film.isNotEmpty()) sb.append("<div class=film>${film.joinToString("") { "<figure><img src=\"${attr(it.src)}\" alt=\"${attr(it.caption)}\" loading=lazy><figcaption>${esc(it.caption)}</figcaption></figure>" }}</div>")
                sb.append("</div>")
            }
            // logcat around the failing moment: the last gesture of the step, else its start
            val at = r.startedAtMs + (s.actions.lastOrNull()?.atMs ?: s.startedMs)
            val lines = Site.logcatWindow(File(r.dir, "logcat.txt"), at - 2000, at + 2000)
            if (lines.isNotEmpty()) sb.append("<div><h4>logcat (탭 전후 2초)</h4><pre class=log>${esc(lines.joinToString("\n") { it.replaceFirst(Regex("^\\d+\\.\\d+\\s+\\d+\\s+\\d+\\s+"), "") })}</pre></div>")
            sb.append("</div>")
        }
        sb.append("</div>")
    }

    /** The app's launch events — what Firebase saw between setup and the first step — as a static row ahead of the steps. */
    private fun setupRow(sb: StringBuilder, r: ResultJson.Result) {
        if (r.setupEvents.isEmpty()) return
        sb.append("<div class=\"step static setup\"><div class=line><span class=icon>⚙</span><div class=text><div class=mono><span class=n>setup</span> 앱 시작 — 첫 스텝 전</div><div class=meta>${esc(humanDuration(r.steps.firstOrNull { it.status != "SKIPPED" }?.startedMs ?: 0))}</div></div></div>")
        eventsBlock(sb, r.setupEvents, 0)
        sb.append("</div>")
    }

    /**
     * The Firebase Analytics events of one step, folded: the summary names them (`w_view · w_exposed_cells ×2`), open
     * shows each with its offset from [fromMs] and parameters — the app's own first, the automatic `ga_*` dim; `auto`
     * events (screen_view, user_engagement) dim as a whole.
     */
    private fun eventsBlock(sb: StringBuilder, events: List<ResultJson.Event>, fromMs: Long) {
        if (events.isEmpty()) return
        val names = events.groupingBy { it.name }.eachCount().entries.joinToString(" · ") { (name, n) -> esc(name) + if (n > 1) " ×$n" else "" }
        sb.append("<details class=events><summary><span class=k>firebase ${events.size}</span> $names</summary><div class=evlist>")
        for (e in events) {
            val params = e.params.entries.sortedBy { it.key.startsWith("ga_") }.joinToString(" · ") { (k, v) ->
                val kv = "${esc(k)}=${esc(v)}"
                if (k.startsWith("ga_")) "<span class=ga>$kv</span>" else kv
            }
            sb.append("<div class=ev><span class=at>+${"%.1f".format((e.atMs - fromMs).coerceAtLeast(0) / 1000.0)}s</span><b class=\"${if (e.origin == "app") "" else "auto"}\">${esc(e.name)}</b><span class=params>$params</span></div>")
        }
        sb.append("</div></details>")
    }

    private fun stepsJson(r: ResultJson.Result, thumbs: Map<Int, String>): String = buildJsonArray {
        r.steps.filter { it.status != "SKIPPED" }.forEach { s ->
            add(buildJsonObject {
                put("n", s.n)
                // what the caption counts: the step's number in its section (the scenario, without sections)
                put("no", noOf(r, s))
                put("total", outOf(r, s))
                put("text", s.text)
                put("status", s.status)
                s.videoMs?.let { put("videoMs", it) }
                put("durationMs", s.durationMs)
                thumbs[s.n]?.let { put("thumb", it) }
            })
        }
    }.toString()

    // ------------------------------------------------------------------ page chrome

    private fun head(sb: StringBuilder, title: String, up: String) {
        sb.append("<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>").append(esc(title)).append("</title>")
        sb.append("<link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css2?family=IBM+Plex+Sans+KR:wght@400;500;600&family=IBM+Plex+Mono:wght@400;500&display=swap\">")
        sb.append("<style>").append(CSS).append("</style></head><body>")
    }

    /**
     * One player per `[data-scope]` — the page, or each section of a scenario that has them: the scope's step rows and
     * its player's bar seek its video, which plays only its own window of the mp4 (`data-from`/`data-to`, ms) and
     * shows its time from there (the metadata may be in before this runs — a local file's usually is — so the window
     * is applied either way). Starting one pauses the others.
     */
    private val JS = """
        (function(){
          function byN(n){for(var i=0;i<STEPS.length;i++)if(STEPS[i].n==n)return STEPS[i];return null}
          function fmt(ms){ms=Math.max(0,ms|0);return Math.floor(ms/60000)+':'+('0'+Math.floor(ms/1000)%60).slice(-2)}
          var videos=[];
          [].slice.call(document.querySelectorAll('[data-scope]')).forEach(function(sc){
            var pl=sc.querySelector('.player');if(!pl)return;
            var v=pl.querySelector('video'),cap=pl.querySelector('.caption'),still=pl.querySelector('img.still'),time=pl.querySelector('.time'),play=pl.querySelector('.play');
            var rows=[].slice.call(sc.querySelectorAll('.step[data-step]')),segs=[].slice.call(pl.querySelectorAll('.segments i[data-step]'));
            var mine=rows.map(function(r){return byN(+r.dataset.step)}).filter(Boolean);
            var from=pl.dataset.from?+pl.dataset.from:0,to=pl.dataset.to?+pl.dataset.to:null;
            function mark(n){rows.forEach(function(r){r.classList.toggle('on',r.dataset.step==n)});segs.forEach(function(s){s.classList.toggle('on',s.dataset.step==n)});var s=byN(n);if(s&&cap)cap.textContent=s.no+'/'+s.total+' · '+s.text;if(s&&still&&s.thumb)still.src=s.thumb}
            function seek(ms){v.currentTime=ms/1000;v.play().catch(function(){})}
            function go(n){var s=byN(n);if(!s)return;mark(n);if(!v||s.videoMs==null)return;if(v.readyState>=1)seek(s.videoMs);else v.addEventListener('loadedmetadata',function(){seek(s.videoMs)},{once:true})}
            rows.forEach(function(r){r.addEventListener('click',function(e){var x=e.target.closest('a,details,code,pre');if(x&&r.contains(x))return;go(+r.dataset.step)})});
            segs.forEach(function(s){s.addEventListener('click',function(){go(+s.dataset.step)})});
            if(mine.length)mark(mine[0].n);
            if(!v)return;
            videos.push(v);
            function end(){return to!=null?to:(v.duration*1000||0)}
            function shown(){if(time)time.textContent=fmt(v.currentTime*1000-from)+' / '+fmt(end()-from)}
            function ready(){if(v.currentTime*1000<from)v.currentTime=from/1000;shown()}
            if(v.readyState>=1)ready();else v.addEventListener('loadedmetadata',ready,{once:true});
            v.addEventListener('timeupdate',function(){var t=v.currentTime*1000,cur=null;if(to!=null&&t>=to&&!v.paused)v.pause();for(var i=0;i<mine.length;i++){if(mine[i].videoMs!=null&&mine[i].videoMs<=t+50)cur=mine[i]}if(cur)mark(cur.n);shown()});
            v.addEventListener('play',function(){play.textContent='❚❚';videos.forEach(function(o){if(o!==v)o.pause()})});
            v.addEventListener('pause',function(){play.textContent='▶'});
            function toggle(){if(!v.paused){v.pause();return}var t=v.currentTime*1000;if(t<from-50||t>=end()-100)v.currentTime=from/1000;v.play().catch(function(){})}
            play.addEventListener('click',toggle);v.addEventListener('click',toggle);
          });
        })();
    """.trimIndent()

    /** The home's table: a screen's row opens its scenarios, the search narrows the screens (opening those it leaves), `실패만` keeps the failures. */
    private val HOME_JS = """
        (function(){
          var q=document.getElementById('q'),of=document.getElementById('onlyfail'),gs=[].slice.call(document.querySelectorAll('tbody.g'));
          gs.forEach(function(g){g.querySelector('tr.srow').addEventListener('click',function(e){if(e.target.closest('a'))return;g.classList.toggle('open')})});
          if(q)q.addEventListener('input',function(){var s=q.value.trim().toLowerCase();gs.forEach(function(g){var hit=g.dataset.q.indexOf(s)>=0;g.hidden=!!s&&!hit;g.classList.toggle('hit',!!s&&hit)})});
          if(of)of.addEventListener('click',function(){of.setAttribute('aria-pressed',document.body.classList.toggle('onlyfail'))});
        })();
    """.trimIndent()

    /** A screen page's list of screens, narrowed by the search. */
    private val SCREEN_JS = """
        (function(){
          var q=document.getElementById('q'),as=[].slice.call(document.querySelectorAll('.snav a'));
          if(q)q.addEventListener('input',function(){var s=q.value.trim().toLowerCase();as.forEach(function(a){a.hidden=!!s&&a.dataset.q.indexOf(s)<0})});
        })();
    """.trimIndent()

    /**
     * The pages' one stylesheet. Every colour is a token: the palette is the dark one, and the light one where the
     * reader's system asks for light — a rule sets no colour of its own (the video's letterbox aside).
     */
    private val CSS = """
        :root{color-scheme:dark;--bg:#161614;--card:#1F1F1D;--fg:#ECECE8;--ink2:#D2D2CC;--dim:#A6A6A0;--faint:#7E7E78;--line:#2C2C29;--line2:#3B3B37;--code:#252523;--panel:#252523;--chipbg:#2C2C29;--hover:#252523;--onfg:#161614;
          --fail:#E0645B;--failfg:#F2A39C;--failbg:#3B1E1B;--onfail:#1B0C0A;--warn:#D9A040;--warnfg:#F0C678;--warnbg:#3A2C12;--info:#7FB0E8;--infobg:#1B2A3D;--ok:#5DB88F;--okfg:#9AD8BA;--okbg:#1C3328;--logbg:#0E0E0D;--logfg:#E6E6E2}
        @media(prefers-color-scheme:light){:root{color-scheme:light;--bg:#F4F4F1;--card:#FFFFFF;--fg:#1B1B1A;--ink2:#3A3A37;--dim:#5A5A56;--faint:#8A8A84;--line:#E2E2DD;--line2:#D6D6D0;--code:#F4F4F1;--panel:#ECECE7;--chipbg:#E8E8E3;--hover:#FAFAF8;--onfg:#FFFFFF;
          --fail:#B3261E;--failfg:#8A1C14;--failbg:#FBE9E7;--onfail:#FFFFFF;--warn:#D08A1A;--warnfg:#7A4A00;--warnbg:#FCEFD6;--info:#1F4E8C;--infobg:#E4EEF9;--ok:#2E7D5B;--okfg:#1F5A3C;--okbg:#E3EFE8;--logbg:#1B1B1A;--logfg:#E6E6E2}}
        *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.5 'IBM Plex Sans KR',system-ui,-apple-system,'Apple SD Gothic Neo',sans-serif}
        a{color:inherit}main{max-width:1200px;margin:0 auto;padding:28px 24px 48px;display:flex;flex-direction:column;gap:32px}main.detail{gap:24px}
        .topbar{background:var(--card);border-bottom:1px solid var(--line)}.topbar .inner{max-width:1200px;margin:0 auto;padding:0 24px;display:flex;flex-wrap:wrap;align-items:center;gap:24px;min-height:56px}
        .brand{font-weight:600;font-size:16px}.apps a{padding:6px 12px;border-radius:6px;color:var(--dim);text-decoration:none}.apps a.on{background:var(--fg);color:var(--onfg);font-weight:500}
        .crumbs{display:flex;gap:8px;color:var(--dim);font-size:13px}.crumbs b{color:var(--fg);font-weight:500}.right{margin-left:auto;display:flex;gap:12px;align-items:center;color:var(--dim);font-size:13px}
        h1{margin:0;font-size:26px;font-weight:600;letter-spacing:-.01em}h2{margin:0;font-size:17px;font-weight:600}h3{margin:14px 0 4px;font-size:14px}h4{margin:0 0 4px;font-size:11px;color:var(--faint);font-weight:600;letter-spacing:.04em}
        .kicker,.dim{color:var(--dim);font-size:13px}.mono,.code,code,pre{font-family:'IBM Plex Mono',ui-monospace,Menlo,monospace;font-size:12.5px}
        .chip{display:inline-block;font-size:11px;font-weight:600;letter-spacing:.04em;padding:2px 8px;border-radius:4px;white-space:nowrap}.chip.big{font-size:13px;padding:6px 12px;border-radius:6px}
        .chip.failed{color:var(--failfg);background:var(--failbg)}.chip.changed{color:var(--warnfg);background:var(--warnbg)}.chip.rerecord{color:var(--info);background:var(--infobg)}.chip.passed{color:var(--okfg);background:var(--okbg)}.chip.setup{color:var(--dim);background:var(--chipbg)}
        .sh{display:flex;align-items:baseline;gap:12px;flex-wrap:wrap;margin-bottom:12px}
        .strip i{display:inline-block;width:10px;height:10px;border-radius:2px;background:var(--line2)}.strip{display:inline-flex;gap:3px;vertical-align:middle}.strip.wide i{width:16px;height:14px}.strip i.passed{background:var(--ok)}.strip i.changed{background:var(--warn)}.strip i.failed{background:var(--fail)}.strip i.none,.strip i.skipped{background:var(--line2)}
        .reason{color:var(--failfg)}.q{font-weight:600}ol.st li .q{color:var(--fg)}ol.st li.failed .q,ol.st li.skipped .q{color:inherit}
        .owner{display:inline-flex;align-items:center;gap:6px}.avatar{width:20px;height:20px;border-radius:50%;background:var(--dim);color:var(--onfg);font-size:10px;display:inline-flex;align-items:center;justify-content:center;font-weight:600}
        .btns{display:flex;gap:8px;flex-wrap:wrap}.btn{flex:1;text-align:center;padding:7px 10px;border-radius:6px;border:1px solid var(--line2);background:var(--card);color:var(--fg);font-size:13px;text-decoration:none;cursor:pointer;white-space:nowrap}.btn.primary{border-color:var(--fg);background:var(--fg);color:var(--onfg);font-weight:500}
        .dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:var(--line2);flex-shrink:0}.dot.failed{background:var(--fail)}.dot.passed{background:var(--ok)}
        main.detail header{display:flex;flex-direction:column;gap:14px}main.detail header .row{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.desc{margin:0;max-width:760px;color:var(--ink2);line-height:1.7}
        .tabs{display:flex;gap:4px;background:var(--chipbg);padding:3px;border-radius:8px}.tabs a{padding:6px 14px;border-radius:6px;color:var(--dim);font-size:13px;display:flex;align-items:center;gap:6px;text-decoration:none}.tabs a[aria-selected=true]{background:var(--card);color:var(--fg);font-weight:500}
        .cols{display:flex;flex-wrap:wrap;gap:20px;align-items:flex-start}section.steps{flex:1 1 560px;min-width:0;display:flex;flex-direction:column;gap:10px}aside.replay{flex:0 1 380px;min-width:300px;display:flex;flex-direction:column;gap:10px;position:sticky;top:12px}
        section.steps h2,aside.replay h2{font-size:13px;color:var(--dim);letter-spacing:.04em;margin-bottom:4px}section.steps h2 .hint{margin-left:12px;font-weight:400;font-size:12px;letter-spacing:0}
        .secgrid{display:grid;grid-template-columns:repeat(auto-fill,minmax(264px,1fr));gap:12px;align-items:start}.secgrid details.sec{margin:0}.secgrid details.sec.wide{grid-column:1/-1}
        .scols{display:flex;flex-direction:column;gap:10px}.ssteps{min-width:0;display:flex;flex-direction:column;gap:8px}.scols .player{order:-1;padding:10px;gap:8px}.scols .caption{font-size:10.5px}.scols .play{width:28px;height:28px}
        .sec.wide .scols{flex-direction:row;flex-wrap:wrap;gap:14px;align-items:flex-start}.sec.wide .ssteps{flex:1 1 320px;gap:10px}.sec.wide .scols .player{order:0;flex:0 0 260px}
        .secgrid .sec:not(.wide) .step{padding:10px}.secgrid .sec:not(.wide) .step .line{gap:8px}.secgrid .sec:not(.wide) .sinfo{margin-left:0;flex-basis:100%;text-align:left;order:9}
        .whole{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-top:4px}.whole .btn{flex:0 0 auto}
        .step{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:12px 14px;cursor:pointer}.step.on{border-color:var(--fg);box-shadow:0 0 0 1px var(--fg)}.step.skipped{opacity:.55;cursor:default;border-style:dashed;border-color:var(--line2)}.step.failed{border-color:var(--fail);box-shadow:0 0 0 3px var(--failbg)}.step.static{cursor:default}
        .step .line{display:flex;gap:12px}.step .icon{flex-shrink:0;width:22px;height:22px;border-radius:50%;display:inline-flex;align-items:center;justify-content:center;font-size:12px;font-weight:700;background:var(--okbg);color:var(--okfg)}.step.failed .icon{background:var(--fail);color:var(--onfail)}.step.skipped .icon{background:var(--chipbg);color:var(--faint)}
        .step .text{flex:1;min-width:0;display:flex;flex-direction:column;gap:3px}.step .n{color:var(--faint)}.criterion{color:var(--dim)}.step .meta{font-size:12px;color:var(--dim)}.step.failed .meta{color:var(--failfg);font-weight:500}
        .fail{margin-top:12px;padding-left:34px;display:flex;flex-direction:column;gap:12px;cursor:auto}.cols2{display:grid;grid-template-columns:1fr 1fr;gap:12px}.cols2>div{display:flex;flex-direction:column;gap:6px}.cols2 .mono{background:var(--code);border-radius:6px;padding:8px 10px;line-height:1.6}.cols2 .mono.actual{background:var(--failbg)}.miss{color:var(--failfg)}.fail .mono.screen{background:var(--code);border-radius:6px;padding:8px 10px;line-height:1.6}
        .film{display:flex;gap:8px;overflow-x:auto;padding:4px 0}.film figure{margin:0;flex:0 0 72px;display:flex;flex-direction:column;gap:3px;font-size:10px;color:var(--dim);text-align:center}.film img{display:block;width:100%;border-radius:6px;border:1px solid var(--line2)}
        .progress{display:flex;align-items:center;gap:10px;flex-wrap:wrap;font-size:12px}
        details.replaysec{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px 16px}details.replaysec summary{cursor:pointer;font-weight:500;font-size:13px}details.replaysec .steps{display:flex;flex-direction:column;gap:10px;margin-top:12px}
        .step.setup{cursor:default;border-style:dashed;border-color:var(--line2)}.step.setup .icon{background:var(--chipbg);color:var(--dim)}
        details.events{margin:8px 0 0 34px;cursor:auto}details.events summary{cursor:pointer;font-size:12px;color:var(--dim);list-style:none;display:flex;gap:8px;align-items:baseline;font-family:'IBM Plex Mono',ui-monospace,Menlo,monospace}details.events summary::-webkit-details-marker{display:none}details.events summary .k{color:var(--faint);flex-shrink:0}details.events summary .k::before{content:'▸ '}details.events[open] summary .k::before{content:'▾ '}
        .evlist{margin-top:6px;background:var(--code);border-radius:6px;padding:8px 10px;display:flex;flex-direction:column;gap:3px}.ev{display:flex;gap:10px;align-items:baseline;font-family:'IBM Plex Mono',ui-monospace,Menlo,monospace;font-size:11.5px;line-height:1.5}.ev .at{color:var(--faint);min-width:44px;text-align:right;flex-shrink:0}.ev b{font-weight:500;flex-shrink:0}.ev b.auto{color:var(--faint);font-weight:400}.ev .params{color:var(--dim);overflow-wrap:anywhere}.ev .params .ga{color:var(--faint)}
        pre.log{margin:0;background:var(--logbg);color:var(--logfg);padding:10px 12px;border-radius:6px;font-size:11.5px;line-height:1.5;overflow:auto;max-height:260px;white-space:pre-wrap}
        .actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.actions code{background:var(--code);padding:6px 10px;border-radius:6px}.actions .btn{flex:0 0 auto}
        .player{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px;display:flex;flex-direction:column;gap:12px}.frame{position:relative;width:100%;aspect-ratio:9/16;max-height:560px;border-radius:10px;background:var(--chipbg);border:1px solid var(--line2);overflow:hidden;display:flex;align-items:center;justify-content:center}
        .frame video,.frame img{width:100%;height:100%;object-fit:contain;background:#000}.caption{margin-top:-4px;color:var(--dim);font-family:'IBM Plex Mono',monospace;font-size:11px;line-height:1.5;overflow-wrap:anywhere}
        .controls{display:flex;align-items:center;gap:10px}.play{width:32px;height:32px;border-radius:50%;border:0;background:var(--fg);color:var(--onfg);cursor:pointer;font-size:11px;flex-shrink:0}
        .segments{flex:1;display:flex;gap:3px}.segments i{flex:1;height:8px;border-radius:4px;background:var(--line2);cursor:pointer;min-width:6px}.segments i.passed{background:var(--ok)}.segments i.failed{background:var(--fail)}.segments i.skipped{cursor:default}.segments i.on{outline:2px solid var(--fg);outline-offset:1px}
        .time{font-family:'IBM Plex Mono',monospace;font-size:12px;color:var(--dim)}
        details.sec{background:var(--panel);border:1px solid var(--line2);border-radius:12px;padding:2px 12px}details.sec+details.sec{margin-top:4px}details.sec.failed{border-color:var(--fail)}details.sec.prep{background:transparent;border-style:dashed}
        details.sec>summary{cursor:pointer;list-style:none;display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:10px 0}details.sec>summary::-webkit-details-marker{display:none}
        details.sec>summary::after{content:'▸';color:var(--dim);font-size:13px;width:12px;flex-shrink:0}details.sec[open]>summary::after{content:'▾'}
        .sec .ord{flex-shrink:0;width:24px;height:24px;border-radius:50%;background:var(--fg);color:var(--onfg);font-size:12px;font-weight:600;display:inline-flex;align-items:center;justify-content:center}.sec.prep .ord{background:var(--line2);color:var(--dim)}.sec.skipped .ord{background:var(--line2);color:var(--dim)}.sec.failed:not(.prep) .ord{background:var(--fail)}
        .sec .stitle{font-weight:600;font-size:15px}.sec.skipped .stitle,.sec.prep .stitle{color:var(--dim)}.sec .sinfo{margin-left:auto;font-size:12px;color:var(--dim);text-align:right}
        .sec .sbody{display:flex;flex-direction:column;gap:10px;padding:0 0 10px}.sec .sdesc{margin:0 2px;color:var(--ink2);font-size:13.5px;line-height:1.6}
        .sec .snote{font-size:12.5px;line-height:1.6;color:var(--dim);margin:0 2px}
        footer{max-width:1200px;margin:0 auto;padding:0 24px 24px;font-size:12px}
        .gate{display:flex;flex-direction:column;gap:10px;background:var(--card);border:1px solid var(--line);border-radius:12px;padding:18px 20px}.gate .grow{display:flex;align-items:center;gap:14px;flex-wrap:wrap}.gate h1{font-size:30px}.gate.failed h1{color:var(--fail)}.gate.passed h1{color:var(--ok)}.gate .chips{display:flex;gap:8px;flex-wrap:wrap}
        .gsum{color:var(--dim);font-size:13px}.gcells{display:flex;flex-wrap:wrap;gap:4px 9px}.gcells i{height:16px}
        .cells{display:inline-flex;flex-wrap:wrap;gap:3px 5px;vertical-align:middle}.cells span{display:inline-flex;gap:1px}.cells i{display:block;width:8px;height:13px;border-radius:2px;background:var(--line2)}.cells i.passed{background:var(--ok)}.cells i.failed{background:var(--fail)}
        .ic{flex-shrink:0;width:18px;height:18px;border-radius:50%;display:inline-flex;align-items:center;justify-content:center;font-size:11px;font-weight:700;font-style:normal;background:var(--okbg);color:var(--okfg)}.ic.failed{background:var(--fail);color:var(--onfail)}.ic.skipped{background:var(--chipbg);color:var(--faint)}
        code.e{background:var(--code);padding:1px 6px;border-radius:4px;font-size:12px;overflow-wrap:anywhere}.bad{color:var(--failfg);font-weight:600}.note{font-size:13px}
        .todo{background:var(--card);border:1px solid var(--line);border-radius:10px;overflow:hidden}.frow{display:grid;grid-template-columns:52px minmax(0,1fr) auto;gap:14px;padding:12px 16px;border-top:1px solid var(--line);align-items:start}.frow:first-child{border-top:0}
        .frow .shot{display:block;width:52px;border-radius:6px;overflow:hidden;border:1px solid var(--line2);background:var(--chipbg)}.frow .shot img{display:block;width:100%}.frow .shot.empty{aspect-ratio:9/19}.frow .shot.none{border:0;background:none}
        .frow .body{display:flex;flex-direction:column;gap:4px;min-width:0}.frow .mono{overflow-wrap:anywhere}.path{display:flex;align-items:center;gap:6px;flex-wrap:wrap;color:var(--dim);font-size:13px}.path a{text-decoration:none}.path a:hover{text-decoration:underline}.path .title{color:var(--fg);font-weight:600;font-size:14.5px}
        .frow .side{display:flex;flex-direction:column;align-items:flex-end;gap:3px;font-size:12.5px;color:var(--dim);text-align:right}
        .tools{margin-left:auto;display:flex;gap:8px;align-items:center}.tools input,.snav input{font:inherit;font-size:13px;padding:6px 10px;border:1px solid var(--line2);border-radius:6px;background:var(--card);color:var(--fg);width:220px;max-width:100%}
        .tog{font:inherit;font-size:13px;padding:5px 12px;border-radius:14px;border:1px solid var(--line2);background:var(--card);color:var(--dim);cursor:pointer}.tog[aria-pressed=true]{background:var(--fg);border-color:var(--fg);color:var(--onfg)}
        .tw{overflow-x:auto;background:var(--card);border:1px solid var(--line);border-radius:10px}table.scr{border-collapse:collapse;width:100%;font-size:13px}table.scr th{font-size:11px;font-weight:600;letter-spacing:.04em;color:var(--faint);text-align:left;padding:8px 14px;white-space:nowrap}table.scr td{padding:9px 14px;border-top:1px solid var(--line);vertical-align:top}
        table.scr .r{text-align:right;white-space:nowrap;font-variant-numeric:tabular-nums}tbody.g[hidden]{display:none}tr.srow{cursor:pointer}tr.srow:hover td{background:var(--hover)}tr.srow td:first-child{white-space:nowrap;font-weight:600}tr.srow a{text-decoration:none}tr.srow a:hover{text-decoration:underline}
        tr.srow td:first-child::before{content:'▸';color:var(--faint);font-size:11px;display:inline-block;width:16px}tbody.g.open tr.srow td:first-child::before,tbody.g.hit tr.srow td:first-child::before{content:'▾'}
        tr.sub{display:none}tbody.g.open tr.sub,tbody.g.hit tr.sub{display:table-row}tr.sub td{background:var(--bg)}tr.sub td:first-child{padding-left:30px}tr.sub td:first-child .dot{margin-right:8px}tr.sub a{text-decoration:none}tr.sub a:hover{text-decoration:underline}
        .onlyfail tbody.g:not(.failed){display:none}.onlyfail tbody.g.failed tr.sub{display:none}.onlyfail tbody.g.failed tr.sub.failed{display:table-row}
        .cl{display:flex;flex-direction:column;gap:5px}.cl>span{display:grid;grid-template-columns:18px minmax(0,1fr);gap:8px}.cl b{font-weight:600}.cl .failed b{color:var(--failfg)}.cl .skipped{color:var(--dim)}
        main.screen{display:grid;grid-template-columns:220px minmax(0,1fr);gap:28px;align-items:start}.snav{position:sticky;top:12px;max-height:calc(100vh - 24px);overflow:auto;display:flex;flex-direction:column;gap:2px}.snav input{width:100%;margin-bottom:8px}
        .snav a{display:flex;align-items:center;gap:8px;padding:5px 8px;border-radius:6px;text-decoration:none;font-size:13.5px}.snav a[hidden]{display:none}.snav a.on{background:var(--card);box-shadow:inset 0 0 0 1px var(--line2);font-weight:500}.snav .nm{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.snav .c{font-size:12px;color:var(--faint);font-variant-numeric:tabular-nums}
        .doc{min-width:0;max-width:860px;display:flex;flex-direction:column;gap:22px}.shead{display:flex;gap:18px;align-items:flex-start;padding-bottom:18px;border-bottom:1px solid var(--line2)}.shead img{width:96px;border-radius:10px;border:1px solid var(--line2);flex-shrink:0}.shead .b{display:flex;flex-direction:column;gap:6px;min-width:0}
        .eyebrow{font-size:11px;font-weight:600;letter-spacing:.06em;color:var(--faint)}.ents{display:flex;flex-direction:column;gap:3px}.entry{display:inline-flex;flex-wrap:wrap;align-items:center;gap:4px 10px;font-size:12.5px;color:var(--dim)}
        .blk{display:flex;flex-direction:column;gap:8px;min-width:0}.blk>h3{margin:0;font-size:12px;font-weight:600;letter-spacing:.05em;color:var(--faint)}.blk>h3 .hn{font-weight:400;letter-spacing:0;margin-left:8px}.blk>h3 code{letter-spacing:0;font-weight:400}
        .ways{border:1px solid var(--line);border-radius:10px;font-size:13px;color:var(--dim)}.in{display:grid;grid-template-columns:18px minmax(0,280px) minmax(0,1fr);gap:4px 10px;align-items:start;padding:8px 14px;border-top:1px solid var(--line)}.in:first-child{border-top:0}
        .in .ip{color:var(--faint);min-width:0;text-decoration:none}.in a.ip:hover{text-decoration:underline}.in .it{min-width:0}.in.failed{color:var(--fg)}.in.failed .ip{color:var(--failfg);font-weight:600}.in .more{margin-left:8px;font-size:12px;color:var(--faint)}
        .ways .ic.passed{background:none;color:var(--ok)}.ic.none{background:none;color:var(--faint)}.st2{color:var(--faint);margin-left:8px}
        .mn{font-weight:500;text-decoration:underline;text-decoration-color:var(--line2);text-underline-offset:3px;white-space:nowrap}a.mn:hover{text-decoration-color:var(--fg)}.mn.here{text-decoration:none;font-weight:600}
        .to{display:inline-block;margin-left:8px;font-size:12px;font-weight:400;color:var(--dim);background:var(--chipbg);border-radius:4px;padding:1px 7px;white-space:nowrap}code.dl{margin-left:6px;color:var(--dim)}
        .fl{border-top:1px solid var(--line2);border-bottom:1px solid var(--line2)}details.ft{border-top:1px solid var(--line);scroll-margin-top:12px}details.ft:first-child{border-top:0}
        details.ft>summary,details.ck>summary{cursor:pointer;list-style:none}details.ft>summary::-webkit-details-marker,details.ck>summary::-webkit-details-marker{display:none}
        details.ft>summary{display:grid;grid-template-columns:18px 160px minmax(0,1fr) auto;gap:6px 12px;align-items:start;padding:10px 0}.fn{font-size:14.5px;font-weight:600;min-width:0}.ft.failed .fn{color:var(--failfg)}
        .fs{color:var(--ink2);max-width:64ch;min-width:0}.fs.titles{color:var(--dim)}.fs.titles .failed{color:var(--failfg);font-weight:600}.fs.titles .skipped{color:var(--faint)}.fs .chip{margin-left:8px}
        .fb{padding:0 0 12px 30px;display:flex;flex-direction:column;gap:2px}.fb>ol.st{margin-left:0}.fb p.reason,.fb p.dim{margin:0 0 4px}.fm{font-size:12px;color:var(--faint);margin-top:4px}
        .spd{margin:0 0 4px;color:var(--ink2);max-width:70ch;line-height:1.65}.prep{margin:0 0 4px;font-size:12.5px;color:var(--faint);border-left:2px dashed var(--line2);padding-left:10px}
        details.ck>summary,.ck.flat{display:grid;grid-template-columns:18px minmax(0,1fr) auto;gap:10px;align-items:start;padding:4px 0}
        .ckt{max-width:68ch}.ckt b{font-weight:600;margin-right:8px}.ck.failed .ckt b{color:var(--failfg)}.ck.skipped .ckt{color:var(--dim)}.ckn{font-size:12px;color:var(--faint);white-space:nowrap}.ckn::after{content:' ▸'}details[open]>summary>.ckn::after{content:' ▾'}
        ol.st{margin:4px 0 10px 28px;padding:10px 12px 10px 34px;background:var(--panel);border-radius:8px;font-size:13px;color:var(--dim);display:flex;flex-direction:column;gap:3px}ol.st li.v{color:var(--fg);font-weight:500}ol.st li.failed{color:var(--failfg)}ol.st li.skipped{color:var(--faint)}
        ol.st .why{display:inline-block;font-weight:400;font-size:12.5px;background:var(--failbg);border-radius:4px;padding:2px 8px;margin-top:2px}
        @media(max-width:900px){aside.replay{position:static}main.screen{grid-template-columns:minmax(0,1fr)}.snav{position:static;max-height:none}.frow{grid-template-columns:52px minmax(0,1fr)}.frow .side{grid-column:2;align-items:flex-start;text-align:left}.tools{margin-left:0}}
        @media(max-width:640px){main{padding:20px 16px 40px}.cols2{grid-template-columns:1fr}.in{grid-template-columns:18px minmax(0,1fr)}.in .it{grid-column:2}
          details.ft>summary{grid-template-columns:18px minmax(0,1fr) auto}details.ft>summary>.fs{grid-column:2/-1;grid-row:2}.fb{padding-left:0}ol.st{margin-left:0}}
    """.trimIndent()
}
