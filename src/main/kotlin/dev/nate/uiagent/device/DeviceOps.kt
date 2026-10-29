package dev.nate.uiagent.device

import dev.nate.uiagent.runProcess
import dev.nate.uiagent.runToFile
import java.io.File

/**
 * Runner-side device operations that are not model tools: scenario setup (deeplink/cold
 * launch), screenshots, and environment facts for the report. Tests substitute a fake.
 */
/** The one app state that needs no snapshot: `pm clear` alone is the whole restore. */
const val GUEST_STATE = "guest"

interface DeviceOps {
    /**
     * Scenario setup: optional app-data restore (state snapshot or clear), cold start, deeplink.
     * After a launch or deeplink it waits for the app's own "I have drawn my content" signal —
     * `Activity.reportFullyDrawn()` (Compose `ReportDrawnWhen`), which the system logs as
     * `Fully drawn <pkg>/<activity>: +Ns` — and returns that duration; null when the app never
     * reported within [AdbOps.FULLY_DRAWN_TIMEOUT_MS] (or when nothing was launched).
     *
     * [locale] is the app's language for this scenario (`cmd locale set-app-locales`), applied
     * after the clear/restore and before the launch: `pm clear` wipes the per-app locale, so
     * setting it any earlier would be undone (measured). null = leave the app's language alone.
     */
    fun setup(app: String?, appState: String?, clearData: Boolean, launch: Boolean, deeplink: String?, locale: String? = null): String?

    /** Capture a PNG screenshot into [dest]; false if capture failed (non-fatal). */
    fun screenshot(dest: File): Boolean

    /**
     * Clear the device log, so the next [logcatDump] covers exactly one scenario — and turn on Firebase
     * Analytics' event log (`log.tag.FA-SVC VERBOSE`: the Play services measurement process then logs every
     * event the app sends, `Logging event: origin=app,name=…,params=Bundle[{…}]`), so the dump carries the
     * events of that scenario (see cli/AnalyticsEvents). A log level only; it persists until reboot and is not restored.
     */
    fun logcatClear()

    /** Dump the device log since the last clear into [dest] (threadtime + epoch timestamps); false when nothing was written. */
    fun logcatDump(dest: File): Boolean

    fun appVersionName(app: String?): String?
    fun deviceName(): String?

    /**
     * Version of the `android` CLI that produces every observation. Its `layout` output
     * format has changed between releases (flat list → tree), so the version is stamped into
     * traces and reports and a replay under a different version gets a warning.
     */
    fun androidCliVersion(): String?

    // ---- display profile enforcement (emulator-only safety net; see DisplayProfile) ----

    /** Current effective display as "WxH@dpi" (override if set, else physical); null if unreadable. */
    fun displayProfile(): String?

    fun isEmulator(): Boolean
    fun setDisplay(profile: DisplayProfile)
    fun resetDisplay()

    // ---- `settings` pinning (emulator-only, see DeviceSettings) ----

    /** `settings get <namespace> <key>`; null when the key is unset (the shell prints `null`). */
    fun settingGet(namespace: String, key: String): String?
    fun settingPut(namespace: String, key: String, value: String)
    fun settingDelete(namespace: String, key: String)

    // ---- per-app language (Android 13+, see DevicePins) ----

    /** `cmd locale get-app-locales <app>`: the app's language list (`ko-KR` / `ko-KR,en-US`); null = follows the system language. */
    fun appLocales(app: String): String?
    /** `cmd locale set-app-locales <app> [--locales <locales>]`; null/empty = follow the system language again. */
    fun setAppLocales(app: String, locales: String?)
}

