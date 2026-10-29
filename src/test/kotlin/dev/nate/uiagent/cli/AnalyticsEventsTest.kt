package dev.nate.uiagent.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Firebase Analytics lines as the Play services process logs them (kloud dev, SDK 183005, 2026-10-29 실측). */
class AnalyticsEventsTest {

    private val view = "         1793237858.113  1566 25221 V FA-SVC  : Logging event: origin=app,name=w_view,params=Bundle[{w_event_id=530, w_path=/notification, ga_event_origin(_o)=app, ga_screen_class(_sc)=MainNavActivity, ga_screen_id(_si)=6163959274523659770, w_notification_unseen=0, w_referrer=/browse}]"
    private val cells = "         1793237858.487  1566 25221 V FA-SVC  : Logging event: origin=app,name=w_exposed_cells,params=Bundle[{w_filter_type=all, w_event_id=115, w_path=/browse, ga_event_origin(_o)=app, w_cells={\"1\":3}, w_referrer=null}]"
    private val focus = "         1793237862.393  1566 25221 V FA-SVC  : Logging event: origin=app,name=w_focus,params=Bundle[{w_cell_index=2, w_row=\"{\\\"narration\\\":\\\"featuring\\\"}\", w_cell_relation_id=gsmqNBJJWN, w_media_type=video}]"

    @Test
    fun `an event line gives its time, origin, name and parameters in logged order`() {
        val e = AnalyticsEvents.parseLine(view)!!
        assertEquals(1793237858113L, e.atEpochMs)
        assertEquals("app", e.origin)
        assertEquals("w_view", e.name)
        assertEquals(listOf("w_event_id", "w_path", "ga_event_origin(_o)", "ga_screen_class(_sc)", "ga_screen_id(_si)", "w_notification_unseen", "w_referrer"), e.params.keys.toList())
        assertEquals("/notification", e.params["w_path"])
        assertEquals("MainNavActivity", e.params["ga_screen_class(_sc)"])
    }

    @Test
    fun `a value holding commas, braces or quotes stays one value`() {
        assertEquals(mapOf("w_filter_type" to "all", "w_event_id" to "115", "w_path" to "/browse", "ga_event_origin(_o)" to "app", "w_cells" to "{\"1\":3}", "w_referrer" to "null"),
            AnalyticsEvents.parseLine(cells)!!.params)
        assertEquals("\"{\\\"narration\\\":\\\"featuring\\\"}\"", AnalyticsEvents.parseLine(focus)!!.params["w_row"])
    }

    @Test
    fun `other FA-SVC chatter and the app-side FA tag are not events`() {
        assertNull(AnalyticsEvents.parseLine("         1793237858.114  1566 25221 V FA-SVC  : Background event processing time, ms: 2"))
        assertNull(AnalyticsEvents.parseLine("         1793237820.843 25177 25258 I FA      : To enable faster debug mode event logging run:"))
    }

    @Test
    fun `events fall on the step that was running - before the first step is the setup's, after the last start the last step's`() {
        val events = listOf(view, cells, focus).map { AnalyticsEvents.parseLine(it)!! }
        // step 1 began after the first event, step 2 between the second and the third
        val buckets = AnalyticsEvents.bucket(events, listOf(1 to 1793237858200L, 2 to 1793237860000L))
        assertEquals(listOf("w_view"), buckets[0]?.map { it.name })
        assertEquals(listOf("w_exposed_cells"), buckets[1]?.map { it.name })
        assertEquals(listOf("w_focus"), buckets[2]?.map { it.name })
    }
}
