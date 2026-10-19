package dev.nate.uiagent.cli

import dev.nate.uiagent.Bounds
import dev.nate.uiagent.Point
import dev.nate.uiagent.el
import dev.nate.uiagent.tempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The nightly site from a runs directory: result.json in, classification + pages out. The fixtures
 * are what `simul run --runs` leaves (ResultJson.write over a ScenarioRunResult), so the writer
 * and the reader are exercised together.
 */
class SiteTest {

    private val root = tempDir()
    private val runs = File(root, "runs/app")

    private fun step(n: Int, status: StepStatus, reason: String? = null, startedMs: Long = (n - 1) * 1000L, marks: List<ActionMark> = emptyList()) =
        StepReport(n, "Tap the \"btn$n\" button" + if (n == 2) " :: the \"list\" item should be shown" else "", status, if (status == StepStatus.SKIPPED) null else "replay",
            emptyList(), if (status == StepStatus.PASSED) listOf("list") else emptyList(), reason, 800, listOf("steps/0$n.png"),
            startedMs = startedMs, marks = marks, expectedEvidence = listOf("list", "toast"), layout = "layout/0$n.json")

    /** One scenario directory under [date] with two steps, their layouts and screenshots. */
    private fun scenario(date: String, feature: String, name: String, state: String?, status: StepStatus, layouts: List<List<dev.nate.uiagent.LogicalElement>>, video: Boolean = false, traceAt: String? = "2026-10-14", setupFailed: Boolean = false) {
        val dir = File(runs, "$date/$feature/$name${state?.let { ".$it" } ?: ""}").apply { mkdirs() }
        val t0 = 1_792_000_000_000L
        layouts.forEachIndexed { i, els ->
            LayoutJson.write(els, File(dir, "layout/0${i + 1}.json"))
            ImageIO.write(BufferedImage(72, 128, BufferedImage.TYPE_INT_RGB), "png", File(dir, "steps/0${i + 1}.png").also { it.parentFile.mkdirs() })
        }
        val steps = if (setupFailed) listOf(StepReport(1, "Tap the \"btn1\" button", StepStatus.SKIPPED, null, emptyList(), emptyList(), "setup failed: deeplink not handled", 0, emptyList()))
        else listOf(
            step(1, StepStatus.PASSED, marks = listOf(ActionMark("tap", "btn1", t0 + 300))),
            step(2, if (status == StepStatus.FAILED) StepStatus.FAILED else StepStatus.PASSED, if (status == StepStatus.FAILED) "replay broken: evidence not on screen: toast" else null, marks = listOf(ActionMark("tap", "btn2", t0 + 1200))),
        )
        val vid = if (video) { File(dir, "replay.mp4").writeText("mp4"); VideoInfo(File(dir, "replay.mp4"), t0 - 2500, 9000) } else null
        if (status == StepStatus.FAILED) File(dir, "logcat.txt").writeText(
            "--------- beginning of main\n" +
            "         ${(t0 + 1000) / 1000}.100   672   833 I CouponVM: apply(code=X)\n" +
            "         ${(t0 + 1500) / 1000}.900   672   833 E CouponRepo: HTTP 422\n" +
            "         ${(t0 + 9000) / 1000}.000   672   833 I Later: unrelated\n")
        val r = ScenarioRunResult(name, status, steps, null, 2000, startedAtMs = t0, setupFailed = setupFailed, reportDir = dir, video = vid)
        ResultJson.write(r, "replay", ResultJson.Meta(feature, name, state, "$feature/$name.md", "쿠폰을 등록한다", "nate", "설정에서 쿠폰 메뉴로 들어간다.", traceAt,
            ResultJson.Env("4.21.0", "sdk_gphone64", "1.0.1")), dir)
    }

