package dev.nate.uiagent.agent

import dev.nate.uiagent.device.DeviceController
import dev.nate.uiagent.json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One LLM conversation for a WHOLE scenario. Each step appends a user message (the command)
 * and runs the tool loop until report(); the next step continues the same conversation, so
 * the model keeps the full action/diff history ("what have I done so far") and — because the
 * transcript only ever grows — llama.cpp's prefix cache re-evaluates just the new tokens.
 * The full initial layout is sent once, with the first step; afterwards the running diffs
 * (and the layout() tool, if the model feels lost) carry the screen state.
 *
 * Verdicts stay per step: report() is required to end every step, and the controller's
 * verdict is reset before each one.
 */
class ScenarioSession(
    private val client: ChatClient,
    private val controller: DeviceController,
    private val maxTurnsPerStep: Int = 12,
    /** Live progress sink — one line per turn (llm/tool timings), a step can run for minutes. */
    private val log: (String) -> Unit = {},
) {
    private val messages = mutableListOf(ChatMessage("system", SYSTEM_PROMPT_V1))

    data class Verdict(val passed: Boolean, val reason: String)

    /**
     * Run one step to its report() verdict. [initialLayout] rides along only at conversation
     * start; later steps send the bare command — the session history (each action's diff)
     * already tells the model what happened and what is on screen.
     *
     * [criterion] is the step's expected result (the `::` clause), deliberately withheld from
     * the COMMAND: the model acts blind (a goal-seeking agent would otherwise repair a broken
     * app's path toward the criterion) and receives it appended to the first tool result —
     * after the action's diff — so it judges informed. If the model tries to report before
     * any tool ran, the report is deflected once with the criterion instead of dispatched.
     */
    fun runStep(stepText: String, initialLayout: String?, criterion: String? = null): Verdict {
        controller.resetVerdict()
        messages += ChatMessage("user", buildString {
            append("COMMAND: ").append(stepText)
            if (initialLayout != null) append("\n\nINITIAL LAYOUT:\n").append(initialLayout)
        })
        var pendingCriterion = criterion

        try {
            repeat(maxTurnsPerStep) { turn ->
                val t0 = System.currentTimeMillis()
                val reply = client.complete(messages, TOOLS, temperature = 0.0).message
                val llmMs = System.currentTimeMillis() - t0
                messages += reply
                if (reply.toolCalls.isEmpty()) {
                    log("turn ${turn + 1}: llm ${sec(llmMs)} → text reply, no tool call")
                    return Verdict(false, "no report() — agent said: ${(reply.content ?: "").take(120)}")
                }
                val parts = mutableListOf<String>()
                for (tc in reply.toolCalls) {
                    if (pendingCriterion != null && tc.name == "report") {
                        messages += ChatMessage("tool", reportGate(pendingCriterion!!), toolCallId = tc.id)
                        pendingCriterion = null
                        parts += "report deflected → judge criterion first"
                        continue
                    }
                    val d0 = System.currentTimeMillis()
                    var result = dispatch(tc)
                    val deliverCriterion = pendingCriterion != null
                    if (deliverCriterion) {
                        result += expectedResult(pendingCriterion!!)
                        pendingCriterion = null
                    }
                    messages += ChatMessage("tool", result, toolCallId = tc.id)
                    parts += describe(tc) + " " + sec(System.currentTimeMillis() - d0) +
                        resultFlag(result) + (if (deliverCriterion) " +criterion" else "")
                }
                log("turn ${turn + 1}: llm ${sec(llmMs)} → ${parts.joinToString(", ")}")
                controller.verdict?.let { v ->
                    return Verdict(v.status == "PASSED", v.reason)
                }
            }
        } catch (ex: Exception) {
            return Verdict(false, "agent error: ${ex.message ?: ex.toString()}")
        }
        return Verdict(false, "no report() within $maxTurnsPerStep turns")
    }

    private fun sec(ms: Long) = "%.1fs".format(ms / 1000.0)

    /** `tap(보관함)`-style label for the live log — tool name plus its primary argument. */
    private fun describe(tc: ToolCall): String {
        val arg = runCatching {
            val o = json.parseToJsonElement(tc.argumentsJson).jsonObject
            listOf("target", "scrollable", "status").firstNotNullOfOrNull { k ->
                (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            }
        }.getOrNull()?.let { if (it.length > 24) it.take(24) + "…" else it }
        return if (arg == null) "${tc.name}()" else "${tc.name}($arg)"
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

    /** Appended to the first tool result of a step that carries a criterion. */
    private fun expectedResult(criterion: String) =
        "\n\nEXPECTED RESULT: $criterion\n" +
            "Judge this from the layout diffs when you report — do not take extra actions to " +
            "make it true. If the command's own effect does not satisfy it, report FAILED."

    /** Tool result for a report() attempted before the criterion was delivered. */
    private fun reportGate(criterion: String) =
        "Before finishing, judge the EXPECTED RESULT: $criterion\n" +
            "Judge it from the layout diffs so far, without further actions, then call report() again."

    // ---------------------------------------------------------------- tool dispatch

    private fun dispatch(tc: ToolCall): String {
        val args = runCatching { json.parseToJsonElement(tc.argumentsJson).jsonObject }
            .getOrElse { return "ERROR: tool arguments are not valid JSON: ${tc.argumentsJson.take(120)}" }

        fun str(name: String): String? =
            (args[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

        return when (tc.name) {
            "tap" -> controller.tap(str("target") ?: return missing(tc, "target"))
            "type" -> controller.type(
                str("target") ?: return missing(tc, "target"),
                str("text") ?: return missing(tc, "text"),
            )
            "scrollToFind" -> controller.scrollToFind(
                str("scrollable") ?: return missing(tc, "scrollable"),
                str("target") ?: return missing(tc, "target"),
                str("direction") ?: return missing(tc, "direction"),
            )
            "scroll" -> controller.scroll(
                str("scrollable") ?: return missing(tc, "scrollable"),
                str("direction") ?: return missing(tc, "direction"),
            )
            "back" -> controller.back()
            "waitForChange" -> controller.waitForChange()
            "layout" -> controller.layoutTool()
            "report" -> controller.report(
                str("status") ?: return missing(tc, "status"),
                str("reason") ?: "",
            )
            else -> "ERROR: unknown tool '${tc.name}'."
        }
    }

    private fun missing(tc: ToolCall, param: String) =
        "ERROR: tool '${tc.name}' requires parameter '$param'."

    companion object {
        /**
         * OpenAI-format tool schemas, descriptions identical to the eval-validated koog-era
         * DeviceTools annotations. Built once — stable ordering keeps request prefixes stable.
         */
        val TOOLS: JsonArray = buildJsonArray {
            add(tool("tap",
                "Tap the element matching the target label. Returns the layout diff after the tap. " +
                    "If multiple elements match, no tap happens and the candidates are returned instead.",
                "target" to "visible label, resourceId, or @x..y.. handle of the element"))
            add(tool("type",
                "Type text into the element matching the target label. Replaces existing text. " +
                    "ASCII only; non-ASCII returns an error. Returns the layout diff.",
                "target" to "label or resourceId of the input field",
                "text" to "ASCII text to type"))
            add(tool("scrollToFind",
                "Scroll inside a scrollable element and search for a target label. Scrolls up to 3 times, " +
                    "stopping early if found or if the layout stops changing. " +
                    "Returns FOUND with the element, or NOT_FOUND with what was tried.",
                "scrollable" to "label or resourceId of the scrollable element",
                "target" to "label to search for",
                "direction" to "up, down, left or right"))
            add(tool("scroll",
                "Scroll once in a direction inside a scrollable element. Returns the layout diff.",
                "scrollable" to "label or resourceId of the scrollable element",
                "direction" to "up, down, left or right"))
            add(tool("back", "Press the hardware back button. Returns the layout diff."))
            add(tool("waitForChange",
                "Wait for the screen to settle (e.g. loading). Returns the layout diff since the last observation."))
            add(tool("layout",
                "Re-observe: return the FULL current layout. Use when you have lost track of the screen."))
            add(tool("report",
                "Finish the command with the final verdict. Call this exactly once, as your last action. " +
                    "PASSED only if the layout diff evidence shows the command succeeded.",
                "status" to "PASSED or FAILED",
                "reason" to "short reason, under 15 words"))
        }

        private fun tool(name: String, description: String, vararg params: Pair<String, String>): JsonObject =
            buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", name)
                    put("description", description)
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            params.forEach { (p, d) ->
                                put(p, buildJsonObject { put("type", "string"); put("description", d) })
                            }
                        })
                        put("required", buildJsonArray {
                            params.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.first)) }
                        })
                    })
                })
            }
    }
}
