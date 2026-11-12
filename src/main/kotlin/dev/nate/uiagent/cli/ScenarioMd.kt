package dev.nate.uiagent.cli

import dev.nate.uiagent.device.GUEST_STATE
import dev.nate.uiagent.device.DevicePins
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
 *
 * A file may hold several scenarios of one subject, one `## title` heading each ([sections]): the runner
 * runs them in file order, each from the app started over at the scenario's entry. The first heading may be `## 준비`: what it says is
 * done by the model before the others, in a replay too, so they start from a state that is known
 * whatever the last run left on the server —
 *
 * ```
 * ## 준비
 * 1. 팔로우하고 있지 않으면 "팔로우"를 눌러 팔로우한다
 *
 * ## 팔로우 취소
 * 팔로우 중인 유저의 팔로우를 취소할 수 있다.
 * 1. "팔로우 취소"를 누른다
 *
 * ## 팔로우 추가
 * 1. "팔로우"를 누른다
 * ```
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
    /** Human title from the frontmatter (`title:`), what the report calls the scenario; null → name. */
    val title: String? = null,
    /** Who answers for this scenario (`owner:`) — the report mentions them on a failure. */
    val owner: String? = null,
    /** `values:` — a name and the candidates a run picks one of for every `$name` in the prose and steps (see [ValuePicks]). */
    val values: Map<String, List<String>> = emptyMap(),
    /** The `## title` sections [steps] is split into, in file order; empty for a scenario without headings. */
    val sections: List<Section> = emptyList(),
    /**
     * The screen (group directory) whose way in this scenario starts from: its `_entry.md` says where the app
     * is opened and the steps from there, and the runner plays them from that file's trace before this
     * scenario's first step ([SimulProject.load] sets it, and [setup] to the entry's). Null for a scenario that
     * opens the app by itself.
     */
    val via: String? = null,
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
         * App locale / emulator `settings` this scenario needs pinned differently from the config
         * baseline, applied before the launch and put back when the scenario ends (see DevicePins).
         */
        val device: DevicePins = DevicePins.NONE,
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

    /**
     * One `## title` part of a scenario: its prose and the steps `[first, first + size)` of [Scenario.steps].
     * The [preparation] section (`## 준비`, always the first) is not a test: its steps go to the model in
     * every mode, are never traced, and bring the app to the state the sections after it start from.
     */
    data class Section(
        val title: String, val description: String, val first: Int, val size: Int, val preparation: Boolean = false,
        /** Where this section starts when that is not the scenario's entry — the line under its heading. */
        val open: Open? = null,
    ) {
        val steps: IntRange get() = first until first + size

        /**
         * The section is a look at another screen — it opens there, or its sentence mentions one (`[[화면]]`).
         * What it finds says nothing about the sections after it, so they run even when it fails.
         */
        val looksElsewhere: Boolean get() = open != null || Mentions.of(description).isNotEmpty()
    }

    /**
     * Where a section starts instead of the scenario's entry: `deeplink: <address>` opens that address,
     * `entry: <화면>` goes the way in of that screen (its `_entry.md`). One of the two.
     */
    data class Open(val deeplink: String? = null, val screen: String? = null)

    companion object {
        /** The one state name that needs no snapshot: without `guest.tar` it means a fresh install. */
        const val GUEST = GUEST_STATE

        /** State names double as file names (`<name>.tar`, `<md>@<name>.trace.json`). */
        private val STATE_NAME = Regex("[A-Za-z0-9._-]+")
        fun isValidStateName(s: String): Boolean = STATE_NAME.matches(s)

        /** ASCII only, so a Korean particle written right after `$name` ends the name. */
        private val VALUE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        fun isValidValueName(s: String): Boolean = VALUE_NAME.matches(s)
    }
}

object ScenarioMd {

    private val stepRegex = Regex("""^\s*\d+[.)]\s+(.+)$""")

    /** `## title` — a section of the scenario (other heading levels are documentation). */
    private val sectionRegex = Regex("""^##\s+(\S.*)$""")

    /** The headings that make a section the scenario's preparation (see [Scenario.Section.preparation]). */
    private val PREPARATION_TITLES = listOf("준비", "Preparation")

