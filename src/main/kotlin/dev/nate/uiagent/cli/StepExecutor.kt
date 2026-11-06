package dev.nate.uiagent.cli

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import dev.nate.uiagent.Defaults
import dev.nate.uiagent.agent.ScenarioSession
import dev.nate.uiagent.agent.SimulLlm
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
    fun runStep(stepText: String, initialLayout: String?, criterion: String? = null): ScenarioSession.Verdict

    /**
     * The next step starts a conversation of its own: a scenario's section begins from a fresh setup,
     * and what the model saw of the screens before it no longer describes the app.
     */
    fun newSession() {}
}

/**
 * All steps of one scenario — of one section, when it has sections ([newSession]) — share ONE LLM conversation ([ScenarioSession], Koog ChatMemory):
 * the model keeps its own action/diff history across steps, and because the transcript is
 * append-only the llama.cpp prefix cache re-evaluates only each step's new tokens (command +
 * new results) instead of re-reading a full layout every step. Verdicts remain per step via
 * report().
 *
 * [executor] is resolved on the first step that needs the model: the runner builds this
 * executor before it knows whether a step will need the LLM at all (replay never does), so
 * key validation is deferred to the first LLM step.
 */
class SessionStepExecutor(
    private val controller: DeviceController,
    private val executor: () -> PromptExecutor,
    private val model: LLModel = SimulLlm.model(),
    private val maxIterations: Int = Defaults.MAX_TURNS,
    private val log: (String) -> Unit = {},
    /** System-prompt background for the session: config `context:` and this scenario's account state (see [dev.nate.uiagent.agent.systemPrompt]). */
    private val appContext: String? = null,
    private val accountState: String? = null,
) : StepExecutor {

    private var session: ScenarioSession? = null
    /** One client for the scenario, however many conversations its sections open. */
    private val client by lazy(executor)

    override fun runStep(stepText: String, initialLayout: String?, criterion: String?): ScenarioSession.Verdict {
        val s = session ?: ScenarioSession(client, controller, maxIterations, log, model, appContext = appContext, accountState = accountState)
            .also { session = it }
        return s.runStep(stepText, initialLayout, criterion)
    }

    override fun newSession() { session = null }
}
