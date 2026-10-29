package dev.nate.uiagent.agent.tools

import ai.koog.agents.core.tools.ToolDescriptor

internal class WaitForChangeTool(ctx: ToolContext) : NoArgsTool(DESCRIPTOR, ctx) {
    override fun run(args: Unit) = controller.waitForChange()

    companion object {
        val DESCRIPTOR = ToolDescriptor(
            name = "waitForChange",
            description = "Wait for the screen to settle (e.g. loading). Returns the layout diff since the last observation.",
        )
    }
}
