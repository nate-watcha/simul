package dev.nate.uiagent.web

import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.LogicalLayout
import dev.nate.uiagent.Origin
import dev.nate.uiagent.Point
import dev.nate.uiagent.device.Device
import dev.nate.uiagent.device.DeviceController
import dev.nate.uiagent.cli.Replayer
import dev.nate.uiagent.cli.TraceAction
import dev.nate.uiagent.cli.TraceJson
import dev.nate.uiagent.cli.TraceStep
import dev.nate.uiagent.cli.TraceTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M3: trace origin field round-trip (backward compatible) and LLM-free web replay. */
class WebTraceTest {

    @Test
    fun `web origin round-trips through trace json`() {
        val target = TraceTarget("grade-basic", "베이직", Point(520, 863), origin = "web")
        val rendered = TraceJson.actionJson(TraceAction("tap", target = target)).toString()
        assertTrue(rendered.contains(""""origin":"web""""), rendered)

        val trace = TraceJson.parse(
            """{"scenario":"s","recordedWith":{"agentVersion":"v"},"steps":[
                 {"text":"t","actions":[{"tool":"tap","target":
                   {"resourceId":"grade-basic","label":"베이직","lastCoords":[520,863],"origin":"web"}}],
                  "evidence":{"appeared":[]}}]}"""
        )
        assertEquals("web", trace.steps[0].actions[0].target?.origin)
    }

    @Test
    fun `native targets omit origin and old traces still parse`() {
        val rendered = TraceJson.actionJson(
            TraceAction("tap", target = TraceTarget("btn", "확인", Point(1, 2)))
        ).toString()
        assertFalse(rendered.contains("origin"), rendered)

        // pre-v3 trace without the field
        val trace = TraceJson.parse(
            """{"scenario":"s","recordedWith":{"agentVersion":"v"},"steps":[
                 {"text":"t","actions":[{"tool":"tap","target":
                   {"resourceId":null,"label":"확인","lastCoords":[1,2]}}],
                  "evidence":{"appeared":[]}}]}"""
        )
        assertNull(trace.steps[0].actions[0].target?.origin)
    }

    // ------------------------------------------------------------ web replay

    private class StaticDevice(var layout: LogicalLayout) : Device {
        override fun observe(): LogicalLayout = layout
        override fun tap(x: Int, y: Int) {}
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
        override fun inputText(text: String) {}
        override fun keyBack() {}
        override fun sleep(ms: Long) {}
    }

    private fun webEl(id: Int, label: String, attrId: String? = null, y: Int = 300 * (id + 1)) =
        LogicalElement(id, label, attrId, listOf("clickable"), emptyList(), null,
            Point(360, y), null, origin = Origin.WEB)

    @Test
    fun `web steps replay by attrId then label with evidence on the merged layout`() {
        val merged = LogicalLayout(listOf(
            webEl(0, "베이직", attrId = "grade-basic"),
            webEl(1, "구독 시작하기"),
        ))
        val c = DeviceController(StaticDevice(merged), settleIntervalMs = 0)
        c.fullLayout()

        // grounding chain: attrId (resourceId slot) first, then label; evidence via labelPresent
        val step = TraceStep(
            text = "'베이직' 선택",
            actions = listOf(
                TraceAction("tap", target = TraceTarget("grade-basic", "옛-라벨", Point(1, 1), origin = "web")),
                TraceAction("tap", target = TraceTarget(null, "구독 시작하기", Point(360, 600), origin = "web")),
            ),
            evidence = listOf("구독 시작하기"),
        )
        val result = Replayer(c).replayStep(step)
        assertNull(result.broken, "web replay should ground without LLM: ${result.broken}")
    }

    @Test
    fun `vanished web target breaks replay`() {
        val c = DeviceController(StaticDevice(LogicalLayout(listOf(webEl(0, "다른 화면")))), settleIntervalMs = 0)
        c.fullLayout()
        val step = TraceStep(
            "'베이직' 선택",
            listOf(TraceAction("tap", target = TraceTarget("grade-basic", "베이직", Point(1, 1), origin = "web"))),
            emptyList(),
        )
        val broken = Replayer(c).replayStep(step).broken
        assertTrue(broken != null && broken.why.contains("cannot re-ground"), "$broken")
    }
}
