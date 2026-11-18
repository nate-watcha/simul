package dev.nate.uiagent.device

/**
 * The device's network, as a scenario needs it: there (every scenario, unless it says otherwise) or taken away
 * (`device.network: off` — the screen an app shows without one is a behaviour too). Like the `settings`, it is
 * device state that is neither app data nor the app under test, and the runner sets it over adb: airplane mode,
 * what a person would use.
 *
 * Because the runner knows what each scenario expects, it also keeps it: before a scenario that needs the
 * network it asks the device whether it reaches the internet ([DeviceOps.online]) and brings it back when it
 * does not ([ensureOn]). An emulator booted without one once (stone run 44235375505, one of four): every
 * scenario it took failed looking like an app regression, and the two ways in among them took twenty scenarios
 * of the other devices with them.
 */
object DeviceNetwork {
    /** How long the radios get to come back after airplane mode goes off (an emulator's Wi-Fi: a few seconds). */
    const val SETTLE_TRIES = 15
    /** How long they get to go away after it goes on. */
    const val GONE_TRIES = 5
    const val POLL_MS = 2_000L

    /**
     * The network must be there. Returns null when it is — or when the device has no way to tell — else why
     * the device cannot run a scenario. Airplane mode left on (a run that died inside an offline scenario) is
     * turned off; a device that still does not reach the internet gets airplane mode on and off once, which
     * re-attaches Wi-Fi and the mobile network. Only a device that is the runner's to set up
     * ([DeviceOps.dedicated]) is touched: on a person's phone the answer is just the reason.
     */
    fun ensureOn(ops: DeviceOps, log: (String) -> Unit, pause: (Long) -> Unit = Thread::sleep): String? {
        var tries = 1
        if (ops.airplaneMode() == true) {
            if (!ops.dedicated()) return "no network: airplane mode is on"
            ops.setAirplaneMode(false)
            log("network: airplane mode was on — turned off")
            tries = SETTLE_TRIES
        }
        if (reaches(ops, tries, pause)) return null
        if (!ops.dedicated()) return "no network: the device does not reach the internet"
        log("network: the device does not reach the internet — airplane mode on and off")
        ops.setAirplaneMode(true)
        pause(POLL_MS)
        ops.setAirplaneMode(false)
        if (reaches(ops, SETTLE_TRIES, pause)) {
            log("network: back")
            return null
        }
        return "no network: the device does not reach the internet, also after airplane mode on and off"
    }

    /**
     * Take the network away for one scenario; throws when that cannot be done, which fails that scenario's
     * setup. Not on a person's phone, and not on a device adb itself reaches over the network — it would not
     * come back to turn it on again.
     */
    fun turnOff(ops: DeviceOps, log: (String) -> Unit, pause: (Long) -> Unit = Thread::sleep) {
        check(ops.dedicated()) { "network: off needs a device the runner may set up — ${DeviceSettings.NOT_DEDICATED}" }
        check(!ops.reachedOverNetwork()) { "network: off on a device adb reaches over the network would lose the device — connect it by USB" }
        if (ops.airplaneMode() != true) ops.setAirplaneMode(true)
        // null = the device cannot tell: airplane mode is on, which is all that can be said
        repeat(GONE_TRIES) { i ->
            if (ops.online() != true) return log("network: off (airplane mode) — back on when the scenario ends")
            if (i < GONE_TRIES - 1) pause(POLL_MS)
        }
        error("network: off — airplane mode is on and the device still reaches the internet")
    }

    /** Give the network back after [turnOff]; never throws (best effort on the way out — the next scenario's [ensureOn] looks again). */
    fun backOn(ops: DeviceOps, log: (String) -> Unit, pause: (Long) -> Unit = Thread::sleep) {
        try {
            ops.setAirplaneMode(false)
            if (reaches(ops, SETTLE_TRIES, pause)) log("network: back on")
            else log("warning: network: airplane mode is off again but the device does not reach the internet yet")
        } catch (e: Exception) {
            log("warning: could not turn the network back on: ${e.message?.lineSequence()?.firstOrNull()}")
        }
    }

    /** Does the device reach the internet within [tries] looks, [POLL_MS] apart? A device that cannot tell counts as reaching it. */
    private fun reaches(ops: DeviceOps, tries: Int, pause: (Long) -> Unit): Boolean {
        repeat(tries) { i ->
            if (ops.online() != false) return true
            if (i < tries - 1) pause(POLL_MS)
        }
        return false
    }
}
