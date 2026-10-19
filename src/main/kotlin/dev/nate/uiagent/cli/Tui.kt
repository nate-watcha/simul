package dev.nate.uiagent.cli

import dev.nate.uiagent.ago
import dev.nate.uiagent.agent.ScenarioSession
import dev.nate.uiagent.cli.TuiText.bold
import dev.nate.uiagent.cli.TuiText.cyan
import dev.nate.uiagent.cli.TuiText.dim
import dev.nate.uiagent.cli.TuiText.green
import dev.nate.uiagent.cli.TuiText.pad
import dev.nate.uiagent.cli.TuiText.plain
import dev.nate.uiagent.cli.TuiText.red
import dev.nate.uiagent.cli.TuiText.yellow
import dev.nate.uiagent.device.AdbOps
import dev.nate.uiagent.device.DeviceController
import dev.nate.uiagent.device.syntheticHandle
import dev.nate.uiagent.runProcess
import dev.nate.uiagent.secs
import dev.nate.uiagent.web.DeviceStack
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.InfoCmp
import org.jline.utils.NonBlockingReader
import java.io.File


/**
 * Full-screen interactive mode: `simul` with no args on a TTY. A shell over the exact same
 * code paths as the subcommands — the scenario list is [collectStatus], running delegates to
 * [executeScenario] (so display forcing, live step logs, reports all behave identically), and the
 * ad-hoc REPL drives one persistent [ScenarioSession] as an authoring workbench.
 *
 * Rendering: every screen is a [TuiText.Frame] of exactly terminal-height rows, painted in
 * place (no clear-screen, no flicker) and never wider than the terminal (no accidental alt-screen
 * scroll). Input: keys plus SGR mouse reporting, so the wheel scrolls both the scenario list and
 * the run log; the list follows the terminal size on resize.
 */
class Tui(private val project: SimulProject) {

    private val terminal: Terminal = TerminalBuilder.builder().system(true).build()
    private val reader: NonBlockingReader get() = terminal.reader()
    private var cooked: org.jline.terminal.Attributes? = null
    private var selected = 0
    private var status = collectStatus(project)
    /** Scenarios in feature(dir) order — what `selected` indexes. */
    private var items: List<ScenarioStatus> = status.scenarios
    private var message: String? = null

    fun run(): Int {
        val saved = terminal.enterRawMode()
        cooked = saved
        enterAlt()
        try {
            var lastSize = 0 to 0
            while (true) {
                val size = terminal.width to terminal.height
                // a Tick only repaints when the terminal was resized
                if (size != lastSize) { render(); lastSize = size }
                // No action may kill the shell: record any escaped Throwable and return home.
                try {
                    val ev = readEvent(300)
                    if (ev == Ev.Tick) continue
                    lastSize = 0 to 0 // force a repaint after any real event
                    when (ev) {
                        Ev.Up -> selected = (selected - 1).coerceAtLeast(0)
                        Ev.Down -> selected = (selected + 1).coerceAtMost(items.lastIndex.coerceAtLeast(0))
                        Ev.PgUp -> selected = (selected - listH).coerceAtLeast(0)
                        Ev.PgDn -> selected = (selected + listH).coerceAtMost(items.lastIndex.coerceAtLeast(0))
                        Ev.Home -> selected = 0
                        Ev.End -> selected = items.lastIndex.coerceAtLeast(0)
                        is Ev.Wheel -> wheelList(ev.down)
                        is Ev.Click -> clickList(ev.row)
                        Ev.Enter -> runSelected("replay")
                        Ev.Quit -> return 0
                        is Ev.Char -> when (ev.c) {
                            'q' -> return 0
                            'j' -> selected = (selected + 1).coerceAtMost(items.lastIndex.coerceAtLeast(0))
                            'k' -> selected = (selected - 1).coerceAtLeast(0)
                            'r' -> runSelected("llm")
                            'v' -> runSelected("video")
                            'x' -> runGroup()
                            'a' -> adhocRepl()
                            's' -> saveState()
                            'o' -> openReport()
                            'g' -> refresh()
                            else -> {}
                        }
                        else -> {}
                    }
                } catch (t: Throwable) {
                    val log = runCatching { writeCrashLog(project.reportsDir, "tui", t) }.getOrNull()
                    message = red("crash: ${t.javaClass.simpleName}: ${(t.message ?: "").take(60)}") +
                        (log?.let { dim("  → ${it.name}") } ?: "")
                }
            }
        } finally {
            exitAlt()
            terminal.attributes = saved
            terminal.flush()
        }
    }

