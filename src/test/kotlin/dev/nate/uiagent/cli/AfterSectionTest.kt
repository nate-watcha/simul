package dev.nate.uiagent.cli

import dev.nate.uiagent.Point
import dev.nate.uiagent.StaticDevice
import dev.nate.uiagent.agent.ScenarioSession
import dev.nate.uiagent.device.Device
import dev.nate.uiagent.device.DeviceController
import dev.nate.uiagent.device.DeviceOps
import dev.nate.uiagent.el
import dev.nate.uiagent.layout
import dev.nate.uiagent.tempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A scenario that starts where a section of another scenario ended (`setup.after`, [Scenario.After]). */
class AfterSectionTest {

    // ------------------------------------------------------------------ the files

    /** stone: a rating scenario that looks at the row its rating lands in, and the row's own scenario for what a tap there does. */
    private fun project(poster: String = POSTER): SimulProject {
        val root = tempDir()
        fun file(path: String, text: String) = File(root, ".simul/scenarios/$path").apply { parentFile.mkdirs(); writeText(text.trimIndent() + "\n") }
        File(root, ".simul").mkdirs()
        File(root, ".simul/config.yaml").writeText("app: com.example.app\nllm:\n  url: http://localhost:8080\n")
        file("프로필/활동/_entry.md", """
            ---
            setup:
              appState: member
              deeplink: app://my
            ---
            1. "활동" 탭을 누른다
        """)
        file("작품상세/rating.md", """
            ---
            name: 평가하고 지우기
            setup:
              appState: member
              deeplink: app://contents/1
            ---
            상단의 별점 바.

            ## 평가
            1. "4점"을 누른다

            ## 평가 뒤 활동 탭

            setup:
              entry: 프로필/활동

            @프로필/활동/평가 영역 맨 앞에 나온다.
            1. 아래로 스크롤해 "치이카와"를 찾는다

            ## 삭제
            1. "4점"을 누른다
        """)
        file("프로필/활동/평가/포스터.md", poster)
        file("프로필/활동/평가/헤더.md", """
            누르면 평가함으로 간다.
            1. "평가 전체 보기"를 누른다
        """)
        return SimulProject.find(root)!!
    }

    private fun SimulProject.md(path: String) = File(scenariosDir, path)

    @Test
    fun `a scenario that starts after another's section opens nothing itself and runs as that scenario's account`() {
        val p = project()

        val poster = p.load(p.md("프로필/활동/평가/포스터.md"))

        assertEquals(Scenario.After("작품상세/rating.md", "평가 뒤 활동 탭", "평가하고 지우기", "작품상세"), poster.after)
        assertNull(poster.via, "its directory has a way in, and it does not take it: the app is already where it starts")
        assertEquals(Scenario.Setup(deeplink = null, launch = false, appState = "member"), poster.setup)
        // the one beside it starts as every scenario of the row does
        assertEquals("프로필/활동", p.load(p.md("프로필/활동/평가/헤더.md")).via)
        assertEquals(listOf(p.md("프로필/활동/평가/포스터.md").name), p.followersOf(p.md("작품상세/rating.md")).map { it.name })
    }

    @Test
    fun `the section is written after the scenario, with either arrow`() {
        val p = project(POSTER.replace("작품상세/rating > 평가 뒤 활동 탭", "작품상세/rating › 평가 뒤 활동 탭"))
        assertEquals("평가 뒤 활동 탭", p.load(p.md("프로필/활동/평가/포스터.md")).after?.section)
    }

    @Test
    fun `what cannot be started after is refused with the scenario, saying what is there`() {
        fun refused(after: String, more: String = ""): String {
            val p = project(POSTER.replace("after: 작품상세/rating > 평가 뒤 활동 탭", "after: $after$more"))
            return assertFailsWith<IllegalArgumentException> { p.load(p.md("프로필/활동/평가/포스터.md")) }.message!!
        }
        fun said(expected: String, message: String) = assertTrue(expected in message, message)
        said("there is no scenario '작품상세/평가.md'", refused("작품상세/평가 > 평가 뒤 활동 탭"))
        said("has no section '## 활동 탭' (its sections: 평가, 평가 뒤 활동 탭, 삭제)", refused("작품상세/rating > 활동 탭"))
        said("write the scenario and its section", refused("작품상세/rating"))
        said("already says where it starts and as whom", refused("작품상세/rating > 평가", "\n              deeplink: app://my"))
        said("a way in (_entry.md) is started from with `entry:`", refused("프로필/활동/_entry > 평가"))
    }

    // ------------------------------------------------------------------ what a run takes along

