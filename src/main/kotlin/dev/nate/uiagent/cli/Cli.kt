package dev.nate.uiagent.cli

import dev.nate.uiagent.device.AdbOps
import java.io.File
import kotlin.system.exitProcess

/**
 * The `simul` CLI: dispatch plus the small subcommands. `run` lives in RunCommand.kt, `report`
 * in Report.kt, `init` in InitCommand.kt, the ad-hoc single command in Adhoc.kt, and the
 * interactive mode (bare `simul` on a TTY inside a project) in Tui.kt.
 */
fun main(argv: Array<String>) {
    exitProcess(cliMain(argv, File(".").absoluteFile.parentFile))
}

fun cliMain(argv: Array<String>, cwd: File): Int = when (argv.firstOrNull()) {
    null -> {
        // bare `simul` on a TTY inside a project = interactive mode; otherwise usage
        val project = SimulProject.find(cwd)
        if (project != null && System.console() != null) Tui(project).run()
        else {
            printUsage()
            2
        }
    }
    "--help", "-h" -> {
        printUsage()
        0
    }
    "run" -> runCommand(argv.drop(1), cwd)
    "report" -> reportCommand(argv.drop(1), cwd)
    "list" -> listCommand(cwd)
    "status" -> statusCommand(cwd)
    "init" -> initCommand(argv.drop(1), cwd)
    "state" -> stateCommand(argv.drop(1), cwd)
    else -> adhocCommand(argv, cwd)
}

private fun statusCommand(cwd: File): Int {
    val project = SimulProject.find(cwd)
        ?: return err("no .simul/ directory found from $cwd upwards")
    project.versionWarning()?.let { println(it) }
    println(renderStatus(collectStatus(project), color = System.console() != null))
    return 0
}

private fun printUsage() {
    println(
        """
        simul $AGENT_VERSION — natural-language UI test runner

        usage:
          simul                               interactive mode (full-screen, TTY + .simul/ 필요)
          simul init [--app <package>]        set up .simul/ + the scenario-authoring Claude skill
          simul run <scenario.md>... [--mode replay|llm|auto] [--dry-run] [--url U]
                    [--gif | --video]                     record the screen → run.gif (1.5×) / run.mp4 in the report dir
          simul run --tag <tag> | --state <name> | --all [same options]
                    [--summary <file.json> [--label <phase>]]   batch summary for `simul report`
          simul report <summary.json>... [--format md|slack|junit|html] [--title T]
                    [--link Name=URL]... [--out FILE] [--check]  join phases → CI/Slack report
          simul status                        scenarios × trace × last run × states, at a glance
          simul list
          simul state save <name>             snapshot current app data → .simul/states/<name>.tar
          simul "<natural-language command>" [ad-hoc options]

        modes:
          auto (default)  replay if a trace exists, otherwise SKIP with a warning
          replay          play the committed trace deterministically; broken steps FAIL (no LLM)
          llm             interpret every step from scratch and (re)record the trace

        nightly pipeline (wire these into your CI — see the README):
          simul run --all --mode replay --gif --summary r/baseline.json   # committed traces
          simul run --all --mode llm          --summary r/record.json     # re-record every scenario
          simul run --all --mode replay --gif --summary r/verify.json     # replay the fresh traces
          simul report r/baseline.json r/record.json r/verify.json --format html --out r/report.html
          simul report r/baseline.json r/record.json r/verify.json --format md --check
        """.trimIndent()
    )
}

/**
 * `simul state save <name>` — snapshot the app's current data (login/profile/flags) so
 * scenarios can restore it via `setup.appState: <name>` instead of logging in through the UI.
 */
private fun stateCommand(args: List<String>, cwd: File): Int {
    if (args.getOrNull(0) != "save" || args.size != 2) return err("usage: simul state save <name>")
    val name = args[1]
    val project = SimulProject.find(cwd) ?: return err("no .simul/ directory found from $cwd upwards")
    print("saving app data → ${project.statesDir.relativeTo(project.appRoot)}/$name.tar … ")
    return saveAppState(project, name).fold(
        onSuccess = { dest ->
            println("done (${dest.length() / 1024}KB)")
            println("use it in a scenario:  setup:\n                         appState: $name\n                         launch: true")
            0
        },
        onFailure = { err(it.message ?: "state save failed") },
    )
}

/**
 * `simul state save <name>` and the TUI's `s` key: snapshot the app's current data (login,
 * profile, flags) into `.simul/states/<name>.tar` so scenarios restore it via `setup.appState`.
 */
internal fun saveAppState(project: SimulProject, name: String): Result<File> {
    if (!Scenario.isValidStateName(name)) return Result.failure(IllegalArgumentException("state name must be [A-Za-z0-9._-]+"))
    val app = project.config.app ?: return Result.failure(IllegalStateException("config.yaml needs `app:` to save app state"))
    val dest = File(project.statesDir, "$name.tar")
    return if (AdbOps().saveState(app, dest)) Result.success(dest)
    else Result.failure(IllegalStateException("state save failed — see the error above"))
}

private fun listCommand(cwd: File): Int {
    val project = SimulProject.find(cwd)
        ?: return err("no .simul/ directory found from $cwd upwards")
    project.versionWarning()?.let { println(it) }
    val files = project.scenarios()
    if (files.isEmpty()) {
        println("no scenarios under ${project.scenariosDir.path}")
        return 0
    }
    for ((md, s) in project.scenarioVariants { f, why -> println("${f.relativeTo(project.scenariosDir).path}  ERROR: $why") }) {
        val tags = if (s.tags.isEmpty()) "" else "  tags=${s.tags.joinToString(",")}"
        println("${scenarioPath(project, md, s)}  steps=${s.steps.size}  trace=${TraceState.of(md, project.traceFileFor(md, s)).name.lowercase()}$tags")
    }
    return 0
}

internal fun err(msg: String): Int {
    System.err.println("error: $msg")
    return 2
}