    // ---------------------------------------------------------------- list screen

    /** Visual rows of the list: feature(dir) headers interleaved with scenario items. */
    private class Row(val header: String?, val item: Int)

    private fun rows(): List<Row> = buildList {
        var lastGroup: String? = null
        items.forEachIndexed { i, s ->
            val g = areaOf(s.relPath).ifEmpty { "(root)" }
            if (g != lastGroup) { add(Row(g, -1)); lastGroup = g }
            add(Row(null, i))
        }
    }

    /** First visible row of the scenario list — kept in sync so the selection stays on screen. */
    private var scrollTop = 0
    /** Viewport height and first terminal row (1-based) of the list, as last rendered. */
    private var listH = 10
    private var listRow0 = 4

    private fun width() = terminal.width.takeIf { it > 20 } ?: 100
    private fun height() = terminal.height.takeIf { it > 8 } ?: 40

    private fun render() {
        val w = width()
        val f = TuiText.Frame(w, height())
        val cfg = project.config
        val left = bold(" simul") + dim(" · ") + bold(project.appRoot.name)
        val facts = listOf(
            "app: ${cfg.app ?: "?"}", "display: ${cfg.display?.let { "on" } ?: "off"}",
            "llm: ${cfg.llm.url.removePrefix("http://").removePrefix("https://")}${if (cfg.llm.apiKey != null) " (key)" else ""}",
        )
        // narrow terminals drop the rightmost facts rather than clipping the title
        val right = (facts.size downTo 1).asSequence().map { n -> dim(facts.take(n).joinToString("  ·  ") + " ") }
            .first { TuiText.width(it) + TuiText.width(left) + 2 <= w || it == dim(facts[0] + " ") }
        f.split(left, right)
        f.rule()

        val rows = rows()
        // chrome below the list: rule + detail + states + (message) + rule + footer
        val below = 5 + (if (message != null) 1 else 0)
        // name column: as wide as the longest name, but the fixed columns (steps/trace/run/state
        // chip + scrollbar ≈ 56) always keep their place — the detail line shows the full path
        val chipW = (items.maxOfOrNull { TuiText.width(it.precondition?.let { p -> "[$p]" } ?: "[state?]") } ?: 8)
        val nameW = (items.maxOfOrNull { TuiText.width(name(it)) } ?: 8).coerceIn(8, (w - 42 - chipW).coerceAtLeast(8))
        f.line(dim("    " + pad("SCENARIO", nameW) + "  " + pad("STEPS", 6) + pad("TRACE", 9) + pad("LAST RUN", 19) + "STATE"))
        listRow0 = 4
        listH = f.remaining(after = below)
        if (items.isEmpty()) {
            f.line(dim("  no scenarios — write one under .simul/scenarios/ (see .claude/skills/simul-scenarios/SKILL.md)"))
            repeat(listH - 1) { f.line() }
        } else {
            // follow the selection: clamp scrollTop (over rows) so the selected row is visible
            val selRow = rows.indexOfFirst { it.item == selected }
            if (selRow < scrollTop) scrollTop = selRow
            if (selRow >= scrollTop + listH) scrollTop = selRow - listH + 1
            scrollTop = scrollTop.coerceIn(0, (rows.size - listH).coerceAtLeast(0))
            val bar = TuiText.scrollbar(rows.size, scrollTop, listH)
            for (r in 0 until listH) {
                val row = rows.getOrNull(scrollTop + r)
                if (row == null) { f.line(pad("", w - 1) + bar[r]); continue }
                if (row.header != null) {
                    val n = items.count { areaOf(it.relPath).ifEmpty { "(root)" } == row.header }
                    f.line(pad(" " + bold("◆ ${row.header}") + dim(" ($n)"), w - 1) + bar[r])
                    continue
                }
                val s = items[row.item]
                val trace = when (s.trace) {
                    TraceState.OK -> green("● ok")
                    TraceState.STALE -> yellow("● stale")
                    TraceState.NONE -> dim("○ none")
                }
                val run = s.lastRun?.let {
                    (if (it.passed) green("✓ PASSED") else red("✗ FAILED")) + dim(" " + ago(System.currentTimeMillis() - it.at))
                } ?: dim("never run")
                val chip = s.precondition?.let { dim("[$it]") } ?: yellow("[state?]")
                val line = "    " + pad(name(s), nameW) + "  " + pad("${s.steps}", 6) + pad(trace, 9) + pad(run, 19) + chip
                if (row.item == selected) f.line("\u001B[7m" + pad("  ▸ " + plain(line).drop(4), w - 1) + "\u001B[27m" + bar[r])
                else f.line(pad(line, w - 1) + bar[r])
            }
        }
        f.rule()
        f.line(detailLine())
        f.line(dim(" states: ") + if (status.states.isEmpty()) dim("(none)")
               else status.states.joinToString("  ") { "${it.name} " + dim("(${it.sizeBytes / 1024 / 1024}MB)") })
        message?.let { f.line(" $it") }
        f.rule()
        val full = keys(
            "↑↓" to "move", "⏎" to "replay", "v" to "replay+video", "r" to "record", "x" to "run group", "a" to "ad-hoc",
            "s" to "save state", "o" to "report", "g" to "refresh", "q" to "quit",
        )
        f.line(if (TuiText.width(full) <= w) full else keys(
            "⏎" to "replay", "v" to "video", "r" to "rec", "x" to "group", "a" to "adhoc", "s" to "state", "o" to "report", "g" to "refresh", "q" to "quit",
        ))
        paint(f)
    }

