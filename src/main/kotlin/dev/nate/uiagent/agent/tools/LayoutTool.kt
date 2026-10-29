package dev.nate.uiagent.agent.tools

import ai.koog.agents.core.tools.ToolDescriptor

internal class LayoutTool(ctx: ToolContext) : NoArgsTool(DESCRIPTOR, ctx) {
    override fun run(args: Unit) = controller.layoutTool()

    companion object {
        val DESCRIPTOR = ToolDescriptor(
            name = "layout",
            description = "Re-observe: return the FULL current layout. Use when you have lost track of the screen.",
        )
    }
}
