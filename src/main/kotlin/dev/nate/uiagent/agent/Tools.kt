package dev.nate.uiagent.agent

import ai.koog.agents.core.tools.ToolRegistry
import dev.nate.uiagent.agent.tools.BackTool
import dev.nate.uiagent.agent.tools.DeviceTool
import dev.nate.uiagent.agent.tools.LayoutTool
import dev.nate.uiagent.agent.tools.ReportTool
import dev.nate.uiagent.agent.tools.ScrollToFindTool
import dev.nate.uiagent.agent.tools.ScrollTool
import dev.nate.uiagent.agent.tools.TapTool
import dev.nate.uiagent.agent.tools.ToolContext
import dev.nate.uiagent.agent.tools.TypeTool
import dev.nate.uiagent.agent.tools.WaitForChangeTool
import dev.nate.uiagent.device.DeviceController

/**
 * The step's `::` criterion while it is still owed to the model. Set by the session at step
 * start, cleared by whichever tool delivers it (see [DeviceTool.execute]).
 */
internal class StepGate {
    var pendingCriterion: String? = null
}

/**
 * The eval-validated tool set (names, descriptions and parameter wording unchanged since the
 * first Koog `DeviceTools`), one class per tool in `agent/tools/`. Besides dispatching, every tool applies
 * the step contract in [DeviceTool.execute]:
 *  - the step's EXPECTED RESULT rides on the FIRST tool result of the step (after the action's
 *    diff), never in the command — the model acts blind, then judges informed;
 *  - a `report()` attempted before any tool ran is deflected once with the criterion instead of
 *    being dispatched;
 *  - each call appends `name(arg) 0.3s ← FLAG` to the live [TurnLog].
 */
internal class DeviceTools(controller: DeviceController, gate: StepGate, log: TurnLog) {
    private val ctx = ToolContext(controller, gate, log)

    val registry: ToolRegistry = ToolRegistry {
        tool(TapTool(ctx))
        tool(TypeTool(ctx))
        tool(ScrollToFindTool(ctx))
        tool(ScrollTool(ctx))
        tool(BackTool(ctx))
        tool(WaitForChangeTool(ctx))
        tool(LayoutTool(ctx))
        tool(ReportTool(ctx))
    }

    companion object {
        /** Appended to the first tool result of a step that carries a criterion. */
        fun expectedResult(criterion: String) =
            "\n\nEXPECTED RESULT: $criterion\n" +
                "Judge this from the layout diffs when you report — do not take extra actions to " +
                "make it true. If the command's own effect does not satisfy it, report FAILED."

        /** Tool result for a report() attempted before the criterion was delivered. */
        fun reportGate(criterion: String) =
            "Before finishing, judge the EXPECTED RESULT: $criterion\n" +
                "Judge it from the layout diffs so far, without further actions, then call report() again."
    }
}
