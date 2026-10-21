package dev.nate.uiagent.cli

import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.Point
import dev.nate.uiagent.device.DeviceController
import dev.nate.uiagent.device.DeviceController.Resolution
import dev.nate.uiagent.device.syntheticHandle

/**
 * LLM-free step replay. Each recorded action is re-grounded against the *current* screen:
 * resourceId first, then label (ambiguity broken by proximity to lastCoords), then — only for
 * targets that never had a label/id — the recorded coordinates. Step completion is judged by
 * the recorded evidence labels being present. Anything that cannot be grounded or verified is
 * a [Broken] result, which the runner reports as a FAILED step (no LLM fallback — a broken
 * replay IS the test result; a human decides between re-recording and fixing the app).
 *
 * Replay runs with [DeviceController.fastObserve] on (single-dump observations), so a
 * grounding/evidence miss may just mean the fast observation caught a mid-transition frame
 * or a screen still loading. Replay knows what it is waiting for — the action's target, the
 * step's evidence — so a miss polls with [DeviceController.waitUntil] for exactly that and
 * continues on the first frame that has it; only a miss that outlasts the poll budget is
 * Broken. No "two identical frames" settle anywhere on the replay path.
 */
class Replayer(
    private val controller: DeviceController,
    /** Max distance (px) between lastCoords and a candidate when disambiguating duplicates. */
    private val coordTolerance: Int = 200,
    /** Live progress sink — stabilization retries take many seconds and deserve a line. */
    private val log: (String) -> Unit = {},
) {

    /** Why replay of a step could not complete deterministically. */
    data class Broken(val atAction: Int?, val why: String)

    data class StepReplay(
        val broken: Broken?,
        /** lastCoords rewrites from coordinate-fallback groundings (actionIndex -> new coords). */
        val coordUpdates: Map<Int, Point>,
        /** The step's evidence that never showed up (the report's "expected vs found"); empty unless the gate broke. */
        val missing: List<String> = emptyList(),
    )

    fun replayStep(step: TraceStep): StepReplay {
        val coordUpdates = mutableMapOf<Int, Point>()
        step.actions.forEachIndexed { i, action ->
            val broken = replayAction(i, action, coordUpdates)
            if (broken != null) return StepReplay(broken, coordUpdates)
        }
        // both channels must resolve: label presence + label-carries-state (e.g. 구독=selected)
        fun missingNow(): List<String> {
            val els = controller.currentElements
            return step.evidence.filterNot { labelPresent(els, it) } +
                step.stateEvidence.filterNot { statePresent(els, it) }.map { it.toString() }
        }

        var missing = missingNow()
        if (missing.isNotEmpty()) {
            // fast observation may predate the evidence appearing — poll until it does
            log("  evidence not on the fast frame (${missing.joinToString(", ")}) — waiting for it…")
            controller.waitUntil { missingNow().isEmpty() }
            missing = missingNow()
        }
        if (missing.isNotEmpty()) {
            return StepReplay(Broken(null, "evidence not on screen: ${missing.joinToString(", ")}"), coordUpdates, missing)
        }
        return StepReplay(null, coordUpdates)
    }

    private fun replayAction(index: Int, action: TraceAction, coordUpdates: MutableMap<Int, Point>): Broken? {
        var broken = replayActionOnce(index, action, coordUpdates)
        if (broken != null) {
            // Guard failures never moved the device, so a retry is safe; the screen may just
            // not have shown the action's target yet when the fast observation was taken.
            log("  action ${index + 1} (${action.tool}) missed on the fast frame — waiting for its target…")
            controller.waitUntil { precondition(action) }
            broken = replayActionOnce(index, action, coordUpdates)
        }
        return broken
    }

    /**
     * What the current screen must show for [action] to proceed past its guards — the
     * condition the retry polls for. Tools without a target (back, waitForChange) have none,
     * so their retry happens after a single re-observation.
     */
    private fun precondition(action: TraceAction): Boolean = when (action.tool) {
        "tap", "type" -> action.target?.let { ground(it) } != null
        // A synthetic handle resolves to the nearest element within 50px, so a full-screen
        // overlay (ad splash) centred where the list was "matches" — the wait must hold out
        // for an element that can actually scroll (detail-soundtrack 2026-10-20: one poll,
        // then the same "not scrollable" error).
        "scroll", "scrollToFind" ->
            (controller.resolve(action.args["scrollable"] ?: "") as? Resolution.Match)
                ?.element?.interactions?.contains("scrollable") == true
        else -> true
    }

    private fun replayActionOnce(index: Int, action: TraceAction, coordUpdates: MutableMap<Int, Point>): Broken? {
        fun guard(result: String): Broken? =
            if (result.startsWith("ERROR") || result.startsWith("AMBIGUOUS")) Broken(index, result.lineSequence().first())
            else null

        return when (action.tool) {
            "tap", "type" -> {
                val target = action.target ?: return Broken(index, "trace action has no target")
                val grounded = ground(target)
                    ?: return Broken(index, "cannot re-ground target (resourceId=${target.resourceId}, label=${target.label})")
                if (grounded.usedCoords) coordUpdates[index] = grounded.element.center
                val handle = grounded.handle
                val result = if (action.tool == "tap") controller.tap(handle)
                else controller.type(handle, action.text ?: "")
                guard(result)
            }
            "scroll" -> guard(
                controller.scroll(action.args["scrollable"] ?: "", action.args["direction"] ?: "")
            )
            // scrollToFind is already a deterministic traversal — rerun the tool as recorded.
            // NOT_FOUND is not a replay failure by itself; the evidence gate decides.
            "scrollToFind" -> guard(
                controller.scrollToFind(
                    action.args["scrollable"] ?: "",
                    action.args["target"] ?: "",
                    action.args["direction"] ?: "",
                )
            )
            "back" -> guard(controller.back())
            "waitForChange" -> guard(controller.waitForChange())
            else -> Broken(index, "unknown tool '${action.tool}' in trace")
        }
    }

    // ------------------------------------------------------------------ grounding

    private class Grounded(val element: LogicalElement, val usedCoords: Boolean) {
        val handle: String get() = syntheticHandle(element) // exact center → unambiguous resolve
    }

    private fun ground(t: TraceTarget): Grounded? {
        // 1. resourceId — the most stable identity.
        t.resourceId?.let { id ->
            (controller.resolve(id) as? Resolution.Match)?.let { return Grounded(it.element, usedCoords = false) }
        }
        // 2. label; duplicates are disambiguated by proximity to the recorded coordinates.
        t.label?.let { label ->
            when (val r = controller.resolve(label)) {
                is Resolution.Match -> return Grounded(r.element, usedCoords = false)
                is Resolution.Ambiguous -> {
                    val c = t.lastCoords ?: return null
                    val near = r.candidates
                        .minByOrNull { it.center.dist2(c) }
                        ?.takeIf { it.center.dist2(c) <= coordTolerance * coordTolerance }
                        ?: return null
                    return Grounded(near, usedCoords = true)
                }
                is Resolution.NotFound -> return null // labeled target vanished: fail, don't blind-tap
            }
        }
        // 3. never-labeled target: nearest element to the recorded coordinates.
        t.lastCoords?.let { c ->
            (controller.resolve(syntheticHandle(c)) as? Resolution.Match)
                ?.let { return Grounded(it.element, usedCoords = it.element.center != c) }
        }
        return null
    }
}