    private fun name(s: ScenarioStatus) = s.relPath.substringAfterLast('/').removeSuffix(".md") + (s.state?.let { "@$it" } ?: "")

    private fun detailLine(): String {
        val s = items.getOrNull(selected) ?: return ""
        val parts = mutableListOf(cyan(s.relPath))
        if (s.tags.isNotEmpty()) parts += dim("tags: ") + s.tags.joinToString(", ")
        s.lastRun?.let { parts += dim("last run: ") + "${it.mode}, ${it.durationMs?.let(::secs) ?: "?"}, ${ago(System.currentTimeMillis() - it.at)}" }
        parts += dim("steps: ") + "${s.steps}"
        return " " + parts.joinToString(dim("  ·  "))
    }

    private fun keys(vararg k: Pair<String, String>) =
        " " + k.joinToString("  ") { (key, label) -> bold(key) + dim(" $label") }

    private fun paint(f: TuiText.Frame) {
        terminal.writer().print(f.render())
        terminal.flush()
    }

    /** Wheel scrolls the viewport; the selection is dragged along only when it would leave it. */
    private fun wheelList(down: Boolean) {
        val rows = rows()
        scrollTop = (scrollTop + if (down) 3 else -3).coerceIn(0, (rows.size - listH).coerceAtLeast(0))
        val selRow = rows.indexOfFirst { it.item == selected }
        val target = when {
            selRow < scrollTop -> rows.drop(scrollTop).firstOrNull { it.item >= 0 }?.item
            selRow >= scrollTop + listH -> rows.take(scrollTop + listH).lastOrNull { it.item >= 0 }?.item
            else -> null
        }
        target?.let { selected = it }
    }

    private fun clickList(termRow: Int) {
        val r = scrollTop + (termRow - listRow0)
        rows().getOrNull(r)?.takeIf { it.item >= 0 }?.let { selected = it.item }
    }

