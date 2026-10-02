package dev.nate.uiagent.agent

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.chatMemory.feature.InMemoryChatHistoryProvider
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MessagePart
import dev.nate.uiagent.device.DeviceController
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * One LLM conversation for a WHOLE scenario, held by Koog's ChatMemory. Each step is one
 * `agent.run(command, sessionId)`: ChatMemory loads the scenario's history at strategy start,
 * the step's tool loop appends to it, and the whole prompt is stored again when the run
 * completes — so the next step continues the same conversation, the model keeps its
 * action/diff history ("what have I done so far"), and because the transcript only ever
 * grows, llama.cpp's prefix cache re-evaluates just the new tokens. The full initial layout is
 * sent once, with the first step; afterwards the running diffs (and the layout() tool, if the
 * model feels lost) carry the screen state.
 *
 * Verdicts stay per step: report() is required to end every step, and the controller's
 * verdict is reset before each one. ChatMemory stores only completed runs: a step that dies
 * with an exception leaves no trace in the conversation (the next step resumes from the last
 * completed one).
 *
 * The per-step strategy is the plain request → tools → results loop with two extra exits:
 * the step ends as soon as report() has been dispatched (no closing text turn), and after
 * [maxTurnsPerStep] LLM calls without a report. Both exits still append the pending tool
 * results to the conversation — a tool call without its result would corrupt the transcript
 * every later step builds on.
 */
class ScenarioSession(
    executor: PromptExecutor,
    private val controller: DeviceController,
    private val maxTurnsPerStep: Int = 12,
    /** Live progress sink — one line per turn (llm/tool timings), a step can run for minutes. */
    log: (String) -> Unit = {},
    model: LLModel = SimulLlm.model(),
    /** Where the scenario's conversation lives between steps; in-memory by default. */
    history: ChatHistoryProvider = InMemoryChatHistoryProvider(),
) {
    data class Verdict(val passed: Boolean, val reason: String)

    private val sessionId = "scenario-" + UUID.randomUUID()
    private val gate = StepGate()
    private val turns = TurnLog(log)
    private val tools = DeviceTools(controller, gate, turns)

    private val agent: GraphAIAgent<String, String> = GraphAIAgent(
        promptExecutor = executor,
        agentConfig = AIAgentConfig(
            prompt = prompt("simul", params = SimulLlm.PARAMS) { system(SYSTEM_PROMPT_V1) },
            model = model,
            // Koog counts graph nodes: start + request + (execute + send) per turn + record + finish.
            maxAgentIterations = maxTurnsPerStep * 2 + 5,
        ),
        strategy = stepStrategy(),
        toolRegistry = tools.registry,
    ) {
        install(ChatMemory) { chatHistoryProvider = history }
        install(EventHandler) {
            onLLMCallStarting { turns.llmStarted() }
            onLLMCallCompleted { ctx ->
                turns.llmCompleted(ctx.response?.parts?.count { it is MessagePart.Tool.Call } ?: 0)
            }
        }
    }

    private fun stepStrategy() = strategy<String, String>("simul-step") {
        val request by nodeLLMRequest("request")
        val execute by nodeExecuteTools("execute")
        val send by nodeLLMSendToolResults("send")
        // what `send` does minus the LLM call: the results still have to answer their tool calls
        val record by node<ReceivedToolResults, String>("record") { results ->
            llm.writeSession {
                appendPrompt { user { results.toolResults.forEach { toolResult(it.toMessagePart()) } } }
            }
            ""
        }

        edge(nodeStart forwardTo request)
        edge(request forwardTo execute onToolCalls { true })
        edge(request forwardTo nodeFinish onTextMessage { true })
        // report() dispatched, or the turn budget spent → the step is over, no closing LLM turn
        edge(execute forwardTo record onCondition { controller.verdict != null || turns.turns >= maxTurnsPerStep })
        edge(record forwardTo nodeFinish)
        edge(execute forwardTo send)
        edge(send forwardTo execute onToolCalls { true })
        edge(send forwardTo nodeFinish onTextMessage { true })
    }

    /**
     * Run one step to its report() verdict. [initialLayout] rides along only at conversation
     * start; later steps send the bare command — the session history (each action's diff)
     * already tells the model what happened and what is on screen.
     *
     * [criterion] is the step's expected result (the `::` clause), deliberately withheld from
     * the COMMAND: the model acts blind (a goal-seeking agent would otherwise repair a broken
     * app's path toward the criterion) and receives it appended to the first tool result —
     * after the action's diff — so it judges informed. If the model tries to report before
     * any tool ran, the report is deflected once with the criterion instead of dispatched
     * (both rules live in [DeviceTools]).
     */
    fun runStep(stepText: String, initialLayout: String?, criterion: String? = null): Verdict {
        controller.resetVerdict()
        gate.pendingCriterion = criterion
        turns.reset()
        val command = buildString {
            append("COMMAND: ").append(stepText)
            if (initialLayout != null) append("\n\nINITIAL LAYOUT:\n").append(initialLayout)
        }
        return try {
            val said = runBlocking { agent.run(command, sessionId) }
            turns.flush()
            val v = controller.verdict
            when {
                v != null -> Verdict(v.status == "PASSED", v.reason)
                turns.turns >= maxTurnsPerStep -> Verdict(false, "no report() within $maxTurnsPerStep turns")
                else -> Verdict(false, "no report() — agent said: ${said.take(120)}")
            }
        } catch (ex: Exception) {
            turns.flush()
            if (generateSequence<Throwable>(ex) { it.cause }.any { it is AIAgentMaxNumberOfIterationsReachedException })
                Verdict(false, "no report() within $maxTurnsPerStep turns")
            else Verdict(false, "agent error: ${ex.message ?: ex.toString()}")
        }
    }
}