    @Test
    fun `asked for alone, it brings the scenario it starts after and runs inside it`() {
        val p = project()
        val poster = p.md("프로필/활동/평가/포스터.md")

        val runs = withHosts(p, listOf(poster to p.load(poster)))

        assertEquals(listOf("평가하고 지우기"), runs.map { it.second.name }, "the rating runs, for the poster's sake")
        assertEquals(listOf("포스터" to "평가 뒤 활동 탭"), runs.single().second.followers.map { it.scenario.name to it.section })
    }

    @Test
    fun `asked for together, it is run once - inside the other, where that one stands in the list`() {
        val p = project()
        val all = listOf("프로필/활동/평가/포스터.md", "프로필/활동/평가/헤더.md", "작품상세/rating.md").map { p.md(it) }.map { it to p.load(it) }

        val runs = withHosts(p, all)

        assertEquals(listOf("헤더", "평가하고 지우기"), runs.map { it.second.name })
        assertEquals(listOf("포스터"), runs[1].second.followers.map { it.scenario.name })
        // and a run of the rating alone takes nothing along that was not asked for
        val alone = p.md("작품상세/rating.md").let { withHosts(p, listOf(it to p.load(it))) }
        assertTrue(alone.single().second.followers.isEmpty())
    }

    @Test
    fun `when the other scenario is not run, the one waiting on it is a row that says so, not a failure`() {
        val p = project()
        val rating = withHosts(p, listOf(p.md("프로필/활동/평가/포스터.md")).map { it to p.load(it) }).single()
        val ops = FakeOps().apply { down = "adb shell does not answer" }
        val logged = mutableListOf<String>()

        val result = BatchSession(p, ops, logged::add, pause = {}).use { batch ->
            batch.pin()
            batch.run(rating.first, rating.second, RunMode.LLM, p.config.llm)
        }

        assertEquals(listOf("CRASH", "SKIPPED"), result.all().map { it.outcome.status })
        val waiting = result.also.single().outcome
        assertEquals("포스터", waiting.name)
        assertEquals("not run — it starts after '평가하고 지우기 › 평가 뒤 활동 탭', which was not run", waiting.reason)
        assertTrue(waiting.reason!!.startsWith(Site.CAME_FROM_FAILED))
    }

    // ------------------------------------------------------------------ the runner

    /** An app with a rating that outlives its restart: the detail screen toggles it, the row shows the title while it is there. */
    private class RatingApp {
        var rated = false
            set(value) { field = value; show() }
        var at = "detail"
            set(value) { field = value; show() }
        private fun screen() = when (at) {
            "detail" -> layout(el("4점", interactions = listOf("clickable"), center = Point(360, 300)))
            "row" -> layout(*listOfNotNull(
                el("평가 전체 보기", interactions = listOf("clickable"), center = Point(360, 200)),
                el("치이카와", interactions = listOf("clickable"), center = Point(100, 400)).takeIf { rated },
            ).toTypedArray())
            else -> layout(el("재감상 기록하기", center = Point(360, 500)))
        }
        private val shown = StaticDevice(screen())
        private fun show() { shown.layout = screen() }
        val device = object : Device by shown {
            override fun tap(x: Int, y: Int) {
                when {
                    at == "detail" -> rated = !rated
                    at == "row" && rated && y == 400 -> at = "opened"
                }
            }
        }
    }

    private val rating = Scenario(
        name = "평가하고 지우기", tags = emptyList(), setup = Scenario.Setup(deeplink = "app://contents/1", launch = false),
        steps = listOf("\"4점\"을 누른다", "\"치이카와\"가 보이는지 확인한다", "\"4점\"을 누른다"),
        sections = listOf(
            Scenario.Section("평가", "", 0, 1),
            Scenario.Section("평가 뒤 활동 탭", "", 1, 1, open = Scenario.Open(deeplink = "app://my")),
            Scenario.Section("삭제", "", 2, 1),
        ),
    )
    private val poster = Scenario(
        name = "포스터", tags = emptyList(), setup = Scenario.Setup(deeplink = null, launch = false),
        steps = listOf("\"치이카와\"를 누른다", "\"재감상 기록하기\"가 보이는지 확인한다"),
    )

    /** One run's runner on [app]: taps what a "누른다" step quotes, and passes a step whose quote is (was, for a tap) on screen. */
    private inner class Run(val app: RatingApp, afterSection: (String, Boolean, () -> Unit) -> Unit = { _, _, _ -> }) {
        val listener = SwitchableListener()
        val controller = DeviceController(app.device, settleIntervalMs = 0, listener = listener)
        val ops = Opens(app)
        val executor = ScriptedExecutor { command ->
            val label = quotedLabels(command).first()
            val there = controller.currentElements.any { it.matchesExact(label) }
            if (there && "누른다" in command) controller.tap(label)
            ScenarioSession.Verdict(there, if (there) "ok" else "\"$label\" is not on screen")
        }
        val runner = ScenarioRunner(controller, listener, ops, executor, tempDir(), app = "com.example", afterSection = afterSection)
    }

