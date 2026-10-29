package dev.nate.uiagent.agent.tools

import ai.koog.agents.core.tools.ToolDescriptor

internal class BackTool(ctx: ToolContext) : NoArgsTool(DESCRIPTOR, ctx) {
    override fun run(args: Unit) = controller.back()

    companion object {
        val DESCRIPTOR = ToolDescriptor(
            name = "back",
            description = "Press the hardware back button. Returns the layout diff.",
        )
    }
}