class AdbOps(
    private val adbBin: String = "adb",
    private val androidBin: String = "android",
    /** Where `simul state save` snapshots live (`.simul/states`); null disables appState. */
    private val statesDir: File? = null,
) : DeviceOps {

    override fun setup(app: String?, appState: String?, clearData: Boolean, launch: Boolean, deeplink: String?, locale: String?): String? {
        if ((clearData || appState != null) && app != null) {
            runCatching { runProcess(listOf(adbBin, "shell", "am", "force-stop", app)) }
            runProcess(listOf(adbBin, "shell", "pm", "clear", app))
            // pm clear also revokes runtime permissions — re-grant the ones whose system
            // dialogs would otherwise block the very first step (seen on-device).
            runCatching {
                runProcess(listOf(adbBin, "shell", "pm", "grant", app, "android.permission.POST_NOTIFICATIONS"))
            }
        }
        appState?.let { name ->
            requireNotNull(app) { "setup.appState requires `app` in config.yaml" }
            val tar = File(statesDir ?: error("appState needs a .simul project"), "$name.tar")
            if (!tar.isFile) {
                // `guest` without a snapshot is a fresh install — the pm clear above is the whole restore
                require(name == GUEST_STATE) { "app state '$name' not found: ${tar.path} — create it with `simul state save $name`" }
                return@let
            }
            // debuggable builds only: restore the app-data snapshot under the app's own uid
            runProcess(listOf(adbBin, "push", tar.path, "/data/local/tmp/simul-state.tar"))
            runProcess(listOf(adbBin, "shell", "run-as", app, "sh", "-c", "'tar xf /data/local/tmp/simul-state.tar'"))
            runCatching { runProcess(listOf(adbBin, "shell", "rm", "/data/local/tmp/simul-state.tar")) }
        }
        // after the clear (which wipes it), before the launch (so the first frame is already in it)
        if (locale != null && app != null) setAppLocales(app, locale)
        if (launch && app != null) {
            // cold start: kill, then fire the launcher intent; give the splash a head start
            // before the runner's wait-for-stable takes over
            runCatching { runProcess(listOf(adbBin, "shell", "am", "force-stop", app)) }
            runProcess(listOf(adbBin, "shell", "monkey", "-p", app, "-c", "android.intent.category.LAUNCHER", "1"))
            Thread.sleep(3000)
        }
        deeplink?.let { link ->
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
        if (app == null || !(launch || deeplink != null)) return null
        return awaitFullyDrawn(app)
    }

    /**
     * Poll the device log for the app's `Fully drawn` line. `am start -W` returns at the first
     * frame of whatever activity the intent resolved to (TTID) — for kloud that is a routing
     * activity showing an ad splash, and the destination screen arrives seconds later, after
     * a network round trip (measured: +3.6s after setup, splash timeout 5s). Only the app knows
     * which activity is the destination, so that activity reports it and this waits for that.
     * The runner cleared logcat before setup, so `-d` covers exactly this launch.
     */
    private fun awaitFullyDrawn(app: String): String? {
        val deadline = System.currentTimeMillis() + FULLY_DRAWN_TIMEOUT_MS
        while (true) {
            val out = runCatching {
                runProcess(listOf(adbBin, "logcat", "-d", "-v", "brief", "ActivityTaskManager:I", "ActivityManager:I", "*:S"))
            }.getOrDefault("")
            fullyDrawn(out, app)?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(FULLY_DRAWN_POLL_MS)
        }
    }


    override fun screenshot(dest: File): Boolean = runToFile(listOf(adbBin, "exec-out", "screencap", "-p"), dest) == null

    override fun logcatClear() {
        // `Log.isLoggable` reads the property live — the already-running Play services process picks it up (measured)
        runCatching { runProcess(listOf(adbBin, "shell", "setprop", "log.tag.FA-SVC", "VERBOSE")) }
        runCatching { runProcess(listOf(adbBin, "logcat", "-c")) }
    }

    // epoch timestamps (seconds.millis, device clock) so a report can slice the log around a step
    override fun logcatDump(dest: File): Boolean =
        runToFile(listOf(adbBin, "logcat", "-d", "-v", "threadtime", "-v", "epoch"), dest) == null

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

    override fun settingGet(namespace: String, key: String): String? =
        runProcess(listOf(adbBin, "shell", "settings", "get", namespace, key)).trim().takeIf { it != "null" }

    override fun settingPut(namespace: String, key: String, value: String) {
        // adb joins the argv and the device shell re-parses it, so an empty value would vanish
        // and spaces would split — single-quote for that shell (`'` itself as '\'').
        val quoted = "'" + value.replace("'", "'\\''") + "'"
        runProcess(listOf(adbBin, "shell", "settings", "put", namespace, key, quoted))
    }

    override fun settingDelete(namespace: String, key: String) {
        runProcess(listOf(adbBin, "shell", "settings", "delete", namespace, key))
    }

    override fun appLocales(app: String): String? =
        parseAppLocales(runProcess(listOf(adbBin, "shell", "cmd", "locale", "get-app-locales", app)), app)

    override fun setAppLocales(app: String, locales: String?) {
        // `cmd locale` reports an unknown package on stdout with exit 0 — read the output.
        val out = runProcess(
            listOf(adbBin, "shell", "cmd", "locale", "set-app-locales", app) +
                (if (locales.isNullOrEmpty()) emptyList() else listOf("--locales", locales))
        )
        localeError(out)?.let { throw RuntimeException("could not set app locale '${locales ?: ""}' for $app: $it") }
    }

    /**
     * Snapshot the app's data dir (login token, selected profile, onboarding flags — caches
     * excluded) into [dest] via `run-as` (debuggable builds only). Binary-safe: the tar
     * stream is redirected straight to the file, never through a String.
     */
    fun saveState(app: String, dest: File): Boolean {
        runCatching { runProcess(listOf(adbBin, "shell", "am", "force-stop", app)) }
        val err = runToFile(
            listOf(adbBin, "exec-out", "run-as", app, "tar", "cf", "-", "--exclude=./cache", "--exclude=./code_cache", "."),
            dest,
        ) ?: return true
        System.err.println("state save failed (run-as needs a debuggable build): ${err.take(200)}")
        return false
    }

    companion object {
        /** How long setup waits for the app to report fully drawn — above kloud's splash-ad timeout (5s) plus a load. */
        const val FULLY_DRAWN_TIMEOUT_MS = 15_000L
        private const val FULLY_DRAWN_POLL_MS = 500L

        /**
         * The `+Ns` the system logs when [app]'s activity calls `reportFullyDrawn()`
         * (`ActivityTaskManager: Fully drawn com.x/.Main: +3s812ms`); null when no such line.
         */
        fun fullyDrawn(logcat: String, app: String): String? =
            Regex("Fully drawn ${Regex.escape(app)}/\\S+: (\\+\\S+)").find(logcat)?.groupValues?.get(1)

        /**
         * The list in `Locales for <app> for user 0 are [ko-KR,en-US]`; null for `[]` (follows the
         * system). An unknown package or any other error line throws — the output is the only signal.
         */
        fun parseAppLocales(out: String, app: String): String? {
            localeError(out)?.let { throw RuntimeException("could not read app locale for $app: $it") }
            val list = Regex("are \\[([^\\]]*)]").find(out)
                ?: throw RuntimeException("could not read app locale for $app: unexpected output '${out.trim()}' (cmd locale needs Android 13+)")
            return list.groupValues[1].trim().ifEmpty { null }
        }

        private fun localeError(out: String): String? =
            out.lineSequence().map { it.trim() }
                .firstOrNull { it.startsWith("Unknown package") || it.startsWith("Error") || it.startsWith("Exception") }

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
