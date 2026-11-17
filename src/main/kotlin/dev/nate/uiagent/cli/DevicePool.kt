package dev.nate.uiagent.cli

import dev.nate.uiagent.device.AdbOps
import dev.nate.uiagent.device.DeviceOps
import dev.nate.uiagent.device.DeviceSettings
import dev.nate.uiagent.runProcess
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Which devices a run uses. Nothing is asked: the number of devices attached is the number it runs on.
 *
 *  - `ANDROID_SERIAL` set, or at most one device online: that one, picked by adb as before ([ADB_PICKS]).
 *  - Several online: every one that is the runner's to set up ([DeviceOps.dedicated]) — the emulators, and the
 *    physical devices marked for simul — and has the app under test installed. A person's phone that happens to
 *    be plugged in beside them is not run on; neither is an emulator that was brought up for something else (a
 *    CI host serves more than one app: the Mac Pro boots kloud's emulator next to stone's). Installing the build
 *    is what makes a device one of this run's.
 */
internal object Devices {
    /** One device, named by nobody: adb takes the only one online, or ANDROID_SERIAL. */
    val ADB_PICKS: List<String?> = listOf(null)

    /** The serials `adb devices` lists as online, in its order (`offline` and `unauthorized` are not). */
    fun parseOnline(out: String): List<String> = out.lineSequence().drop(1)
        .map { it.trim().split(Regex("\\s+")) }.filter { it.size >= 2 && it[1] == "device" }.map { it[0] }.toList()

    /**
     * The devices of this run of [app] (config `app:`; null = no app to look for), or why there are none to pick
     * ([IllegalStateException]). [dedicated] answers [DeviceOps.dedicated] for a serial, [installed] whether the
     * app is on it; [note] gets a line for every device left out for want of the app.
     */
    fun forRun(
        app: String?,
        env: (String) -> String? = System::getenv,
        online: () -> List<String> = { parseOnline(runProcess(listOf("adb", "devices"))) },
        dedicated: (String) -> Boolean = { AdbOps(serial = it).dedicated() },
        installed: (String, String) -> Boolean = { serial, pkg -> AdbOps(serial = serial).installed(pkg) },
        note: (String) -> Unit = ::println,
    ): List<String?> {
        if (!env("ANDROID_SERIAL").isNullOrBlank()) return ADB_PICKS
        // adb itself says what is wrong when it cannot list or finds nothing — on the first command, as before
        val serials = runCatching(online).getOrDefault(emptyList())
        if (serials.size <= 1) return ADB_PICKS
        val ours = serials.filter(dedicated).ifEmpty {
            error("${serials.size} devices online (${serials.joinToString(", ")}) and none is an emulator or marked for simul — " +
                "name one with ANDROID_SERIAL. ${DeviceSettings.NOT_DEDICATED.replaceFirstChar(Char::uppercase)}.")
        }
        if (app == null) return ours
        val (with, without) = ours.partition { installed(it, app) }
        if (with.isEmpty()) error("${ours.size} devices online (${ours.joinToString(", ")}) and none has $app installed — install the build under test on the devices to run on, or name one with ANDROID_SERIAL.")
        without.forEach { note("devices: $it left out — $app is not installed on it") }
        return with
    }
}

/**
 * [runs] over several devices at once: one [BatchSession] per device, each taking the next piece of work when it
 * is free — a faster device takes more, and what a device that stopped answering had left goes to the others.
 * Returns one [BatchResult] per run, in the order of [runs].
 *
 * What is handed out, and in which order:
 *  1. every way in (`_entry.md`) among [runs], one at a time — all of them before anything else, as a run on one
 *     device orders them, so a scenario never starts before its way in has been judged;
 *  2. the rest by screen (the directory of the file), largest first, each screen's scenarios on one device in
 *     the order given. Scenarios of one screen are the ones most likely to write the same account's data — an
 *     edit of the name and an edit of the link of one profile — and stay one after the other, as they always ran.
 *
 * The devices share the record of ways in that failed, so the scenarios that start from one are skipped on
 * every device. Each scenario's lines are printed together when it ends: the log reads as a run on one device
 * does, with the device named in the scenario's first line.
 */