    /** `deeplink: <address>` / `entry: <화면>` right under a `## title`: where that section starts ([Scenario.Open]). */
    private val openRegex = Regex("""^(deeplink|entry):\s*(\S.*)$""")

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

        val parsed = parseBody(body, name, states)
        val stepDefs = parsed.steps
        val description = parsed.description
        val sections = parsed.sections
        val setupMap = y["setup"] as? Map<*, *>
        val setup = if (setupMap != null) {
            Scenario.Setup(
                deeplink = setupMap["deeplink"] as? String,
                launch = (setupMap["launch"] as? String)?.equals("true", ignoreCase = true) == true,
                clearData = (setupMap["clearData"] as? String)?.equals("true", ignoreCase = true) == true,
                appState = setupMap["appState"] as? String,
                device = DevicePins.parse(setupMap["device"], "scenario '$name' setup"),
            )
        } else null
        if (states.isNotEmpty()) {
            // the state IS the precondition — a second one in setup would be contradictory
            require(setup?.appState == null && setup?.clearData != true) {
                "scenario '$name': `states:` already selects the app state — drop setup.appState/clearData"
            }
        }
        val values = parseValues(y["values"], name)
        if (values.isNotEmpty()) {
            // with `values:` declared a `$word` is a reference — one that names nothing is a typo, not text
            val written = stepDefs.flatMap { d -> listOf(d.command) + d.criteria.map { it.text } } + description + sections.map { it.description }
            val unknown = written.flatMap(::valueRefs).distinct() - values.keys
            require(unknown.isEmpty()) {
                "scenario '$name': ${unknown.joinToString(", ") { "\$$it" }} is not in `values:` (declared: ${values.keys.joinToString(", ")})"
            }
        }
        return Scenario(
            name = name,
            tags = MiniYaml.list(y, "tags"),
            title = MiniYaml.string(y, "title")?.takeIf { it.isNotBlank() },
            owner = MiniYaml.string(y, "owner")?.takeIf { it.isNotBlank() },
            setup = setup,
            steps = stepDefs.map { it.render(null) },
            description = description,
            states = states,
            stepDefs = stepDefs,
            values = values,
            sections = sections,
        )
    }

    /** `values:` — `name: [a, b, …]`, at least two candidates each (one would be the same value every run). */
    private fun parseValues(raw: Any?, scenario: String): Map<String, List<String>> {
        if (raw == null) return emptyMap()
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("scenario '$scenario': `values:` must be `name: [a, b, …]` entries")
        return map.entries.associate { (k, v) ->
            val key = k as String
            require(Scenario.isValidValueName(key)) { "scenario '$scenario': value name '$key' must be [A-Za-z_][A-Za-z0-9_]*" }
            val candidates = (v as? List<*>)?.filterIsInstance<String>()?.distinct() ?: emptyList()
            require(candidates.size >= 2) { "scenario '$scenario': values.$key needs at least two candidates, as `$key: [a, b]`" }
            require(candidates.none { c -> c.any { it in "\"“”‘’'$" } }) { "scenario '$scenario': values.$key candidates cannot contain quotes or \$" }
            key to candidates
        }
    }

    fun parse(file: File): Scenario = parse(file.readText(), file)

    private class Body(val steps: List<Scenario.StepDef>, val description: String, val sections: List<Scenario.Section>)

    /**
     * Numbered lines start a step; `::` lines directly under one attach criteria to it; a `## title`
     * line starts a section that owns the steps up to the next one (`## 준비` the preparation). Prose ahead of the first step or
     * heading is the scenario's description, prose between a heading and its first step that section's
     * (other headings and blank lines dropped).
     */
    private fun parseBody(body: String, name: String, states: List<String>): Body {
        val steps = mutableListOf<Scenario.StepDef>()
        val prose = mutableListOf<String>()
        class Open(val title: String, val first: Int) { val prose = mutableListOf<String>(); var open: Scenario.Open? = null }
        val opened = mutableListOf<Open>()
        fun attach(raw: String) {
            require(steps.isNotEmpty()) { "scenario '$name': `:: $raw` must follow a numbered step" }
            val c = criterion(raw, name, states) ?: return
            steps[steps.lastIndex] = steps.last().let { it.copy(criteria = it.criteria + c) }
        }
        for (line in body.lines()) {
            val heading = sectionRegex.matchEntire(line)
            if (heading != null) {
                require(opened.isNotEmpty() || steps.isEmpty()) { "scenario '$name': steps ahead of the first `##` section belong to no section — give them a heading" }
                opened += Open(heading.groupValues[1].trim(), steps.size)
                continue
            }
            val step = stepRegex.matchEntire(line)
            if (step != null) {
                val spec = StepSpec.parse(step.groupValues[1].trim())
                steps += Scenario.StepDef(spec.command)
                spec.criterion?.let(::attach) // inline `command :: criterion`
                continue
            }
            val criterion = criterionLineRegex.matchEntire(line)
            if (criterion != null) { attach(criterion.groupValues[1].trim()); continue }
            val text = line.trim().takeIf { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val open = opened.lastOrNull()
            if (open == null) { if (steps.isEmpty()) prose += text }
            else if (steps.size == open.first) {
                val at = openRegex.matchEntire(text)?.takeIf { open.prose.isEmpty() && open.open == null }
                if (at == null) open.prose += text
                else {
                    val value = Mentions.plain(at.groupValues[2]).trim()
                    open.open = if (at.groupValues[1] == "deeplink") Scenario.Open(deeplink = value) else Scenario.Open(screen = Mentions.norm(value))
                }
            }
        }
        val sections = opened.mapIndexed { i, o ->
            val size = (opened.getOrNull(i + 1)?.first ?: steps.size) - o.first
            require(size > 0) { "scenario '$name': section '${o.title}' has no steps" }
            val preparation = PREPARATION_TITLES.any { it.equals(o.title, ignoreCase = true) }
            require(!preparation || o.open == null) { "scenario '$name': `## ${o.title}` prepares the scenario's own entry — it takes no `deeplink:`/`entry:` line" }
            Scenario.Section(o.title, o.prose.joinToString(" "), o.first, size, preparation = preparation, open = o.open)
        }
        val titles = sections.map { it.title }
        require(titles.distinct().size == titles.size) { "scenario '$name': two sections share a title (${titles.joinToString(", ")})" }
        // the preparation is ahead of what it prepares for — and of something: alone it prepares nothing
        require(sections.drop(1).none { it.preparation }) { "scenario '$name': `## ${sections.first { it.preparation }.title}` comes first — it is what runs before the other sections" }
        require(sections.firstOrNull()?.preparation != true || sections.size > 1) { "scenario '$name': `## ${sections[0].title}` needs a section after it to prepare for" }
        return Body(steps, prose.joinToString(" "), sections)
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

/**
 * `[[화면]]` in a scenario's prose or a section's sentence: the check is about that screen too. The scenario is
 * written once, where the action happens; the report shows the check on the mentioned screen's page as well
 * (a screen is a group directory, named as the directory is). Only the report reads it — a step never does.
 */
object Mentions {
    private val RE = Regex("""\[\[([^\[\]]+)]]""")

    /** The screens [text] mentions, in order, each once. */
    fun of(text: String): List<String> = RE.findAll(text).map { norm(it.groupValues[1]) }.distinct().toList()

    /** [text] with each mention as its screen's name alone. */
    fun plain(text: String): String = RE.replace(text) { it.groupValues[1].trim() }

    /** [text] cut at its mentions: `(plain text, null)` and `(name as written, screen)` in turn. */
    fun parts(text: String): List<Pair<String, String?>> = buildList {
        var at = 0
        for (m in RE.findAll(text)) {
            if (m.range.first > at) add(text.substring(at, m.range.first) to null)
            add(m.groupValues[1].trim() to norm(m.groupValues[1]))
            at = m.range.last + 1
        }
        if (at < text.length) add(text.substring(at) to null)
    }

    /** A directory name read from disk and one typed in a file may differ in Unicode form (macOS hands out decomposed Hangul). */
    fun norm(name: String): String = java.text.Normalizer.normalize(name.trim(), java.text.Normalizer.Form.NFC)
}
