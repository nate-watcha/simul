package dev.nate.uiagent.cli

import dev.nate.uiagent.Trace
import dev.nate.uiagent.agent.HttpChatClient
import dev.nate.uiagent.agent.ScenarioSession
import dev.nate.uiagent.device.AdbDevice
import dev.nate.uiagent.device.DeviceController
import dev.nate.uiagent.runProcess
import dev.nate.uiagent.web.CdpClient
import dev.nate.uiagent.web.WebAwareDevice
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.InfoCmp
import java.io.File

/**
 * Full-screen interactive mode: `simul` with no args on a TTY. A shell over the exact same
 * code paths as the subcommands — the scenario list is [collectStatus], running delegates to
 * [runCommand] (so display forcing, live step logs, reports all behave identically), and the
 * ad-hoc REPL drives one persistent [ScenarioSession] as an authoring workbench.
 */
class Tui(private val project: SimulProject, private val cwd: File) {


    private val terminal: Terminal = TerminalBuilder.builder().system(true).build()
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
            while (true) {
                render()
                // No action may kill the shell: record any escaped Throwable and return home.
                try {
                    when (readKey()) {
                        Key.UP -> selected = (selected - 1).coerceAtLeast(0)
                        Key.DOWN -> selected = (selected + 1).coerceAtMost(items.lastIndex.coerceAtLeast(0))
                        Key.PGUP -> selected = (selected - 10).coerceAtLeast(0)
                        Key.PGDN -> selected = (selected + 10).coerceAtMost(items.lastIndex.coerceAtLeast(0))
                        Key.ENTER -> runSelected("replay")
                        Key.RECORD -> runSelected("llm")
                        Key.GROUP -> runGroup()
                        Key.ADHOC -> adhocRepl()
                        Key.STATE -> saveState()
                        Key.OPEN -> openReport()
                        Key.REFRESH -> refresh()
                        Key.QUIT -> return 0
                        Key.NONE -> {}
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

    // ---------------------------------------------------------------- rendering

    /** First visible row of the scenario list — kept in sync so the selection stays on screen. */
    private var scrollTop = 0

    private fun render() {
        val w = terminal.width.takeIf { it > 20 } ?: 100
        val h = terminal.height.takeIf { it > 8 } ?: 40
        val sb = StringBuilder("\u001B[2J\u001B[H")
        val cfg = project.config
        sb.appendLine(bold(" simul · ${project.appRoot.name} ").padEnd(w - 40) +
            dim("app: ${cfg.app ?: "?"} · display: ${cfg.display?.let { "on" } ?: "off"} · llm: ${cfg.llmUrl.removePrefix("http://")}"))
        sb.appendLine(dim("─".repeat(w)))

        // viewport: everything except header(2) + states(2) + optional message(2) + footer(2)
        val chrome = 6 + (if (message != null) 2 else 0)
        val listH = (h - chrome).coerceAtLeast(3)
        if (items.isEmpty()) {
            sb.appendLine(dim("  no scenarios — write one under .simul/scenarios/ (see .claude/skills/simul-scenarios/references/scenarios.md)"))
        } else {
            // rows = feature(dir) headers + scenario items; states stay per-row chips
            data class Row(val header: String?, val item: Int)
            val rows = buildList {
                var lastGroup: String? = "\u0000"
                items.forEachIndexed { i, s ->
                    val g = featureGroup(s.relPath)
                    if (g != lastGroup) { add(Row(g, -1)); lastGroup = g }
                    add(Row(null, i))
                }
            }
            // follow the selection: clamp scrollTop (over rows) so the selected row is visible
            val selRow = rows.indexOfFirst { it.item == selected }
            if (selRow < scrollTop) scrollTop = selRow
            if (selRow >= scrollTop + listH) scrollTop = selRow - listH + 1
            scrollTop = scrollTop.coerceIn(0, (rows.size - listH).coerceAtLeast(0))

            val end = (scrollTop + listH).coerceAtMost(rows.size)
            for (r in scrollTop until end) {
                val edge = when {
                    r == scrollTop && scrollTop > 0 -> dim(" ↑$scrollTop")
                    r == end - 1 && end < rows.size -> dim(" ↓${rows.size - end}")
                    else -> ""
                }
                val row = rows[r]
                if (row.header != null) {
                    val n = items.count { featureGroup(it.relPath) == row.header }
                    sb.appendLine(" ${bold("◆ ${row.header} ($n)")}$edge")
                    continue
                }
                val i = row.item
                val s = items[i]
                val marker = if (i == selected) "▸ " else "  "
                val trace = when (s.trace) {
                    TraceState.OK -> green("trace ok   ")
                    TraceState.STALE -> yellow("trace stale")
                    TraceState.NONE -> dim("no trace   ")
                }
                val run = s.lastRun?.let {
                    (if (it.passed) green("✓ PASSED") else red("✗ FAILED")) + " " + ago(System.currentTimeMillis() - it.at)
                } ?: dim("never run")
                val chip = s.precondition?.let { dim("[$it]") } ?: yellow("[state?]")
                val name = s.relPath.substringAfter('/')
                val line = "  " + marker + name.padEnd(32).take(32) + " " + "${s.steps}stp".padEnd(6) +
                    trace + "  " + run + "  " + chip + edge
                sb.appendLine(if (i == selected) invert(line) else line)
            }
        }
        sb.appendLine()
        sb.append(" states: ")
        if (status.states.isEmpty()) sb.appendLine(dim("(none)"))
        else sb.appendLine(status.states.joinToString("  ") { "${it.name} (${it.sizeBytes / 1024 / 1024}MB)" })
        message?.let { sb.appendLine(); sb.appendLine(" $it") }
        sb.appendLine(dim("─".repeat(w)))
        sb.append(dim(" [enter] replay  [x] run group  [r] record  [a] ad-hoc  [s] state save  [o] report  [g] refresh  [q] quit"))
        terminal.writer().print(sb.toString())
        terminal.flush()
    }

    private fun bold(s: String) = "\u001B[1m$s\u001B[0m"
    private fun dim(s: String) = "\u001B[90m$s\u001B[0m"
    private fun green(s: String) = "\u001B[32m$s\u001B[0m"
    private fun yellow(s: String) = "\u001B[33m$s\u001B[0m"
    private fun red(s: String) = "\u001B[31m$s\u001B[0m"
    private fun invert(s: String) = "\u001B[7m$s\u001B[27m"

    // ---------------------------------------------------------------- input

    private enum class Key { UP, DOWN, PGUP, PGDN, ENTER, RECORD, GROUP, ADHOC, STATE, OPEN, REFRESH, QUIT, NONE }

    private fun readKey(): Key {
        val c = terminal.reader().read()
        return when (c) {
            'q'.code, 3 -> Key.QUIT             // q / ctrl-c
            'j'.code -> Key.DOWN
            'k'.code -> Key.UP
            13, 10 -> Key.ENTER
            'r'.code -> Key.RECORD
            'a'.code -> Key.ADHOC
            's'.code -> Key.STATE
            'o'.code -> Key.OPEN
            'g'.code -> Key.REFRESH
            'x'.code -> Key.GROUP
            27 -> { // ESC [ ... — arrows (incl. wheel via alternate-scroll), PgUp/PgDn
                if (terminal.reader().read(50L) != '['.code) return Key.NONE
                when (terminal.reader().read(50L)) {
                    'A'.code -> Key.UP
                    'B'.code -> Key.DOWN
                    '5'.code -> { terminal.reader().read(50L); Key.PGUP } // consume '~'
                    '6'.code -> { terminal.reader().read(50L); Key.PGDN }
                    else -> Key.NONE
                }
            }
            else -> Key.NONE
        }
    }

    // ---------------------------------------------------------------- actions

    private fun refresh() {
        status = collectStatus(project)
        items = status.scenarios
        selected = selected.coerceIn(0, items.lastIndex.coerceAtLeast(0))
        message = null
    }

    private fun runSelected(mode: String) {
        val s = items.getOrNull(selected) ?: return
        runScenariosWithPanel(listOf(s), if (mode == "llm") RunMode.LLM else RunMode.REPLAY, s.relPath)
    }

    private fun saveState() {
        val app = project.config.app
        if (app == null) { message = red("config.yaml needs `app:` to save state"); return }
        onNormalScreen {
            val reader = LineReaderBuilder.builder().terminal(terminal).build()
            val name = runCatching { reader.readLine("state name: ").trim() }.getOrDefault("")
            if (name.matches(Regex("[A-Za-z0-9._-]+"))) {
                val dest = File(project.statesDir, "$name.tar")
                message = if (AdbOps().saveState(app, dest)) green("state '$name' saved (${dest.length() / 1024}KB)")
                else red("state save failed — see error above")
                if (message!!.contains("failed")) pause()
            } else message = red("invalid state name")
        }
        val m = message
        refresh()
        message = m
    }

    /** Replay every scenario in the selected scenario's feature group (top-level directory). */
    private fun runGroup() {
        val s = items.getOrNull(selected) ?: return
        val group = items.filter { featureGroup(it.relPath) == featureGroup(s.relPath) }
        runScenariosWithPanel(group, RunMode.REPLAY, "group: ${featureGroup(s.relPath)}")
    }

    /**
     * Run scenarios in a full-screen live panel (alt screen kept): header with mode + progress
     * count, the current scenario's description, and its step lines streaming in. Runs on a
     * worker thread so the render loop stays responsive; a key returns to the list when done.
     */
    private fun runScenariosWithPanel(targets: List<ScenarioStatus>, mode: RunMode, title: String) {
        if (targets.isEmpty()) return
        val ops = AdbOps(statesDir = project.statesDir)
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val idx = java.util.concurrent.atomic.AtomicInteger(0)
        val cur = java.util.concurrent.atomic.AtomicReference(Pair("", "")) // name, desc
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val results = java.util.Collections.synchronizedList(mutableListOf<Pair<String, StepStatus>>())

        val worker = Thread {
            // one display-forcing session for the whole batch, like the CLI path
            var displayBefore: String? = null
            val display = project.config.display
            if (display != null && ops.isEmulator() && ops.displayProfile() != display.toString()) {
                ops.setDisplay(display); displayBefore = ops.displayProfile() ?: "unknown"
            }
            try {
                targets.forEachIndexed { i, st ->
                    idx.set(i)
                    val md = File(project.scenariosDir, st.relPath)
                    val scenario = ScenarioMd.parse(md)
                    cur.set(scenario.name to scenario.description)
                    val traceFile = project.traceFileFor(md)
                    val trace = traceFile.takeIf { it.isFile }?.let { runCatching { TraceJson.read(it) }.getOrNull() }
                    if (mode == RunMode.REPLAY && trace == null) {
                        log.add("  SKIPPED ${scenario.name}: no trace"); results.add(scenario.name to StepStatus.SKIPPED); return@forEachIndexed
                    }
                    val res = try {
                        executeScenario(project, scenario, trace, mode, project.config.llmUrl, traceFile, ops, log::add)
                    } catch (t: Throwable) {
                        runCatching { writeCrashLog(project.reportsDir, scenario.name, t) }
                        log.add("  CRASH: ${t.javaClass.simpleName}"); null
                    }
                    results.add(scenario.name to (res?.status ?: StepStatus.FAILED))
                }
            } finally {
                displayBefore?.let { b -> DisplayProfile.parse(b)?.let { ops.setDisplay(it) } ?: ops.resetDisplay() }
                done.set(true)
            }
        }
        worker.isDaemon = true
        worker.start()

        // render loop — repaint until the worker finishes, then wait for a key
        while (!done.get()) {
            val (n, d) = cur.get()
            renderRunPanel(title, mode, idx.get(), targets.size, n, d, log, results, running = true)
            Thread.sleep(120)
        }
        val (n, d) = cur.get()
        renderRunPanel(title, mode, idx.get(), targets.size, n, d, log, results, running = false)
        terminal.reader().read()
        refresh()
    }

    private fun renderRunPanel(
        title: String, mode: RunMode, idx: Int, total: Int,
        curName: String, curDesc: String,
        log: List<String>, results: List<Pair<String, StepStatus>>, running: Boolean,
    ) {
        val w = terminal.width.takeIf { it > 20 } ?: 100
        val h = terminal.height.takeIf { it > 8 } ?: 40
        val sb = StringBuilder("[2J[H")
        val modeLabel = if (mode == RunMode.LLM) yellow("● REC (llm)") else green("▶ replay")
        sb.appendLine(bold(" $title ").padEnd(w - 24) + modeLabel + dim("  ${idx + 1}/$total"))
        sb.appendLine(dim("─".repeat(w)))
        if (curName.isNotEmpty()) {
            sb.appendLine(" " + bold(curName))
            if (curDesc.isNotEmpty()) sb.appendLine(dim("   " + curDesc.take(w - 4)))
            sb.appendLine()
        }
        // tail of the log fills the rest
        val head = 4 + (if (curName.isNotEmpty()) 3 else 0)
        val bodyH = (h - head - 2).coerceAtLeast(3)
        synchronized(log) { log.toList() }.takeLast(bodyH).forEach { line ->
            val colored = when {
                "PASSED" in line -> green(line)
                "FAILED" in line || "CRASH" in line -> red(line)
                line.trimStart().startsWith("step") -> line
                line.startsWith("──") || line.startsWith("  setup") -> dim(line)
                else -> line
            }
            sb.appendLine(colored.take(w + 20))
        }
        sb.append(dim("─".repeat(w)) + "\n")
        sb.append(if (running) dim(" running…  (finishes then any key returns)")
                  else dim(" done — ${results.count { it.second == StepStatus.PASSED }}/${results.size} passed · press any key"))
        terminal.writer().print(sb.toString())
        terminal.flush()
    }

    private fun openReport() {
        val s = items.getOrNull(selected) ?: return
        val stamp = Regex("^${Regex.escape(s.name)}-\\d{8}-\\d{6}$")
        val dir = project.reportsDir.listFiles { f -> f.isDirectory && stamp.matches(f.name) }
            ?.maxByOrNull { it.name }
        message = if (dir == null) dim("no report for ${s.name}")
        else { runCatching { runProcess(listOf("open", dir.path)) }; dim("opened ${dir.name}") }
    }

    /**
     * The authoring workbench: a persistent conversation against the live device. Type a
     * natural-language command → verdict; `:labels` shows what the current screen exposes
     * (exactly what scenario steps can reference); `:q` returns to the list.
     */
    private fun adhocRepl() = onNormalScreen {
        val cfg = project.config
        val cdp = CdpClient()
        val controller = DeviceController(
            WebAwareDevice(AdbDevice(), cdp, cfg.app), Trace.DISABLED)
        val session = ScenarioSession(HttpChatClient(cfg.llmUrl), controller, cfg.maxTurns)
        val reader = LineReaderBuilder.builder().terminal(terminal).build()
        println("ad-hoc — natural-language commands against the live device. :labels = current screen, :q = back")
        var first = true
        try {
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
        } finally {
            cdp.close()
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
            println("  ${(e.label ?: "@x${e.center.x}y${e.center.y}").take(48).padEnd(48)} ${e.resourceId?.let { "#$it " } ?: ""}($kind)$state")
        }
    }

    // ---------------------------------------------------------------- terminal plumbing

    private fun enterAlt() {
        terminal.puts(InfoCmp.Capability.enter_ca_mode)
        terminal.puts(InfoCmp.Capability.cursor_invisible)
        terminal.writer().print("\u001B[?1007h") // alternate scroll: mouse wheel → arrow keys
        terminal.flush()
    }

    private fun exitAlt() {
        terminal.writer().print("\u001B[?1007l")
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