    private val screenA = listOf(el("설정", resourceId = "tab_settings", bounds = Bounds(0, 0, 100, 50), center = Point(50, 25)), el(resourceId = "btn_apply", bounds = Bounds(0, 100, 100, 150), center = Point(50, 125), id = 1))
    private val screenB = listOf(el("목록", resourceId = "coupon_list_item", bounds = Bounds(0, 0, 100, 50), center = Point(50, 25)))
    private val screenBMoved = listOf(el("목록", resourceId = "coupon_list_item", bounds = Bounds(0, 20, 100, 70), center = Point(50, 45)))
    private val screenBNew = screenB + el(resourceId = "price_badge", bounds = Bounds(0, 60, 100, 80), center = Point(50, 70), id = 1)

    @Test
    fun `result json round-trips, with the recording's timeline on every step and action`() {
        scenario("2026-10-19", "billing", "coupon", "member", StepStatus.FAILED, listOf(screenA, screenB), video = true)
        val r = assertNotNull(ResultJson.read(File(runs, "2026-10-19/billing/coupon.member/result.json")))
        assertEquals("billing/coupon.member", r.id)
        assertEquals("쿠폰을 등록한다", r.title)
        assertEquals("FAILED", r.status)
        assertEquals(2, r.failedStep)
        assertEquals("replay.mp4", r.video?.file)
        val s2 = r.steps[1]
        assertEquals("Tap the \"btn2\" button", s2.text)
        assertEquals("the \"list\" item should be shown", s2.criterion)
        assertEquals(listOf("list", "toast"), s2.expected)
        assertEquals(emptyList(), s2.found)
        // the mp4 started 2.5s before the scenario: step 2 began at +1000ms → 3500ms into the video; its tap at +1200 → 3700
        assertEquals(3500L, s2.videoMs)
        assertEquals(3700L, s2.actions.single().videoMs)
        assertEquals("steps/02.png", s2.screenshot)
        assertEquals("layout/02.json", s2.layout)
        assertTrue(r.layoutHash.length == 12)
        assertEquals(true, ResultJson.readLastRun(File(r.dir, "result.json"))?.passed?.not())
    }

    @Test
    fun `setup failure is SKIPPED_SETUP, not a failed scenario`() {
        scenario("2026-10-19", "auth", "login", null, StepStatus.FAILED, emptyList(), setupFailed = true)
        val r = assertNotNull(ResultJson.read(File(runs, "2026-10-19/auth/login/result.json")))
        assertEquals("SKIPPED_SETUP", r.status)
        assertEquals("setup failed: deeplink not handled", r.reason)
        assertEquals("auth/login", r.id)
        val rows = Site.classify(Site.load(File(runs, "2026-10-19")), null)
        assertEquals(Site.Kind.SETUP, rows.single().kind)
    }

    @Test
    fun `classification - failed, screen changed vs yesterday, acknowledged but not re-recorded, passed`() {
        // yesterday
        scenario("2026-10-18", "billing", "coupon", "member", StepStatus.PASSED, listOf(screenA, screenB))
        scenario("2026-10-18", "billing", "subscribe", "nosub", StepStatus.PASSED, listOf(screenA, screenB))
        scenario("2026-10-18", "settings", "notify", null, StepStatus.PASSED, listOf(screenA, screenB))
        scenario("2026-10-18", "player", "resume", null, StepStatus.PASSED, listOf(screenA, screenB))
        // today
        scenario("2026-10-19", "billing", "coupon", "member", StepStatus.FAILED, listOf(screenA, screenB), video = true)
        scenario("2026-10-19", "billing", "subscribe", "nosub", StepStatus.PASSED, listOf(screenA, screenBNew))   // new resourceId → changed
        scenario("2026-10-19", "settings", "notify", null, StepStatus.PASSED, listOf(screenA, screenB), traceAt = "2026-10-12") // acked 10/17, trace still 10/12
        scenario("2026-10-19", "player", "resume", null, StepStatus.PASSED, listOf(screenA, screenBMoved.map { it.copy(bounds = Bounds(0, 4, 100, 54)) })) // moved 4px < threshold → passed
        val acks = File(root, "acks.json").apply { writeText("""{"settings/notify": {"at": "2026-10-17", "by": "joseph", "layout_hash": "x"}}""") }

        val today = Site.load(File(runs, "2026-10-19"))
        val rows = Site.classify(today, Site.load(File(runs, "2026-10-18")), Site.readAcks(acks))
        assertEquals(
            mapOf("billing/coupon.member" to Site.Kind.FAILED, "billing/subscribe.nosub" to Site.Kind.CHANGED, "settings/notify" to Site.Kind.RERECORD, "player/resume" to Site.Kind.PASSED),
            rows.associate { it.id to it.kind },
        )
        val change = assertNotNull(rows.first { it.id == "billing/subscribe.nosub" }.change)
        assertEquals(2, change.step)
        assertEquals(listOf("price_badge"), change.added)
        // a moved element counts from 8px
        assertNotNull(LayoutJson.compare(2, LayoutJson.nodes(screenB), LayoutJson.nodes(screenBMoved)))
        assertNull(LayoutJson.compare(2, LayoutJson.nodes(screenB), LayoutJson.nodes(screenB.map { it.copy(label = "다른 텍스트") })), "text changes are not screen changes")

        // without a yesterday nothing is "changed"; the acknowledgement still holds
        val fresh = Site.classify(today, null, Site.readAcks(acks))
        assertEquals(listOf(Site.Kind.FAILED, Site.Kind.PASSED, Site.Kind.PASSED, Site.Kind.RERECORD), fresh.map { it.kind })
    }

