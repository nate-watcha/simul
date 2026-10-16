package dev.nate.uiagent.cli

import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.device.computeDiff

/**
 * Deterministic step evidence and the Verify judgment — the content-independent signals a
 * replay re-checks without a model: quoted labels, structural resourceIds and stable states.
 */

/** The labels a scenario could reference on the current screen, deduplicated and capped. */
fun screenLabels(elements: List<LogicalElement>, cap: Int = 30): List<String> =
    elements.mapNotNull { it.label ?: it.resourceId }.map { it.replace("\r\n", " ").replace('\n', ' ').trim() }
        .filter { it.isNotEmpty() }.distinct().take(cap)

private val quotedRegex = Regex("[\"“‘']([^\"“”‘’']+)[\"”’']")

/** Does [label] name an element in [elements]? The same matching family as `DeviceController.resolve`. */
fun labelPresent(elements: List<LogicalElement>, label: String): Boolean =
    elements.any { it.matchesExact(label) || it.matchesLoose(label) }

/** The two evidence channels replay checks: label presence and label+state presence. */
data class Evidence(val appeared: List<String>, val states: List<StateEvidence>)

/** States that survive across observations — worth asserting. (focused is IME-transient.) */
private val STABLE_STATES = setOf("selected", "checked")

/**
 * Deterministic step-completion evidence. Free-text labels are NEVER auto-harvested — a
 * screen change surfaces dynamic content (rotating banner titles, page indicators) right next
 * to the stable chrome, and one volatile label in the evidence breaks every future replay.
 * Only content-independent signals qualify:
 *
 * 1. Quoted labels from the step text that are present afterwards — the author's explicit
 *    assertion (this is what Verify steps are).
 * 2. resourceIds of newly appeared nodes — a view id names the view, not its content, so the
 *    set {btn_play, tv_title, …} is a stable structural signature of the destination screen
 *    (recycled cells keep their ids whatever they display).
 * 3. Stable states (selected/checked) gained during the step, keyed by resourceId when the
 *    element has one — "tab_library=selected" is the real proof of a tab switch.
 *
 * Replay passes only when everything resolves on screen (labelPresent matches ids too).
 */
fun extractEvidence(
    stepText: String,
    before: List<LogicalElement>,
    after: List<LogicalElement>,
    volatileKeys: Set<String>,
): Evidence {
    // Compare state ownership directly — computeDiff's `changed` picks an arbitrary element
    // among same-key duplicates, which can hide the state carrier.
    fun stableStates(els: List<LogicalElement>): Set<StateEvidence> = els
        .filter { it.label != null || it.resourceId != null }
        .flatMap { e -> e.state.filter { it in STABLE_STATES }.map { StateEvidence(e.resourceId ?: e.label!!, it) } }
        .toSet()

    val quotedPresent = quotedLabels(stepText).filter { labelPresent(after, it) }

    // States GAINED during the step, plus the current stable state of any quoted element —
    // "Tap the 구독 tab" asserts 구독=selected even when 구독 was already the launch tab.
    val gained = stableStates(after) - stableStates(before)
    val quotedStates = quotedPresent.flatMap { q ->
        after.filter { it.label?.equals(q, ignoreCase = true) == true || it.resourceId?.equals(q, ignoreCase = true) == true }
            .flatMap { e -> e.state.filter { it in STABLE_STATES }.map { StateEvidence(e.resourceId ?: e.label!!, it) } }
    }
    val states = (gained + quotedStates)
        .distinct()
        .filterNot { it.ref in volatileKeys }
        .take(2)

    val newIds = computeDiff(before, after, volatileKeys).added
        .mapNotNull { it.resourceId }
        .distinct()
        .take(3)
    return Evidence((quotedPresent.take(3) + newIds).distinct(), states)
}

/** Does an element matching [se]'s ref (resourceId or label) currently carry its state? */
fun statePresent(elements: List<LogicalElement>, se: StateEvidence): Boolean =
    elements.any { it.matchesExact(se.ref) && se.state in it.state }

// ------------------------------------------------------- deterministic verify judgment

/** The quoted labels of a step sentence — the author-chosen anchors. */
fun quotedLabels(text: String): List<String> = quotedRegex.findAll(text)
    .map { it.groupValues[1].trim() }
    .filter { it.isNotEmpty() }
    .distinct()
    .toList()

private val negationRegex = Regex("""\bnot\b|\bno longer\b|n't\b""", RegexOption.IGNORE_CASE)

/** Does the sentence assert absence rather than presence? */
fun isNegated(text: String): Boolean = negationRegex.containsMatchIn(text)

data class VerifyJudgment(val passed: Boolean, val reason: String?)

/**
 * Deterministic judgment of a Verify step against the current elements — the record-mode
 * twin of the replay evidence gate, so a record-time pass can never assert more than CI
 * will re-check. Supports presence (`is shown`), absence (`is not shown`) and stable state
 * (`is selected` / `is checked`) over the quoted anchors. Returns null when the step names
 * no quoted anchor — nothing machine-checkable, the caller falls back to the LLM.
 */
fun judgeVerify(stepText: String, elements: List<LogicalElement>): VerifyJudgment? {
    val anchors = quotedLabels(stepText)
    if (anchors.isEmpty()) return null
    val negated = isNegated(stepText)
    // only words outside quotes count — a quoted label may itself contain "selected"
    val outside = quotedRegex.replace(stepText, "").lowercase()
    val state = STABLE_STATES.firstOrNull { it in outside }

    fun holds(anchor: String) =
        if (state != null) statePresent(elements, StateEvidence(anchor, state))
        else labelPresent(elements, anchor)

    val offenders = if (negated) anchors.filter(::holds) else anchors.filterNot(::holds)
    if (offenders.isEmpty()) return VerifyJudgment(true, null)
    val what = state ?: "on screen"
    val quoted = offenders.joinToString(", ") { "\"$it\"" }
    return VerifyJudgment(false, if (negated) "unexpectedly $what: $quoted" else "not $what: $quoted")
}
