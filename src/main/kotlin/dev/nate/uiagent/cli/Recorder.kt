package dev.nate.uiagent.cli

import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.Origin
import dev.nate.uiagent.device.ActionListener

/** Tools that mutate or observe device state as an *action* (screenshot + record worthy). */
private val ACTION_TOOLS = setOf("tap", "type", "scroll", "scrollToFind", "back", "waitForChange")

/**
 * When an action happened, for the report's timeline: [atMs] is wall-clock millis at the
 * moment the gesture went to the device, so a screen recording can be seeked to it.
 */
data class ActionMark(val tool: String, val target: String?, val atMs: Long)

/**
 * Controller listener for one step: records replayable actions, stamps each with the time it
 * happened, and (when given a [screenshot] sink) takes before/after screenshots. Guard-rejected
 * calls (ERROR/AMBIGUOUS — no gesture happened) are not recorded; everything that moved the
 * device is, including failed explorations (a NOT_FOUND scrollToFind still scrolled, and
 * replay must reproduce that state trajectory).
 */
class StepRecorder(
    private val stepIndex: Int,
    private val screenshot: ((name: String) -> String?)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The step command's quoted labels — the author's names for what gets tapped (see [targetOf]). */
    private val anchors: List<String> = emptyList(),
) : ActionListener {

    val actions = mutableListOf<TraceAction>()
    val screenshots = mutableListOf<String>()
    /** Every gesture that reached the device, in order, with its wall-clock time. */
    val marks = mutableListOf<ActionMark>()
    private var seq = 0
    private var pendingAt = 0L

    override fun before(tool: String, args: List<Pair<String, String>>) {
        if (tool !in ACTION_TOOLS) return
        pendingAt = clock()
        shoot("step$stepIndex-${seq + 1}-$tool-before")
    }

    override fun after(tool: String, args: List<Pair<String, String>>, resolved: LogicalElement?, result: String) {
        if (tool !in ACTION_TOOLS) return
        seq++
        shoot("step$stepIndex-$seq-$tool-after")
        if (result.startsWith("ERROR") || result.startsWith("AMBIGUOUS")) return
        val m = args.toMap()
        marks += ActionMark(tool, markTarget(tool, resolved, m), pendingAt)
        actions += when (tool) {
            "tap" -> TraceAction("tap", target = targetOf(resolved))
            "type" -> TraceAction("type", target = targetOf(resolved), text = m["text"])
            "scroll", "scrollToFind" ->
                TraceAction(tool, args = args.associateTo(LinkedHashMap()) { it.first to it.second })
            else -> TraceAction(tool) // back, waitForChange
        }
    }

    /** What the timeline calls the action's target: the element's label/id, or the scroll container. */
    private fun markTarget(tool: String, resolved: LogicalElement?, m: Map<String, String>): String? = when (tool) {
        "tap", "type" -> resolved?.label ?: resolved?.resourceId ?: m["target"]
        "scroll", "scrollToFind" -> listOfNotNull(m["scrollable"], m["direction"], m["target"]).joinToString(" ").ifEmpty { null }
        else -> null
    }

    /**
     * The trace's identity for a tapped element. When the step quotes a label that this element
     * carries, that quoted anchor is recorded instead of the element's full label: the author chose
     * it as the stable part, and the rest of the label may be content that changes between runs
     * (실측: Google Play's payment row reads "Test card, always declines. Change payment method" —
     * the card name is whatever the account used last, so a trace holding the full label broke as
     * soon as another scenario paid with a different card). Replay resolves the anchor the same way
     * the model's tap did (exact, then substring).
     */
    private fun targetOf(resolved: LogicalElement?): TraceTarget = TraceTarget(
        resolved?.resourceId,
        resolved?.label?.let { label -> anchors.firstOrNull { resolved.matchesExact(it) || resolved.matchesLoose(it) } ?: label },
        resolved?.center,
        origin = if (resolved?.origin == Origin.WEB) "web" else null,
    )

    private fun shoot(name: String) {
        val path = screenshot?.invoke(name) ?: return
        screenshots += path
    }
}

/** Lets the runner swap the active recorder per step on a single long-lived controller. */
class SwitchableListener : ActionListener {
    var delegate: ActionListener? = null

    override fun before(tool: String, args: List<Pair<String, String>>) {
        delegate?.before(tool, args)
    }

    override fun after(tool: String, args: List<Pair<String, String>>, resolved: LogicalElement?, result: String) {
        delegate?.after(tool, args, resolved, result)
    }
}
