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
        assertEquals(Devices.ADB_PICKS, Devices.forRun("com.x", env = { null }, online = { listOf("emulator-5554") }, dedicated = { error("not asked") }))
        assertEquals(Devices.ADB_PICKS, Devices.forRun("com.x", env = { null }, online = { emptyList() }, dedicated = { error("not asked") }))
        assertEquals(Devices.ADB_PICKS, Devices.forRun("com.x", env = { "emulator-5556" }, online = { error("not asked") }, dedicated = { error("not asked") }))
        assertEquals(Devices.ADB_PICKS, Devices.forRun("com.x", env = { null }, online = { error("adb: not found") }, dedicated = { error("not asked") }))
    }

    @Test
    fun `several devices - the emulators and the phones marked for simul, never a person's phone`() {
        val online = listOf("emulator-5554", "PERSONAL", "emulator-5556", "PLACED")
        assertEquals(
            listOf("emulator-5554", "emulator-5556", "PLACED"),
            Devices.forRun("com.x", env = { null }, online = { online }, dedicated = { it != "PERSONAL" }, installed = { _, _ -> true }),
        )
        val e = assertFailsWith<IllegalStateException> {
            Devices.forRun("com.x", env = { null }, online = { listOf("A", "B") }, dedicated = { false })
        }
        assertTrue("ANDROID_SERIAL" in e.message!! && "simul_device" in e.message!!, e.message)
    }

    @Test
    fun `several devices - only the ones with the app under test, so another app's emulator on the same host is left out`() {
        // the Mac Pro: stone's emulators on 5554 and 5558, kloud's on 5556
        val online = listOf("emulator-5554", "emulator-5556", "emulator-5558")
        val notes = mutableListOf<String>()
        assertEquals(
            listOf("emulator-5554", "emulator-5558"),
            Devices.forRun("com.frograms.watcha.dev", env = { null }, online = { online }, dedicated = { true },
                installed = { serial, app -> app == "com.frograms.watcha.dev" && serial != "emulator-5556" }, note = notes::add),
        )
        assertEquals(listOf("devices: emulator-5556 left out — com.frograms.watcha.dev is not installed on it"), notes)

        val e = assertFailsWith<IllegalStateException> {
            Devices.forRun("com.x", env = { null }, online = { online }, dedicated = { true }, installed = { _, _ -> false })
        }
        assertTrue("none has com.x installed" in e.message!!, e.message)
        // a project without `app:` has nothing to look for
        assertEquals(online, Devices.forRun(null, env = { null }, online = { online }, dedicated = { true }, installed = { _, _ -> error("not asked") }))
    }

    // ------------------------------------------------------------------ handing the work out

    private class Worker(override val name: String, val log: MutableList<String>, val onRun: (Worker, Int) -> Unit = { _, _ -> }) : PoolWorker {
        @Volatile override var alive = true
        val ran: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        /** Indices this worker cannot start: it hands them back ([PoolWorker.run] false). */
        var refuses: Set<Int> = emptySet()
        override fun run(index: Int): Boolean {
            if (index in refuses) { alive = false; return false }
            log += "$name start $index"
            onRun(this, index)
            ran += index
            log += "$name end $index"
            return true
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
            override fun run(index: Int): Boolean { ran += index; return true }
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

    @Test
    fun `a scenario a device could not start goes to another device with the rest of its piece`() {
        val log = Collections.synchronizedList(mutableListOf<String>())
        // `a` finds out on its second scenario that it can run nothing any more (its network is gone)
        val a = Worker("a", log).apply { refuses = setOf(1) }
        val gate = CountDownLatch(1)
        val b = object : PoolWorker by Worker("b", log) {
            val ran = mutableListOf<Int>()
            override fun start() { gate.await(2, TimeUnit.SECONDS) }
            override fun run(index: Int): Boolean { ran += index; return true }
        }
        val t = Thread { while (a.alive) Thread.sleep(5); gate.countDown() }.apply { start() }
        val left = handOut(first = emptyList(), then = listOf(listOf(0, 1, 2)), workers = listOf(a, b))
        t.join()
        assertTrue(left.isEmpty())
        assertEquals(listOf(0), a.ran.toList())
        assertEquals(listOf(1, 2), b.ran, "the one it could not start first, then what came after it")
    }

    @Test
    fun `a way in a device could not start is handed out again before the screens`() {
        val log = Collections.synchronizedList(mutableListOf<String>())
        val a = Worker("a", log).apply { refuses = setOf(0) }
        val b = Worker("b", log)
        val left = handOut(first = listOf(listOf(0)), then = listOf(listOf(1, 2)), workers = listOf(a, b))
        assertTrue(left.isEmpty())
        assertEquals(listOf(0, 1, 2), b.ran.toList(), "$log")
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

    @Test
    fun `when no device has a network every scenario is a row that says so, and none was run`() {
        val p = project()
        val runs = p.scenarioVariants()
        val devices = listOf("emulator-5554", "emulator-5556").map { s -> FakeOps().apply { named = s; offline = true } }
        val out = Collections.synchronizedList(mutableListOf<String>())

        val results = runOverDevices(p, runs, devices, RunMode.LLM, p.config.llm, video = false, runsDir = null, out = out::add, pause = {})

        assertEquals(runs.map { it.second.name }, results.map { it.outcome.name })
        assertTrue(results.all { it.outcome.status == "CRASH" && it.outcome.reason!!.startsWith("not run — device has no network") },
            results.joinToString { "${it.outcome.status} ${it.outcome.reason}" })
        assertTrue(devices.all { it.setups.isEmpty() }, "no scenario was started on a device without a network")
        assertTrue(devices.all { it.airplaneLog == listOf("airplane on", "airplane off") }, "each was given one chance to come back")
        assertTrue(out.any { "goes back to be run on another device" in it }, "$out")
    }

    // ------------------------------------------------------------------ recording over devices

    /** A project of two screens, each with a way in and scenarios that start from it. */
    private fun projectWithEntries(): SimulProject {
        val root = tempDir()
        File(root, ".simul/config.yaml").apply { parentFile.mkdirs() }.writeText("app: com.example.app\nllm:\n  url: http://localhost:8080\n")
        fun md(path: String, front: String, step: String) =
            File(root, ".simul/scenarios/$path.md").apply { parentFile.mkdirs() }.writeText("---\n$front---\n1. $step\n")
        md("홈/_entry", "setup:\n  launch: true\n", "\"홈\"이 보이는지 확인한다")
        md("검색/_entry", "setup:\n  deeplink: app://search\n", "\"검색\"이 보이는지 확인한다")
        for (name in listOf("홈/a", "홈/b", "검색/c")) md(name, "name: ${name.substringAfter('/')}\n", "\"열기\"를 누른다")
        return SimulProject.find(root)!!
    }

    /** What stands in for the devices: every run as `<serial> <start|end> <scenario> <mode>`, in the order it happened. */
    private class Recorded(val project: SimulProject, val failing: Set<String> = emptySet(), val slow: Set<String> = emptySet()) {
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val runOne: (BatchSession, File, Scenario, RunMode, (String) -> Unit) -> BatchResult = { _, md, scenario, mode, say ->
            val device = Thread.currentThread().name.removePrefix("simul-")
            log += "$device start ${scenario.name} ${mode.name.lowercase()}"
            if (scenario.name in slow) Thread.sleep(150)
            say("ran ${scenario.name}")
            log += "$device end ${scenario.name} ${mode.name.lowercase()}"
            BatchResult(outcomeWithout(project, md, scenario, if (scenario.name in failing) "FAILED" else "PASSED", ""), null)
        }
    }

    private fun twoDevices() = listOf("emulator-5554", "emulator-5558").map { s -> FakeOps().apply { named = s } }

    @Test
    fun `a recording runs over the devices - every way in is recorded before a scenario that starts from one`() {
        val p = projectWithEntries()
        val runs = withEntries(p, p.scenarioVariants(), RunMode.LLM)
        val entries = runs.filter { it.first.name == SimulProject.ENTRY_FILE }.map { it.second.name }
        assertEquals(2, entries.size, "${runs.map { it.second.name }}")
        // one way in is slow: the device that finished the other waits for it instead of starting a screen
        val rec = Recorded(p, slow = setOf(entries[0]))

        val results = runOverDevices(p, runs, twoDevices(), RunMode.LLM, p.config.llm, video = false, runsDir = null, out = {}, runOne = rec.runOne)

        assertEquals(runs.map { it.second.name }, results.map { it.outcome.name })
        assertTrue(results.all { it.outcome.passed })
        val firstScenario = rec.log.indexOfFirst { it.contains(" start ") && entries.none { e -> " $e " in it } }
        assertTrue(entries.all { e -> rec.log.indexOfFirst { it.contains(" end $e ") } in 0 until firstScenario }, "${rec.log}")
        assertEquals(2, rec.log.filter { it.contains(" start ") && entries.any { e -> " $e " in it } }.map { it.substringBefore(' ') }.toSet().size,
            "the ways in were recorded side by side, one on each device: ${rec.log}")
        assertTrue(rec.log.all { it.endsWith(" llm") })
    }

    @Test
    fun `with replay after, a recording that passed is replayed on the device that recorded it before it takes anything else`() {
        val p = projectWithEntries()
        val runs = withEntries(p, p.scenarioVariants(), RunMode.LLM)
        val rec = Recorded(p, failing = setOf("b"))

        val results = runOverDevices(p, runs, twoDevices(), RunMode.LLM, p.config.llm, video = false, runsDir = null, out = {},
            thenReplay = true, runOne = rec.runOne)

        // in the order of the runs: each recording, then its replay — none for the recording that failed
        val expected = runs.flatMap { (_, sc) -> if (sc.name == "b") listOf(sc.name) else listOf(sc.name, sc.name) }
        assertEquals(expected, results.map { it.outcome.name })
        assertEquals(1, results.count { it.outcome.status == "FAILED" })
        assertTrue(rec.log.none { " b replay" in it }, "${rec.log}")
        for ((_, sc) in runs.filter { it.second.name != "b" }) {
            val device = rec.log.first { " start ${sc.name} llm" in it }.substringBefore(' ')
            val own = rec.log.filter { it.startsWith("$device ") }
            val at = own.indexOf("$device end ${sc.name} llm")
            assertEquals("$device start ${sc.name} replay", own[at + 1], "${rec.log}")
        }
    }
}