internal fun runOverDevices(
    project: SimulProject,
    runs: List<Pair<File, Scenario>>,
    devices: List<DeviceOps>,
    mode: RunMode,
    llm: LlmConfig,
    video: Boolean,
    runsDir: File?,
    out: (String) -> Unit = ::println,
): List<BatchResult> {
    val (entries, rest) = runs.indices.partition { runs[it].first.name == SimulProject.ENTRY_FILE }
    val results = arrayOfNulls<BatchResult>(runs.size)
    val broken = ConcurrentHashMap<String, String>()
    val print = Any()

    class Device(val ops: DeviceOps) : PoolWorker {
        override val name = ops.serial ?: "device"
        /** The lines of the scenario this device is running; null between scenarios. */
        @Volatile var lines: MutableList<String>? = null
        // between scenarios a line is the device's own (pinning, not answering): printed at once, under its name
        val log: (String) -> Unit = { line -> lines?.add(line) ?: synchronized(print) { out("[$name] $line") } }
        val session = BatchSession(project, ops, log, runsDir, brokenEntries = broken)

        override fun start() = session.pin()
        override val alive get() = session.deviceDown == null

        override fun run(index: Int) {
            val (md, scenario) = runs[index]
            val block = mutableListOf<String>().also { lines = it }
            results[index] = try {
                session.run(md, scenario, mode, llm, video, scenarioLog = log)
            } catch (t: Throwable) {
                // the session turns a scenario's crash into a row itself; what still gets here is the runner's own
                log("CRASH ${scenario.name}: $t")
                BatchResult(outcomeWithout(project, md, scenario, "CRASH", t.toString()), null)
            } finally {
                lines = null
                synchronized(print) { block.forEach(out) }
            }
        }
    }

    val pool = devices.map(::Device)
    try {
        val untaken = handOut(
            first = entries.map { listOf(it) },
            then = rest.groupBy { project.screenOf(runs[it].first) }.values.sortedByDescending { it.size },
            workers = pool,
            onStopped = { w, e -> synchronized(print) { out("[${w.name}] stopped: $e") } },
        )
        // no device left to take them: each becomes the row a single device gives a scenario it could not run
        val stopped = pool.firstOrNull { !it.alive }?.session
        for (index in untaken) {
            val (md, scenario) = runs[index]
            results[index] = stopped?.run(md, scenario, mode, llm, video)
                ?: BatchResult(outcomeWithout(project, md, scenario, "CRASH", "not run — no device took it"), null)
        }
    } finally {
        pool.forEach { runCatching { it.session.close() } }
    }
    return results.mapIndexed { i, r ->
        r ?: BatchResult(outcomeWithout(project, runs[i].first, runs[i].second, "CRASH", "not run — the runner stopped while it ran"), null)
    }
}

/** One device of a run over several, as [handOut] sees it. */
internal interface PoolWorker {
    val name: String
    /** Before its first piece of work. */
    fun start() {}
    /** False once it can take no more — what it had left then goes to the others. */
    val alive: Boolean
    fun run(index: Int)
}

/**
 * Hand the pieces of work out to [workers], each on its own thread, until none is left or no worker is alive;
 * returns what nobody ran. A piece is a list of indices one worker runs in order. Every piece of [first] is
 * over before any of [then] is handed out — a worker that asks in between waits. A worker that stops being
 * [PoolWorker.alive] in the middle of a piece gives the rest of it back; one that throws ([onStopped]) does too,
 * except for the index it threw on.
 */
internal fun <W : PoolWorker> handOut(
    first: List<List<Int>>,
    then: List<List<Int>>,
    workers: List<W>,
    onStopped: (W, Throwable) -> Unit = { _, _ -> },
): List<Int> {
    val queue = WorkQueue(first, then)
    workers.map { w ->
        Thread({
            try {
                w.start()
                while (w.alive) {
                    val taken = queue.take() ?: break
                    var left = taken.piece
                    try {
                        while (left.isNotEmpty() && w.alive) {
                            val index = left.first()
                            left = left.drop(1)
                            w.run(index)
                        }
                    } finally {
                        queue.done(taken, left)
                    }
                }
            } catch (t: Throwable) {
                onStopped(w, t)
            }
        }, "simul-${w.name}").apply { start() }
    }.forEach(Thread::join)
    return queue.remaining()
}

/**
 * The work of a run over several devices: [first] is handed out before [then], and [then] only once nothing of
 * [first] is still running — a device that asks in between waits for that.
 */
private class WorkQueue(first: List<List<Int>>, then: List<List<Int>>) {
    class Taken(val piece: List<Int>, val isFirst: Boolean)

    private val first = ArrayDeque(first)
    private val then = ArrayDeque(then)
    private var firstRunning = 0
    private val lock = Object()

    /** The next piece of work; null when there is none left for anyone. */
    fun take(): Taken? = synchronized(lock) {
        var taken: Taken? = null
        while (true) {
            val piece = first.removeFirstOrNull()
            if (piece != null) { firstRunning++; taken = Taken(piece, isFirst = true); break }
            if (firstRunning == 0) { taken = then.removeFirstOrNull()?.let { Taken(it, isFirst = false) }; break }
            lock.wait()
        }
        taken
    }

    /** [taken] is over for the device that took it; [left] is what it did not run. */
    fun done(taken: Taken, left: List<Int>) = synchronized(lock) {
        if (left.isNotEmpty()) (if (taken.isFirst) first else then).addFirst(left)
        if (taken.isFirst) firstRunning--
        lock.notifyAll()
    }

    /** What nobody took, once every device is done. */
    fun remaining(): List<Int> = synchronized(lock) { (first + then).flatten().also { first.clear(); then.clear() } }
}
