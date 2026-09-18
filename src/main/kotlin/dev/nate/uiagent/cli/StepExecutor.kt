package dev.nate.uiagent.cli

import dev.nate.uiagent.agent.ChatClient
import dev.nate.uiagent.agent.HttpChatClient
import dev.nate.uiagent.agent.ScenarioSession
import dev.nate.uiagent.device.DeviceController

/**
 * Executes one natural-language step. The runner records actions via the controller's
 * [dev.nate.uiagent.device.ActionListener]; this interface only reports the verdict.
 * Tests substitute a scripted fake; production uses [SessionStepExecutor].
 */
interface StepExecutor {
    /**
     * [initialLayout] is non-null only when a fresh observation was taken for this step
     * (scenario start). [criterion] is the step's `::` expected-result clause, if any —
     * delivered to the model only after its first tool result (see [ScenarioSession.runStep]).
     */
    fun runStep(stepText: String, initialLayout: String?, criterion: String? = null): StepVerdict
}

data class StepVerdict(val passed: Boolean, val reason: String)

/**
 * All steps of one scenario share ONE LLM conversation ([ScenarioSession]): the model keeps
 * its own action/diff history across steps, and because the transcript is append-only the
 * llama.cpp prefix cache re-evaluates only each step's new tokens (command + new results)
 * instead of re-reading a full layout every step. Verdicts remain per step via report().
 */
class SessionStepExecutor(
    private val controller: DeviceController,
    url: String,
    private val maxIterations: Int = 12,
    private val log: (String) -> Unit = {},
    private val client: ChatClient = HttpChatClient(url),
) : StepExecutor {

    private var session: ScenarioSession? = null

    override fun runStep(stepText: String, initialLayout: String?, criterion: String?): StepVerdict {
        val s = session ?: ScenarioSession(client, controller, maxIterations, log)
            .also { session = it }
        val v = s.runStep(stepText, initialLayout, criterion)
        return StepVerdict(v.passed, v.reason)
    }
}