    // ---------------------------------------------------------------- input

    private sealed class Ev {
        object Up : Ev(); object Down : Ev(); object PgUp : Ev(); object PgDn : Ev()
        object Home : Ev(); object End : Ev(); object Enter : Ev(); object Esc : Ev()
        object Quit : Ev(); object Tick : Ev(); object None : Ev()
        data class Char(val c: kotlin.Char) : Ev()
        data class Wheel(val down: Boolean) : Ev()
        /** Left-button press at a 1-based terminal cell. */
        data class Click(val row: Int) : Ev()
    }

    /** One input event, or [Ev.Tick] after [timeoutMs] of silence (the render loop's heartbeat). */
    private fun readEvent(timeoutMs: Long): Ev {
        val c = reader.read(timeoutMs)
        return when (c) {
            NonBlockingReader.READ_EXPIRED -> Ev.Tick
            NonBlockingReader.EOF, 3 -> Ev.Quit        // closed stdin / ctrl-c
            13, 10 -> Ev.Enter
            27 -> readEscape()
            else -> Ev.Char(c.toChar())
        }
    }

    /** CSI/SS3 sequences: arrows, Home/End, PgUp/PgDn, and SGR mouse (`ESC [ < b ; x ; y M|m`). */
    private fun readEscape(): Ev {
        val intro = reader.read(60L)
        if (intro == 'O'.code) return when (reader.read(60L)) {
            'A'.code -> Ev.Up; 'B'.code -> Ev.Down; 'H'.code -> Ev.Home; 'F'.code -> Ev.End; else -> Ev.None
        }
        if (intro != '['.code) return Ev.Esc
        val params = StringBuilder()
        var final = -1
        while (true) {
            val x = reader.read(60L)
            if (x < 0) return Ev.None
            if (x.toChar() in '@'..'~') { final = x; break }
            params.append(x.toChar())
        }
        val p = params.toString()
        return when (final.toChar()) {
            'A' -> Ev.Up
            'B' -> Ev.Down
            'H' -> Ev.Home
            'F' -> Ev.End
            '~' -> when (p) { "5" -> Ev.PgUp; "6" -> Ev.PgDn; "1", "7" -> Ev.Home; "4", "8" -> Ev.End; else -> Ev.None }
            'M', 'm' -> {
                if (!p.startsWith("<")) return Ev.None
                val n = p.drop(1).split(';').mapNotNull { it.toIntOrNull() }
                if (n.size != 3) return Ev.None
                val (cb, cx, cy) = n
                when {
                    cb and 64 != 0 -> Ev.Wheel(down = cb and 1 == 1)
                    final.toChar() == 'M' && cb and 3 == 0 -> Ev.Click(cy)
                    else -> Ev.None
                }
            }
            else -> Ev.None
        }
    }

    // ---------------------------------------------------------------- actions

    private fun refresh() {
        status = collectStatus(project)
        items = status.scenarios
        selected = selected.coerceIn(0, items.lastIndex.coerceAtLeast(0))
        message = null
    }

    /** [mode]: `replay`, `llm` (record), or `video` — a replay that also records the screen to replay.mp4. */
    private fun runSelected(mode: String) {
        val s = items.getOrNull(selected) ?: return
        runScenariosWithPanel(listOf(s), if (mode == "llm") RunMode.LLM else RunMode.REPLAY, s.relPath,
            video = mode == "video")
    }

    private fun saveState() {
        if (project.config.app == null) { message = red("config.yaml needs `app:` to save state"); return }
        onNormalScreen {
            val reader = LineReaderBuilder.builder().terminal(terminal).build()
            val name = runCatching { reader.readLine("state name: ").trim() }.getOrDefault("")
            saveAppState(project, name).fold(
                onSuccess = { dest -> message = green("state '$name' saved (${dest.length() / 1024}KB)") },
                onFailure = { message = red(it.message ?: "state save failed"); pause() },
            )
        }
        val m = message
        refresh()
        message = m
    }

