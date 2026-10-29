package dev.nate.uiagent.agent.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.serialization.JSONObject
import ai.koog.serialization.typeToken

internal class ScrollToFindTool(ctx: ToolContext) : DeviceTool<ScrollToFindTool.Args>(DESCRIPTOR, typeToken<Args>(), ctx) {
    data class Args(val scrollable: String, val target: String, val direction: String)

    override fun decode(raw: JSONObject) = Args(
        scrollable = raw.required("scrollable"),
        target = raw.required("target"),
        direction = raw.required("direction"),
    )
    override fun encode(args: Args) =
        json("scrollable" to args.scrollable, "target" to args.target, "direction" to args.direction)
    override fun primaryArg(args: Args) = args.target
    override fun run(args: Args) = controller.scrollToFind(args.scrollable, args.target, args.direction)

    companion object {
        val DESCRIPTOR = ToolDescriptor(
            name = "scrollToFind",
            description = "Scroll inside a scrollable element and search for a target label. Scrolls up to 3 times, " +
                "stopping early if found or if the layout stops changing. " +
                "Returns FOUND with the element, or NOT_FOUND with what was tried.",
            requiredParameters = listOf(
                param("scrollable", "label or resourceId of the scrollable element"),
                param("target", "label to search for"),
                param("direction", "up, down, left or right"),
            ),
        )
    }
}