    /** The device's app lifecycle on [app]: the address says which screen it opens on. */
    private class Opens(private val app: RatingApp, private val fake: FakeOps = FakeOps()) : DeviceOps by fake {
        val opened = mutableListOf<String>()
        private fun open(deeplink: String?) { opened += deeplink ?: "-"; app.at = if (deeplink == "app://my") "row" else "detail" }
        override fun setup(app: String?, appState: String?, clearData: Boolean, launch: Boolean, deeplink: String?, locale: String?): String? { open(deeplink); return null }
        override fun reopen(app: String?, launch: Boolean, deeplink: String?): String? { open(deeplink); return null }
    }

    @Test
    fun `it runs where the section ended, on what the other scenario wrote, before that scenario puts it back`() {
        val app = RatingApp()
        val ended = mutableListOf<Pair<String, Boolean>>()
        var taken: ScenarioRunResult? = null
        var inside: Run? = null
        val host = Run(app) { section, passed, _ ->
            ended += section to passed
            if (section == "평가 뒤 활동 탭") inside = Run(app).also { taken = it.runner.run(poster, trace = null, mode = RunMode.LLM, continued = true) }
        }

        val recorded = host.runner.run(rating, trace = null, mode = RunMode.LLM)

        assertEquals(listOf("평가" to true, "평가 뒤 활동 탭" to true, "삭제" to true), ended)
        assertEquals(StepStatus.PASSED, taken!!.status, taken!!.steps.firstOrNull { it.status == StepStatus.FAILED }?.reason)
        assertTrue(inside!!.ops.opened.isEmpty(), "it opened nothing: the app stood where it starts")
        assertEquals(listOf("\"치이카와\"를 누른다", "\"재감상 기록하기\"가 보이는지 확인한다"), taken!!.updatedTrace!!.steps.map { it.text })
        // the rating went on from its own places: the section after starts the app over, whatever the other one left on screen
        assertEquals(StepStatus.PASSED, recorded.status, recorded.steps.firstOrNull { it.status == StepStatus.FAILED }?.reason)
        assertEquals(listOf("app://contents/1", "app://my", "app://contents/1"), host.ops.opened)
        assertEquals(false, app.rated, "and put back what it wrote")

        // replayed, the same: the other scenario's trace to the section, then this one's own
        var replayed: ScenarioRunResult? = null
        val again = Run(app) { section, _, _ ->
            if (section == "평가 뒤 활동 탭") replayed = Run(app).runner.run(poster, trace = taken!!.updatedTrace, mode = RunMode.REPLAY, continued = true)
        }
        assertEquals(StepStatus.PASSED, again.runner.run(rating, trace = recorded.updatedTrace, mode = RunMode.REPLAY).status)
        assertEquals(StepStatus.PASSED, replayed!!.status, replayed!!.steps.firstOrNull { it.status == StepStatus.FAILED }?.reason)
        assertEquals(0, again.executor.calls, "no model in either")
    }

    @Test
    fun `a section that did not pass says so, and so do the ones skipped after it`() {
        val app = RatingApp().apply { at = "opened" } // nothing to rate on this screen
        val ended = mutableListOf<Pair<String, Boolean>>()
        val host = Run(app) { section, passed, _ -> ended += section to passed }
        // the app opens on a screen without the star, whatever the address
        val broken = rating.copy(steps = listOf("\"5점\"을 누른다") + rating.steps.drop(1))

        assertEquals(StepStatus.FAILED, host.runner.run(broken, trace = null, mode = RunMode.LLM).status)

        assertEquals(listOf("평가" to false, "평가 뒤 활동 탭" to false, "삭제" to false), ended)
    }

    @Test
    fun `the second scenario that starts there gets the section's end back - the first one walked away from it`() {
        val app = RatingApp()
        val starts = mutableListOf<String>()
        val host = Run(app) { section, _, back ->
            if (section != "평가 뒤 활동 탭") return@Run
            Run(app).runner.run(poster, trace = null, mode = RunMode.LLM, continued = true)
            starts += app.at // the poster opened the title
            back()
            starts += app.at
        }

        host.runner.run(rating, trace = null, mode = RunMode.LLM)

        assertEquals(listOf("opened", "row"), starts)
        assertEquals(listOf("app://contents/1", "app://my", "app://my", "app://contents/1"), host.ops.opened, "the section's place is opened again, and its steps played")
    }

    // ------------------------------------------------------------------ the report

