package dev.nate.uiagent.device

import dev.nate.uiagent.secs

/**
 * What a stretch of a run (a tool call, a step, a scenario, a batch) cost on the device side, in
 * the units the time is actually lost in. An observation is the system's cost unit (~1s warm, 4s on
 * a busy runner), and a log that only says "tap 35.2s" cannot tell a slow app from a settle loop
 * that never converged: the pedia home's first observation took 33s in 26 recordings and the only
 * trace of it was a gap between two timestamps (stone run 41992964956, 2026-11-05).
 *
 *  - [dumps]/[dumpMs]: observations — `android layout` with its retries and the WebView merge.
 *  - [idleRefused]/[idleRefusedMs]: dumps whose idle wait ran out (NO_IDLE) and had to be asked
 *    again with `--no-idle`, and how long those refused attempts took. Pure loss, part of [dumpMs].
 *  - [unsettled]: settle loops that used every poll and still had no two identical frames.
 *  - [waitMs]: the pauses between polls.
 *  - [gestures]/[gestureMs]: `adb shell input …` / `android input text`.
 *  - [shots]/[shotMs]: `screencap` — per action in a recording, per step otherwise.
 */
data class DeviceCost(
    val dumps: Int = 0,
    val dumpMs: Long = 0,
    val idleRefused: Int = 0,
    val idleRefusedMs: Long = 0,
    val unsettled: Int = 0,
    val waitMs: Long = 0,
    val gestures: Int = 0,
    val gestureMs: Long = 0,
    val shots: Int = 0,
    val shotMs: Long = 0,
) {
    operator fun plus(o: DeviceCost) = DeviceCost(
        dumps + o.dumps, dumpMs + o.dumpMs, idleRefused + o.idleRefused, idleRefusedMs + o.idleRefusedMs,
        unsettled + o.unsettled, waitMs + o.waitMs, gestures + o.gestures, gestureMs + o.gestureMs,
        shots + o.shots, shotMs + o.shotMs,
    )

    /** The cost since [o] was read off the same meter. */
    operator fun minus(o: DeviceCost) = DeviceCost(
        dumps - o.dumps, dumpMs - o.dumpMs, idleRefused - o.idleRefused, idleRefusedMs - o.idleRefusedMs,
        unsettled - o.unsettled, waitMs - o.waitMs, gestures - o.gestures, gestureMs - o.gestureMs,
        shots - o.shots, shotMs - o.shotMs,
    )

    val totalMs: Long get() = dumpMs + waitMs + gestureMs + shotMs

    /**
     * `observe 32.1s/8 dumps (idle wait refused 8× 24.3s, 1 settle ran out)`; null when nothing was
     * observed. [share] puts each time's part of a whole in front of it (`observe 39% 32.1s/…`).
     */
    fun observed(time: (Long) -> String = ::secs, share: (Long) -> String = { "" }): String? {
        if (dumps == 0) return null
        val lost = listOfNotNull(
            if (idleRefused > 0) "idle wait refused $idleRefused× ${time(idleRefusedMs)}" else null,
            if (unsettled > 0) "$unsettled settle${if (unsettled == 1) "" else "s"} ran out" else null,
        )
        return "observe ${share(dumpMs)}${time(dumpMs)}/$dumps dump${if (dumps == 1) "" else "s"}" +
            if (lost.isEmpty()) "" else lost.joinToString(", ", " (", ")")
    }

    /** The two that dominate a tool call or a step: observations and screenshots. */
    fun brief(): List<String> = listOfNotNull(observed(), if (shots > 0) "screenshots ${secs(shotMs)}/$shots" else null)

    /** Every part that took time, for the lines that account for a whole scenario or batch. */
    fun parts(time: (Long) -> String = ::secs, share: (Long) -> String = { "" }): List<String> = listOfNotNull(
        observed(time, share),
        if (waitMs > 0) "waits ${share(waitMs)}${time(waitMs)}" else null,
        if (gestures > 0) "gestures ${share(gestureMs)}${time(gestureMs)}/$gestures" else null,
        if (shots > 0) "screenshots ${share(shotMs)}${time(shotMs)}/$shots" else null,
    )
}

/**
 * The running [DeviceCost] of one device stack. [DeviceController] feeds it every observation,
 * pause and gesture it makes, [AdbDevice] the idle waits the CLI refused, the runner its
 * screenshots; a reader takes [cost] before and after the stretch it wants to account for.
 */
class DeviceMeter {
    var cost = DeviceCost()
        private set

    fun dump(ms: Long) { cost = cost.copy(dumps = cost.dumps + 1, dumpMs = cost.dumpMs + ms) }
    fun idleRefused(ms: Long) { cost = cost.copy(idleRefused = cost.idleRefused + 1, idleRefusedMs = cost.idleRefusedMs + ms) }
    fun unsettled() { cost = cost.copy(unsettled = cost.unsettled + 1) }
    fun waited(ms: Long) { cost = cost.copy(waitMs = cost.waitMs + ms) }
    fun gesture(ms: Long) { cost = cost.copy(gestures = cost.gestures + 1, gestureMs = cost.gestureMs + ms) }
    fun shot(ms: Long) { cost = cost.copy(shots = cost.shots + 1, shotMs = cost.shotMs + ms) }

    /** Run [block] and hand its wall time to [record] — also when it throws. */
    inline fun <T> timed(record: (Long) -> Unit, block: () -> T): T {
        val t0 = System.currentTimeMillis()
        try {
            return block()
        } finally {
            record(System.currentTimeMillis() - t0)
        }
    }
}
