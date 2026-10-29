package dev.nate.uiagent.agent.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.serialization.JSONObject
import ai.koog.serialization.typeToken

internal class ScrollTool(ctx: ToolContext) : DeviceTool<ScrollTool.Args>(DESCRIPTOR, typeToken<Args>(), ctx) {
    data class Args(val scrollable: String, val direction: String)

    override fun decode(raw: JSONObject) = Args(scrollable = raw.required("scrollable"), direction = raw.required("direction"))
    override fun encode(args: Args) = json("scrollable" to args.scrollable, "direction" to args.direction)
    override fun primaryArg(args: Args) = args.scrollable
    override fun run(args: Args) = controller.scroll(args.scrollable, args.direction)

    companion object {
        val DESCRIPTOR = ToolDescriptor(
            name = "scroll",
            description = "Scroll once in a direction inside a scrollable element. Returns the layout diff.",
            requiredParameters = listOf(
                param("scrollable", "label or resourceId of the scrollable element"),
                param("direction", "up, down, left or right"),
            ),
        )
    }
}