    /** Replay every scenario in the selected scenario's feature area (its directory). */
    private fun runGroup() {
        val s = items.getOrNull(selected) ?: return
        val area = areaOf(s.relPath)
        runScenariosWithPanel(items.filter { areaOf(it.relPath) == area }, RunMode.REPLAY, "group: ${area.ifEmpty { "(root)" }}")
    }

    /**
     * Run scenarios in a full-screen live panel (alt screen kept): header with mode, progress
     * and elapsed time, the current scenario's description, and its step lines streaming into a
     * scrollable log (wheel/arrows/PgUp/PgDn; it follows the tail until you scroll up, End
     * re-attaches). Runs on a worker thread so the panel stays responsive; q/enter return to
     * the list once the batch is done.
     */
    private fun runScenariosWithPanel(targets: List<ScenarioStatus>, mode: RunMode, title: String, video: Boolean = false) {
        if (targets.isEmpty()) return
        val ops = AdbOps(statesDir = project.statesDir)
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val idx = java.util.concurrent.atomic.AtomicInteger(0)
        val cur = java.util.concurrent.atomic.AtomicReference(Pair("", "")) // name, desc
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val results = java.util.Collections.synchronizedList(mutableListOf<Pair<String, StepStatus>>())
        val videos = java.util.Collections.synchronizedList(mutableListOf<File>())

        val worker = Thread {
            // the same batch session as `simul run`: display + settings pinned once, restored at the end
            try {
                BatchSession(project, ops, log::add).use { batch ->
                    batch.pin()
                    targets.forEachIndexed { i, st ->
                        idx.set(i)
                        val md = File(project.scenariosDir, st.relPath)
                        val scenario = ScenarioMd.parse(md).variant(st.state)
                        cur.set(scenario.name to scenario.description)
                        val r = batch.run(md, scenario, mode, project.config.llm, video, log::add)
                        results.add(scenario.name to when (r.outcome.status) {
                            "PASSED" -> StepStatus.PASSED
                            "SKIPPED" -> StepStatus.SKIPPED
                            else -> StepStatus.FAILED
                        })
                        r.result?.video?.let { videos.add(it.file) }
                    }
                }
            } finally {
                done.set(true)
            }
        }
        worker.isDaemon = true
        worker.start()

        val panel = RunPanel(title, mode, targets.size, System.currentTimeMillis(), video)
        // render loop: repaint on every event or 120ms tick (log lines stream in, the clock ticks)
        while (true) {
            val (n, d) = cur.get()
            val finished = done.get()
            panel.render(idx.get(), n, d, synchronized(log) { log.toList() }, results.toList(), running = !finished)
            val ev = readEvent(120)
            when (ev) {
                Ev.Up -> panel.scroll(-1)
                Ev.Down -> panel.scroll(+1)
                is Ev.Wheel -> panel.scroll(if (ev.down) 3 else -3)
                Ev.PgUp -> panel.scroll(-panel.bodyH)
                Ev.PgDn -> panel.scroll(+panel.bodyH)
                Ev.Home -> panel.toTop()
                Ev.End -> panel.follow = true
                is Ev.Char -> when (ev.c) {
                    'k' -> panel.scroll(-1); 'j' -> panel.scroll(+1)
                    'G' -> panel.follow = true
                    'q' -> if (finished) break
                }
                Ev.Enter, Ev.Esc, Ev.Quit -> if (finished) break
                else -> {}
            }
        }
        refresh()
        // the recording is what this run was for — keep its path on screen once the panel is gone
        videos.toList().takeIf { it.isNotEmpty() }?.let { g ->
            message = green("video: ") + g.joinToString("  ") { it.relativeToOrSelf(project.appRoot).path } + dim("  (o opens the report)")
        }
    }

