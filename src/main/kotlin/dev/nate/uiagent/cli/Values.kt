package dev.nate.uiagent.cli

import dev.nate.uiagent.LogicalElement

/** `$name` in a scenario's prose and steps — a reference to an entry of its frontmatter `values:`. */
private val valueRef = Regex("""\$([A-Za-z_][A-Za-z0-9_]*)""")

/** The `values:` names [text] refers to, in order of first appearance. */
fun valueRefs(text: String): List<String> = valueRef.findAll(text).map { it.groupValues[1] }.distinct().toList()

/** [text] with every `$name` that [picked] has a value for replaced by it; other references stay as written. */
fun fillValues(text: String, picked: Map<String, String>): String =
    if (picked.isEmpty()) text else valueRef.replace(text) { m -> picked[m.groupValues[1]] ?: m.value }

/**
 * The values one run of a scenario uses for its `values:` — a name with a few candidates the author
 * listed, written `$name` where the value goes:
 *
 * ```
 * values:
 *   name: [노을, 바다, 구름]
 * ---
 * 2. "이름" 필드에 "$name"을 입력한다
 * 3. "완료"를 누른다 :: 프로필에 "$name"이 보인다
 * ```
 *
 * A value a scenario saves stays on the server, so the same value typed again by the next run changes
 * nothing and the save does nothing (the step FAILS on a disabled button). A run therefore picks, for
 * each name, a candidate that is NOT on the screen at the first step that uses it — the edit screen
 * shows the value the last run left — and keeps it for the rest of the scenario. No memory of earlier
 * runs is needed, which a CI checkout would not have. When the current value is not on that screen the
 * pick is random among the candidates, so a repeat is possible (1 in n).
 *
 * The model and the report get the step with the value filled in; the trace keeps `$name`
 * ([template]) — a trace must not differ between two runs — and replay fills its own pick back in
 * ([fill]), still without a model.
 */
class ValuePicks(
    private val values: Map<String, List<String>>,
    private val log: (String) -> Unit = {},
    private val choose: (List<String>) -> String = { it.random() },
) {
    private val picks = linkedMapOf<String, String>()
    /** Filled string → the template it came from: what [template] puts back, exactly. */
    private val templates = mutableMapOf<String, String>()

    val picked: Map<String, String> get() = picks

    /** Pick a value for every name [text] refers to that has none yet, avoiding what [screen] already shows. */
    fun settle(text: String, screen: List<LogicalElement>) {
        for (name in valueRefs(text)) {
            if (name in picks) continue
            val candidates = values[name] ?: continue
            val free = candidates.filterNot { labelPresent(screen, it) }
            val value = if (free.isEmpty()) candidates.first() else choose(free)
            picks[name] = value
            templates.putIfAbsent(value, "$$name")
            val shown = candidates - free.toSet()
            log("    value \$$name = \"$value\"" + when {
                free.isEmpty() -> " — every candidate is already on screen, a save of it may change nothing"
                shown.isNotEmpty() -> " (on screen now: ${shown.joinToString(", ") { "\"$it\"" }})"
                else -> ""
            })
        }
    }

    /** [text] with this run's values in place of their `$name`. */
    fun fill(text: String): String =
        fillValues(text, picks).also { if (it != text) templates.putIfAbsent(it, text) }

    /** A string of this run put back into the form the scenario wrote it in. */
    fun template(text: String): String = templates[text] ?: text

    /** A trace step as this run performs it. */
    fun fill(step: TraceStep): TraceStep = if (picks.isEmpty()) step else step.copy(
        actions = step.actions.map { a ->
            a.copy(
                args = a.args.mapValuesTo(LinkedHashMap()) { fill(it.value) },
                target = a.target?.let { t -> t.copy(label = t.label?.let(::fill)) },
                text = a.text?.let(::fill),
            )
        },
        evidence = step.evidence.map(::fill),
        stateEvidence = step.stateEvidence.map { it.copy(ref = fill(it.ref)) },
    )

    /**
     * What this run did in [step], as the trace keeps it. Anything the run filled is put back exactly;
     * the one string the model wrote on its own is the text it typed, where a value it was told to
     * type is found inside whatever it typed.
     */
    fun template(step: TraceStep): TraceStep = if (picks.isEmpty()) step else step.copy(
        actions = step.actions.map { a ->
            a.copy(
                args = a.args.mapValuesTo(LinkedHashMap()) { template(it.value) },
                target = a.target?.let { t -> t.copy(label = t.label?.let(::template)) },
                text = a.text?.let(::templateTyped),
            )
        },
        evidence = step.evidence.map(::template),
        stateEvidence = step.stateEvidence.map { it.copy(ref = template(it.ref)) },
    )

    private fun templateTyped(text: String): String = templates[text]
        ?: picks.entries.sortedByDescending { it.value.length }.fold(text) { acc, (name, value) -> acc.replace(value, "$$name") }

    /** The scenario's quoted labels of [stepText] become known both ways, so a recorded anchor finds its template. */
    fun learnQuotes(stepText: String) = quotedLabels(stepText).forEach { fill(it) }
}
