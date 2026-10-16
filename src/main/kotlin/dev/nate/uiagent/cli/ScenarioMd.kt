package dev.nate.uiagent.cli

import dev.nate.uiagent.device.GUEST_STATE
import dev.nate.uiagent.device.DeviceSetting
import java.io.File

/**
 * A markdown scenario: YAML frontmatter + numbered steps. Any other text is ignored so the
 * file doubles as documentation.
 *
 * A scenario may declare `states: [a, b, …]` — the same steps run once per state (one trace
 * each, see [variants]) and a step's `::` criterion can differ per state:
 *
 * ```
 * 3. Tap the "btn_play" button
 *    :: member → the "screen:player" screen should be shown
 *    :: guest  → the "btn_login" button should be shown
 * ```
 *
 * The instance the runner receives is always one concrete variant ([state] set, [steps]
 * rendered for it); the parsed template keeps the per-state criteria in [stepDefs].
 */
data class Scenario(
    val name: String,
    val tags: List<String>,
    val setup: Setup?,
    val steps: List<String>,
    /** Free prose between frontmatter and the first numbered step — a one-line summary. */
    val description: String = "",
    /** Declared `states:` — one run and one trace per state. Empty for a stateless scenario. */
    val states: List<String> = emptyList(),
    /** The state this instance is resolved for; null for a stateless scenario (or the template). */
    val state: String? = null,
    /** Steps with their per-state criteria; [steps] is this list rendered for [state]. */
    val stepDefs: List<StepDef> = steps.map { StepDef.parse(it) },
) {
    /** The app state this scenario requires: appState name, "guest" for clearData, or null
     *  (undeclared — runs against whatever state the device happens to be in). */
    val precondition: String? get() = setup?.appState ?: if (setup?.clearData == true) "guest" else null

    /**
     * The concrete scenarios to run: one per declared state, or just this one. A variant is
     * named `<name>@<state>` (report dirs, trace files and summaries key on it) and its setup
     * restores the state's snapshot (`.simul/states/<state>.tar`); [GUEST] may also have no
     * snapshot at all, in which case the restore is just the pm clear (see AdbOps.setup).
     */
    fun variants(): List<Scenario> = if (states.isEmpty()) listOf(this) else states.map(::forState)

    /** This scenario resolved for one declared state; stateless scenarios return themselves. */
    fun variant(state: String?): Scenario = if (state == null || states.isEmpty()) this else forState(state)

    private fun forState(state: String): Scenario {
        require(state in states) { "scenario '$name' declares no state '$state' (states: $states)" }
        val base = setup ?: Setup(deeplink = null, launch = false)
        return copy(
            name = "$name@$state",
            state = state,
            steps = stepDefs.map { it.render(state) },
            setup = base.copy(appState = state),
        )
    }

    /** Setup is executed by the runner (adb), never exposed to the model as a tool. */
    data class Setup(
        val deeplink: String?,
        val launch: Boolean,
        /** pm clear before launching — guarantees a fresh-install (guest) state. */
        val clearData: Boolean = false,
        /** Restore `.simul/states/<name>.tar` (saved via `simul state save`) — implies clearData. */
        val appState: String? = null,
        /**
         * Emulator `settings` this scenario needs pinned differently from the config baseline,
         * applied before the launch and put back when the scenario ends (see DeviceSettings).
         */
        val device: List<DeviceSetting> = emptyList(),
    )

    /** One numbered step: the command and its `::` criteria, each optionally bound to a state. */
    data class StepDef(val command: String, val criteria: List<Criterion> = emptyList()) {
        /**
         * The step sentence for [state] in the inline `command :: criterion` form the rest of
         * the pipeline consumes. State-bound criteria override the unbound ones for that state
         * (an unbound `::` is the expectation shared by every state until one is found to differ);
         * with no state only the unbound criteria apply.
         */
        fun render(state: String?): String {
            val bound = if (state == null) emptyList() else criteria.filter { it.state == state }
            val applicable = bound.ifEmpty { criteria.filter { it.state == null } }
            return if (applicable.isEmpty()) command
            else command + StepSpec.SEPARATOR + applicable.joinToString("; ") { it.text }
        }

        companion object {
            /** A step sentence in inline form — its `::` clause, if any, is unbound. */
            fun parse(text: String): StepDef {
                val spec = StepSpec.parse(text)
                return StepDef(spec.command, listOfNotNull(spec.criterion?.let { Criterion(null, it) }))
            }
        }
    }

    /** `state → text` applies to one state; [state] null applies to every state. */
    data class Criterion(val state: String?, val text: String)

    companion object {
        /** The one state name that needs no snapshot: without `guest.tar` it means a fresh install. */
        const val GUEST = GUEST_STATE

        /** State names double as file names (`<name>.tar`, `<md>@<name>.trace.json`). */
        private val STATE_NAME = Regex("[A-Za-z0-9._-]+")
        fun isValidStateName(s: String): Boolean = STATE_NAME.matches(s)
    }
}

