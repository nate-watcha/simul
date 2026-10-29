package dev.nate.uiagent.agent.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.serialization.JSONObject
import ai.koog.serialization.typeToken

internal class ReportTool(ctx: ToolContext) : DeviceTool<ReportTool.Args>(DESCRIPTOR, typeToken<Args>(), ctx) {
    /** [status] stays a string: the controller validates PASSED/FAILED and answers the model in its own words. */
    data class Args(val status: String, val reason: String)

    override val isVerdict = true
    override fun decode(raw: JSONObject) = Args(status = raw.required("status"), reason = raw.optional("reason") ?: "")
    override fun encode(args: Args) = json("status" to args.status, "reason" to args.reason)
    override fun primaryArg(args: Args) = args.status
    override fun run(args: Args) = controller.report(args.status, args.reason)

    companion object {
        val DESCRIPTOR = ToolDescriptor(
            name = "report",
            description = "Finish the command with the final verdict. Call this exactly once, as your last action. " +
                "PASSED only if the layout diff evidence shows the command succeeded.",
            requiredParameters = listOf(
                param("status", "PASSED or FAILED"),
                param("reason", "short reason, under 15 words"),
            ),
        )
    }
}
