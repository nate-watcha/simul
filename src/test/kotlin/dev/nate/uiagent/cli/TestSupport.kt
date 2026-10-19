package dev.nate.uiagent.cli

import dev.nate.uiagent.agent.ScenarioSession
import dev.nate.uiagent.device.DisplayProfile
import dev.nate.uiagent.device.DeviceOps
import java.io.File

// ---------------------------------------------------------------- runner fakes

internal class FakeOps(private val versionName: String? = "5.4.0") : DeviceOps {
    /** Every setup call as `app|appState|clearData|launch|deeplink`. */
    val setups = mutableListOf<String>()
    var display: String? = null

    override fun setup(app: String?, appState: String?, clearData: Boolean, launch: Boolean, deeplink: String?) {
        setups += "$app|$appState|$clearData|$launch|$deeplink"
    }
    override fun screenshot(dest: File): Boolean = false
    override fun logcatClear() {}
    override fun logcatDump(dest: File): Boolean = false
    override fun appVersionName(app: String?): String? = versionName
    override fun deviceName(): String? = "fake-device"
    override fun displayProfile(): String? = display
    var androidCli: String? = "1.0.test"
    override fun androidCliVersion(): String? = androidCli

    var emulator = true
    override fun isEmulator(): Boolean = emulator
    override fun setDisplay(profile: DisplayProfile) { display = profile.toString() }
    override fun resetDisplay() { display = null }
    /** `namespace.key` → value; absent = unset. */
    val settings = linkedMapOf<String, String>()
    /** Every settings write in order: `put ns.key=value` / `delete ns.key`. */
    val settingLog = mutableListOf<String>()
    override fun settingGet(namespace: String, key: String): String? = settings["$namespace.$key"]
    override fun settingPut(namespace: String, key: String, value: String) {
        settings["$namespace.$key"] = value; settingLog += "put $namespace.$key=$value"
    }
    override fun settingDelete(namespace: String, key: String) {
        settings.remove("$namespace.$key"); settingLog += "delete $namespace.$key"
    }
}

/** Step executor driven by a lambda (which may drive the controller to simulate agent actions). */
internal class ScriptedExecutor(private val behavior: (String) -> ScenarioSession.Verdict) : StepExecutor {
    var calls = 0
    val criteria = mutableListOf<String?>()

    override fun runStep(stepText: String, initialLayout: String?, criterion: String?): ScenarioSession.Verdict {
        calls++
        criteria += criterion
        return behavior(stepText)
    }
}

/** Write a run's result.json the way the CLI does, with placeholder scenario metadata. */
internal fun writeResult(r: ScenarioRunResult, mode: String, ops: DeviceOps, dir: File): File {
    ResultJson.write(r, mode, ResultJson.Meta(
        feature = "", name = r.scenario, state = null, file = "${r.scenario}.md", title = r.scenario, owner = null,
        description = "", traceRecordedAt = null,
        env = ResultJson.Env(ops.appVersionName(null), ops.deviceName(), ops.androidCliVersion()),
    ), dir)
    return File(dir, ResultJson.FILE)
}

internal val neverCalledExecutor = ScriptedExecutor { throw AssertionError("LLM must not be called in pure replay") }
