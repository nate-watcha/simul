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
    "--version", "-v" -> {
        println("simul $AGENT_VERSION")
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
          simul                               interactive mode — scenarios, runs, recording, states, ad-hoc REPL (TTY + .simul/)
          simul init [--app <package>]        set up .simul/ + the scenario-authoring Claude skill
          simul "<natural-language command>"  ad-hoc single command, no .simul/ needed
          simul --version                     print the version (for CI to check which simul is on the runner)

        nightly (wired into the app repo's CI — see the README):
          simul report                        re-record every scenario with the LLM, replay the recordings without it,
                                              write .simul/reports/<stamp>/ (index.html + record.json + run.json)
          simul report <stamp>/record.json <stamp>/run.json --format md|junit [--check]   # job summary / trace PR body / gate
          simul report <scenario.md>...       the same record → replay → HTML for those scenarios only; prints their pages
                                              (<stamp>/<feature>/<name>.<state>.html) — the try-out of a newly written scenario
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
