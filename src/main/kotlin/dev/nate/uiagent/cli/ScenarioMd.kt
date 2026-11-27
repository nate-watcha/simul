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
 * runs them in file order, each from the app started over at the scenario's entry. Ahead of the first heading a
 * `setup:` block may say the state they all start from ([Ensure]): `ensure` is a sentence about the app, `by` what
 * to do when it does not hold. That is not a test and is not written as one — no heading, no numbered steps — and
 * it is looked at in every run, a replay too, since what the last run left on the server is only known then —
 *
 * ```
 * setup:
 *   ensure: 이 유저를 팔로우하고 있다
 *   by: "팔로우"를 누른다
 *
 * ## 팔로우 취소
 * 팔로우 중인 유저의 팔로우를 취소할 수 있다.
 * 1. "팔로우 취소"를 누른다
 *
 * ## 팔로우 추가
 * 1. "팔로우"를 누른다
 * ```
 *
 * What is written where. The frontmatter is the file's: `name`, `tags`, `values`, `states`, and `setup` — as
 * whom and where every section starts (`appState`, and one of `deeplink`, `launch`, `entry`). A section that
 * starts otherwise says so in a `setup:` block of its own right under its heading, with the same keys
 * (`appState`, `deeplink`, `entry`) and the same meaning, for that section alone:
 *
 * ```
 * ## 다시 들어와도 유지
 *
 * setup:
 *   entry: 나의왓챠-프로필헤더
 *
 * @나의왓챠-프로필헤더 링크가 저장한 주소로 바뀌어 있다.
 * 1. "https://$link"가 보이는지 확인한다
 * ```
 *
 * `@화면` in the prose or a section's sentence is a mention ([Mentions]) — the report's, not the runner's.
 *
 * A scenario may start where a section of another scenario ended, on what that scenario wrote
 * (`setup.after`, see [After]): the row a rating lands in is checked by the rating scenario, and what
 * tapping the rated title in that row does is the row's own scenario, started right there —
 *
 * ```
 * setup:
 *   after: 작품상세/rating > 평가 뒤 활동 탭
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
    /** The state every section starts from, and how to get there (the body's `setup:` block); null when the file has none. */
    val ensure: Ensure? = null,
    /**
     * The screen (group directory) whose way in this scenario starts from: its `_entry.md` says where the app
     * is opened and the steps from there, and the runner plays them from that file's trace before this
     * scenario's first step ([SimulProject.load] sets it, and [setup] to the entry's). Null for a scenario that
     * opens the app by itself. It is a key ([Modes.key]): where the way in is one mode's, the mode follows the
     * screen (`프로필/홈@타인`).
     */
    val via: String? = null,
    /** The screens [description] mentions (`@화면`), as [SimulProject.load] resolved them against the project's screens. */
    val mentions: List<String> = emptyList(),
    /**
     * The mode of its screen this scenario is of ([Modes]): the one its file's name says (`헤더@타인.md`), or the
     * only one that has the place it is in. Null for a scenario common to the modes — it runs once, in the first
     * of them — and for every scenario of a screen without modes. [SimulProject.load] sets it.
     */
    val mode: String? = null,
    /**
     * The values of the mode this scenario runs in ([ModeData]), when it names one (`$주인`): its steps are filled
     * with them, its prose keeps the names — the report reads the line as spec and shows the values beside it.
     */
    val data: Map<String, String> = emptyMap(),
    /**
     * A file common to its screen's modes that names a mode's value is the same behaviour on each mode's data: it
     * runs once in each, and this is how it starts in each ([SimulProject.load] sets it; see [variants]).
     */
    val inModes: List<InMode> = emptyList(),
    /** This instance is one of those runs, the one in [mode]. */
    val perMode: Boolean = false,
    /** Where this scenario starts when that is the end of another scenario's section ([SimulProject.load] resolves `setup.after`). */
    val after: After? = null,
    /** The scenarios that start where a section of this one ends, as a run takes them along ([withHosts]); not part of the file. */
    val followers: List<Follower> = emptyList(),
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
    fun variants(): List<Scenario> = when {
        inModes.isNotEmpty() -> inModes.map(::forMode)
        states.isEmpty() -> listOf(this)
        else -> states.map(::forState)
    }

    /** This scenario resolved for one of its runs ([run]): a declared state, or a mode; one that has neither returns itself. */
    fun variant(run: String?): Scenario = when {
        run == null -> this
        inModes.isNotEmpty() -> forMode(inModes.firstOrNull { it.mode == run } ?: throw IllegalArgumentException("scenario '$name' runs in no mode '$run' (modes: ${inModes.map { it.mode }})"))
        states.isEmpty() -> this
        else -> forState(run)
    }

    /**
     * What tells this run from the file's others, after an `@` in its name and its trace's: the state of a
     * `states:` scenario, the mode of one that runs in each. Null for a file that is one run.
     */
    val run: String? get() = state ?: mode.takeIf { perMode }

    /** How a file that runs in each mode starts in [mode]: that mode's values, its way in, and the sections as they open there. */
    data class InMode(val mode: String, val data: Map<String, String>, val via: String?, val setup: Setup?, val sections: List<Section>)

    private fun forMode(m: InMode): Scenario = filled(m.data).copy(
        name = "$name@${m.mode}", mode = m.mode, perMode = true, via = m.via, setup = m.setup, sections = m.sections, inModes = emptyList(),
    )

    /** This scenario with [data] in its steps, where they name a value of it. */
    fun filled(data: Map<String, String>): Scenario = if (data.isEmpty()) this else copy(
        data = data,
        steps = steps.map { ModeData.fill(it, data) },
        stepDefs = stepDefs.map { d -> d.copy(command = ModeData.fill(d.command, data), criteria = d.criteria.map { it.copy(text = ModeData.fill(it.text, data)) }) },
    )

    /** Everything written in the file that a value can be named in: its prose, its sections' sentences, its steps. */
    val written: List<String> get() = listOf(description) + sections.map { it.description } + stepDefs.flatMap { d -> listOf(d.command) + d.criteria.map { it.text } }

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
        /**
         * `entry: <화면>` — start from that screen's way in (its `_entry.md`) instead of an address of one's own.
         * Left out, a scenario that opens nothing itself starts from the way in of its own directory when it has
         * one ([SimulProject.load] resolves either into [Scenario.via] and the entry's address).
         */
        val entry: String? = null,
        /**
         * `after: <시나리오> > <구간>` — start on the screen that section of another scenario ended on, with what
         * that scenario had written by then, instead of opening the app ([After]). It takes the place of
         * `entry`, `deeplink`, `launch` and the account: all of those are the other scenario's.
         */
        val after: String? = null,
    )

    /**
     * A scenario that starts where the section [section] of the scenario [file] ended. It is run inside that
     * scenario's run, right after the section — on the same device, on the screen the section left, before the
     * sections that follow put back what the scenario wrote. What it taps is then something that scenario put
     * there, by a name both of them write, not whatever the account happened to hold (stone 나의 왓챠 활동,
     * 2026-11: the first poster of a row was "재혼 황후" when recorded and "애프터 썬셋" three weeks later).
     * When the section does not pass, this scenario is not run: the failure is the other scenario's, once.
     *
     * [file] is the other scenario's path under the scenarios directory; [title] what the report calls it and
     * [screen] the screen it is of.
     */
    data class After(val file: String, val section: String, val title: String, val screen: String)

    /** A scenario taken along by the run of the one it starts from: its file, itself as it runs, and the section it follows. */
    data class Follower(val md: File, val scenario: Scenario, val section: String)

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
     * `setup:` in the body, ahead of the first heading — [state] (`ensure:`) is one sentence about the app as it has
     * to be before the sections run, [by] (`by:`, one step or a list of them) what to do when it is not so, each a
     * step as a section would write it: one action, its target in quotes. The runner asks the model whether [state]
     * holds; when it does nothing is touched, when it does not the [by] steps are done, the app is started over at the
     * scenario's entry and it asks again — as often as it takes to delete what is there one at a time, up to a limit.
     */
    data class Ensure(val state: String, val by: List<String>)

    /**
     * One `## title` part of a scenario: its prose and the steps `[first, first + size)` of [Scenario.steps].
     * The [preparation] is not a section of the file but what its [Ensure] becomes for the run — always the first,
     * its one step the state's sentence: looked at by the model in every mode, never traced, and what brings the app
     * to the state the sections after it start from.
     */
    data class Section(
        val title: String, val description: String, val first: Int, val size: Int, val preparation: Boolean = false,
        /** How this section starts when that is not as the scenario does — the `setup:` block under its heading. */
        val open: Open? = null,
        /** The screens [description] mentions (`@화면`), as [SimulProject.load] resolved them. */
        val mentions: List<String> = emptyList(),
    ) {
        val steps: IntRange get() = first until first + size

        /**
         * The section is a look from somewhere else — it has a `setup:` of its own, or its sentence mentions
         * another screen (`@화면`). What it finds says nothing about the sections after it, so they run even
         * when it fails.
         */
        val looksElsewhere: Boolean get() = open != null || Mentions.has(description)
    }

    /**
     * A section's own `setup:`: what it starts with instead of the scenario's. `deeplink: <address>` opens that
     * address, `entry: <화면>` goes the way in of that screen (its `_entry.md`) — one of the two, or neither
     * and the scenario's own place; `appState: <name>` is the account it runs as. A key left out is the scenario's.
     */
    data class Open(val deeplink: String? = null, val screen: String? = null, val appState: String? = null) {
        /** The section starts at a place of its own, not only as someone else. */
        val elsewhere: Boolean get() = deeplink != null || screen != null
    }

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

    /** How a preparation was written before the body's `setup:` block ([Scenario.Ensure]): said, so the heading is not read as a test. */
    private val OLD_PREPARATION_TITLES = listOf("준비", "Preparation")
    /** What the report calls the part of a run that looked at — and made — the state the scenario starts from. */
    const val PREPARATION = "준비"
    private val FILE_SETUP_KEYS = listOf("ensure", "by")
    /** One of several `by:` steps, on its own indented `- ` line. */
    private val setupItemRegex = Regex("""^\s+-\s+(\S.*)$""")

    /** `setup:` right under a `## title` opens that section's own setup ([Scenario.Open]); its keys are the indented lines after it. */
    private val setupKeyRegex = Regex("""^\s+([A-Za-z]+):\s*(.*)$""")
    private val SECTION_SETUP_KEYS = listOf("entry", "deeplink", "appState")

    /** How a section's start and a mention were written before `setup:` and `@화면`: said, so the file is not read as prose. */
    private val oldOpenRegex = Regex("""^(deeplink|entry|appState):\s*\S.*$""")
    private val oldMentionRegex = Regex("""\[\[([^\[\]]+)]]""")

    /** A continuation line under a step: `:: criterion` or `:: state → criterion`. */
    private val criterionLineRegex = Regex("""^\s*::\s*(.*)$""")
    private val boundCriterionRegex = Regex("""^([A-Za-z0-9._-]+)\s*(?:→|->)\s*(.*)$""")

    /** [known] are `$name`s declared outside the file — its screen's ([ModeData]) — which its `values:` need not list. */
    fun parse(text: String, file: File? = null, known: Collection<String> = emptyList()): Scenario {
        val (front, body) = splitFrontmatter(text)
        val y = if (front != null) MiniYaml.parse(front) else emptyMap()
        val name = MiniYaml.string(y, "name") ?: file?.nameWithoutExtension?.let(Modes::base) ?: "scenario"
        val states = MiniYaml.list(y, "states")
        require(states.distinct().size == states.size) { "scenario '$name': duplicate entries in states: $states" }
        require(states.all(Scenario::isValidStateName)) { "scenario '$name': state names must be [A-Za-z0-9._-]+ (states: $states)" }

        // where the tabs of a screen were written before `_screen.md`: said, so the line is not dropped unread
        require(y["tabs"] == null) { "scenario '$name': a screen's tabs are the bracketed row of `parts:` in its ${ScreenMd.FILE} — `parts: [상단 바, [홈, 활동]]`" }
        val parsed = parseBody(body, name, states)
        val stepDefs = parsed.steps
        val description = parsed.description
        val sections = parsed.sections
        val ensure = parsed.ensure
        val setupMap = y["setup"] as? Map<*, *>
        val setup = if (setupMap != null) {
            Scenario.Setup(
                deeplink = setupMap["deeplink"] as? String,
                launch = (setupMap["launch"] as? String)?.equals("true", ignoreCase = true) == true,
                clearData = (setupMap["clearData"] as? String)?.equals("true", ignoreCase = true) == true,
                appState = setupMap["appState"] as? String,
                device = DevicePins.parse(setupMap["device"], "scenario '$name' setup"),
                entry = (setupMap["entry"] as? String)?.let(Mentions::norm)?.takeIf { it.isNotEmpty() },
                after = (setupMap["after"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            )
        } else null
        require(setup?.entry == null || (setup.deeplink == null && !setup.launch)) {
            "scenario '$name': setup takes one of `entry`, `deeplink`, `launch` — `entry: ${setup?.entry}` already says where it starts"
        }
        if (setup?.after != null) {
            // where it starts, and as whom, is all the other scenario's
            require(setup.entry == null && setup.deeplink == null && !setup.launch && setup.appState == null && !setup.clearData) {
                "scenario '$name': `after: ${setup.after}` already says where it starts and as whom — drop setup.entry/deeplink/launch/appState/clearData"
            }
            require(states.isEmpty()) { "scenario '$name': it starts after another scenario's section, in that scenario's account — it takes no `states:`" }
            // a section starts the app over, and this scenario starts where another one stood
            require(sections.isEmpty()) { "scenario '$name': it starts after another scenario's section and goes on from that screen — it takes no `##` sections of its own" }
        }
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
            val unknown = written.flatMap(::valueRefs).distinct() - values.keys - known.toSet()
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
            ensure = ensure,
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

    fun parse(file: File, known: Collection<String> = emptyList()): Scenario = parse(file.readText(), file, known)

    private class Body(val steps: List<Scenario.StepDef>, val description: String, val sections: List<Scenario.Section>, val ensure: Scenario.Ensure? = null)

    /**
     * Numbered lines start a step; `::` lines directly under one attach criteria to it; a `## title`
     * line starts a section that owns the steps up to the next one. Prose ahead of the first step or
     * heading is the scenario's description, prose between a heading and its first step that section's
     * (other headings and blank lines dropped). A `setup:` block ahead of the first heading is the state the
     * sections start from ([Scenario.Ensure]): it becomes the run's preparation, ahead of every step.
     */
    private fun parseBody(body: String, name: String, states: List<String>): Body {
        val steps = mutableListOf<Scenario.StepDef>()
        val prose = mutableListOf<String>()
        class Open(val title: String, val first: Int) { val prose = mutableListOf<String>(); var setup: MutableMap<String, String>? = null }
        val opened = mutableListOf<Open>()
        /** Inside the `setup:` block of the last heading: its indented `key: value` lines, up to a blank or an unindented one. */
        var inSetup = false
        /** The file's own `setup:` block, ahead of the first heading: `ensure:` and `by:` — a step after it, or `- ` lines under it. */
        var fileSetup: MutableMap<String, MutableList<String>>? = null
        var inFileSetup = false
        var listKey: String? = null
        fun attach(raw: String) {
            require(steps.isNotEmpty()) { "scenario '$name': `:: $raw` must follow a numbered step" }
            val c = criterion(raw, name, states) ?: return
            steps[steps.lastIndex] = steps.last().let { it.copy(criteria = it.criteria + c) }
        }
        for (line in body.lines()) {
            if (inFileSetup) {
                val key = setupKeyRegex.matchEntire(line)
                val item = setupItemRegex.matchEntire(line)
                if (key != null) {
                    val (k, v) = key.destructured
                    require(k in FILE_SETUP_KEYS) { "scenario '$name': the `setup:` block ahead of the sections takes ${FILE_SETUP_KEYS.joinToString(" and ") { "`$it`" }} — `$k` is the frontmatter's" }
                    require(fileSetup!!.put(k, mutableListOf()) == null) { "scenario '$name': setup names `$k` twice" }
                    if (v.isNotBlank()) fileSetup!![k]!! += v.trim()
                    listKey = k.takeIf { v.isBlank() }
                    continue
                }
                if (item != null && listKey != null) { fileSetup!![listKey]!! += item.groupValues[1].trim(); continue }
                inFileSetup = false
            }
            if (inSetup) {
                val key = setupKeyRegex.matchEntire(line)
                if (key != null) {
                    val (k, v) = key.destructured
                    val section = opened.last()
                    require(k in SECTION_SETUP_KEYS) { "scenario '$name': section '${section.title}' setup takes ${SECTION_SETUP_KEYS.joinToString(", ")} — `$k` is the file's (frontmatter)" }
                    require(v.isNotBlank()) { "scenario '$name': section '${section.title}' setup: `$k:` has no value" }
                    require(section.setup!!.put(k, v.trim()) == null) { "scenario '$name': section '${section.title}' setup names `$k` twice" }
                    continue
                }
                inSetup = false
            }
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
            if (open == null) {
                if (steps.isEmpty() && text == "setup:") {
                    require(fileSetup == null) { "scenario '$name': two `setup:` blocks ahead of the sections" }
                    fileSetup = mutableMapOf(); inFileSetup = true
                } else if (steps.isEmpty()) prose += text
            }
            else if (steps.size == open.first) {
                val first = open.prose.isEmpty() && open.setup == null
                if (first && text == "setup:") { open.setup = mutableMapOf(); inSetup = true; continue }
                require(!(first && oldOpenRegex.matches(text))) {
                    "scenario '$name': section '${open.title}': `$text` goes in a `setup:` block under the heading — `setup:` and, indented under it, `$text`"
                }
                open.prose += text
            }
        }
        (prose + opened.flatMap { it.prose }).firstNotNullOfOrNull { oldMentionRegex.find(it) }?.let { m ->
            throw IllegalArgumentException("scenario '$name': a mention is written `@${m.groupValues[1].trim()}`, not `${m.value}`")
        }
        val sections = opened.mapIndexed { i, o ->
            val size = (opened.getOrNull(i + 1)?.first ?: steps.size) - o.first
            require(size > 0) { "scenario '$name': section '${o.title}' has no steps" }
            require(OLD_PREPARATION_TITLES.none { it.equals(o.title, ignoreCase = true) }) {
                "scenario '$name': `## ${o.title}` — the state a scenario starts from is not a section: write it in a `setup:` block ahead of the first heading, " +
                    "`ensure:` the state in one sentence and `by:` the step(s) to do when it does not hold"
            }
            val own = o.setup?.let { m ->
                require(m.isNotEmpty()) { "scenario '$name': section '${o.title}' setup is empty — ${SECTION_SETUP_KEYS.joinToString(", ")} go indented under it" }
                require(m["entry"] == null || m["deeplink"] == null) { "scenario '$name': section '${o.title}' setup takes `entry` or `deeplink`, not both" }
                m["appState"]?.let { require(Scenario.isValidStateName(it)) { "scenario '$name': section '${o.title}' setup: appState '$it' must be [A-Za-z0-9._-]+" } }
                Scenario.Open(deeplink = m["deeplink"], screen = m["entry"]?.let(Mentions::norm), appState = m["appState"])
            }
            Scenario.Section(o.title, o.prose.joinToString(" "), o.first, size, open = own)
        }
        val titles = sections.map { it.title }
        require(titles.distinct().size == titles.size) { "scenario '$name': two sections share a title (${titles.joinToString(", ")})" }
        val ensure = fileSetup?.let { m ->
            val state = m["ensure"]?.singleOrNull()
            require(state != null) { "scenario '$name': setup needs `ensure:` — the state the sections start from, in one sentence" }
            val by = m["by"].orEmpty()
            require(by.isNotEmpty()) { "scenario '$name': setup needs `by:` — what to do when `ensure` does not hold: one step after it, or `- ` lines under it" }
            // the sections are what the state is for, and what a trace keys its steps by
            require(sections.isNotEmpty()) { "scenario '$name': `setup: ensure` is the state `##` sections start from — give the steps a heading" }
            Scenario.Ensure(state, by)
        } ?: return Body(steps, prose.joinToString(" "), sections)
        // the run's preparation: one step, the state's sentence, ahead of every section's
        return Body(
            listOf(Scenario.StepDef(ensure.state)) + steps, prose.joinToString(" "),
            listOf(Scenario.Section(PREPARATION, "", 0, 1, preparation = true)) + sections.map { it.copy(first = it.first + 1) },
            ensure,
        )
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
 * `@화면` in a scenario's prose or a section's sentence: the check is about that screen too. The scenario is
 * written once, where the action happens; the report shows the check on the mentioned screen's page as well
 * (a screen is a group directory, named as the directory is). Only the report reads it — a step never does.
 *
 * A screen's name may hold spaces and a Korean particle may follow it unspaced (`@프로필 수정의 링크`), so where a
 * mention ends is not in the text: it is the longest of the known screens' names the text goes on with.
 * [SimulProject.load] resolves a scenario's mentions against the project's screens (and refuses an `@` that
 * names none); a result carries them, and the report cuts the text by those.
 */
object Mentions {
    /** An `@` that starts a mention: not inside a word or an address (`simul201@watcha.com`). */
    private val AT = Regex("""(?<![\p{L}\p{N}_.@])@(?=\S)""")

    /** Does [text] mention anything — by its writing alone, whatever the screens are. */
    fun has(text: String): Boolean = AT.containsMatchIn(text)

    /** [text] cut at its mentions of [screens]: `(text, null)` and `(the screen's name, screen)` in turn. An `@` that names none of them stays text. */
    fun parts(text: String, screens: Collection<String>): List<Pair<String, String?>> = buildList {
        val t = norm(text)
        val names = screens.map(::norm).filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
        var at = 0
        for (m in AT.findAll(t)) {
            if (m.range.first < at) continue
            val name = names.firstOrNull { t.startsWith(it, m.range.first + 1) } ?: continue
            if (m.range.first > at) add(t.substring(at, m.range.first) to null)
            add(name to name)
            at = m.range.first + 1 + name.length
        }
        if (at < t.length) add(t.substring(at) to null)
    }

    /** The [screens] that [text] mentions, in order, each once. */
    fun of(text: String, screens: Collection<String>): List<String> = parts(text, screens).mapNotNull { it.second }.distinct()

    /**
     * What follows each `@` of [text] that names none of [screens], up to the next space — a typo, or a screen that
     * does not exist. A path that goes on past the longest name it starts with (`@나의 왓챠/없는 곳`) names nothing
     * either: it is not a mention of the screen above.
     */
    fun unknown(text: String, screens: Collection<String>): List<String> {
        val t = norm(text)
        val names = screens.map(::norm).filter { it.isNotEmpty() }
        return AT.findAll(t).mapNotNull { m ->
            val at = m.range.first + 1
            val name = names.filter { t.startsWith(it, at) }.maxByOrNull { it.length }
            when {
                name == null -> t.substring(at).takeWhile { !it.isWhitespace() }
                t.getOrNull(at + name.length) == '/' -> name + t.substring(at + name.length).takeWhile { !it.isWhitespace() }
                else -> null
            }
        }.toList()
    }

    /** A directory name read from disk and one typed in a file may differ in Unicode form (macOS hands out decomposed Hangul). */
    fun norm(name: String): String = java.text.Normalizer.normalize(name.trim(), java.text.Normalizer.Form.NFC)
}
