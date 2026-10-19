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
 *  1. Home: `index.html` — header with status chips, the failure cards (failed step, reason, screenshot),
 *     setup failures, a card per feature, the pass line and the full list.
 *  2. Scenario detail: `<feature>/<name>.<state>.html` — the md prose, the states, the steps (the failed
 *     one expanded with expected/found evidence and logcat) beside the replay: a `<video>` of replay.mp4
 *     with a step-coloured progress bar; clicking a step or a segment seeks the video to that step's
 *     `video_ms`. Without a recording the frame shows the step's screenshot instead.
 *
 * Pages are self-contained apart from their assets beside them: `<name>.<state>/replay.mp4` and
 * `steps/NN.jpg` (downscaled screenshots). Built inside the run directory itself (`simul run --runs`
 * does this; the page's asset directory *is* the scenario's directory) nothing is copied but the JPEGs.
 */
object SitePages {

    class Options(
        /** What the pages call the app (`--title`); default the package name from run.json. */
        val appName: String? = null,
        val thumbWidth: Int = 360,
    )

    class Built(val index: File, val pages: List<File>, val rows: List<Site.Row>) {
        val failed get() = rows.count { it.kind == Site.Kind.FAILED }
        /** `실패 1 · 안 돌림 1 · 통과 7` */
        fun counts(): String = Site.Kind.entries.mapNotNull { k -> rows.count { it.kind == k }.let { n -> if (n > 0 || k == Site.Kind.PASSED) "${k.label} $n" else null } }.joinToString(" · ")
    }

    fun build(runDir: File, out: File, opts: Options = Options()): Built {
        val run = Site.load(runDir)
        val rows = Site.classify(run)
        out.mkdirs()
        val pages = rows.map { row -> renderDetail(run, row, rows, out, opts) }
        val index = File(out, "index.html").apply { writeText(renderHome(run, rows, opts)) }
        return Built(index, pages, rows)
    }

    // ------------------------------------------------------------------ helpers

    private fun esc(s: String) = escapeHtml(s)
    private fun attr(s: String) = esc(s).replace("'", "&#39;")

    /** `10/19` from `2026-10-19`. */
    private fun md(date: String?): String = date?.takeIf { it.length >= 10 }?.let { "${it.substring(5, 7).trimStart('0')}/${it.substring(8, 10).trimStart('0')}" } ?: "?"
    /** `02:14` from an ISO stamp. */
    private fun hm(iso: String?): String = iso?.takeIf { it.length >= 16 }?.substring(11, 16) ?: ""
    private fun clock(ms: Long): String = "${ms / 60000}:${"%02d".format((ms / 1000) % 60)}"
    private fun up(feature: String) = if (feature.isEmpty()) "" else "../".repeat(feature.count { it == '/' } + 1)
    private fun pageOf(r: ResultJson.Result) = r.id + ".html"
    private fun chip(kind: Site.Kind, text: String = kind.label) = "<span class=\"chip ${kind.cls}\">${esc(text)}</span>"
    private fun avatar(owner: String?) = owner?.let { "<span class=owner><span class=avatar>${esc(it.take(1).uppercase())}</span>${esc(it)}</span>" } ?: ""
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

