package dev.nate.uiagent.device

import dev.nate.uiagent.ProcessError
import dev.nate.uiagent.ProcessTimeout
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
    /** The device these operations are for, when the run is over several ([adbCommand]); null = the one adb picks. */
    val serial: String? get() = null

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

    /**
     * Enter again inside a scenario — what a section after the first starts from: stop the app and bring
     * it back to the scenario's entry ([launch]/[deeplink] as in [setup]) with its data as it is. The
     * process is new, so nothing the last section left open survives and what the screen shows was read
     * again; the account and whatever the app saved are not reset — that is done once, by [setup]. Like a
     * user who closes the app and opens it again. Returns the fully-drawn time like [setup].
     */
    fun reopen(app: String?, launch: Boolean, deeplink: String?): String?

    /**
     * How the last [setup] (or [reopen]: `stop`, `launch`, `fully drawn wait`) spent its time, phase by phase in order (`restore`, `locale`, `launch`,
     * `fully drawn wait`) — setup is paid once per scenario and per phase of a nightly (8s × 78 runs
     * of a 71-minute batch), and its one number could not say which part to look at.
     */
    val setupPhases: List<Pair<String, Long>> get() = emptyList()

    /** Capture a PNG screenshot into [dest]; false if capture failed (non-fatal). */
    fun screenshot(dest: File): Boolean

    /**
     * Is the device still answering? null when it is, else what does not come back — asked by the
     * batch at its start and after a scenario that did not pass ([dev.nate.uiagent.cli.BatchSession]).
     * Two things are looked at, because one was alive while the other was not: the emulator of stone
     * run 42039775943 kept answering `adb shell` (am start, logcat) while nothing was drawn any more —
     * the app never reported fully drawn, a layout dump took 39s, a screencap never returned.
     */
    fun unresponsive(): String? = null

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

    // ---- display profile enforcement (a device that is the runner's to set up; see DisplayProfile) ----

    /** Current effective display as "WxH@dpi" (override if set, else physical); null if unreadable. */
    fun displayProfile(): String?

    fun isEmulator(): Boolean

    /**
     * Is this device the runner's to set up — its display forced, its `settings` pinned? An emulator is. A
     * physical device is only when whoever placed it said so on the device itself, once:
     * `adb shell settings put global simul_device 1` ([AdbOps.DEDICATED_KEY]). A phone without that mark is a
     * person's and is left as it is; the mark lives on the device, so no run has to be told which is which.
     */
    fun dedicated(): Boolean = isEmulator()

    /**
     * Turn the screen on and leave the lock screen, before a batch: a physical device that sits plugged in
     * sleeps between runs (an emulator's screen never goes off). Only a device without a PIN or pattern can
     * be brought past its lock screen this way — setting that up is the placing of the device, not a run's work.
     */
    fun wake() {}

    fun setDisplay(profile: DisplayProfile)
    fun resetDisplay()

    // ---- `settings` pinning (see DeviceSettings) ----

    /** `settings get <namespace> <key>`; null when the key is unset (the shell prints `null`). */
    fun settingGet(namespace: String, key: String): String?
    fun settingPut(namespace: String, key: String, value: String)
    fun settingDelete(namespace: String, key: String)

    // ---- network (see DeviceNetwork) ----

    /**
     * Does the device reach the internet — a name resolved and a connection opened from inside it, the way the
     * app under test has to? null when the device has no way to tell (no `nc` on it): then nothing is concluded.
     */
    fun online(): Boolean? = null

    /** Is airplane mode on? null when unreadable. */
    fun airplaneMode(): Boolean? = null

    fun setAirplaneMode(on: Boolean) {}

    /** Does adb itself reach this device over the network (wireless debugging, `adb connect`)? Its network cannot be taken away then. */
    fun reachedOverNetwork(): Boolean = false

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
    override val serial: String? = null,
) : DeviceOps {

    private fun adb(vararg args: String): List<String> = adbCommand(adbBin, serial, *args)

    override var setupPhases: List<Pair<String, Long>> = emptyList()
        private set

    override fun setup(app: String?, appState: String?, clearData: Boolean, launch: Boolean, deeplink: String?, locale: String?): String? {
        val phase = phaseClock()
        if ((clearData || appState != null) && app != null) {
            runCatching { runProcess(adb("shell", "am", "force-stop", app)) }
            runProcess(adb("shell", "pm", "clear", app))
            // pm clear also revokes runtime permissions — re-grant the ones whose system
            // dialogs would otherwise block the very first step (seen on-device).
            runCatching {
                runProcess(adb("shell", "pm", "grant", app, "android.permission.POST_NOTIFICATIONS"))
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
            runProcess(adb("push", tar.path, "/data/local/tmp/simul-state.tar"))
            runProcess(adb("shell", "run-as", app, "sh", "-c", "'tar xf /data/local/tmp/simul-state.tar'"))
            runCatching { runProcess(adb("shell", "rm", "/data/local/tmp/simul-state.tar")) }
        }
        phase("restore")
        // after the clear (which wipes it), before the launch (so the first frame is already in it)
        if (locale != null && app != null) setAppLocales(app, locale)
        phase("locale")
        return open(app, launch, deeplink, phase)
    }

    override fun reopen(app: String?, launch: Boolean, deeplink: String?): String? {
        val phase = phaseClock()
        if (app != null) runCatching { runProcess(adb("shell", "am", "force-stop", app)) }
        phase("stop")
        return open(app, launch, deeplink, phase)
    }

    /** Starts [setupPhases] over; each call of the result closes the phase that ran since the last one. */
    private fun phaseClock(): (String) -> Unit {
        val phases = mutableListOf<Pair<String, Long>>()
        var mark = System.currentTimeMillis()
        setupPhases = emptyList()
        return { name ->
            val now = System.currentTimeMillis()
            if (now - mark > 0) phases += name to now - mark
            mark = now
            setupPhases = phases.toList()
        }
    }

    /** Launch and/or deeplink into [app], then wait for the fully-drawn report of THIS launch. */
    private fun open(app: String?, launch: Boolean, deeplink: String?, phase: (String) -> Unit): String? {
        // What the app has reported so far: a scenario's log is cleared once, so on a second entry the
        // first one's `Fully drawn` line is still there and must not be taken for this launch's.
        val drawnBefore = if (app != null && (launch || deeplink != null)) runCatching { fullyDrawnAll(launchLog(), app) }.getOrDefault(emptyList()) else emptyList()
        fun start() {
            if (launch && app != null) {
                // cold start: kill, then fire the launcher intent. What follows is the wait for the
                // app's own fully-drawn signal ([awaitFullyDrawn]), so nothing is slept here: the
                // fixed 3s head start that predates that wait was paid on top of it by every
                // launcher scenario (52 setups of stone run 41992964956, the app drawn in 1.4s).
                // Only a deeplink fired onto the app still starting keeps it — untested without.
                runCatching { runProcess(adb("shell", "am", "force-stop", app)) }
                runProcess(adb("shell", "monkey", "-p", app, "-c", "android.intent.category.LAUNCHER", "1"))
                if (deeplink != null) Thread.sleep(3000)
            }
            deeplink?.let { link ->
                // Pin the package: with both prod and dev builds installed, an unpinned VIEW
                // intent opens the system app chooser instead of the app under test.
                val out = runProcess(
                    adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", link) +
                        listOfNotNull(app)
                )
                // `am start -W` exits 0 even when nothing handled the URI — the app can't process
                // this deeplink. Surface it as a precise setup failure instead of letting the test
                // fail later at the Verify step with a confusing "landmark not found".
                deeplinkError(out)?.let { throw RuntimeException("deeplink not handled by app: $link — $it") }
            }
        }
        start()
        phase("launch")
        if (app == null || !(launch || deeplink != null)) return null
        return awaitFullyDrawn(app, ::start, drawnBefore).also { phase("fully drawn wait") }
    }

    /** The activity manager's lines of the device log — launches, kills, `Fully drawn`. */
    private fun launchLog(): String =
        runProcess(adb("logcat", "-d", "-v", "brief", "ActivityTaskManager:I", "ActivityManager:I", "*:S"))

    /**
     * Poll the device log for the app's `Fully drawn` line. `am start -W` returns at the first
     * frame of whatever activity the intent resolved to (TTID) — for kloud that is a routing
     * activity showing an ad splash, and the destination screen arrives seconds later, after
     * a network round trip (measured: +3.6s after setup, splash timeout 5s). Only the app knows
     * which activity is the destination, so that activity reports it and this waits for that.
     * The runner cleared logcat before the scenario, so `-d` covers its launches; [drawnBefore] is what
     * the app had already reported when this one was fired (a section's re-entry), and only a report
     * after that counts.
     *
     * The same log shows the one launch that has to be repeated ([killedWithOldTask]): [relaunch]
     * runs once when the process just started was taken down with the previous scenario's task.
     */
    private fun awaitFullyDrawn(app: String, relaunch: () -> Unit, drawnBefore: List<String> = emptyList()): String? {
        var deadline = System.currentTimeMillis() + FULLY_DRAWN_TIMEOUT_MS
        var relaunched = false
        while (true) {
            val out = runCatching { launchLog() }.getOrDefault("")
            if (!relaunched && killedWithOldTask(out, app)) {
                relaunched = true
                System.err.println("setup: the launch was killed with the previous scenario's task — launching again")
                relaunch()
                deadline = System.currentTimeMillis() + FULLY_DRAWN_TIMEOUT_MS
                continue
            }
            drawnSince(drawnBefore, fullyDrawnAll(out, app))?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(FULLY_DRAWN_POLL_MS)
        }
    }


    override fun screenshot(dest: File): Boolean =
        runToFile(adb("exec-out", "screencap", "-p"), dest, SCREENCAP_TIMEOUT_MS) == null

    override fun unresponsive(): String? {
        try {
            if (runProcess(adb("shell", "echo", "ok"), PROBE_TIMEOUT_MS).trim() != "ok") return "adb shell does not answer"
        } catch (e: Exception) {
            return "adb shell does not answer (${(e as? ProcessError)?.brief ?: e.message})"
        }
        // Only a capture that never returns counts: one that fails says nothing about the display.
        try {
            runProcess(adb("shell", "screencap -p > /dev/null"), SCREENCAP_TIMEOUT_MS)
        } catch (e: ProcessTimeout) {
            return "a screencap does not return within ${e.timeoutMs / 1000}s — the display has stopped"
        } catch (_: Exception) {
        }
        return null
    }

    override fun logcatClear() {
        // `Log.isLoggable` reads the property live — the already-running Play services process picks it up (measured)
        runCatching { runProcess(adb("shell", "setprop", "log.tag.FA-SVC", "VERBOSE")) }
        runCatching { runProcess(adb("logcat", "-c")) }
    }

    // epoch timestamps (seconds.millis, device clock) so a report can slice the log around a step
    override fun logcatDump(dest: File): Boolean =
        runToFile(adb("logcat", "-d", "-v", "threadtime", "-v", "epoch"), dest) == null

    override fun appVersionName(app: String?): String? {
        if (app == null) return null
        return runCatching {
            runProcess(adb("shell", "dumpsys", "package", app))
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

    // with the serial when the run names its devices: four emulators of one image are one model
    override fun deviceName(): String? = runCatching {
        runProcess(adb("shell", "getprop", "ro.product.model")).trim().ifEmpty { null }
    }.getOrNull().let { model -> if (serial == null) model else listOfNotNull(model, "($serial)").joinToString(" ") }

    override fun displayProfile(): String? = runCatching {
        parseWm(
            runProcess(adb("shell", "wm", "size")),
            runProcess(adb("shell", "wm", "density")),
        )
    }.getOrNull()

    override fun isEmulator(): Boolean = runCatching {
        runProcess(adb("shell", "getprop", "ro.kernel.qemu")).trim() == "1" ||
            runProcess(adb("shell", "getprop", "ro.boot.qemu")).trim() == "1"
    }.getOrDefault(false)

    /** Is [app] installed? `pm path` prints its APK, or exits 1 for a package the device does not know. */
    fun installed(app: String): Boolean =
        runCatching { runProcess(adb("shell", "pm", "path", app)).lineSequence().any { it.startsWith("package:") } }.getOrDefault(false)

    override fun dedicated(): Boolean =
        isEmulator() || runCatching { settingGet("global", DEDICATED_KEY) == "1" }.getOrDefault(false)

    override fun wake() {
        runCatching { runProcess(adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")) }
        runCatching { runProcess(adb("shell", "wm", "dismiss-keyguard")) }
    }

    override fun setDisplay(profile: DisplayProfile) {
        runProcess(adb("shell", "wm", "size", "${profile.width}x${profile.height}"))
        runProcess(adb("shell", "wm", "density", "${profile.density}"))
    }

    override fun resetDisplay() {
        runCatching { runProcess(adb("shell", "wm", "size", "reset")) }
        runCatching { runProcess(adb("shell", "wm", "density", "reset")) }
    }

    override fun settingGet(namespace: String, key: String): String? =
        runProcess(adb("shell", "settings", "get", namespace, key)).trim().takeIf { it != "null" }

    override fun settingPut(namespace: String, key: String, value: String) {
        // adb joins the argv and the device shell re-parses it, so an empty value would vanish
        // and spaces would split — single-quote for that shell (`'` itself as '\'').
        val quoted = "'" + value.replace("'", "'\\''") + "'"
        runProcess(adb("shell", "settings", "put", namespace, key, quoted))
    }

    override fun settingDelete(namespace: String, key: String) {
        runProcess(adb("shell", "settings", "delete", namespace, key))
    }

    override fun online(): Boolean? = runCatching {
        // the exit code comes back on stdout: `adb shell` passes it on only in its newer protocol
        onlineOf(runProcess(adb("shell", "timeout 5 nc -w 3 $ONLINE_HOST 443 </dev/null >/dev/null 2>&1; echo \$?"), PROBE_TIMEOUT_MS))
    }.getOrNull()

    override fun airplaneMode(): Boolean? = runCatching {
        when (settingGet("global", "airplane_mode_on")) { "1" -> true; "0" -> false; else -> null }
    }.getOrNull()

    override fun setAirplaneMode(on: Boolean) {
        // Android 11+; the setting alone would not tell the radios
        runProcess(adb("shell", "cmd", "connectivity", "airplane-mode", if (on) "enable" else "disable"))
    }

    override fun reachedOverNetwork(): Boolean = runCatching {
        overNetwork(serial ?: runProcess(adb("get-serialno")).trim())
    }.getOrDefault(false)

    override fun appLocales(app: String): String? =
        parseAppLocales(runProcess(adb("shell", "cmd", "locale", "get-app-locales", app)), app)

    override fun setAppLocales(app: String, locales: String?) {
        // `cmd locale` reports an unknown package on stdout with exit 0 — read the output.
        val out = runProcess(
            adb("shell", "cmd", "locale", "set-app-locales", app) +
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
        runCatching { runProcess(adb("shell", "am", "force-stop", app)) }
        val err = runToFile(
            adb("exec-out", "run-as", app, "tar", "cf", "-", "--exclude=./cache", "--exclude=./code_cache", "."),
            dest, timeoutMs = 120_000,
        ) ?: return true
        System.err.println("state save failed (run-as needs a debuggable build): ${err.take(200)}")
        return false
    }

    companion object {
        /** `settings put global simul_device 1`: the mark of a physical device placed for simul (see [DeviceOps.dedicated]). */
        const val DEDICATED_KEY = "simul_device"

        /** How long setup waits for the app to report fully drawn — above kloud's splash-ad timeout (5s) plus a load. */
        const val FULLY_DRAWN_TIMEOUT_MS = 15_000L
        private const val FULLY_DRAWN_POLL_MS = 500L
        /** A screencap takes 0.4s, ~3s on a software-rendered emulator that is also recording. */
        private const val SCREENCAP_TIMEOUT_MS = 20_000L
        private const val PROBE_TIMEOUT_MS = 10_000L

        /** Where [online] connects to: the host Android itself asks to decide that a network has internet. */
        const val ONLINE_HOST = "connectivitycheck.gstatic.com"

        /**
         * What the exit code of the connection attempt says: 0 = connected; 126/127 = the device has no `nc`
         * or `timeout` to try with (null — nothing concluded); anything else (name not resolved, no route,
         * timed out) = not reached.
         */
        fun onlineOf(out: String): Boolean? = when (out.trim().lineSequence().lastOrNull()?.trim()?.toIntOrNull()) {
            0 -> true
            null, 126, 127 -> null
            else -> false
        }

        /** A serial adb reaches over the network: `192.168.0.7:5555`, `adb-R58M…._adb-tls-connect._tcp`. An emulator's (`emulator-5554`) is not. */
        fun overNetwork(serial: String): Boolean = "_adb-tls-" in serial || Regex(".+:\\d+").matches(serial)

        /**
         * The `+Ns` the system logs when [app]'s activity calls `reportFullyDrawn()`
         * (`ActivityTaskManager: Fully drawn com.x/.Main: +3s812ms`); null when no such line.
         */
        fun fullyDrawn(logcat: String, app: String): String? = fullyDrawnAll(logcat, app).firstOrNull()

        /** Every `+Ns` [app] has reported as fully drawn in [logcat], in log order — one per launch that got there. */
        fun fullyDrawnAll(logcat: String, app: String): List<String> =
            Regex("Fully drawn ${Regex.escape(app)}/\\S+: (\\+\\S+)").findAll(logcat).map { it.groupValues[1] }.toList()

        /**
         * The report of the launch fired after [before] was read; null while [now] holds nothing newer. A
         * longer list has a new report; so does one whose last differs (the log may have dropped old lines).
         */
        fun drawnSince(before: List<String>, now: List<String>): String? =
            now.lastOrNull()?.takeIf { now.size > before.size || it != before.lastOrNull() }

        /**
         * Was [app]'s process killed by the removal of an old task after its last launch? A scenario
         * that ends on another package's activity inside the app's task (the system share sheet, a
         * custom tab) leaves that task alive past `am force-stop`; it is removed a moment later and
         * the removal kills the package's processes — the one the next scenario's setup started 5ms
         * earlier included (`Killing <pid>:<app>/… : remove-package-task`). The run then observed
         * the launcher: 0 elements, step 1 FAILED (stone owner-profile-stats 2026-11-05, right after
         * owner-profile-share). Only this system kill counts — a crash on launch is the app's.
         */
        fun killedWithOldTask(logcat: String, app: String): Boolean {
            val lines = logcat.lines()
            val launch = lines.indexOfLast { "START u0" in it && "cmp=$app/" in it }
            return launch >= 0 && lines.drop(launch).any { "Killing" in it && ":$app/" in it && "remove-package-task" in it }
        }

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
