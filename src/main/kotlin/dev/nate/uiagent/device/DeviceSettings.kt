package dev.nate.uiagent.device

/**
 * One Android `settings` entry the runner pins over adb before scenarios run:
 * `settings put <namespace> <key> <value>` (or `settings delete` when [value] is null).
 *
 * These are emulator state that is neither app data (pm clear and `.simul/states` snapshots
 * never touch them) nor part of the app under test: the Google credential provider that pops
 * a "Use your saved password" sheet over a guest cold start, the autofill service, the
 * immersive-mode "Got it" hint. Two layers declare them — config.yaml `device.settings` is
 * the baseline every scenario assumes, a scenario's `setup.device.settings` is the exception
 * that needs the opposite (the saved-password sheet IS the feature under test) and lasts for
 * that scenario only.
 *
 * The runner does this with adb, never by having the model drive the Settings app: adb gives
 * the same end state deterministically, replay must stay LLM-free, and a Settings-app journey
 * that breaks would pollute the verdicts of the app actually under test.
 */
data class DeviceSetting(val namespace: String, val key: String, val value: String?) {
    override fun toString() = "$namespace.$key = ${show(value)}"

    companion object {
        val NAMESPACES = setOf("system", "secure", "global")
        val KEY = Regex("[A-Za-z0-9_]+")

        /** Human form of a value: `unset` for null, `""` for empty, the text otherwise. */
        fun show(value: String?): String = when {
            value == null -> "unset"
            value.isEmpty() -> "\"\""
            else -> value
        }
    }
}

/**
 * Everything a `device:` block (config.yaml top level or a scenario's `setup`) asks the runner
 * to pin: the app's language and the emulator `settings`.
 *
 * ```yaml
 * device:
 *   locale: ko-KR                        # per-app language: `cmd locale set-app-locales <app>`
 *   settings:
 *     secure.credential_service: ""      # put an empty string
 *     secure.autofill_service: null      # settings delete (key unset)
 * ```
 *
 * [locale] is a BCP-47 tag (or a comma-separated preference list, `ko-KR,en-US`). Unlike the
 * `settings` it is app state, not device state: `pm clear` wipes it (measured on the API 37
 * emulator — set, clear, read back `[]`), so the runner applies it inside setup, after the clear
 * and before the launch (see [DeviceOps.setup]), and it applies on physical devices too — it
 * touches only the app under test, like the clear itself. The system language is never changed
 * (that needs root and a framework restart).
 */
data class DevicePins(val locale: String? = null, val settings: List<DeviceSetting> = emptyList()) {
    companion object {
        val NONE = DevicePins()
        /** A language tag, or a comma-separated list of them: `ko`, `ko-KR`, `zh-Hant-TW`, `ko-KR,en-US`. */
        private val LOCALE = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*(,[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*)*")

        /**
         * Parse a `device:` block. Absent block → [NONE]. Anything malformed throws — a typo that
         * silently pinned nothing would defeat the point, exactly as for `display:`.
         */
        fun parse(deviceBlock: Any?, where: String): DevicePins {
            if (deviceBlock == null) return NONE
            val device = deviceBlock as? Map<*, *>
                ?: throw IllegalArgumentException("$where: `device:` must be a block with `locale:` and/or `settings:`")
            val unknown = device.keys.map { it.toString() } - setOf("locale", "settings")
            require(unknown.isEmpty()) { "$where: unknown key(s) under device: $unknown (only `locale`, `settings`)" }
            val locale = device["locale"]?.let { v ->
                require(v is String && LOCALE.matches(v)) {
                    "$where: device.locale must be a language tag like `ko-KR` (or a list `ko-KR,en-US`), got '$v'"
                }
                v
            }
            return DevicePins(locale, parseSettings(device["settings"], where))
        }

        private fun parseSettings(settings: Any?, where: String): List<DeviceSetting> {
            if (settings == null) return emptyList()
            require(settings is Map<*, *>) { "$where: device.settings must be a map of `<namespace>.<key>: value`" }
            val namespaces = DeviceSetting.NAMESPACES.joinToString("|")
            return settings.entries.map { (k, v) ->
                val name = k.toString()
                val dot = name.indexOf('.')
                require(dot > 0 && dot < name.length - 1) {
                    "$where: device setting '$name' must be `<namespace>.<key>` (namespace $namespaces)"
                }
                val ns = name.substring(0, dot)
                val key = name.substring(dot + 1)
                require(ns in DeviceSetting.NAMESPACES) { "$where: device setting '$name': namespace must be $namespaces" }
                require(DeviceSetting.KEY.matches(key)) { "$where: device setting '$name': key must be [A-Za-z0-9_]+" }
                require(v is String) { "$where: device setting '$name' must be a scalar" }
                DeviceSetting(ns, key, if (v == "null" || v == "~") null else v)
            }
        }
    }
}

/** A setting the runner changed and what it was before ([was] null = the key was unset). */
data class SettingChange(val setting: DeviceSetting, val was: String?)

/**
 * Pin / restore [DeviceSetting]s. Same contract as the display profile: emulators only, read
 * first and touch only what differs, put back exactly what was there (a developer's own
 * value included) when the scope ends.
 */
object DeviceSettings {
    /**
     * Bring the device to [wanted]. Each change is appended to [sink] as it happens — so a
     * failure half-way still leaves the caller able to undo what did change — and reported
     * through [log] as `<ns>.<key> = <value> (was <before>)`.
     */
    fun apply(
        ops: DeviceOps,
        wanted: List<DeviceSetting>,
        log: (String) -> Unit,
        sink: MutableList<SettingChange>,
    ) {
        if (wanted.isEmpty()) return
        if (!ops.isEmulator()) {
            log("warning: ${wanted.size} device setting(s) ignored — target is not an emulator")
            return
        }
        for (s in wanted) {
            val current = ops.settingGet(s.namespace, s.key)
            if (current == s.value) continue
            if (s.value == null) ops.settingDelete(s.namespace, s.key) else ops.settingPut(s.namespace, s.key, s.value)
            sink += SettingChange(s, current)
            log("$s (was ${DeviceSetting.show(current)})")
        }
    }

    /** Undo [changes], newest first; each restore failure is logged, never thrown (best effort on exit). */
    fun restore(ops: DeviceOps, changes: List<SettingChange>, log: (String) -> Unit) {
        for (c in changes.asReversed()) {
            val s = c.setting
            try {
                if (c.was == null) ops.settingDelete(s.namespace, s.key) else ops.settingPut(s.namespace, s.key, c.was)
                log("${s.namespace}.${s.key} restored to ${DeviceSetting.show(c.was)}")
            } catch (e: Exception) {
                log("warning: could not restore ${s.namespace}.${s.key} to ${DeviceSetting.show(c.was)}: ${e.message?.lineSequence()?.firstOrNull()}")
            }
        }
    }
}
