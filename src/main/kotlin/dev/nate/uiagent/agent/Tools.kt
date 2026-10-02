package dev.nate.uiagent.agent

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.typeToken
import dev.nate.uiagent.device.DeviceController

/**
 * The step's `::` criterion while it is still owed to the model. Set by the session at step
 * start, cleared by whichever tool delivers it (see [DeviceTools]).
 */
internal class StepGate {
    var pendingCriterion: String? = null
}

/**
 * Live per-turn log line: `turn N: llm 1.2s → tap(보관함) 0.3s, report(PASSED) 0.1s`. LLM timing
 * comes from Koog's EventHandler, tool parts from the tools themselves; a line is flushed when
 * the next LLM call starts, or at the end of the step.
 */
internal class TurnLog(private val sink: (String) -> Unit) {
    var turns: Int = 0
        private set
    private var llmStartedAt = 0L
    private var llmMs = 0L
    private var open = false
    private val parts = mutableListOf<String>()

    fun reset() { turns = 0; open = false; parts.clear() }

    fun llmStarted() {
        flush()
        llmStartedAt = System.currentTimeMillis()
    }

    fun llmCompleted(toolCalls: Int) {
        turns++
        llmMs = System.currentTimeMillis() - llmStartedAt
        open = true
        if (toolCalls == 0) parts += "text reply, no tool call"
    }

    fun tool(part: String) { parts += part }

    fun flush() {
        if (!open) return
        sink("turn $turns: llm ${sec(llmMs)} → ${parts.joinToString(", ")}")
        parts.clear()
        open = false
    }

    companion object {
        fun sec(ms: Long) = "%.1fs".format(ms / 1000.0)
    }
}

/** Thrown by a tool body when a required argument is missing; turned into an `ERROR:` tool result. */
private class MissingParameter(val name: String) : RuntimeException(name)

/**
 * One device tool as Koog sees it. Arguments stay a raw [JSONObject] (no @Serializable args
 * class — project rule) and the result goes to the model as plain text, not JSON-quoted.
 */
private class DeviceTool(
    name: String,
    description: String,
    params: List<Pair<String, String>>,
    private val body: suspend (JSONObject) -> String,
) : Tool<JSONObject, String>(
    argsType = typeToken<JSONObject>(),
    resultType = typeToken<String>(),
    descriptor = ToolDescriptor(
        name = name,
        description = description,
        requiredParameters = params.map { (p, d) -> ToolParameterDescriptor(p, d, ToolParameterType.String) },
    ),
) {
    override fun decodeArgs(rawArgs: JSONObject, serializer: JSONSerializer): JSONObject = rawArgs
    override fun encodeArgs(args: JSONObject, serializer: JSONSerializer): JSONObject = args
    override fun encodeResultToString(result: String, serializer: JSONSerializer): String = result
    override suspend fun execute(args: JSONObject): String = body(args)
}

/**
 * The eval-validated tool set (names, descriptions and parameter wording unchanged since the
 * first koog-era `DeviceTools`), bound to a [DeviceController]. Besides dispatching, every tool
 * applies the step contract:
 *  - the step's EXPECTED RESULT rides on the FIRST tool result of the step (after the action's
 *    diff), never in the command — the model acts blind, then judges informed;
 *  - a `report()` attempted before any tool ran is deflected once with the criterion instead of
 *    being dispatched;
 *  - each call appends `name(arg) 0.3s ← FLAG` to the live [TurnLog].
 */
internal class DeviceTools(
    private val controller: DeviceController,
    private val gate: StepGate,
    private val log: TurnLog,
) {
    val registry: ToolRegistry = ToolRegistry {
        tool(make("tap",
            "Tap the element matching the target label. Returns the layout diff after the tap. " +
                "If multiple elements match, no tap happens and the candidates are returned instead.",
            listOf("target" to "visible label, resourceId, or @x..y.. handle of the element"),
        ) { a -> controller.tap(a.req("target")) })
        tool(make("type",
            "Type text into the element matching the target label. Replaces existing text. " +
                "ASCII only; non-ASCII returns an error. Returns the layout diff.",
            listOf("target" to "label or resourceId of the input field", "text" to "ASCII text to type"),
        ) { a -> controller.type(a.req("target"), a.req("text")) })
        tool(make("scrollToFind",
            "Scroll inside a scrollable element and search for a target label. Scrolls up to 3 times, " +
                "stopping early if found or if the layout stops changing. " +
                "Returns FOUND with the element, or NOT_FOUND with what was tried.",
            listOf(
                "scrollable" to "label or resourceId of the scrollable element",
                "target" to "label to search for",
                "direction" to "up, down, left or right",
            ),
        ) { a -> controller.scrollToFind(a.req("scrollable"), a.req("target"), a.req("direction")) })
        tool(make("scroll",
            "Scroll once in a direction inside a scrollable element. Returns the layout diff.",
            listOf(
                "scrollable" to "label or resourceId of the scrollable element",
                "direction" to "up, down, left or right",
            ),
        ) { a -> controller.scroll(a.req("scrollable"), a.req("direction")) })
        tool(make("back", "Press the hardware back button. Returns the layout diff.", emptyList()) {
            controller.back()
        })
        tool(make("waitForChange",
            "Wait for the screen to settle (e.g. loading). Returns the layout diff since the last observation.",
            emptyList(),
        ) { controller.waitForChange() })
        tool(make("layout",
            "Re-observe: return the FULL current layout. Use when you have lost track of the screen.",
            emptyList(),
        ) { controller.layoutTool() })
        tool(make("report",
            "Finish the command with the final verdict. Call this exactly once, as your last action. " +
                "PASSED only if the layout diff evidence shows the command succeeded.",
            listOf("status" to "PASSED or FAILED", "reason" to "short reason, under 15 words"),
        ) { a -> controller.report(a.req("status"), a.str("reason") ?: "") })
    }

    private fun make(
        name: String,
        description: String,
        params: List<Pair<String, String>>,
        body: suspend (JSONObject) -> String,
    ): Tool<JSONObject, String> = DeviceTool(name, description, params) { args ->
        val t0 = System.currentTimeMillis()
        val criterion = gate.pendingCriterion
        var note = ""
        val result = when {
            criterion != null && name == "report" -> {
                gate.pendingCriterion = null
                note = " (deflected → judge criterion first)"
                reportGate(criterion)
            }
            else -> {
                var r = try { body(args) } catch (e: MissingParameter) {
                    "ERROR: tool '$name' requires parameter '${e.name}'."
                }
                if (criterion != null) {
                    gate.pendingCriterion = null
                    r += expectedResult(criterion)
                    note = " +criterion"
                }
                r
            }
        }
        log.tool(describe(name, args) + " " + TurnLog.sec(System.currentTimeMillis() - t0) + resultFlag(result) + note)
        result
    }

    private fun JSONObject.str(name: String): String? = (entries[name] as? JSONPrimitive)?.contentOrNull
    private fun JSONObject.req(name: String): String = str(name) ?: throw MissingParameter(name)

    /** `tap(보관함)`-style label for the live log — tool name plus its primary argument. */
    private fun describe(name: String, args: JSONObject): String {
        val arg = listOf("target", "scrollable", "status").firstNotNullOfOrNull { args.str(it) }
            ?.let { if (it.length > 24) it.take(24) + "…" else it }
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
