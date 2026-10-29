package dev.nate.uiagent.agent.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.serialization.JSONObject
import ai.koog.serialization.typeToken

internal class TapTool(ctx: ToolContext) : DeviceTool<TapTool.Args>(DESCRIPTOR, typeToken<Args>(), ctx) {
    data class Args(val target: String)

    override fun decode(raw: JSONObject) = Args(target = raw.required("target"))
    override fun encode(args: Args) = json("target" to args.target)
    override fun primaryArg(args: Args) = args.target
    override fun run(args: Args) = controller.tap(args.target)

    companion object {
        val DESCRIPTOR = ToolDescriptor(
            name = "tap",
            description = "Tap the element matching the target label. Returns the layout diff after the tap. " +
                "If multiple elements match, no tap happens and the candidates are returned instead.",
            requiredParameters = listOf(
                param("target", "visible label, resourceId, or @x..y.. handle of the element"),
            ),
        )
    }
}
