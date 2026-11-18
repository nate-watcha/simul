package dev.nate.uiagent.device

import dev.nate.uiagent.cli.FakeOps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeviceNetworkTest {

    private val log = mutableListOf<String>()
    private val pauses = mutableListOf<Long>()

    // ------------------------------------------------------------------ the network must be there

    @Test
    fun `a device that reaches the internet is asked once and left alone`() {
        val ops = FakeOps()
        assertEquals(null, DeviceNetwork.ensureOn(ops, log::add, pauses::add))
        assertEquals(1, ops.onlineAsked)
        assertTrue(ops.airplaneLog.isEmpty() && log.isEmpty() && pauses.isEmpty())
    }

    @Test
    fun `airplane mode left on is turned off`() {
        val ops = FakeOps().apply { airplane = true }
        assertEquals(null, DeviceNetwork.ensureOn(ops, log::add, pauses::add))
        assertEquals(listOf("airplane off"), ops.airplaneLog)
        assertEquals(listOf("network: airplane mode was on — turned off"), log)
    }

    @Test
    fun `a device that does not reach the internet gets airplane mode on and off, and is used when that brings it back`() {
        val ops = FakeOps().apply { offline = true; healsOnToggle = true }
        assertEquals(null, DeviceNetwork.ensureOn(ops, log::add, pauses::add))
        assertEquals(listOf("airplane on", "airplane off"), ops.airplaneLog)
        assertEquals("network: back", log.last())
    }

    @Test
    fun `when that does not bring it back the answer is why, after waiting for the radios`() {
        val ops = FakeOps().apply { offline = true }
        val why = DeviceNetwork.ensureOn(ops, log::add, pauses::add)
        assertEquals("no network: the device does not reach the internet, also after airplane mode on and off", why)
        assertEquals(listOf("airplane on", "airplane off"), ops.airplaneLog)
        assertEquals(1 + DeviceNetwork.SETTLE_TRIES, ops.onlineAsked)
        // one pause between on and off, then one between each look
        assertEquals(DeviceNetwork.SETTLE_TRIES, pauses.size)
    }

    @Test
    fun `a person's phone is only asked - airplane mode is theirs`() {
        val off = FakeOps().apply { emulator = false; offline = true }
        assertEquals("no network: the device does not reach the internet", DeviceNetwork.ensureOn(off, log::add, pauses::add))
        val flying = FakeOps().apply { emulator = false; airplane = true }
        assertEquals("no network: airplane mode is on", DeviceNetwork.ensureOn(flying, log::add, pauses::add))
        assertTrue(off.airplaneLog.isEmpty() && flying.airplaneLog.isEmpty())
        // marked for simul, it is the runner's like an emulator
        val placed = FakeOps().apply { emulator = false; marked = true; airplane = true }
        assertEquals(null, DeviceNetwork.ensureOn(placed, log::add, pauses::add))
        assertEquals(listOf("airplane off"), placed.airplaneLog)
    }

    @Test
    fun `a device that cannot tell is taken to have a network`() {
        val ops = FakeOps().apply { canTell = false; offline = true }
        assertEquals(null, DeviceNetwork.ensureOn(ops, log::add, pauses::add))
        assertTrue(ops.airplaneLog.isEmpty())
    }

    // ------------------------------------------------------------------ taking it away

    @Test
    fun `off is airplane mode, and back on waits until the device reaches the internet again`() {
        val ops = FakeOps()
        DeviceNetwork.turnOff(ops, log::add, pauses::add)
        assertTrue(ops.airplane)
        assertEquals("network: off (airplane mode) — back on when the scenario ends", log.single())
        DeviceNetwork.backOn(ops, log::add, pauses::add)
        assertEquals(listOf("airplane on", "airplane off"), ops.airplaneLog)
        assertEquals("network: back on", log.last())
    }

    @Test
    fun `off is refused where the runner may not or could not come back`() {
        val phone = FakeOps().apply { emulator = false }
        assertTrue("simul_device" in assertFailsWith<IllegalStateException> { DeviceNetwork.turnOff(phone, log::add, pauses::add) }.message!!)
        val wireless = FakeOps().apply { wireless = true }
        assertTrue("USB" in assertFailsWith<IllegalStateException> { DeviceNetwork.turnOff(wireless, log::add, pauses::add) }.message!!)
        assertTrue(phone.airplaneLog.isEmpty() && wireless.airplaneLog.isEmpty())
    }

    @Test
    fun `giving it back never throws`() {
        val ops = object : DeviceOps by FakeOps() {
            override fun setAirplaneMode(on: Boolean) = throw RuntimeException("adb: device offline\nmore")
        }
        DeviceNetwork.backOn(ops, log::add, pauses::add)
        assertEquals("warning: could not turn the network back on: adb: device offline", log.single())
    }

    // ------------------------------------------------------------------ reading the device

    @Test
    fun `the exit code of the connection attempt says reached, not reached, or cannot tell`() {
        assertEquals(true, AdbOps.onlineOf("0\n"))
        assertEquals(false, AdbOps.onlineOf("1\n"))     // name not resolved / no route
        assertEquals(false, AdbOps.onlineOf("124\n"))   // timed out
        assertEquals(null, AdbOps.onlineOf("127\n"))    // no nc or timeout on the device
        assertEquals(null, AdbOps.onlineOf(""))
        assertEquals(null, AdbOps.onlineOf("/system/bin/sh: timeout: inaccessible or not found\n127\r\n"))
    }

    @Test
    fun `a serial adb reaches over the network is told from a cable and an emulator`() {
        assertTrue(AdbOps.overNetwork("192.168.0.7:5555"))
        assertTrue(AdbOps.overNetwork("adb-R58M12ABCDE-x1Y2z3._adb-tls-connect._tcp"))
        assertTrue(!AdbOps.overNetwork("emulator-5554"))
        assertTrue(!AdbOps.overNetwork("R58M12ABCDE"))
    }
}
