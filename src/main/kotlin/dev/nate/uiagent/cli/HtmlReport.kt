package dev.nate.uiagent.cli

import dev.nate.uiagent.json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/**
 * `simul report --format html`: one self-contained page — verdict table, then every scenario
 * expanded into its steps per phase, with the screenshots of failed steps inlined as
 * thumbnails (data URIs) so the file can be opened anywhere the artifact lands: no report
 * dir, no device, no server. Step detail comes from each phase's `report.json`, resolved
 * against [root] (the app root `simul report` runs in); a missing report dir degrades to the
 * summary line.
 */
object HtmlReport {

    /** One step as report.json records it. */
    data class Step(
        val index: Int, val text: String, val status: String, val mode: String?, val reason: String?,
        val durationMs: Long, val screenshots: List<String>, val screen: List<String>, val evidence: List<String>,
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

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val MAX_IMAGES = 200

    fun render(r: PipelineReport, root: File): String {
        var images = 0
        val sb = StringBuilder()
        sb.append("<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>").append(esc(r.title)).append("</title><style>").append(CSS).append("</style></head><body>")
        sb.append("<h1>").append(r.overallEmoji).append(' ').append(esc(r.title)).append("</h1>")
        sb.append("<p class=counts><b>").append(esc(r.countsLine())).append("</b></p>")
        val env = listOfNotNull(
            r.appVersionName?.let { "app $it" }, r.device?.let { "device $it" }, r.agentVersion?.let { "simul $it" },
            r.androidCli?.let { "android CLI $it" }, r.startedAt?.let { "started $it" },
        )
        if (env.isNotEmpty()) sb.append("<p class=env>").append(esc(env.joinToString(" · "))).append("</p>")
        if (r.links.isNotEmpty()) sb.append("<p class=links>").append(r.links.joinToString(" · ") { (n, u) -> "<a href=\"${esc(u)}\">${esc(n)}</a>" }).append("</p>")

        // ---- verdict table (anchors into the detail sections)
        sb.append("<table class=verdicts><thead><tr><th>Scenario</th>")
        r.phases.forEach { sb.append("<th>").append(esc(it.heading)).append("</th>") }
        sb.append("<th>Verdict</th></tr></thead><tbody>")
        for ((i, row) in r.rows.withIndex()) {
            sb.append("<tr class=\"sev-${row.verdict.severity.name.lowercase()}\"><td><a href=\"#s$i\"><code>").append(esc(row.path)).append("</code></a></td>")
            row.outcomes.forEach { sb.append("<td>").append(esc(cell(it))).append("</td>") }
            sb.append("<td>").append(row.verdict.kind.emoji).append(' ').append(esc(row.verdict.kind.title)).append("</td></tr>")
        }
        sb.append("</tbody></table>")

        // ---- details: attention first, open; the rest collapsed
        val ordered = r.attention + r.rows.filter { it.verdict.severity == Severity.OK }
        sb.append("<h2>Scenarios</h2>")
        for (row in ordered) {
            val i = r.rows.indexOf(row)
            val open = if (row.verdict.severity != Severity.OK) " open" else ""
            sb.append("<details id=\"s$i\" class=\"sev-${row.verdict.severity.name.lowercase()}\"$open><summary>")
            sb.append(row.verdict.kind.emoji).append(" <code>").append(esc(row.path)).append("</code> — ").append(esc(row.verdict.kind.title))
            sb.append("</summary>")
            if (row.verdict.kind.advice.isNotEmpty()) sb.append("<p class=advice>").append(esc(row.verdict.kind.advice)).append("</p>")
            for ((pi, o) in row.outcomes.withIndex()) {
                if (o == null) continue
                val phase = r.phases[pi]
                sb.append("<section class=phase><h3>").append(esc(phase.heading)).append(" · ").append(esc(cell(o)))
                o.reportDir?.let { sb.append(" <span class=dim>").append(esc(it)).append("</span>") }
                sb.append("</h3>")
                if (o.status == "SKIPPED" || o.status == "CRASH") {
                    sb.append("<p class=reason>").append(esc(o.reason ?: o.status)).append("</p></section>")
                    continue
                }
                val reportDir = o.reportDir?.let { File(it).takeIf { f -> f.isAbsolute } ?: File(root, it) }
                val steps = reportDir?.let { File(it, "report.json") }?.takeIf { it.isFile }?.let(::readSteps) ?: emptyList()
                if (steps.isEmpty()) {
                    o.failedStep?.let { f ->
                        sb.append("<p class=reason>step ").append(f.index).append(" <code>").append(esc(f.text)).append("</code> — ").append(esc(f.reason ?: "failed")).append("</p>")
                    }
                    sb.append("</section>")
                    continue
                }
                sb.append("<ol class=steps>")
                for (st in steps) {
                    sb.append("<li class=\"st-").append(st.status.lowercase()).append("\"><span class=st>").append(esc(st.status)).append("</span> ")
                    sb.append("<code>").append(esc(st.text)).append("</code>")
                    st.mode?.let { sb.append(" <span class=dim>(").append(esc(it)).append(", ").append(humanDuration(st.durationMs)).append(")</span>") }
                    st.reason?.let { sb.append("<div class=reason>").append(esc(it)).append("</div>") }
                    if (st.status == "FAILED") {
                        if (st.screen.isNotEmpty()) sb.append("<div class=screen>on screen: ").append(st.screen.joinToString(" · ") { "<code>${esc(it)}</code>" }).append("</div>")
                        // the failing moment: the step's last two screenshots (before/after, or the -failed shot)
                        val shots = st.screenshots.takeLast(2)
                        if (shots.isNotEmpty() && reportDir != null) {
                            sb.append("<div class=shots>")
                            for (shot in shots) {
                                val uri = if (images < MAX_IMAGES) thumbnail(File(reportDir, shot)) else null
                                if (uri != null) { images++; sb.append("<figure><img src=\"").append(uri).append("\" alt=\"").append(esc(shot)).append("\"><figcaption>").append(esc(shot.substringAfterLast('/'))).append("</figcaption></figure>") }
                                else sb.append("<span class=dim>").append(esc(shot)).append("</span> ")
                            }
                            sb.append("</div>")
                        }
                    } else if (st.evidence.isNotEmpty()) {
                        sb.append("<div class=dim>evidence: ").append(esc(st.evidence.joinToString(", "))).append("</div>")
                    }
                    sb.append("</li>")
                }
                sb.append("</ol></section>")
            }
            sb.append("</details>")
        }
        sb.append("<p class=dim>generated by simul ").append(esc(r.agentVersion ?: "")).append("</p></body></html>\n")
        return sb.toString()
    }

    private val CSS = """
        :root{--bg:#fff;--fg:#1b1b1b;--dim:#6b6b6b;--line:#e3e3e3;--ok:#e8f5e9;--warn:#fff8e1;--fail:#fdecea}
        @media(prefers-color-scheme:dark){:root{--bg:#141414;--fg:#e6e6e6;--dim:#9a9a9a;--line:#333;--ok:#15301b;--warn:#3a2f10;--fail:#3d1c1a}}
        body{margin:0;padding:24px 16px;font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Apple SD Gothic Neo",sans-serif;background:var(--bg);color:var(--fg);max-width:1100px;margin-inline:auto}
        h1{font-size:22px;margin:0 0 4px}h2{font-size:17px;margin:28px 0 8px}h3{font-size:14px;margin:12px 0 4px}
        .counts{margin:0}.env,.dim{color:var(--dim)}.links a{margin-right:8px}
        code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12.5px}
        table.verdicts{border-collapse:collapse;width:100%;margin-top:12px}
        .verdicts th,.verdicts td{border-bottom:1px solid var(--line);padding:6px 8px;text-align:left;vertical-align:top}
        .verdicts th{color:var(--dim);font-weight:600;font-size:12px}
        tr.sev-fail td:last-child{background:var(--fail)}tr.sev-warn td:last-child{background:var(--warn)}tr.sev-ok td:last-child{background:var(--ok)}
        details{border:1px solid var(--line);border-radius:8px;padding:8px 12px;margin:8px 0}
        details.sev-fail{border-left:4px solid #d9483b}details.sev-warn{border-left:4px solid #e0a800}details.sev-ok{border-left:4px solid #3c9a4e}
        summary{cursor:pointer;font-weight:600}
        .advice{margin:6px 0;color:var(--dim)}
        section.phase{margin:6px 0 10px;padding-left:4px}
        ol.steps{margin:4px 0;padding-left:22px}ol.steps li{margin:3px 0}
        .st{display:inline-block;min-width:58px;font-size:11px;font-weight:700;letter-spacing:.02em}
        li.st-passed .st{color:#2e7d32}li.st-failed .st{color:#c62828}li.st-skipped .st{color:var(--dim)}
        .reason{color:#c62828}.screen{margin-top:2px}
        .shots{display:flex;gap:10px;flex-wrap:wrap;margin:6px 0}
        figure{margin:0}figure img{display:block;width:170px;max-width:40vw;border:1px solid var(--line);border-radius:4px}
        figcaption{font-size:11px;color:var(--dim);max-width:170px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
        @media(max-width:600px){body{padding:16px 12px}.verdicts{display:block;overflow-x:auto}}
    """.trimIndent()
}