    /** Copy a result's media next to its page: `<out>/<id>/replay.mp4`, `steps/NN.jpg`; returns the relative thumbnail per step n. */
    private fun copyAssets(r: ResultJson.Result, out: File, opts: Options, sub: String = "steps"): Map<Int, String> {
        val dir = File(out, r.id)
        val thumbs = LinkedHashMap<Int, String>()
        for (s in r.steps) {
            val png = s.screenshot?.let { File(r.dir, it) }?.takeIf { it.isFile } ?: continue
            val rel = "${r.key}/$sub/${s.n.toString().padStart(2, '0')}.jpg"
            if (writeJpeg(png, File(dir, "$sub/${s.n.toString().padStart(2, '0')}.jpg"), opts.thumbWidth)) thumbs[s.n] = rel
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

    // ------------------------------------------------------------------ home

    fun renderHome(run: Site.Run, rows: List<Site.Row>, opts: Options): String {
        val counts = rows.groupingBy { it.kind }.eachCount()
        val app = opts.appName ?: run.app ?: "app"
        val build = run.results.firstNotNullOfOrNull { it.appVersion } ?: run.summary?.appVersionName
        val failed = rows.filter { it.kind == Site.Kind.FAILED }
        val setup = rows.filter { it.kind == Site.Kind.SETUP }
        val passed = rows.filter { it.kind == Site.Kind.PASSED }
        val byFeature = rows.groupBy { it.result.feature }
        val features = byFeature.keys.sortedWith(compareBy({ byFeature.getValue(it).minOf { r -> r.kind.ordinal } }, { it }))
        val sb = StringBuilder()
        head(sb, "$app nightly ${run.date}", "")
        sb.append("<div class=topbar><div class=inner><span class=brand>simul</span><nav class=apps><a class=on href=\"index.html\">${esc(app)}</a></nav></div></div><main>")

        // ---- header
        sb.append("<header class=home><div><div class=kicker>${esc(run.date)} ${esc(hm(run.summary?.startedAt ?: run.results.firstOrNull()?.startedAt))}</div><h1>${esc(app)}${build?.let { " ${esc(it)}" } ?: ""}</h1></div><div class=chips>")
        for (k in Site.Kind.entries) {
            val n = counts[k] ?: 0
            if (n == 0 && k != Site.Kind.PASSED) continue
            sb.append("<span class=\"chip big ${k.cls}\">${k.label} $n</span>")
        }
        sb.append("</div></header>")

        // ---- failures
        val diffs = failed + setup
        sb.append("<section><div class=sh><h2>실패</h2><span class=dim>${diffs.size}건</span></div>")
        if (diffs.isEmpty()) sb.append("<p class=dim>전부 통과 ${passed.size} — 끝까지 재생됨.</p>")
        if (failed.isNotEmpty()) {
            sb.append("<div class=\"cards two\">")
            for (row in failed) failureCard(sb, row)
            sb.append("</div>")
        }
        if (setup.isNotEmpty()) {
            sb.append("<div class=\"cards three\">")
            for (row in setup) setupCard(sb, row)
            sb.append("</div>")
        }
        sb.append("</section>")

        // ---- per feature
        sb.append("<section><div class=sh><h2>기능별 상태</h2><span class=dim>${features.size}개 기능 · ${rows.size}개 시나리오</span></div><div class=\"cards four\">")
        features.forEachIndexed { i, f ->
            if (i == 8) sb.append("</div><details class=more><summary>나머지 ${features.size - 8}개 기능 펼치기</summary><div class=\"cards four\">")
            featureCard(sb, f, byFeature.getValue(f))
        }
        sb.append("</div>")
        if (features.size > 8) sb.append("</details>")
        sb.append("</section>")

        // ---- the pass line + the full list
        val wall = run.summary?.durationMs ?: rows.sumOf { it.result.durationMs }
        sb.append("<section class=passline><span class=\"chip passed\">통과 ${passed.size}</span><span class=dim>끝까지 재생됨</span><span class=right>총 소요 ${esc(humanDuration(wall))} · 에뮬레이터 1대 · <a href=\"#all\">전체 목록</a></span></section>")
        sb.append("<details id=all class=all><summary>전체 목록</summary>")
        for (f in features) {
            sb.append("<h3>${esc(f.ifEmpty { "(root)" })}</h3><ul>")
            for (row in byFeature.getValue(f).sortedBy { it.kind.ordinal }) {
                sb.append("<li><i class=\"dot ${row.kind.cls}\"></i><a href=\"${attr(pageOf(row.result))}\">${esc(row.result.title)}</a> <span class=dim>${esc(row.result.key)}${row.result.state?.let { "" } ?: ""} · ${row.kind.label}</span></li>")
            }
            sb.append("</ul>")
        }
        sb.append("</details>")
        sb.append("</main><footer class=dim>generated by simul $AGENT_VERSION</footer></body></html>\n")
        return sb.toString()
    }

    private fun failureCard(sb: StringBuilder, row: Site.Row) {
        val r = row.result
        val step = r.failedStepOf
        val thumb = step?.n?.let { "${r.id}/steps/${it.toString().padStart(2, '0')}.jpg" }
        sb.append("<article class=\"card fail\">")
        if (thumb != null) sb.append("<a class=shot href=\"${attr(pageOf(r))}\"><img src=\"${attr(thumb)}\" alt=\"실패 시점 스크린샷\" loading=lazy></a>")
        else sb.append("<div class=\"shot empty\">실패 시점<br>스크린샷 없음</div>")
        sb.append("<div class=body><div class=row>${chip(Site.Kind.FAILED, "FAILED")}<span class=dim>${esc(r.feature.ifEmpty { "(root)" })}${r.state?.let { " · ${esc(it)}" } ?: ""}</span></div>")
        sb.append("<div class=title><a href=\"${attr(pageOf(r))}\">${esc(r.title)}</a></div>")
        if (step != null) sb.append("<div class=mono>${step.n}. ${esc(step.text)}<br><span class=reason>${esc(step.reason ?: "failed")}</span></div>")
        sb.append("<div class=foot><span class=dim>${esc(hm(r.startedAt))} · ${esc(humanDuration(r.durationMs))}</span>${avatar(r.owner)}<a class=more href=\"${attr(pageOf(r))}\">상세</a></div></div></article>")
    }

    private fun setupCard(sb: StringBuilder, row: Site.Row) {
        val r = row.result
        sb.append("<article class=\"card none\"><div class=row>${chip(Site.Kind.SETUP)}<span class=dim>${esc(r.feature.ifEmpty { "(root)" })}${r.state?.let { " · ${esc(it)}" } ?: ""}</span></div>")
        sb.append("<div class=title><a href=\"${attr(pageOf(r))}\">${esc(r.title)}</a></div><div class=note>${esc(r.reason ?: "setup failed")} — 앱 판정이 아니라 환경 문제입니다.</div></article>")
    }

    private fun featureCard(sb: StringBuilder, f: String, rows: List<Site.Row>) {
        val worst = rows.minBy { it.kind.ordinal }.kind
        val n = rows.count { it.kind == worst }
        val label = when (worst) { Site.Kind.FAILED -> "$n 실패"; Site.Kind.SETUP -> "안 돌림 $n"; Site.Kind.PASSED -> "통과" }
        val states = rows.mapNotNull { it.result.state }.distinct()
        sb.append("<a class=\"card feature ${worst.cls}\" href=\"#all\"><div class=row><span class=name>${esc(f.ifEmpty { "(root)" })}</span>${chip(worst, label)}</div>")
        sb.append("<div class=dim>${rows.size} 시나리오${if (states.isNotEmpty()) " · ${esc(states.joinToString(" "))}" else ""}</div></a>")
    }

    // ------------------------------------------------------------------ detail

    private fun renderDetail(run: Site.Run, row: Site.Row, all: List<Site.Row>, out: File, opts: Options): File {
        val r = row.result
        val app = opts.appName ?: run.app ?: "app"
        val thumbs = copyAssets(r, out, opts)
        val video = copyVideo(r, out)
        val u = up(r.feature)
        val executed = r.steps.filter { it.status != "SKIPPED" }
        val passedN = r.steps.count { it.status == "PASSED" }
        val sb = StringBuilder()
        head(sb, r.title, u)
        sb.append("<div class=topbar><div class=inner><span class=brand>simul</span><nav class=crumbs><a href=\"${u}index.html\">${esc(app)}</a><span>/</span><span>${esc(r.feature.ifEmpty { "(root)" })}</span><span>/</span><b>${esc(r.name)}</b></nav><span class=right>${esc(run.date)}</span></div></div><main class=detail>")

        // ---- header
        val statusText = when (r.status) { "PASSED" -> "PASSED · $passedN/${r.steps.size}"; "FAILED" -> "FAILED · ${r.failedStep}/${r.steps.size}"; else -> "SETUP · 안 돌림" }
        sb.append("<header><div class=row><h1>${esc(r.title)}</h1>${chip(row.kind, statusText)}")
        sb.append("<span class=right>${avatar(r.owner)}${if (r.owner != null) " · " else ""}${esc(hm(r.startedAt))} · ${esc(humanDuration(r.durationMs))}</span></div>")
        if (r.description.isNotEmpty()) sb.append("<p class=desc>${esc(r.description)}</p>")
        sb.append("<div class=row>")
        val siblings = all.filter { it.result.feature == r.feature && it.result.name == r.name }
        if (siblings.size > 1 || r.state != null) {
            sb.append("<span class=tabs role=tablist>")
            for (s in siblings) sb.append("<a role=tab aria-selected=\"${s.id == r.id}\" href=\"${attr(u + pageOf(s.result))}\"><i class=\"dot ${s.kind.cls}\"></i>${esc(s.result.state ?: "default")}</a>")
            sb.append("</span>")
        }
        sb.append("<span class=right><span class=dim>${esc(".simul/scenarios/${r.file}")}</span></span></div></header>")

        sb.append("<div class=cols><section class=steps><h2>STEPS</h2>")
        for (s in r.steps) stepRow(sb, r, s)
        sb.append("</section>")

        // ---- the replay
        sb.append("<aside class=replay><h2>REPLAY</h2><div class=player>")
        sb.append("<div class=frame>")
        if (video != null) sb.append("<video id=v src=\"${attr(video)}\" preload=metadata playsinline></video>")
        else {
            val first = executed.firstOrNull()?.let { thumbs[it.n] }
            sb.append(if (first != null) "<img id=still src=\"${attr(first)}\" alt=\"스텝 화면\">" else "<span class=dim>녹화 없음 (--video)</span>")
        }
        sb.append("<div class=caption id=cap>${executed.firstOrNull()?.let { "${it.n}/${r.steps.size} · ${esc(it.text)}" } ?: ""}</div></div>")
        sb.append("<div class=controls>")
        if (video != null) sb.append("<button type=button id=play aria-label=\"재생\">▶</button>")
        sb.append("<span class=segments id=segs>")
        val total = executed.sumOf { it.durationMs }.coerceAtLeast(1)
        for (s in executed) sb.append("<i class=\"${s.status.lowercase()}\" data-step=${s.n} style=\"flex:${(s.durationMs * 1000 / total).coerceAtLeast(20)}\" title=\"${s.n}. ${attr(s.text)}\"></i>")
        sb.append("</span>")
        if (video != null) sb.append("<span class=time id=time>0:00 / ${clock(r.video?.durationMs ?: 0)}</span>")
        sb.append("</div><div class=dim>${if (video != null) "스텝이나 막대를 클릭하면 영상이 그 스텝으로 이동" else "스텝을 클릭하면 그 스텝의 화면"}</div><div class=btns>")
        if (video != null) sb.append("<a class=btn href=\"${attr(video)}\" download>mp4 다운로드</a>")
        sb.append("</div></div></aside></div>")

        // ---- the step timeline for the player
        sb.append("<script>const STEPS=").append(stepsJson(r, thumbs)).append(";").append(JS).append("</script>")
        sb.append("</main><footer class=dim>generated by simul $AGENT_VERSION</footer></body></html>\n")
        val file = File(out, pageOf(r))
        file.parentFile?.mkdirs()
        file.writeText(sb.toString())
        return file
    }

    private fun stepRow(sb: StringBuilder, r: ResultJson.Result, s: ResultJson.Step) {
        val cls = s.status.lowercase()
        val icon = when (s.status) { "PASSED" -> "✓"; "FAILED" -> "✕"; else -> "–" }
        sb.append("<div class=\"step $cls\" data-step=${s.n}${s.videoMs?.let { " data-video-ms=$it" } ?: ""}><div class=line><span class=icon>$icon</span><div class=text><div class=mono><span class=n>${s.n}.</span> ${esc(s.text)}")
        s.criterion?.let { sb.append("<br><span class=criterion>:: ${esc(it)}</span>") }
        sb.append("</div><div class=meta>")
        sb.append(when (s.status) {
            "PASSED" -> (s.found.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "+ ") ?: "evidence 없음") + " · " + "%.1fs".format(s.durationMs / 1000.0)
            "FAILED" -> "FAILED · ${esc(s.reason ?: "")}"
            else -> "SKIPPED"
        })
        sb.append("</div></div></div>")
        if (s.status == "FAILED") {
            val missing = s.expected - s.found.toSet()
            sb.append("<div class=fail><div class=cols2><div><h4>기대 (trace)</h4><div class=mono>")
            sb.append(if (s.expected.isEmpty()) "<span class=dim>evidence 없음</span>" else s.expected.joinToString("<br>") { "+ ${esc(it)}" })
            sb.append("</div></div><div><h4>실제</h4><div class=mono>")
            val actual = s.found.map { "+ ${esc(it)}" } + missing.map { "<span class=miss>· ${esc(it)} 없음</span>" } +
                s.screen.take(6).map { "<span class=dim>· ${esc(it)}</span>" }
            sb.append(if (actual.isEmpty()) "<span class=dim>-</span>" else actual.joinToString("<br>"))
            sb.append("</div></div></div>")
            // logcat around the failing moment: the last gesture of the step, else its start
            val at = r.startedAtMs + (s.actions.lastOrNull()?.atMs ?: s.startedMs)
            val lines = Site.logcatWindow(File(r.dir, "logcat.txt"), at - 2000, at + 2000)
            if (lines.isNotEmpty()) sb.append("<h4>logcat (탭 전후 2초)</h4><pre class=log>${esc(lines.joinToString("\n") { it.replaceFirst(Regex("^\\d+\\.\\d+\\s+\\d+\\s+\\d+\\s+"), "") })}</pre>")
            sb.append("</div>")
        }
        sb.append("</div>")
    }

    private fun stepsJson(r: ResultJson.Result, thumbs: Map<Int, String>): String = buildJsonArray {
        r.steps.filter { it.status != "SKIPPED" }.forEach { s ->
            add(buildJsonObject {
                put("n", s.n)
                put("total", r.steps.size)
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

    private val JS = """
        (function(){
          var v=document.getElementById('v'),cap=document.getElementById('cap'),still=document.getElementById('still'),time=document.getElementById('time'),play=document.getElementById('play');
          var rows=[].slice.call(document.querySelectorAll('.step[data-step]')),segs=[].slice.call(document.querySelectorAll('#segs i'));
          function byN(n){for(var i=0;i<STEPS.length;i++)if(STEPS[i].n==n)return STEPS[i];return null}
          function mark(n){rows.forEach(function(r){r.classList.toggle('on',r.dataset.step==n)});segs.forEach(function(s){s.classList.toggle('on',s.dataset.step==n)});var s=byN(n);if(s&&cap)cap.textContent=s.n+'/'+s.total+' · '+s.text;if(s&&still&&s.thumb)still.src=s.thumb}
          function seek(ms){v.currentTime=ms/1000;v.play&&v.play().catch(function(){})}
          function go(n){var s=byN(n);if(!s)return;mark(n);if(!v||s.videoMs==null)return;if(v.readyState>=1)seek(s.videoMs);else v.addEventListener('loadedmetadata',function(){seek(s.videoMs)},{once:true})}
          rows.forEach(function(r){r.addEventListener('click',function(e){if(e.target.closest('a,details,code,pre'))return;go(+r.dataset.step)})});
          segs.forEach(function(s){s.addEventListener('click',function(){go(+s.dataset.step)})});
          if(STEPS.length)mark(STEPS[0].n);
          if(!v)return;
          function fmt(ms){ms=Math.max(0,ms|0);return Math.floor(ms/60000)+':'+('0'+Math.floor(ms/1000)%60).slice(-2)}
          v.addEventListener('timeupdate',function(){var t=v.currentTime*1000,cur=null;for(var i=0;i<STEPS.length;i++){if(STEPS[i].videoMs!=null&&STEPS[i].videoMs<=t+50)cur=STEPS[i]}if(cur)mark(cur.n);if(time)time.textContent=fmt(t)+' / '+fmt(v.duration*1000||0)});
          v.addEventListener('loadedmetadata',function(){if(time)time.textContent='0:00 / '+fmt(v.duration*1000)});
          v.addEventListener('play',function(){play.textContent='❚❚'});v.addEventListener('pause',function(){play.textContent='▶'});
          play.addEventListener('click',function(){v.paused?v.play():v.pause()});
          v.addEventListener('click',function(){v.paused?v.play():v.pause()});
        })();
    """.trimIndent()

    private val CSS = """
        :root{--bg:#F4F4F1;--card:#FFFFFF;--fg:#1B1B1A;--dim:#5A5A56;--faint:#8A8A84;--line:#E2E2DD;--line2:#D6D6D0;--code:#F4F4F1;
          --fail:#B3261E;--failfg:#8A1C14;--failbg:#FBE9E7;--warn:#D08A1A;--warnfg:#7A4A00;--warnbg:#FCEFD6;--info:#1F4E8C;--infobg:#E4EEF9;--ok:#2E7D5B;--okfg:#1F5A3C;--okbg:#E3EFE8}
        *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.5 'IBM Plex Sans KR',system-ui,-apple-system,'Apple SD Gothic Neo',sans-serif}
        a{color:inherit}main{max-width:1200px;margin:0 auto;padding:28px 24px 48px;display:flex;flex-direction:column;gap:32px}main.detail{gap:24px}
        .topbar{background:var(--card);border-bottom:1px solid var(--line)}.topbar .inner{max-width:1200px;margin:0 auto;padding:0 24px;display:flex;flex-wrap:wrap;align-items:center;gap:24px;min-height:56px}
        .brand{font-weight:600;font-size:16px}.apps a{padding:6px 12px;border-radius:6px;color:var(--dim);text-decoration:none}.apps a.on{background:var(--fg);color:#fff;font-weight:500}
        .crumbs{display:flex;gap:8px;color:var(--dim);font-size:13px}.crumbs b{color:var(--fg);font-weight:500}.right{margin-left:auto;display:flex;gap:12px;align-items:center;color:var(--dim);font-size:13px}
        h1{margin:0;font-size:26px;font-weight:600;letter-spacing:-.01em}h2{margin:0;font-size:17px;font-weight:600}h3{margin:14px 0 4px;font-size:14px}h4{margin:0 0 4px;font-size:11px;color:var(--faint);font-weight:600;letter-spacing:.04em}
        .kicker,.dim{color:var(--dim);font-size:13px}.mono,.code,code,pre{font-family:'IBM Plex Mono',ui-monospace,Menlo,monospace;font-size:12.5px}
        header.home{display:flex;align-items:flex-end;gap:16px;flex-wrap:wrap}header.home .chips{margin-left:auto;display:flex;gap:10px;flex-wrap:wrap}
        .chip{display:inline-block;font-size:11px;font-weight:600;letter-spacing:.04em;padding:2px 8px;border-radius:4px;white-space:nowrap}.chip.big{font-size:13px;padding:6px 12px;border-radius:6px}
        .chip.failed{color:var(--failfg);background:var(--failbg)}.chip.changed{color:var(--warnfg);background:var(--warnbg)}.chip.rerecord{color:var(--info);background:var(--infobg)}.chip.passed{color:var(--okfg);background:var(--okbg)}.chip.setup{color:var(--dim);background:#E8E8E3}
        .sh{display:flex;align-items:baseline;gap:12px;flex-wrap:wrap;margin-bottom:14px}.legend{margin-left:auto;display:flex;gap:12px;font-size:12px;color:var(--dim)}.legend span{display:flex;align-items:center;gap:5px}
        .legend i,.strip i{display:inline-block;width:10px;height:10px;border-radius:2px;background:var(--line2)}.strip{display:inline-flex;gap:3px;vertical-align:middle}.strip.wide i{width:16px;height:14px}.strip i.passed{background:var(--ok)}.strip i.changed{background:var(--warn)}.strip i.failed{background:var(--fail)}.strip i.none{background:var(--line2)}
        .legend i.passed{background:var(--ok)}.legend i.changed{background:var(--warn)}.legend i.failed{background:var(--fail)}
        .cards{display:grid;gap:16px}.cards.two{grid-template-columns:repeat(2,minmax(0,1fr))}.cards.three{grid-template-columns:repeat(3,minmax(0,1fr));margin-top:16px}.cards.four{grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}
        .card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:16px;display:flex;flex-direction:column;gap:10px;min-width:0}.card.fail{border-left:4px solid var(--fail);flex-direction:row}.card.warn{border-left:4px solid var(--warn)}.card.info{border-left:4px solid var(--info)}.card.none{border-left:4px solid var(--line2)}
        .card .row{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.card .title{font-weight:600;font-size:15px}.card .title a{text-decoration:none}.card .body{display:flex;flex-direction:column;gap:8px;min-width:0;flex:1}
        .card .shot{flex-shrink:0;width:84px;border-radius:10px;overflow:hidden;border:1px solid var(--line2);background:#E8E8E3}.card .shot img{display:block;width:100%}.card .shot.empty{height:168px;display:flex;align-items:center;justify-content:center;color:var(--faint);font-size:11px;text-align:center}
        .reason{color:var(--failfg)}.card .foot{display:flex;align-items:center;gap:12px;margin-top:auto;font-size:13px;color:var(--dim)}.card .foot .more{margin-left:auto}
        .owner{display:inline-flex;align-items:center;gap:6px}.avatar{width:20px;height:20px;border-radius:50%;background:var(--dim);color:#fff;font-size:10px;display:inline-flex;align-items:center;justify-content:center;font-weight:600}
        .pair{display:flex;gap:10px}.pair figure{flex:1;margin:0;display:flex;flex-direction:column;gap:4px;align-items:center;font-size:12px;color:var(--dim)}.pair img{width:100%;border-radius:8px;border:1px solid var(--line2)}.pair img.today{border:2px solid var(--warn)}
        .note{font-size:13px}.card .code,.code{background:var(--code);padding:8px 10px;border-radius:6px;line-height:1.5;word-break:break-all}
        .btns{display:flex;gap:8px;flex-wrap:wrap}.btn{flex:1;text-align:center;padding:7px 10px;border-radius:6px;border:1px solid #C9C9C3;background:var(--card);color:var(--fg);font-size:13px;text-decoration:none;cursor:pointer;white-space:nowrap}.btn.primary{border-color:var(--fg);background:var(--fg);color:#fff;font-weight:500}
        .card.feature{text-decoration:none;gap:8px;padding:14px}.card.feature .name{font-weight:600;font-size:15px}.card.feature .row{justify-content:space-between}
        details.more{margin-top:12px}details.more summary,details.all summary{cursor:pointer;color:var(--dim);font-size:13px}details.more>summary{margin-bottom:12px}
        .passline{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px 16px;display:flex;align-items:center;gap:12px;flex-wrap:wrap}
        details.all ul{list-style:none;margin:4px 0;padding:0}details.all li{padding:3px 0;display:flex;gap:8px;align-items:baseline}.dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:var(--line2);flex-shrink:0}.dot.failed{background:var(--fail)}.dot.changed,.dot.rerecord{background:var(--warn)}.dot.passed{background:var(--ok)}
        main.detail header{display:flex;flex-direction:column;gap:14px}main.detail header .row{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.desc{margin:0;max-width:760px;color:#3A3A37;line-height:1.7}
        .tabs{display:flex;gap:4px;background:#E8E8E3;padding:3px;border-radius:8px}.tabs a{padding:6px 14px;border-radius:6px;color:var(--dim);font-size:13px;display:flex;align-items:center;gap:6px;text-decoration:none}.tabs a[aria-selected=true]{background:var(--card);color:var(--fg);font-weight:500}
        .cols{display:flex;flex-wrap:wrap;gap:20px;align-items:flex-start}section.steps{flex:1 1 560px;min-width:0;display:flex;flex-direction:column;gap:10px}aside.replay{flex:0 1 380px;min-width:300px;display:flex;flex-direction:column;gap:10px;position:sticky;top:12px}
        section.steps h2,aside.replay h2{font-size:13px;color:var(--dim);letter-spacing:.04em;margin-bottom:4px}
        .step{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:12px 14px;cursor:pointer}.step.on{border-color:var(--fg);box-shadow:0 0 0 1px var(--fg)}.step.skipped{opacity:.55;cursor:default}.step.failed{border-color:var(--fail)}
        .step .line{display:flex;gap:12px}.step .icon{flex-shrink:0;width:22px;height:22px;border-radius:50%;display:inline-flex;align-items:center;justify-content:center;font-size:12px;font-weight:700;background:var(--okbg);color:var(--okfg)}.step.failed .icon{background:var(--failbg);color:var(--failfg)}.step.skipped .icon{background:#E8E8E3;color:var(--faint)}
        .step .text{flex:1;min-width:0;display:flex;flex-direction:column;gap:3px}.step .n{color:var(--faint)}.criterion{color:var(--dim)}.step .meta{font-size:12px;color:var(--dim)}.step.failed .meta{color:var(--failfg)}
        .fail{margin-top:10px;display:flex;flex-direction:column;gap:10px;cursor:auto}.cols2{display:grid;grid-template-columns:1fr 1fr;gap:10px}.cols2>div{background:var(--code);border-radius:6px;padding:8px 10px}.cols2 .mono{line-height:1.5}.miss{color:var(--failfg)}
        pre.log{margin:0;background:#1B1B1A;color:#E6E6E2;padding:10px 12px;border-radius:6px;font-size:11.5px;line-height:1.5;overflow:auto;max-height:260px;white-space:pre-wrap}
        .actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.actions code{background:var(--code);padding:6px 10px;border-radius:6px}.actions .btn{flex:0 0 auto}
        .player{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px;display:flex;flex-direction:column;gap:12px}.frame{position:relative;width:100%;aspect-ratio:9/16;max-height:560px;border-radius:10px;background:#E8E8E3;border:1px solid var(--line2);overflow:hidden;display:flex;align-items:center;justify-content:center}
        .frame video,.frame img{width:100%;height:100%;object-fit:contain;background:#000}.caption{position:absolute;left:10px;right:10px;bottom:10px;background:rgba(27,27,26,.88);color:#fff;padding:8px 10px;border-radius:8px;font-family:'IBM Plex Mono',monospace;font-size:11px;line-height:1.5;pointer-events:none}
        .controls{display:flex;align-items:center;gap:10px}#play{width:32px;height:32px;border-radius:50%;border:0;background:var(--fg);color:#fff;cursor:pointer;font-size:11px;flex-shrink:0}
        .segments{flex:1;display:flex;gap:3px}.segments i{height:8px;border-radius:4px;background:var(--line2);cursor:pointer;min-width:6px}.segments i.passed{background:var(--ok)}.segments i.failed{background:var(--fail)}.segments i.on{outline:2px solid var(--fg);outline-offset:1px}
        .time{font-family:'IBM Plex Mono',monospace;font-size:12px;color:var(--dim)}
        footer{max-width:1200px;margin:0 auto;padding:0 24px 24px;font-size:12px}
        @media(max-width:900px){.cards.two,.cards.three,.cards.four{grid-template-columns:repeat(2,minmax(0,1fr))}aside.replay{position:static}}
        @media(max-width:600px){main{padding:20px 16px 40px}.cards.two,.cards.three,.cards.four{grid-template-columns:1fr}.cols2{grid-template-columns:1fr}.card.fail{flex-direction:column}}
    """.trimIndent()
}