    /** Scroll state + painter for the run screen. */
    private inner class RunPanel(val title: String, val mode: RunMode, val total: Int, val startedAt: Long, val recording: Boolean) {
        /** Attached to the tail: new lines scroll into view. Any upward scroll detaches. */
        var follow = true
        var top = 0
        var bodyH = 10
        private var rowCount = 0

        fun scroll(delta: Int) {
            if (delta < 0) follow = false
            top = (top + delta).coerceIn(0, (rowCount - bodyH).coerceAtLeast(0))
            if (top >= (rowCount - bodyH).coerceAtLeast(0)) follow = true
        }

        fun toTop() { follow = false; top = 0 }

        fun render(idx: Int, curName: String, curDesc: String, log: List<String>, results: List<Pair<String, StepStatus>>, running: Boolean) {
            val w = width()
            val f = TuiText.Frame(w, height())
            val modeLabel = (if (mode == RunMode.LLM) yellow("● REC (llm)") else green("▶ replay")) +
                if (recording) red(" ● rec") else ""
            val passed = results.count { it.second == StepStatus.PASSED }
            val failed = results.count { it.second == StepStatus.FAILED }
            val skipped = results.count { it.second == StepStatus.SKIPPED }
            val tally = listOfNotNull(
                green("✓$passed").takeIf { passed > 0 }, red("✗$failed").takeIf { failed > 0 }, yellow("⊘$skipped").takeIf { skipped > 0 },
            ).joinToString(" ")
            val elapsed = (System.currentTimeMillis() - startedAt) / 1000
            f.split(
                bold(" $title"),
                "$modeLabel  " + dim("${(idx + 1).coerceAtMost(total)}/$total") + (if (tally.isNotEmpty()) "  $tally" else "") +
                    dim("  %d:%02d ".format(elapsed / 60, elapsed % 60)),
            )
            f.rule()
            if (curName.isNotEmpty()) {
                f.line(" " + bold(curName) + if (running) dim("  …") else "")
                if (curDesc.isNotEmpty()) TuiText.wrap(curDesc, w - 4).take(2).forEach { f.line(dim("   $it")) }
                f.line()
            }
            // the log body fills the rest, above rule + footer
            bodyH = f.remaining(after = 2)
            val rows = log.flatMap { styledRows(it, w - 2) }
            rowCount = rows.size
            val maxTop = (rowCount - bodyH).coerceAtLeast(0)
            if (follow) top = maxTop else top = top.coerceIn(0, maxTop)
            val bar = TuiText.scrollbar(rowCount, top, bodyH)
            for (r in 0 until bodyH) f.line(pad(rows.getOrNull(top + r) ?: "", w - 1) + bar[r])
            f.rule()
            val below = rowCount - (top + bodyH).coerceAtMost(rowCount)
            val scrollHint = if (!follow) yellow(" ↓$below more") + dim(" (End follows)  ") else ""
            f.line(
                if (running) scrollHint + dim("running…  ") + keys("↑↓/wheel" to "scroll", "PgUp/PgDn" to "page")
                else scrollHint + bold(" done ") + dim("— $passed/${results.size} passed  ") + keys("q/⏎" to "back to list", "↑↓/wheel" to "scroll"),
            )
            paint(f)
        }
    }

    private val stepHead = Regex("^(\\s*step \\d+/\\d+:)")

    /** One log line → wrapped visual rows, styled by what the runner's line format says it is. */
    private fun styledRows(line: String, cols: Int): List<String> {
        val t = line.trimStart()
        val style: (String) -> String = when {
            line.startsWith("──") || t.startsWith("setup") || t.startsWith("observing") -> TuiText::dim
            t.startsWith("warning") -> TuiText::yellow
            t.startsWith("llm ") || t.startsWith("video:") -> TuiText::cyan // cost summary, media paths
            t.startsWith("CRASH") || t.startsWith("FAILED") || Regex("^step \\d+: FAILED").containsMatchIn(t) -> TuiText::red
            t.startsWith("PASSED") || Regex("^step \\d+: PASSED").containsMatchIn(t) || t.startsWith("trace ") -> TuiText::green
            t.startsWith("SKIPPED") || Regex("^step \\d+: SKIPPED").containsMatchIn(t) -> TuiText::yellow
            else -> { s -> s }
        }
        return TuiText.wrap(line, cols, indent = 6).mapIndexed { i, piece ->
            if (i == 0 && stepHead.containsMatchIn(piece)) stepHead.replace(piece) { cyan(bold(it.value)) }
            else style(piece)
        }
    }

