package dev.nate.uiagent.cli

/**
 * One scenario step, split into what the model executes and what the harness judges.
 *
 * `<command> :: <criterion>` attaches an expected result to an action step. The criterion is
 * withheld while the model acts (a goal-seeking agent would otherwise repair a broken app's
 * path toward it) and delivered only after the first tool result, when the action's diff is
 * already on the table — the model then judges informed instead of guessing the app's policy
 * (e.g. "re-entering a tab restores its nested screen" vs "a tab tap opens the tab root").
 *
 * The full step text (including the criterion) stays in the trace and the report; only the
 * COMMAND message sent to the model is stripped. Quoted anchors in the criterion are therefore
 * harvested as replay evidence by the existing pipeline.
 */
data class StepSpec(val command: String, val criterion: String?) {
    /** Assertion steps — judged by the runner when they carry quoted anchors (see Runner). */
    val isVerify: Boolean get() = command.startsWith("Verify", ignoreCase = true)

    companion object {
        private const val SEPARATOR = " :: "

        fun parse(text: String): StepSpec {
            val idx = text.indexOf(SEPARATOR)
            if (idx < 0) return StepSpec(text.trim(), null)
            return StepSpec(
                command = text.take(idx).trim(),
                criterion = text.substring(idx + SEPARATOR.length).trim().ifEmpty { null },
            )
        }
    }
}