object ScenarioMd {

    private val stepRegex = Regex("""^\s*\d+[.)]\s+(.+)$""")

    /** A continuation line under a step: `:: criterion` or `:: state → criterion`. */
    private val criterionLineRegex = Regex("""^\s*::\s*(.*)$""")
    private val boundCriterionRegex = Regex("""^([A-Za-z0-9._-]+)\s*(?:→|->)\s*(.*)$""")

    fun parse(text: String, file: File? = null): Scenario {
        val (front, body) = splitFrontmatter(text)
        val y = if (front != null) MiniYaml.parse(front) else emptyMap()
        val name = MiniYaml.string(y, "name") ?: file?.nameWithoutExtension ?: "scenario"
        val states = MiniYaml.list(y, "states")
        require(states.distinct().size == states.size) { "scenario '$name': duplicate entries in states: $states" }
        require(states.all(Scenario::isValidStateName)) { "scenario '$name': state names must be [A-Za-z0-9._-]+ (states: $states)" }

        val stepDefs = parseSteps(body, name, states)
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
                device = DeviceSetting.parse(setupMap["device"], "scenario '$name' setup"),
            )
        } else null
        if (states.isNotEmpty()) {
            // the state IS the precondition — a second one in setup would be contradictory
            require(setup?.appState == null && setup?.clearData != true) {
                "scenario '$name': `states:` already selects the app state — drop setup.appState/clearData"
            }
        }
        return Scenario(
            name = name,
            tags = MiniYaml.list(y, "tags"),
            setup = setup,
            steps = stepDefs.map { it.render(null) },
            description = description,
            states = states,
            stepDefs = stepDefs,
        )
    }

    fun parse(file: File): Scenario = parse(file.readText(), file)

    /** Numbered lines start a step; `::` lines directly under one attach criteria to it. */
    private fun parseSteps(body: String, name: String, states: List<String>): List<Scenario.StepDef> {
        val steps = mutableListOf<Scenario.StepDef>()
        fun attach(raw: String) {
            require(steps.isNotEmpty()) { "scenario '$name': `:: $raw` must follow a numbered step" }
            val c = criterion(raw, name, states) ?: return
            steps[steps.lastIndex] = steps.last().let { it.copy(criteria = it.criteria + c) }
        }
        for (line in body.lines()) {
            val step = stepRegex.matchEntire(line)
            if (step != null) {
                val spec = StepSpec.parse(step.groupValues[1].trim())
                steps += Scenario.StepDef(spec.command)
                spec.criterion?.let(::attach) // inline `command :: criterion`
                continue
            }
            criterionLineRegex.matchEntire(line)?.let { attach(it.groupValues[1].trim()) }
        }
        return steps
    }

    /** `state → text` binds to a declared state; anything else is an unbound criterion. */
    private fun criterion(raw: String, name: String, states: List<String>): Scenario.Criterion? {
        if (raw.isEmpty()) return null
        val bound = boundCriterionRegex.matchEntire(raw) ?: return Scenario.Criterion(null, raw)
        val (prefix, text) = bound.destructured
        require(states.isNotEmpty()) { "scenario '$name': `:: $prefix →` needs `states:` in the frontmatter" }
        require(prefix in states) { "scenario '$name': unknown state '$prefix' in `:: $raw` (states: $states)" }
        return text.trim().takeIf { it.isNotEmpty() }?.let { Scenario.Criterion(prefix, it) }
    }

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
