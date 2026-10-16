package dev.nate.uiagent.device

import dev.nate.uiagent.cli.FakeOps
import dev.nate.uiagent.cli.MiniYaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeviceSettingsTest {

    private fun yaml(text: String) = MiniYaml.parse(text.trimIndent())

    // ------------------------------------------------------------------ parsing

    @Test
    fun `device settings block parses namespace, key, empty string and null`() {
        val y = yaml(
            """
            device:
              settings:
                secure.credential_service: ""
                secure.autofill_service: null
                global.window_animation_scale: 0
                secure.immersive_mode_confirmations: confirmed   # trailing comment
            """
        )
        assertEquals(
            listOf(
                DeviceSetting("secure", "credential_service", ""),
                DeviceSetting("secure", "autofill_service", null),
                DeviceSetting("global", "window_animation_scale", "0"),
                DeviceSetting("secure", "immersive_mode_confirmations", "confirmed"),
            ),
            DeviceSetting.parse(y["device"], "t"),
        )
    }

    @Test
    fun `absent block is empty, malformed blocks fail loudly`() {
        assertEquals(emptyList(), DeviceSetting.parse(null, "t"))
        assertFailsWith<IllegalArgumentException> { DeviceSetting.parse(yaml("device:\n  settings: []")["device"], "t") }
        assertFailsWith<IllegalArgumentException> { DeviceSetting.parse(yaml("device:\n  settings:\n    credential_service: x")["device"], "t") }
        assertFailsWith<IllegalArgumentException> { DeviceSetting.parse(yaml("device:\n  settings:\n    private.credential_service: x")["device"], "t") }
        assertFailsWith<IllegalArgumentException> { DeviceSetting.parse(yaml("device:\n  setting:\n    secure.x: y")["device"], "t") }
        assertFailsWith<IllegalArgumentException> { DeviceSetting.parse(yaml("device:\n  settings:\n    secure.x: [a, b]")["device"], "t") }
    }

    // ------------------------------------------------------------------ apply / restore

    @Test
    fun `apply touches only what differs and restore puts the prior values back, unset included`() {
        val ops = FakeOps()
        ops.settings["secure.credential_service"] = "com.google.android.gms/.PasswordAndPasskeyService"
        ops.settings["secure.immersive_mode_confirmations"] = "confirmed"
        // autofill_service unset on the device
        val logged = mutableListOf<String>()
        val wanted = listOf(
            DeviceSetting("secure", "credential_service", ""),
            DeviceSetting("secure", "immersive_mode_confirmations", "confirmed"),
            DeviceSetting("secure", "autofill_service", "null-service"),
        )

        val changes = mutableListOf<SettingChange>()
        DeviceSettings.apply(ops, wanted, logged::add, changes)

        assertEquals(
            listOf("put secure.credential_service=", "put secure.autofill_service=null-service"),
            ops.settingLog, "the already-matching key is never written",
        )
        assertEquals(listOf("com.google.android.gms/.PasswordAndPasskeyService", null), changes.map { it.was })
        assertEquals(listOf("secure.credential_service = \"\" (was com.google.android.gms/.PasswordAndPasskeyService)",
            "secure.autofill_service = null-service (was unset)"), logged)

        ops.settingLog.clear()
        DeviceSettings.restore(ops, changes, logged::add)
        assertEquals(listOf("delete secure.autofill_service", "put secure.credential_service=com.google.android.gms/.PasswordAndPasskeyService"),
            ops.settingLog, "restored newest first; a key that was unset is deleted, not put back as a string")
        assertEquals(mapOf("secure.credential_service" to "com.google.android.gms/.PasswordAndPasskeyService",
            "secure.immersive_mode_confirmations" to "confirmed"), ops.settings)
    }

    @Test
    fun `null value means delete, and a key already unset is a no-op`() {
        val ops = FakeOps()
        ops.settings["secure.autofill_service"] = "com.x/.Autofill"
        val changes = mutableListOf<SettingChange>()
        DeviceSettings.apply(ops, listOf(DeviceSetting("secure", "autofill_service", null), DeviceSetting("secure", "other", null)), log = {}, sink = changes)
        assertEquals(listOf("delete secure.autofill_service"), ops.settingLog)
        assertEquals(1, changes.size)
        DeviceSettings.restore(ops, changes, log = {})
        assertEquals("com.x/.Autofill", ops.settings["secure.autofill_service"])
    }

    @Test
    fun `physical devices are never touched`() {
        val ops = FakeOps().also { it.emulator = false }
        val logged = mutableListOf<String>()
        val changes = mutableListOf<SettingChange>()
        DeviceSettings.apply(ops, listOf(DeviceSetting("secure", "credential_service", "")), logged::add, changes)
        assertTrue(changes.isEmpty() && ops.settingLog.isEmpty())
        assertTrue(logged.single().startsWith("warning:") && "not an emulator" in logged.single(), logged.toString())
    }

    @Test
    fun `a failure half-way leaves the changes made so far in the sink for restore`() {
        val ops = object : DeviceOps by FakeOps() {
            val inner = FakeOps()
            override fun isEmulator() = true
            override fun settingGet(namespace: String, key: String) = inner.settingGet(namespace, key)
            override fun settingPut(namespace: String, key: String, value: String) {
                if (key == "boom") throw RuntimeException("adb: device offline")
                inner.settingPut(namespace, key, value)
            }
        }
        val sink = mutableListOf<SettingChange>()
        assertFailsWith<RuntimeException> {
            DeviceSettings.apply(ops, listOf(DeviceSetting("secure", "first", "1"), DeviceSetting("secure", "boom", "2")), {}, sink)
        }
        assertEquals(listOf("secure.first"), sink.map { "${it.setting.namespace}.${it.setting.key}" })
    }
}
