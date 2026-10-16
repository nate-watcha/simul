package dev.nate.uiagent.device

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

}