    @Test
    fun `site - home cards, scenario page with the step timeline, yesterday thumbnails, latest redirect, slack`() {
        scenario("2026-10-17", "billing", "coupon", "member", StepStatus.PASSED, listOf(screenA, screenB))
        scenario("2026-10-18", "billing", "coupon", "member", StepStatus.PASSED, listOf(screenA, screenB))
        scenario("2026-10-18", "billing", "subscribe", "nosub", StepStatus.PASSED, listOf(screenA, screenB))
        scenario("2026-10-19", "billing", "coupon", "member", StepStatus.FAILED, listOf(screenA, screenB), video = true)
        scenario("2026-10-19", "billing", "subscribe", "nosub", StepStatus.PASSED, listOf(screenA, screenBNew))
        File(runs, "2026-10-19/run.json").writeText(RunSummaryJson.toJson(RunSummary("replay", "replay", "2026-10-19T02:10:00+09:00", 38 * 60_000L, "0.2.0", "4.21.0", "sdk", emptyList(), app = "WatchaPlay")).toString())
        val site = File(root, "site/app/2026-10-19")

        val built = SitePages.build(File(runs, "2026-10-19"), site, opts = SitePages.Options(repoUrl = "https://github.com/org/app"))

        val home = File(site, "index.html").readText()
        assertTrue("실패 1" in home && "화면 바뀜 1" in home && "통과 0" in home, home.substringAfter("<header"))
        assertTrue("10/18 nightly 기준 · 2건" in home)
        assertTrue("<article class=\"card fail\">" in home && "2. Tap the &quot;btn2&quot; button" in home && "evidence not on screen: toast" in home)
        assertTrue("2일 연속 통과 후 첫 실패" in home, "streak from the 7-day history")
        assertTrue("스텝 2: 새 resourceId 1개: price_badge · 테스트는 통과" in home)
        assertTrue("billing/subscribe.nosub/yesterday/02.jpg" in home && File(site, "billing/subscribe.nosub/yesterday/02.jpg").isFile, "yesterday's frame of the changed step")
        assertTrue("https://github.com/org/app/edit/main/report-state/WatchaPlay/acknowledged.json" in home && "issues/new?title=" in home)
        assertTrue("<span class=name>billing</span>" in home && "마지막 전부 통과 10/18" in home, home.substringAfter("기능별 상태").take(1500))
        assertTrue("WatchaPlay 4.21.0" in home && "총 소요 38m00s" in home)

        val page = File(site, "billing/coupon.member.html")
        assertTrue(page in built.pages)
        val detail = page.readText()
        assertTrue("<h1>쿠폰을 등록한다</h1>" in detail && "FAILED · 2/2" in detail && "trace 10/14 녹화" in detail)
        assertTrue("<video id=v src=\"coupon.member/replay.mp4\"" in detail && File(site, "billing/coupon.member/replay.mp4").isFile)
        assertTrue("data-step=1 data-video-ms=2500" in detail && "data-step=2 data-video-ms=3500" in detail, "each step knows where it is in the mp4")
        assertTrue("\"videoMs\":3500" in detail, "the player script gets the same timeline")
        assertTrue("<span class=segments id=segs><i class=\"passed\" data-step=1" in detail && "<i class=\"failed\" data-step=2" in detail)
        assertTrue(":: the &quot;list&quot; item should be shown" in detail, "criterion on its own line")
        assertTrue("+ list<br>+ toast" in detail && "· toast 없음" in detail, "expected vs found")
        assertTrue("CouponRepo: HTTP 422" in detail && "Later: unrelated" !in detail, "logcat ±2s around the failing tap")
        assertTrue("simul run billing/coupon.md --state member --mode llm" in detail)
        assertTrue("href=\"../../2026-10-18/billing/coupon.member.html\"" in detail, "yesterday's page")
        assertTrue("href=\"../index.html\"" in detail)
        assertTrue(File(site, "billing/coupon.member/steps/02.jpg").isFile)
        // a page without a recording shows the step frame instead
        assertTrue("<img id=still src=\"subscribe.nosub/steps/01.jpg\"" in File(site, "billing/subscribe.nosub.html").readText())
        assertTrue("url=2026-10-19/index.html" in File(root, "site/app/index.html").readText())

        val slack = SitePages.slack(Site.load(File(runs, "2026-10-19")), built.rows, "https://x/site")
        assertTrue("WatchaPlay nightly · 10/19 · 4.21.0" in slack && "*실패 1*  billing/쿠폰을 등록한다  @nate" in slack && "*화면 바뀜 1*" in slack && "통과 0 · 38m00s" in slack, slack)
    }

