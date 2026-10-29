package dev.nate.uiagent.agent.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.serialization.JSONObject
import ai.koog.serialization.typeToken

internal class TypeTool(ctx: ToolContext) : DeviceTool<TypeTool.Args>(DESCRIPTOR, typeToken<Args>(), ctx) {
    data class Args(val target: String, val text: String)

    override fun decode(raw: JSONObject) = Args(target = raw.required("target"), text = raw.required("text"))
    override fun encode(args: Args) = json("target" to args.target, "text" to args.text)
    override fun primaryArg(args: Args) = args.target
    override fun run(args: Args) = controller.type(args.target, args.text)

    companion object {
        val DESCRIPTOR = ToolDescriptor(
            name = "type",
            description = "Type text into the element matching the target label. Replaces existing text. " +
                "Returns the layout diff.",
            requiredParameters = listOf(
                param("target", "label or resourceId of the input field"),
                param("text", "text to type, in any language"),
            ),
        )
    }
}
