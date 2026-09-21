package dev.nate.uiagent.cli

import dev.nate.uiagent.runProcess
import java.io.File

/**
 * Runner-side device operations that are not model tools: scenario setup (deeplink/cold
 * launch), screenshots, and environment facts for the report. Tests substitute a fake.
 */
interface DeviceOps {
    fun setup(setup: Scenario.Setup, app: String?)

    /** Capture a PNG screenshot into [dest]; false if capture failed (non-fatal). */
    fun screenshot(dest: File): Boolean

    fun appVersionName(app: String?): String?
    fun deviceName(): String?

    /**
     * Version of the `android` CLI that produces every observation. Its `layout` output
     * format has changed between releases (flat list → tree), so the version is stamped into
     * traces and reports and a replay under a different version gets a warning.
     */
    fun androidCliVersion(): String? = null

    // ---- display profile enforcement (emulator-only safety net; see DisplayProfile) ----

    /** Current effective display as "WxH@dpi" (override if set, else physical); null if unreadable. */
    fun displayProfile(): String? = null

    fun isEmulator(): Boolean = false
    fun setDisplay(profile: DisplayProfile) {}
    fun resetDisplay() {}
}

class AdbOps(
    private val adbBin: String = "adb",
    private val androidBin: String = "android",
    /** Where `simul state save` snapshots live (`.simul/states`); null disables appState. */
    private val statesDir: File? = null,
) : DeviceOps {

    override fun setup(setup: Scenario.Setup, app: String?) {
        if ((setup.clearData || setup.appState != null) && app != null) {
            runCatching { runProcess(listOf(adbBin, "shell", "am", "force-stop", app)) }
            runProcess(listOf(adbBin, "shell", "pm", "clear", app))
            // pm clear also revokes runtime permissions — re-grant the ones whose system
            // dialogs would otherwise block the very first step (seen on-device).
            runCatching {
                runProcess(listOf(adbBin, "shell", "pm", "grant", app, "android.permission.POST_NOTIFICATIONS"))
            }
        }
        setup.appState?.let { name ->
            requireNotNull(app) { "setup.appState requires `app` in config.yaml" }
            val tar = File(statesDir ?: error("appState needs a .simul project"), "$name.tar")
            require(tar.isFile) { "app state '$name' not found: ${tar.path} — create it with `simul state save $name`" }
            // debuggable builds only: restore the app-data snapshot under the app's own uid
            runProcess(listOf(adbBin, "push", tar.path, "/data/local/tmp/simul-state.tar"))
            runProcess(listOf(adbBin, "shell", "run-as", app, "sh", "-c", "'tar xf /data/local/tmp/simul-state.tar'"))
            runCatching { runProcess(listOf(adbBin, "shell", "rm", "/data/local/tmp/simul-state.tar")) }
        }
        if (setup.launch && app != null) {
            // cold start: kill, then fire the launcher intent; give the splash a head start
            // before the runner's wait-for-stable takes over
            runCatching { runProcess(listOf(adbBin, "shell", "am", "force-stop", app)) }
            runProcess(listOf(adbBin, "shell", "monkey", "-p", app, "-c", "android.intent.category.LAUNCHER", "1"))
            Thread.sleep(3000)
        }
        setup.deeplink?.let { link ->
            // Pin the package: with both prod and dev builds installed, an unpinned VIEW
            // intent opens the system app chooser instead of the app under test.
            val out = runProcess(
                listOf(adbBin, "shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", link) +
                    listOfNotNull(app)
            )
            // `am start -W` exits 0 even when nothing handled the URI — the app can't process
            // this deeplink. Surface it as a precise setup failure instead of letting the test
            // fail later at the Verify step with a confusing "landmark not found".
            deeplinkError(out)?.let { throw RuntimeException("deeplink not handled by app: $link — $it") }
        }
    }


    override fun screenshot(dest: File): Boolean = try {
        dest.parentFile?.mkdirs()
        val p = ProcessBuilder(adbBin, "exec-out", "screencap", "-p")
            .redirectOutput(dest)
            .redirectErrorStream(false)
            .start()
        p.waitFor() == 0 && dest.length() > 0
    } catch (_: Exception) {
        false
    }

    override fun appVersionName(app: String?): String? {
        if (app == null) return null
        return runCatching {
            runProcess(listOf(adbBin, "shell", "dumpsys", "package", app))
                .lineSequence()
                .firstOrNull { "versionName=" in it }
                ?.substringAfter("versionName=")
                ?.trim()
        }.getOrNull()
    }

    override fun androidCliVersion(): String? = runCatching {
        // stdout is the bare version; the "new version available" notice goes to stderr
        runProcess(listOf(androidBin, "--version")).lineSequence()
            .map { it.trim() }.firstOrNull { it.matches(Regex("\\d+(\\.\\d+)+")) }
    }.getOrNull()

    override fun deviceName(): String? = runCatching {
        runProcess(listOf(adbBin, "shell", "getprop", "ro.product.model")).trim().ifEmpty { null }
    }.getOrNull()

    override fun displayProfile(): String? = runCatching {
        parseWm(
            runProcess(listOf(adbBin, "shell", "wm", "size")),
            runProcess(listOf(adbBin, "shell", "wm", "density")),
        )
    }.getOrNull()

    override fun isEmulator(): Boolean = runCatching {
        runProcess(listOf(adbBin, "shell", "getprop", "ro.kernel.qemu")).trim() == "1" ||
            runProcess(listOf(adbBin, "shell", "getprop", "ro.boot.qemu")).trim() == "1"
    }.getOrDefault(false)

    override fun setDisplay(profile: DisplayProfile) {
        runProcess(listOf(adbBin, "shell", "wm", "size", "${profile.width}x${profile.height}"))
        runProcess(listOf(adbBin, "shell", "wm", "density", "${profile.density}"))
    }

    override fun resetDisplay() {
        runCatching { runProcess(listOf(adbBin, "shell", "wm", "size", "reset")) }
        runCatching { runProcess(listOf(adbBin, "shell", "wm", "density", "reset")) }
    }

    /**
     * Snapshot the app's data dir (login token, selected profile, onboarding flags — caches
     * excluded) into [dest] via `run-as` (debuggable builds only). Binary-safe: the tar
     * stream is redirected straight to the file, never through a String.
     */
    fun saveState(app: String, dest: File): Boolean {
        runCatching { runProcess(listOf(adbBin, "shell", "am", "force-stop", app)) }
        dest.parentFile?.mkdirs()
        val p = ProcessBuilder(
            adbBin, "exec-out", "run-as", app,
            "tar", "cf", "-", "--exclude=./cache", "--exclude=./code_cache", ".",
        ).redirectOutput(dest).redirectErrorStream(false).start()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor()
        if (p.exitValue() != 0 || dest.length() == 0L) {
            System.err.println("state save failed (run-as needs a debuggable build): ${err.trim().take(200)}")
            dest.delete()
            return false
        }
        return true
    }

    companion object {
        /** The error `am start -W` reports (on stdout, exit 0) when a URI resolves to nothing. */
        fun deeplinkError(amOutput: String): String? =
            amOutput.lineSequence().map { it.trim() }
                .firstOrNull { it.startsWith("Error:") || "unable to resolve Intent" in it }

        /**
         * Combine `wm size` / `wm density` outputs into "WxH@dpi". Both print a
         * "Physical ...:" line and, when an override is active, an "Override ...:" line —
         * the override is what apps actually see, so it wins.
         */
        fun parseWm(sizeOut: String, densityOut: String): String? {
            fun pick(out: String, key: String): String? {
                val lines = out.lines().map { it.trim() }
                return (lines.firstOrNull { it.startsWith("Override $key:") }
                    ?: lines.firstOrNull { it.startsWith("Physical $key:") })
                    ?.substringAfter(':')?.trim()
            }
            val size = pick(sizeOut, "size") ?: return null
            val density = pick(densityOut, "density") ?: return null
            return "$size@$density"
        }
    }
}