    @Test
    fun `report command builds the site from --runs and the slack summary, and --check fails on a failure`() {
        scenario("2026-10-19", "billing", "coupon", null, StepStatus.FAILED, listOf(screenA, screenB))
        assertEquals(1, reportCommand(listOf("--runs", "runs/app/2026-10-19", "--out", "site/app/2026-10-19", "--check"), root))
        assertTrue(File(root, "site/app/2026-10-19/billing/coupon.html").isFile)
        assertEquals(0, reportCommand(listOf("--runs", "runs/app/2026-10-19", "--format", "slack", "--out", "slack.json"), root))
        assertTrue("실패 1" in File(root, "slack.json").readText())
        assertEquals(2, reportCommand(listOf("--format", "html", "x.json"), root), "html needs --runs")
    }

    @Test
    fun `runs layout - one directory per scenario and state, replaced on rerun`() {
        val p = SimulProject(root.also { File(it, ".simul/scenarios/billing").mkdirs(); File(it, ".simul/config.yaml").writeText("app: com.x\n") })
        val md = File(p.scenariosDir, "billing/coupon.md").apply { writeText("---\nname: coupon\nstates: [member]\n---\n1. a\n") }
        val sc = ScenarioMd.parse(md).variants().single()
        val dir = ReportDirs.inRuns(File(root, "runs/x"), p, md, sc)
        assertEquals(File(root, "runs/x/billing/coupon.member"), dir)
        File(dir, "stale.txt").writeText("x")
        assertTrue(!File(ReportDirs.inRuns(File(root, "runs/x"), p, md, sc), "stale.txt").exists())
        val rootMd = File(p.scenariosDir, "smoke.md").apply { writeText("---\nname: smoke\n---\n1. a\n") }
        assertEquals(File(root, "runs/x/smoke"), ReportDirs.inRuns(File(root, "runs/x"), p, rootMd, ScenarioMd.parse(rootMd)))
    }
}
