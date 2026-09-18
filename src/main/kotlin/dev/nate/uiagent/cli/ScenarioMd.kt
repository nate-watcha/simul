package dev.nate.uiagent.cli

import java.io.File

/**
 * A markdown scenario: YAML frontmatter + numbered steps. Any other text is ignored so the
 * file doubles as documentation.
 */
data class Scenario(
    val name: String,
    val tags: List<String>,
    val setup: Setup?,
    val steps: List<String>,
    val file: File? = null,
    /** Free prose between frontmatter and the first numbered step — a one-line summary. */
    val description: String = "",
) {
    /** The app state this scenario requires: appState name, "guest" for clearData, or null
     *  (undeclared — runs against whatever state the device happens to be in). */
    val precondition: String? get() = setup?.appState ?: if (setup?.clearData == true) "guest" else null

    /** Setup is executed by the runner (adb), never exposed to the model as a tool. */
    data class Setup(
        val deeplink: String?,
        val launch: Boolean,
        /** pm clear before launching — guarantees a fresh-install (guest) state. */
        val clearData: Boolean = false,
        /** Restore `.simul/states/<name>.tar` (saved via `simul state save`) — implies clearData. */
        val appState: String? = null,
    )
}

object ScenarioMd {

    private val stepRegex = Regex("""^\s*\d+[.)]\s+(.+)$""")

    fun parse(text: String, file: File? = null): Scenario {
        val (front, body) = splitFrontmatter(text)
        val y = if (front != null) MiniYaml.parse(front) else emptyMap()
        val steps = body.lines().mapNotNull { stepRegex.matchEntire(it)?.groupValues?.get(1)?.trim() }
        // description = prose lines before the first numbered step (headings/blank lines dropped)
        val description = body.lineSequence()
            .takeWhile { !stepRegex.matches(it) }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString(" ")
        val setupMap = y["setup"] as? Map<*, *>
        val setup = if (setupMap != null) {
            Scenario.Setup(
                deeplink = setupMap["deeplink"] as? String,
                launch = (setupMap["launch"] as? String)?.equals("true", ignoreCase = true) == true,
                clearData = (setupMap["clearData"] as? String)?.equals("true", ignoreCase = true) == true,
                appState = setupMap["appState"] as? String,
            )
        } else null
        return Scenario(
            name = MiniYaml.string(y, "name") ?: file?.nameWithoutExtension ?: "scenario",
            tags = MiniYaml.list(y, "tags"),
            setup = setup,
            steps = steps,
            file = file,
            description = description,
        )
    }

    fun parse(file: File): Scenario = parse(file.readText(), file)

    /** Returns (frontmatter or null, body). Frontmatter = leading `---` ... `---` block. */
    private fun splitFrontmatter(text: String): Pair<String?, String> {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "---") return null to text
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return null to text
        val front = lines.subList(1, end + 1).joinToString("\n")
        val body = lines.subList(end + 2, lines.size).joinToString("\n")
        return front to body
    }
}
