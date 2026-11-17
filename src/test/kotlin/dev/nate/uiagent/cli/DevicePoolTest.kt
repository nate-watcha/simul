package dev.nate.uiagent.cli

import dev.nate.uiagent.tempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DevicePoolTest {

    // ------------------------------------------------------------------ which devices

    @Test
    fun `adb devices - only the ones online count`() {
        val out = """
            List of devices attached
            emulator-5554	device
            emulator-5556	offline
            R58M12ABCDE	unauthorized
            emulator-5558          device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 transport_id:3

        """.trimIndent()
        assertEquals(listOf("emulator-5554", "emulator-5558"), Devices.parseOnline(out))
    }

    @Test
    fun `one device, or ANDROID_SERIAL, is adb's pick as before`() {
        assertEquals(Devices.ADB_PICKS, Devices.forRun(env = { null }, online = { listOf("emulator-5554") }, dedicated = { error("not asked") }))
        assertEquals(Devices.ADB_PICKS, Devices.forRun(env = { null }, online = { emptyList() }, dedicated = { error("not asked") }))
        assertEquals(Devices.ADB_PICKS, Devices.forRun(env = { "emulator-5556" }, online = { error("not asked") }, dedicated = { error("not asked") }))
        assertEquals(Devices.ADB_PICKS, Devices.forRun(env = { null }, online = { error("adb: not found") }, dedicated = { error("not asked") }))
    }

    @Test
    fun `several devices - the emulators and the phones marked for simul, never a person's phone`() {
        val online = listOf("emulator-5554", "PERSONAL", "emulator-5556", "PLACED")
        assertEquals(
            listOf("emulator-5554", "emulator-5556", "PLACED"),
            Devices.forRun(env = { null }, online = { online }, dedicated = { it != "PERSONAL" }),
        )
        val e = assertFailsWith<IllegalStateException> {
            Devices.forRun(env = { null }, online = { listOf("A", "B") }, dedicated = { false })
        }
        assertTrue("ANDROID_SERIAL" in e.message!! && "simul_device" in e.message!!, e.message)
    }

    // ------------------------------------------------------------------ handing the work out

    private class Worker(override val name: String, val log: MutableList<String>, val onRun: (Worker, Int) -> Unit = { _, _ -> }) : PoolWorker {
        @Volatile override var alive = true
        val ran: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        override fun run(index: Int) {
            log += "$name start $index"
            onRun(this, index)
            ran += index
            log += "$name end $index"
        }
    }

    @Test
    fun `every way in is over before anything else is handed out`() {
        val log = Collections.synchronizedList(mutableListOf<String>())
        // the first way in is slow: the other device finishes its own and must wait, not start a screen
        val workers = listOf("a", "b").map { Worker(it, log) { _, i -> if (i == 0) Thread.sleep(150) } }
        val left = handOut(first = listOf(listOf(0), listOf(1)), then = listOf(listOf(2, 3), listOf(4)), workers = workers)
        assertTrue(left.isEmpty())
        val firstScreen = log.indexOfFirst { it.endsWith("start 2") || it.endsWith("start 4") }
        assertTrue(log.indexOfFirst { it.endsWith("end 0") } < firstScreen && log.indexOfFirst { it.endsWith("end 1") } < firstScreen, "$log")
        assertEquals(setOf(0, 1, 2, 3, 4), workers.flatMap { it.ran }.toSet())
    }

    @Test
    fun `a screen's scenarios run on one device, in order, while another device runs another screen`() {
        val log = Collections.synchronizedList(mutableListOf<String>())
        val both = CountDownLatch(2)
        // each device holds its first scenario until the other has one too: the screens really ran side by side
        val workers = listOf("a", "b").map { Worker(it, log) { _, i -> if (i == 0 || i == 3) { both.countDown(); both.await(2, TimeUnit.SECONDS) } } }
        handOut(first = emptyList(), then = listOf(listOf(0, 1, 2), listOf(3, 4)), workers = workers)
        assertEquals(setOf(listOf(0, 1, 2), listOf(3, 4)), workers.map { it.ran.toList() }.toSet())
        assertEquals(0L, both.count, "both screens were running at once")
    }

    @Test
    fun `what a device that stopped had left goes to the others`() {
        val log = Collections.synchronizedList(mutableListOf<String>())
        val dying = Worker("a", log) { w, i -> if (i == 0) w.alive = false }
        // the other device takes its first piece only after the first one has stopped
        val gate = CountDownLatch(1)
        val other = object : PoolWorker {
            override val name = "b"
            override val alive = true
            val ran = mutableListOf<Int>()
            override fun start() { gate.await(2, TimeUnit.SECONDS) }
            override fun run(index: Int) { ran += index }
        }
        val a = Thread { while (dying.alive) Thread.sleep(5); gate.countDown() }.apply { start() }
        val left = handOut(first = emptyList(), then = listOf(listOf(0, 1, 2)), workers = listOf(dying, other))
        a.join()
        assertTrue(left.isEmpty())
        assertEquals(listOf(0), dying.ran.toList())
        assertEquals(listOf(1, 2), other.ran)
    }

    @Test
    fun `with no device left, what nobody ran is returned - and a waiting device is not left waiting`() {
        val log = Collections.synchronizedList(mutableListOf<String>())
        val workers = listOf("a", "b").map { Worker(it, log) { w, _ -> w.alive = false } }
        val left = handOut(first = listOf(listOf(0), listOf(1), listOf(2)), then = listOf(listOf(3, 4)), workers = workers)
        assertEquals(listOf(2, 3, 4), left.sorted())
    }

    @Test
    fun `a device that throws stops, and the rest of its piece is run by another`() {
        val log = Collections.synchronizedList(mutableListOf<String>())
        val stopped = Collections.synchronizedList(mutableListOf<String>())
        val workers = listOf("a", "b").map { n -> Worker(n, log) { w, i -> if (w.name == "a" && i == 0) throw IllegalStateException("boom") } }
        // only `a` may take the piece first
        val gate = CountDownLatch(1)
        val b = object : PoolWorker by workers[1] { override fun start() { gate.await(2, TimeUnit.SECONDS) } }
        val left = handOut(first = emptyList(), then = listOf(listOf(0, 1)), workers = listOf(workers[0], b), onStopped = { w, e -> stopped += "${w.name}: ${e.message}"; gate.countDown() })
        assertTrue(left.isEmpty())
        assertEquals(listOf("a: boom"), stopped.toList())
        assertEquals(listOf(1), workers[1].ran.toList())
    }

    // ------------------------------------------------------------------ over devices

    private fun project(): SimulProject {
        val root = tempDir()
        File(root, ".simul/config.yaml").apply { parentFile.mkdirs() }.writeText(
            "app: com.example.app\nllm:\n  url: http://localhost:8080\ndisplay:\n  sizeClass: small\n"
        )
        for (name in listOf("홈/a", "홈/b", "검색/c")) {
            File(root, ".simul/scenarios/$name.md").apply { parentFile.mkdirs() }
                .writeText("---\nname: ${name.substringAfter('/')}\nsetup:\n  launch: true\n---\n1. \"홈\" 탭을 누른다\n")
        }
        return SimulProject.find(root)!!
    }

    @Test
    fun `every device is pinned and put back, and the results come in the order of the runs`() {
        val p = project()
        val runs = p.scenarioVariants()
        val devices = listOf("emulator-5554", "emulator-5556").map { s -> FakeOps().apply { named = s; display = "1080x2400@420" } }
        val out = Collections.synchronizedList(mutableListOf<String>())

        // nothing is recorded yet: a replay skips each scenario without touching a device
        val results = runOverDevices(p, runs, devices, RunMode.REPLAY, p.config.llm, video = false, runsDir = null, out = out::add)

        assertEquals(runs.map { it.second.name }, results.map { it.outcome.name })
        assertTrue(results.all { it.outcome.status == "SKIPPED" }, "$out")
        assertTrue(devices.all { it.woken == 1 && it.display == "1080x2400@420" && it.setups.isEmpty() }, "pinned for the run, as they were after it")
        for (s in listOf("emulator-5554", "emulator-5556")) {
            assertTrue(out.any { it.startsWith("[$s] display: forced to 720x1280@320") }, "$out")
        }
    }

    @Test
    fun `when no device answers every scenario is a row that says so`() {
        val p = project()
        val runs = p.scenarioVariants()
        val devices = listOf("emulator-5554", "emulator-5556").map { s -> FakeOps().apply { named = s; down = "adb shell does not answer" } }

        val results = runOverDevices(p, runs, devices, RunMode.LLM, p.config.llm, video = false, runsDir = null, out = {})

        assertEquals(3, results.size)
        assertTrue(results.all { it.outcome.status == "CRASH" && it.outcome.reason == "not run — device not responding: adb shell does not answer" },
            results.joinToString { "${it.outcome.status} ${it.outcome.reason}" })
        assertTrue(devices.all { it.setups.isEmpty() && it.display == null })
    }
}
