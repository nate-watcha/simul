package dev.nate.uiagent.cli

import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.Origin
import dev.nate.uiagent.device.ActionListener

/** Tools that mutate or observe device state as an *action* (screenshot + record worthy). */
private val ACTION_TOOLS = setOf("tap", "type", "scroll", "scrollToFind", "back", "waitForChange")

/**
 * Controller listener for one step: records replayable actions and takes before/after
 * screenshots. Guard-rejected calls (ERROR/AMBIGUOUS — no gesture happened) are not recorded;
 * everything that moved the device is, including failed explorations (a NOT_FOUND scrollToFind
 * still scrolled, and replay must reproduce that state trajectory).
 */
class StepRecorder(
    private val stepIndex: Int,
    private val screenshot: ((name: String) -> String?)? = null,
) : ActionListener {

    val actions = mutableListOf<TraceAction>()
    val screenshots = mutableListOf<String>()
    private var seq = 0

    override fun before(tool: String, args: List<Pair<String, String>>) {
        if (tool !in ACTION_TOOLS) return
        shoot("step$stepIndex-${seq + 1}-$tool-before")
    }

    override fun after(tool: String, args: List<Pair<String, String>>, resolved: LogicalElement?, result: String) {
        if (tool !in ACTION_TOOLS) return
        seq++
        shoot("step$stepIndex-$seq-$tool-after")
        if (result.startsWith("ERROR") || result.startsWith("AMBIGUOUS")) return
        val m = args.toMap()
        actions += when (tool) {
            "tap" -> TraceAction("tap", target = targetOf(resolved))
            "type" -> TraceAction("type", target = targetOf(resolved), text = m["text"])
            "scroll", "scrollToFind" ->
                TraceAction(tool, args = args.associateTo(LinkedHashMap()) { it.first to it.second })
            else -> TraceAction(tool) // back, waitForChange
        }
    }

    private fun targetOf(resolved: LogicalElement?): TraceTarget = TraceTarget(
        resolved?.resourceId,
        resolved?.label,
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