    @Test
    fun `the report says under the scenario's sentence where its data came from, and no more`() {
        val day = File(tempDir(), "2026-11-27")
        fun st(n: Int, text: String) = StepReport(n, text, StepStatus.PASSED, "replay", emptyList(), emptyList(), null, 500, emptyList(), startedMs = n * 1000L)
        fun write(feature: String, name: String, title: String, prose: String, steps: List<StepReport>, sections: List<SectionReport> = emptyList(), after: ResultJson.After? = null) {
            val dir = File(day, "$feature/$name").apply { mkdirs() }
            ResultJson.write(ScenarioRunResult(title, StepStatus.PASSED, steps, null, 5000, startedAtMs = 1_794_000_000_000L, reportDir = dir, sections = sections), "replay",
                ResultJson.Meta(feature, name, null, "$feature/$name.md", title, null, prose, "2026-11-27", ResultJson.Env("6.4.0", "sdk", "1.1"),
                    setup = ResultJson.Setup(null, false, "member"), after = after), dir)
        }
        write("작품상세", "rating", "평가하고 지우기", "상단의 별점 바.", listOf(st(1, "\"4점\"을 누른다"), st(2, "\"치이카와\"를 찾는다")),
            listOf(SectionReport("평가", "별을 누르면 저장된다.", StepStatus.PASSED, 1..1), SectionReport("평가 뒤 활동 탭", "맨 앞에 나온다.", StepStatus.PASSED, 2..2)))
        write("활동/평가", "포스터", "포스터", "누르면 그 작품의 상세로 간다.", listOf(st(1, "\"치이카와\"를 누른다")),
            after = ResultJson.After("작품상세", "평가하고 지우기", "평가 뒤 활동 탭", "rating", listOf("치이카와")))

        assertEquals(listOf("치이카와"), Site.load(day).results.single { it.name == "포스터" }.after?.labels, "result.json carries it")
        SitePages.build(day, day)

        val page = day.walkTopDown().filter { it.name == "_screen.html" }.map { it.readText() }.first { "<b class=fn>포스터</b>" in it }
        assertTrue("누르면 그 작품의 상세로 간다.<span class=ran>치이카와 · <a href=\"../작품상세/_screen.html#rating\">작품상세 › 평가하고 지우기</a>에서 넣음</span></span>" in page,
            page.substringAfter("누르면 그 작품의 상세로 간다.").take(400))
    }

    @Test
    fun `a scenario that was not run because the other one failed there reads as not run, with where`() {
        val day = File(tempDir(), "2026-11-27")
        val dir = File(day, "작품상세/rating").apply { mkdirs() }
        val failed = StepReport(1, "\"4점\"을 누른다", StepStatus.FAILED, "replay", emptyList(), emptyList(), "replay broken", 500, emptyList())
        ResultJson.write(ScenarioRunResult("평가하고 지우기", StepStatus.FAILED, listOf(failed), null, 5000, startedAtMs = 1_794_000_000_000L, reportDir = dir), "replay",
            ResultJson.Meta("작품상세", "rating", null, "작품상세/rating.md", "평가하고 지우기", null, "상단의 별점 바.", "2026-11-27", ResultJson.Env("6.4.0", "sdk", "1.1")), dir)
        fun row(name: String, path: String, status: String, reason: String?) = ScenarioOutcome(name, path, emptyList(), status, "replay", 0, null, false, 0, 0, 1, null, reason, description = "누르면 그 작품의 상세로 간다.")
        RunSummaryJson.write(RunSummary("replay", "replay", "2026-11-27T02:00:00+09:00", 1000, "0.2.0", "6.4.0", "sdk", listOf(
            row("평가하고 지우기", "작품상세/rating.md", "FAILED", null),
            row("포스터", "활동/평가/포스터.md", "SKIPPED", "${Site.CAME_FROM_FAILED} '평가하고 지우기 › 평가 뒤 활동 탭', which did not pass"),
        )), File(day, "run.json"))

        val rows = Site.classify(Site.load(day))
        assertEquals(Site.Kind.SETUP, rows.single { it.result.name == "포스터" }.kind, "not a failure of its own")
        SitePages.build(day, day)

        val page = day.walkTopDown().filter { it.name == "_screen.html" }.map { it.readText() }.first { "<b class=fn>포스터</b>" in it }
        assertTrue("<span class=ran><span class=off>돌리지 못함</span> · 통과하지 못한 곳: 평가하고 지우기 › 평가 뒤 활동 탭</span>" in page, page.substringAfter("포스터").take(600))
    }

    private companion object {
        val POSTER = """
            ---
            setup:
              after: 작품상세/rating > 평가 뒤 활동 탭
            ---
            누르면 그 작품의 상세로 간다.

            1. "치이카와"를 누른다
            2. 작품 상세에 "재감상 기록하기"가 보이는지 확인한다
        """
    }
}
