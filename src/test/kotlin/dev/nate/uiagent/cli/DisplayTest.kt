package dev.nate.uiagent.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DisplayTest {

    @Test
    fun `size classes resolve to canonical profiles inside the target-app dp ranges`() {
        // size-class boundaries: SMALL <600dp, MEDIUM 600..799dp, LARGE >=800dp
        val small = DisplayProfile.fromConfig("small", null, null)!!
        assertEquals(DisplayProfile(720, 1280, 320), small)
        assertEquals(360, small.dpWidth)
        assertEquals("720x1280@320", small.toString())

        val medium = DisplayProfile.fromConfig("medium", null, null)!!.dpWidth
        assertEquals(true, medium in 600..799, "medium=$medium")
        val large = DisplayProfile.fromConfig("large", null, null)!!.dpWidth
        assertEquals(true, large >= 800, "large=$large")
    }

    @Test
    fun `explicit size and density stand alone`() {
        assertEquals(DisplayProfile(1080, 2400, 480), DisplayProfile.fromConfig(null, "1080x2400", "480"))
    }

    @Test
    fun `explicit fields override the size class base`() {
        assertEquals(DisplayProfile(720, 1280, 160), DisplayProfile.fromConfig("small", null, "160"))
    }

    @Test
    fun `absent block resolves to null, malformed blocks fail loudly`() {
        assertNull(DisplayProfile.fromConfig(null, null, null))
        assertFailsWith<IllegalArgumentException> { DisplayProfile.fromConfig("phone", null, null) }
        assertFailsWith<IllegalArgumentException> { DisplayProfile.fromConfig(null, "720x1280", null) } // density missing
        assertFailsWith<IllegalArgumentException> { DisplayProfile.fromConfig(null, "wide", "320") }
        assertFailsWith<IllegalArgumentException> { DisplayProfile.fromConfig(null, "720x1280", "-1") }
    }

    @Test
    fun `config yaml display block parses`() {
        val dir = File.createTempFile("simul-test", "").let { it.delete(); it.mkdirs(); it }
        File(dir, "config.yaml").writeText(
            """
            app: com.example
            display:
              sizeClass: small
            """.trimIndent()
        )
        assertEquals(DisplayProfile(720, 1280, 320), SimulConfig.load(File(dir, "config.yaml")).display)
        assertNull(SimulConfig.load(File(dir, "missing.yaml")).display)
    }

    @Test
    fun `canonical string form round-trips through parse`() {
        assertEquals(DisplayProfile(720, 1280, 320), DisplayProfile.parse("720x1280@320"))
        assertNull(DisplayProfile.parse("720x1280"))
        assertNull(DisplayProfile.parse("unknown"))
    }

    // ------------------------------------------------------------ wm output parsing

    @Test
    fun `wm outputs parse, override wins over physical`() {
        assertEquals(
            "720x1280@320",
            AdbOps.parseWm("Physical size: 720x1280\n", "Physical density: 320\n"),
        )
        assertEquals(
            "720x1280@320",
            AdbOps.parseWm(
                "Physical size: 1600x2560\nOverride size: 720x1280\n",
                "Physical density: 276\nOverride density: 320\n",
            ),
        )
        assertNull(AdbOps.parseWm("garbage", "Physical density: 320"))
    }

    // ------------------------------------------------------------ trace stamp

    @Test
    fun `state evidence round-trips through trace json and stays optional`() {
        val step = TraceStep("t", emptyList(), listOf("구독"),
            stateEvidence = listOf(StateEvidence("구독", "selected")))
        val t = TraceFile("s", "v", null, listOf(step))
        val parsed = TraceJson.parse(TraceJson.render(t))
        assertEquals(listOf(StateEvidence("구독", "selected")), parsed.steps[0].stateEvidence)

        // steps without state evidence emit no "states" field; old traces parse to empty
        val plain = TraceJson.render(TraceFile("s", "v", null, listOf(TraceStep("t", emptyList(), listOf("a")))))
        assertEquals(false, plain.contains("states"))
        val legacy = TraceJson.parse(
            """{"scenario":"s","recordedWith":{"agentVersion":"v"},"steps":[
                 {"text":"t","actions":[],"evidence":{"appeared":["a"]}}]}"""
        )
        assertEquals(emptyList(), legacy.steps[0].stateEvidence)
    }

    @Test
    fun `trace display stamp round-trips and stays optional`() {
        val stamped = TraceFile("s", "0.2.0", "1.0", emptyList(), display = "720x1280@320")
        val parsed = TraceJson.parse(TraceJson.render(stamped))
        assertEquals("720x1280@320", parsed.display)

        // pre-display traces keep parsing, and rendering without a stamp emits no field
        val legacy = TraceJson.parse("""{"scenario":"s","recordedWith":{"agentVersion":"v"},"steps":[]}""")
        assertNull(legacy.display)
        assertEquals(false, TraceJson.render(TraceFile("s", "v", null, emptyList())).contains("display"))
    }
}
