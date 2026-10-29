package dev.nate.uiagent.agent.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.TypeToken
import ai.koog.serialization.typeToken
import dev.nate.uiagent.agent.DeviceTools
import dev.nate.uiagent.agent.StepGate
import dev.nate.uiagent.agent.TurnLog
import dev.nate.uiagent.device.DeviceController
import dev.nate.uiagent.secs

/** What every device tool is bound to: the controller it drives and the step contract it applies. */
internal class ToolContext(val controller: DeviceController, val gate: StepGate, val log: TurnLog)

/** Thrown by a tool's [DeviceTool.decode] when a required argument is missing. */
private class MissingParameter(val name: String) : RuntimeException(name)

/**
 * One device tool as Koog sees it: a typed args class [A] the tool decodes by hand (no @Serializable
 * args class — project rule, see CLAUDE.md), an explicit [ToolDescriptor], and a plain-text
 * result (not JSON-quoted). Subclasses give the descriptor, the arg codec and [run]; the step
 * contract (criterion delivery, report deflection, turn log) lives here in [execute] so every
 * tool applies it identically.
 *
 * A missing required argument fails decoding: Koog then returns its own
 * `Tool with name '…' failed to parse arguments due to the error: tool '…' requires parameter '…'`
 * result to the model without running the tool, and the criterion stays pending for the next
 * call ([ScenarioSession] logs that failure through the EventHandler).
 */
internal abstract class DeviceTool<A>(
    descriptor: ToolDescriptor,
    argsType: TypeToken,
    private val ctx: ToolContext,
) : Tool<A, String>(argsType = argsType, resultType = typeToken<String>(), descriptor = descriptor) {

    protected val controller: DeviceController get() = ctx.controller

    /** Parse the model's raw arguments; use [required]/[optional] so a missing one is reported by name. */
    protected abstract fun decode(raw: JSONObject): A
    protected abstract fun encode(args: A): JSONObject
    /** The argument shown in the live log — `tap(보관함)`; null for `back()`-style tools. */
    protected abstract fun primaryArg(args: A): String?
    /** The tool's own effect on the controller; the step contract wraps it. */
    protected abstract fun run(args: A): String
    /** Only report() ends a step, and so is the one call the pending criterion deflects. */
    protected open val isVerdict: Boolean = false

    final override fun decodeArgs(rawArgs: JSONObject, serializer: JSONSerializer): A = try {
        decode(rawArgs)
    } catch (e: MissingParameter) {
        throw IllegalArgumentException("tool '$name' requires parameter '${e.name}'")
    }

    final override fun encodeArgs(args: A, serializer: JSONSerializer): JSONObject = encode(args)
    final override fun encodeResultToString(result: String, serializer: JSONSerializer): String = result

    final override suspend fun execute(args: A): String {
        val t0 = System.currentTimeMillis()
        val criterion = ctx.gate.pendingCriterion
        var note = ""
        val result = if (criterion != null && isVerdict) {
            ctx.gate.pendingCriterion = null
            note = " (deflected → judge criterion first)"
            DeviceTools.reportGate(criterion)
        } else {
            var r = run(args)
            if (criterion != null) {
                ctx.gate.pendingCriterion = null
                r += DeviceTools.expectedResult(criterion)
                note = " +criterion"
            }
            r
        }
        ctx.log.tool(describe(args) + " " + secs(System.currentTimeMillis() - t0) + resultFlag(result) + note)
        return result
    }

    private fun describe(args: A): String {
        val arg = primaryArg(args)?.let { if (it.length > 24) it.take(24) + "…" else it }
        return if (arg == null) "$name()" else "$name($arg)"
    }

    /** Flags guard/miss results so a stuck step is legible from the log alone. */
    private fun resultFlag(result: String): String {
        val head = result.lineSequence().first()
        return when {
            head.startsWith("ERROR") -> " ← ERROR"
            head.startsWith("AMBIGUOUS") -> " ← AMBIGUOUS"
            head.startsWith("NOT_FOUND") -> " ← NOT_FOUND"
            else -> ""
        }
    }

    protected fun JSONObject.optional(name: String): String? = (entries[name] as? JSONPrimitive)?.contentOrNull
    protected fun JSONObject.required(name: String): String = optional(name) ?: throw MissingParameter(name)
    protected fun json(vararg fields: Pair<String, String>): JSONObject =
        JSONObject(fields.associate { (k, v) -> k to JSONPrimitive(v) })
}

internal fun param(name: String, description: String) = ToolParameterDescriptor(name, description, ToolParameterType.String)

/** A tool without arguments: the model sends `{}` and the log shows `name()`. */
internal abstract class NoArgsTool(descriptor: ToolDescriptor, ctx: ToolContext) :
    DeviceTool<Unit>(descriptor, typeToken<Unit>(), ctx) {
    final override fun decode(raw: JSONObject) = Unit
    final override fun encode(args: Unit) = JSONObject(emptyMap())
    final override fun primaryArg(args: Unit): String? = null
}
