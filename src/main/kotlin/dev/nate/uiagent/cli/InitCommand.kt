package dev.nate.uiagent.cli

import java.io.File

/**
 * `simul init [--app <package>]` — scaffold the `.simul/` convention in a target app repo: the
 * config template, the scenarios dir, and the Claude Code skill (SKILL.md — the scenario-authoring
 * guide — plus `references/setup.md` (preconditions/entry) and `references/external.md`
 * (external-element catalogue)) so agent sessions in that repo write scenarios by the book.
 * Never overwrites existing files — rerunning after a simul upgrade refreshes only what is
 * missing (delete a doc to refresh it).
 */
internal fun initCommand(args: List<String>, cwd: File): Int {
    var app: String? = null
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--app" -> app = args.getOrNull(++i) ?: return err("--app needs a value")
            else -> return err("unknown option $a")
        }
        i++
    }

    fun resource(name: String): String =
        object {}.javaClass.getResourceAsStream("/simul/$name")?.bufferedReader()?.readText()
            ?: throw IllegalStateException("packaging error: /simul/$name missing")

    val config = resource("config.yaml")
        .replace("__AGENT_VERSION__", AGENT_VERSION)
        .let { if (app != null) it.replace("app: your.app.package   # TODO: set the application id under test", "app: $app") else it }

    val files = listOf(
        File(cwd, ".simul/config.yaml") to config,
        File(cwd, ".claude/skills/simul-scenarios/SKILL.md") to resource("SKILL.md"),
        File(cwd, ".claude/skills/simul-scenarios/references/setup.md") to resource("setup.md"),
        File(cwd, ".claude/skills/simul-scenarios/references/external.md") to resource("external.md"),
    )
    File(cwd, ".simul/scenarios").mkdirs()
    for ((file, content) in files) {
        if (file.exists()) {
            println("kept    ${file.relativeTo(cwd)}")
        } else {
            file.parentFile?.mkdirs()
            file.writeText(content)
            println("created ${file.relativeTo(cwd)}")
        }
    }
    println(
        """

        next steps:
          1. .simul/config.yaml 의 app / llm.url 확인
          2. .claude/skills/simul-scenarios/SKILL.md 읽고 첫 시나리오 작성 → .simul/scenarios/<그룹>/<이름>.md
          3. simul run <그룹>/<이름>.md --mode llm   (녹화 후 trace를 md와 함께 커밋)
          4. CI: `simul run --all` (replay만, LLM 0회) — 나이틀리 3단계·리포트는 simul README 참조
        """.trimIndent()
    )
    return 0
}