    private fun openReport() {
        val s = items.getOrNull(selected) ?: return
        val dir = ReportDirs.latest(project.reportsDir, s.name)
        message = if (dir == null) dim("no report for ${s.name}")
        else { runCatching { runProcess(listOf("open", dir.path)) }; dim("opened ${dir.name}") }
    }

    /**
     * The authoring workbench: a persistent conversation against the live device. Type a
     * natural-language command → verdict; `:labels` shows what the current screen exposes
     * (exactly what scenario steps can reference); `:q` returns to the list.
     */
    private fun adhocRepl() {
        project.config.llm.keyProblem()?.let { message = red(it); return }
        onNormalScreen { adhocLoop() }
    }

    private fun adhocLoop() {
        val cfg = project.config
        DeviceStack(cfg.app, scrollLimit = cfg.scrollLimit).use { stack ->
            val controller = stack.controller
            val session = ScenarioSession(cfg.llm.client(), controller, cfg.maxTurns, model = cfg.llm.llmModel())
            val reader = LineReaderBuilder.builder().terminal(terminal).build()
            println("ad-hoc — natural-language commands against the live device. :labels = current screen, :q = back")
            var first = true
            while (true) {
                val line = runCatching { reader.readLine("simul> ") }.getOrNull()?.trim() ?: break
                when {
                    line.isEmpty() -> {}
                    line == ":q" -> break
                    line == ":labels" -> printLabels(controller)
                    else -> {
                        val initial = if (first) { println("observing…"); controller.fullLayout() } else null
                        first = false
                        val v = session.runStep(line, initial)
                        println((if (v.passed) green("PASSED") else red("FAILED")) + ": ${v.reason}")
                    }
                }
            }
        }
    }

    private fun printLabels(controller: DeviceController) {
        if (controller.currentElements.isEmpty()) {
            println("observing…")
            controller.fullLayout() // observation is LLM-free — the workbench works offline
        }
        controller.currentElements.forEach { e ->
            val kind = when {
                e.interactions.isNotEmpty() -> e.interactions.joinToString(",")
                else -> "label"
            }
            val state = if (e.state.isNotEmpty()) " [${e.state.joinToString(",")}]" else ""
            println("  ${pad(e.label ?: syntheticHandle(e), 48)} ${e.resourceId?.let { "#$it " } ?: ""}($kind)$state")
        }
    }

    // ---------------------------------------------------------------- terminal plumbing

    private fun enterAlt() {
        terminal.puts(InfoCmp.Capability.enter_ca_mode)
        terminal.puts(InfoCmp.Capability.cursor_invisible)
        // SGR mouse reporting: wheel + click land in readEscape (text selection needs shift/alt-drag)
        terminal.writer().print("\u001B[?1000h\u001B[?1006h")
        terminal.flush()
    }

    private fun exitAlt() {
        terminal.writer().print("\u001B[?1006l\u001B[?1000l")
        terminal.puts(InfoCmp.Capability.cursor_normal)
        terminal.puts(InfoCmp.Capability.exit_ca_mode)
        terminal.flush()
    }

    /** Run [body] on the normal screen with cooked attributes (normal scrolling output),
     *  then re-enter raw mode and the alt screen. */
    private fun onNormalScreen(body: () -> Unit) {
        exitAlt()
        cooked?.let { terminal.attributes = it }
        try {
            body()
        } finally {
            terminal.enterRawMode()
            enterAlt()
        }
    }

    private fun pause() {
        terminal.writer().print(dim("\n[press any key to return]"))
        terminal.flush()
        terminal.enterRawMode() // body runs cooked; a single-key pause needs raw
        terminal.reader().read()
    }
}
