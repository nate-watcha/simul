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
import kotlin.test.assertTrue

/**
 * The HTML report pages from a runs directory: result.json in, index + scenario pages out. The fixtures
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
            "         ${(t0 + 1600) / 1000}.600  1566  1600 V FA-SVC  : Logging event: origin=app,name=w_coupon_apply,params=Bundle[{w_code=X, ga_screen_class(_sc)=MainNavActivity}]\n" +
            "         ${(t0 + 9000) / 1000}.000   672   833 I Later: unrelated\n" +
            "         ${(t0 + 9000) / 1000}.500  1566  1600 V FA-SVC  : Logging event: origin=auto,name=user_engagement,params=Bundle[{engagement_time_msec=8000}]\n")
        val r = ScenarioRunResult(name, status, steps, null, 2000, startedAtMs = t0, setupFailed = setupFailed, reportDir = dir, video = vid)
        ResultJson.write(r, "replay", ResultJson.Meta(feature, name, state, "$feature/$name.md", "쿠폰을 등록한다", "nate", "설정에서 쿠폰 메뉴로 들어간다.", traceAt,
            ResultJson.Env("4.21.0", "sdk_gphone64", "1.0.1")), dir)
    }

    private val screenA = listOf(el("설정", resourceId = "tab_settings", bounds = Bounds(0, 0, 100, 50), center = Point(50, 25)), el(resourceId = "btn_apply", bounds = Bounds(0, 100, 100, 150), center = Point(50, 125), id = 1))
    private val screenB = listOf(el("목록", resourceId = "coupon_list_item", bounds = Bounds(0, 0, 100, 50), center = Point(50, 25)))
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
        // the Firebase events of the scenario, on the step they fell in: step 2 began at +1000 and nothing came after it
        assertEquals(emptyList(), r.steps[0].events)
        assertEquals(listOf("w_coupon_apply", "user_engagement"), s2.events.map { it.name })
        assertEquals(ResultJson.Event(1600, 4100, "app", "w_coupon_apply", mapOf("w_code" to "X", "ga_screen_class(_sc)" to "MainNavActivity")), s2.events[0])
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
        val rows = Site.classify(Site.load(File(runs, "2026-10-19")))
        assertEquals(Site.Kind.SETUP, rows.single().kind)
    }

    @Test
    fun `pages - home with failure cards, scenario page with the step timeline, in place or copied`() {
        scenario("2026-10-19", "billing", "coupon", "member", StepStatus.FAILED, listOf(screenA, screenB), video = true)
        scenario("2026-10-19", "billing", "subscribe", "nosub", StepStatus.PASSED, listOf(screenA, screenBNew))
        scenario("2026-10-19", "auth", "login", null, StepStatus.FAILED, emptyList(), setupFailed = true)
        File(runs, "2026-10-19/run.json").writeText(RunSummaryJson.toJson(RunSummary("verify", "replay", "2026-10-19T02:10:00+09:00", 38 * 60_000L, "0.2.0", "4.21.0", "sdk", emptyList(), app = "WatchaPlay")).toString())
        val site = File(root, "site/2026-10-19")

        val built = SitePages.build(File(runs, "2026-10-19"), site)
        assertEquals("실패 1 · 안 돌림 1 · 통과 1", built.counts())

        val home = File(site, "index.html").readText()
        assertTrue("실패 1" in home && "안 돌림 1" in home && "통과 1" in home, home.substringAfter("<header"))
        assertTrue("<article class=\"card fail\">" in home && "2. Tap the &quot;btn2&quot; button" in home && "evidence not on screen: toast" in home)
        assertTrue("billing/coupon.member/steps/02.jpg" in home, "the failure card shows the failed step's frame")
        assertTrue("setup failed: deeplink not handled" in home)
        assertTrue("<span class=name>billing</span>" in home && "WatchaPlay 4.21.0" in home && "총 소요 38m00s" in home)
        assertTrue("어제" !in home && "7일" !in home && "acknowledged" !in home, "no history, no site machinery")

        val page = File(site, "billing/coupon.member.html")
        assertTrue(page in built.pages)
        val detail = page.readText()
        assertTrue("<h1>쿠폰을 등록한다</h1>" in detail && "FAILED · 2/2" in detail)
        assertTrue("<video id=v src=\"coupon.member/replay.mp4\"" in detail && File(site, "billing/coupon.member/replay.mp4").isFile)
        assertTrue("data-step=1 data-video-ms=2500" in detail && "data-step=2 data-video-ms=3500" in detail, "each step knows where it is in the mp4")
        assertTrue("\"videoMs\":3500" in detail, "the player script gets the same timeline")
        assertTrue("<span class=segments id=segs><i class=\"passed\" data-step=1" in detail && "<i class=\"failed\" data-step=2" in detail)
        assertTrue(":: the &quot;list&quot; item should be shown" in detail, "criterion on its own line")
        assertTrue("+ list<br>+ toast" in detail && "· toast 없음" in detail, "expected vs found")
        assertTrue("CouponRepo: HTTP 422" in detail && "Later: unrelated" !in detail, "logcat ±2s around the failing tap")
        assertTrue("firebase 2</span> w_coupon_apply · user_engagement" in detail && "w_code=X" in detail, "the step's Firebase events, folded under it")
        assertTrue("href=\"../index.html\"" in detail)
        assertTrue(File(site, "billing/coupon.member/steps/02.jpg").isFile)
        // a page without a recording shows the step frame instead
        assertTrue("<img id=still src=\"subscribe.nosub/steps/01.jpg\"" in File(site, "billing/subscribe.nosub.html").readText())

        // the LLM re-recording that preceded the replay: a failed recording is a failure even though the committed trace replays
        val rec = RunSummary("record", "llm", "2026-10-19T01:00:00+09:00", 20 * 60_000L, "0.2.0", "4.21.0", "sdk", listOf(
            ScenarioOutcome("coupon@member", "billing/coupon.md", emptyList(), "PASSED", "llm", 60_000, "r1", true, 2, 0, 0, null, null),
            ScenarioOutcome("subscribe@nosub", "billing/subscribe.md", emptyList(), "FAILED", "llm", 5_000, "r2", false, 1, 1, 0,
                FailedStep(2, "Tap the \"구독\" button", "agent error: Error from client: OpenAILLMClient Status code: 500", null), null),
        ), app = "WatchaPlay")
        File(runs, "2026-10-19/record.json").writeText(RunSummaryJson.toJson(rec).toString())
        val withRecord = SitePages.build(File(runs, "2026-10-19"), File(root, "site2"))
        assertEquals("실패 2 (녹화 실패 1) · 안 돌림 1 · 통과 0", withRecord.counts())
        val home2 = File(root, "site2/index.html").readText()
        assertTrue("녹화 실패 1</span>" in home2 && "LLM 녹화: step 2 `Tap the &quot;구독&quot; button` — agent error: Error from client: OpenAILLMClient Status code: 500" in home2, home2.substringAfter("<header"))
        assertTrue("1/2 녹화됨" in home2)
        val sub2 = File(root, "site2/billing/subscribe.nosub.html").readText()
        assertTrue("녹화 실패</span>" in sub2 && "trace는 커밋본 그대로, 아래는 그 재생" in sub2)
        assertTrue("LLM 녹화: PASSED" in File(root, "site2/billing/coupon.member.html").readText())

        // built inside the run directory (what `simul run --runs` does): the scenario directory is the asset directory, the mp4 is not copied onto itself
        val inPlace = SitePages.build(File(runs, "2026-10-19"), File(runs, "2026-10-19"))
        assertTrue(File(runs, "2026-10-19/index.html").isFile && File(runs, "2026-10-19/billing/coupon.member.html").isFile && File(runs, "2026-10-19/billing/coupon.member/steps/02.jpg").isFile)
        assertEquals(3, inPlace.pages.size)
    }

    /**
     * What `simul report` leaves of the LLM recording of one scenario under `<stamp>/record/`: steps up to [failedAt] passed,
     * that one failed after a tap (its before/after frames in screenshots/), the rest skipped.
     */
    private fun recording(date: String, feature: String, name: String, failedAt: Int, total: Int, reason: String) {
        val dir = File(runs, "$date/record/$feature/$name").apply { mkdirs() }
        val t0 = 1_792_000_100_000L
        fun png(rel: String) = ImageIO.write(BufferedImage(72, 128, BufferedImage.TYPE_INT_RGB), "png", File(dir, rel).also { it.parentFile.mkdirs() })
        val steps = (1..total).map { n ->
            when {
                n < failedAt -> { png("steps/0$n.png"); StepReport(n, "Tap the \"btn$n\" button", StepStatus.PASSED, "llm", emptyList(), listOf("screen$n"), null, 3000, listOf("steps/0$n.png"), startedMs = (n - 1) * 3000L, marks = listOf(ActionMark("tap", "btn$n", t0 + (n - 1) * 3000L + 500))) }
                n == failedAt -> {
                    png("screenshots/step$n-1-tap-before.png"); png("screenshots/step$n-1-tap-after.png"); png("screenshots/step$n-2-scroll-after.png"); png("steps/0$n.png")
                    StepReport(n, "Tap the \"보고싶어요\" button :: \"다른 방법으로 계속하기\" is shown", StepStatus.FAILED, "llm", emptyList(), emptyList(), reason, 9000,
                        listOf("screenshots/step$n-1-tap-before.png", "screenshots/step$n-1-tap-after.png", "screenshots/step$n-2-scroll-after.png", "steps/0$n.png"),
                        screen = listOf("호프", "보고싶어요"), startedMs = (n - 1) * 3000L, expectedEvidence = listOf("다른 방법으로 계속하기"),
                        marks = listOf(ActionMark("tap", "보고싶어요", t0 + (n - 1) * 3000L + 700), ActionMark("scroll", "scroll@0", t0 + (n - 1) * 3000L + 4000)))
                }
                else -> StepReport(n, "Tap the \"btn$n\" button", StepStatus.SKIPPED, null, emptyList(), emptyList(), null, 0, emptyList())
            }
        }
        // within 2s of the step's last gesture (the scroll at +4000)
        File(dir, "logcat.txt").writeText("         ${(t0 + (failedAt - 1) * 3000L + 4500) / 1000}.000   672   833 E LoginSheet: not shown\n")
        // the recording is screen-recorded too; its mp4 started 1.5s before the scenario
        File(dir, "replay.mp4").writeText("mp4")
        val r = ScenarioRunResult(name, StepStatus.FAILED, steps, null, 12000, startedAtMs = t0, reportDir = dir, video = VideoInfo(File(dir, "replay.mp4"), t0 - 1500, 13500))
        ResultJson.write(r, "llm", ResultJson.Meta(feature, name, null, "$feature/$name.md", name, "nate", "", null, ResultJson.Env("4.21.0", "sdk", "1.0.1")), dir)
    }

    @Test
    fun `a failed recording is told by its own timeline - the replay of yesterday's trace, if any, folds under it`() {
        // run 39395891887: the LLM failed 7 recordings, nothing was committed, the replay skipped them (no trace) and
        // wrote no directory — and the index showed 7 passes and nothing else. And when a trace *was* committed, the page
        // showed yesterday's replay as if it were tonight's failure, with one frame of the recording.
        scenario("2026-10-20", "auth", "login", null, StepStatus.PASSED, listOf(screenA, screenBNew))
        val run = RunSummary("verify", "replay", "2026-10-20T02:10:00+09:00", 60_000L, "0.2.0", "4.21.0", "sdk", listOf(
            ScenarioOutcome("auth-login", "auth/login.md", emptyList(), "PASSED", "replay", 20_000, "auth/login", false, 2, 0, 0, null, null),
            ScenarioOutcome("auth-wish-wall", "auth/wish-wall.md", emptyList(), "SKIPPED", null, 0, null, false, 0, 0, 3, null, "no trace", description = "게스트가 보고싶어요를 누르면 로그인 시트가 뜬다."),
        ), app = "WatchaPlay")
        File(runs, "2026-10-20/run.json").writeText(RunSummaryJson.toJson(run).toString())
        recording("2026-10-20", "auth", "wish-wall", failedAt = 2, total = 3, reason = "criterion anchor not on screen: \"다른 방법으로 계속하기\"")
        recording("2026-10-20", "auth", "login", failedAt = 1, total = 2, reason = "agent error: Error from client: OpenAILLMClient Status code: 500")
        val rec = RunSummary("record", "llm", "2026-10-20T01:00:00+09:00", 20 * 60_000L, "0.2.0", "4.21.0", "sdk", listOf(
            ScenarioOutcome("auth-login", "auth/login.md", emptyList(), "FAILED", "llm", 4_000, "r1", false, 0, 1, 1, FailedStep(1, "Tap the \"btn1\" button", "agent error: Error from client: OpenAILLMClient Status code: 500", null), null),
            ScenarioOutcome("auth-wish-wall", "auth/wish-wall.md", emptyList(), "FAILED", "llm", 23_889, "r2", false, 1, 1, 1,
                FailedStep(2, "Tap the \"보고싶어요\" button", "criterion anchor not on screen: \"다른 방법으로 계속하기\"", ".simul/reports/20261020-0100/record/auth/wish-wall/steps/02.png", listOf("호프", "보고싶어요")), null),
        ), app = "WatchaPlay")
        File(runs, "2026-10-20/record.json").writeText(RunSummaryJson.toJson(rec).toString())

        val loaded = Site.load(File(runs, "2026-10-20"))
        assertEquals(listOf("auth/login", "auth/wish-wall"), loaded.results.map { it.id }, "the recordings under record/ are not replay rows")
        assertEquals(setOf("auth/login", "auth/wish-wall"), loaded.recordings.keys)
        val rows = Site.classify(loaded)
        assertEquals(listOf("auth/login" to Site.Kind.FAILED, "auth/wish-wall" to Site.Kind.FAILED), rows.map { it.id to it.kind })
        assertTrue(rows[1].recordFailed && !rows[1].replayed && rows[1].recordTimeline != null)
        assertEquals("step 2 `Tap the \"보고싶어요\" button` — criterion anchor not on screen: \"다른 방법으로 계속하기\"", rows[1].recordFailure)

        val built = SitePages.build(File(runs, "2026-10-20"), File(runs, "2026-10-20"))
        assertEquals("실패 2 (녹화 실패 2) · 통과 0", built.counts())
        val home = File(runs, "2026-10-20/index.html").readText()
        val card = home.split("<article").first { "auth-wish-wall</a>" in it }.substringBefore("</article>")
        // the card is the recording: how far the agent got, the step it stopped on, the frame it stopped on; the replay is one dim line
        assertTrue("3스텝 중 2에서 멈춤" in card && "<i class=\"passed\" title=\"1." in card && "<i class=\"failed\" title=\"2." in card && "<i class=\"skipped\" title=\"3." in card, card)
        assertTrue("2. Tap the &quot;보고싶어요&quot; button" in card && "criterion anchor not on screen" in card && "재생 없음 · no trace" in card, card)
        assertTrue("src=\"auth/wish-wall/record/02.jpg\"" in card && File(runs, "2026-10-20/auth/wish-wall/record/02.jpg").isFile, card)
        assertTrue("2스텝 중 1에서 멈춤" in home && "커밋 trace 재생 · PASSED" in home, "login: recorded nothing, yesterday's trace still replays")

        val detail = File(runs, "2026-10-20/auth/wish-wall.html").readText()
        assertTrue("녹화 실패 · 2/3</span>" in detail && "오늘 녹화 (LLM)" in detail && "3스텝 중 2에서 멈춤" in detail, detail.substringAfter("<header"))
        // the timeline is the recording's: the passed step, the failed one expanded with what the agent did there, the rest skipped
        assertTrue("<div class=\"step passed\" data-step=1" in detail && "→ btn1 · + screen1 · 3.0s" in detail)
        assertTrue("<div class=\"step failed\" data-step=2" in detail && "→ scroll@0 · FAILED · criterion anchor not on screen" in detail)
        assertTrue("기대 (시나리오)" in detail && "+ 다른 방법으로 계속하기" in detail && "· 다른 방법으로 계속하기 없음" in detail && "· 호프" in detail)
        assertTrue("에이전트가 한 일" in detail && "tap 보고싶어요 → scroll scroll@0" in detail)
        assertTrue("src=\"wish-wall/record/02-1.jpg\"" in detail && "<figcaption>1 tap</figcaption>" in detail && "src=\"wish-wall/record/02-2.jpg\"" in detail && "<figcaption>2 scroll</figcaption>" in detail, "the frame after each action of the failed step")
        assertTrue(File(runs, "2026-10-20/auth/wish-wall/record/02-1.jpg").isFile)
        assertTrue("LoginSheet: not shown" in detail, "logcat from the recording's directory")
        assertTrue("<div class=\"step skipped\" data-step=3" in detail && "<i class=\"skipped\" data-step=3" in detail, "the skipped tail is on the timeline and the bar")
        assertTrue("<video id=v src=\"../record/auth/wish-wall/replay.mp4\"" in detail && "0:00 / 0:13" in detail, "the player is the recording's own mp4, linked where it lies")
        assertTrue("data-step=2 data-video-ms=4500" in detail && "\"thumb\":\"wish-wall/record/02.jpg\"" in detail, "steps seek into it")
        assertTrue(!File(runs, "2026-10-20/auth/wish-wall/record/replay.mp4").exists(), "built in place: not copied")
        // built elsewhere, the mp4 is copied beside the page
        SitePages.build(File(runs, "2026-10-20"), File(root, "elsewhere"))
        assertTrue(File(root, "elsewhere/auth/wish-wall/record/replay.mp4").isFile && "<video id=v src=\"wish-wall/record/replay.mp4\"" in File(root, "elsewhere/auth/wish-wall.html").readText())
        assertTrue("커밋된 trace가 없어 재생하지 않음" in detail && "<details class=replaysec" !in detail)

        // a committed trace that still replays folds under the failed recording — static rows, not the player's
        val login = File(runs, "2026-10-20/auth/login.html").readText()
        assertTrue("녹화 실패 · 1/2</span>" in login && "<details class=replaysec><summary>커밋된 trace의 재생 · PASSED · 2/2" in login, login.substringAfter("<header"))
        assertTrue("<div class=\"step passed static\"><div class=line>" in login && "step passed\" data-step=1" !in login, "the replay's steps do not drive the player")
    }

    @Test
    fun `report command writes the pages from --runs, in place by default, and --check fails on a failure`() {
        scenario("2026-10-19", "billing", "coupon", null, StepStatus.FAILED, listOf(screenA, screenB))
        assertEquals(1, reportCommand(listOf("--runs", "runs/app/2026-10-19", "--check"), root))
        assertTrue(File(root, "runs/app/2026-10-19/index.html").isFile && File(root, "runs/app/2026-10-19/billing/coupon.html").isFile)
        assertEquals(0, reportCommand(listOf("--runs", "runs/app/2026-10-19", "--out", "site/x"), root))
        assertTrue(File(root, "site/x/billing/coupon.html").isFile)
        assertEquals(2, reportCommand(listOf("--runs", "runs/app/2026-10-19", "--format", "slack", "--out", "x.json"), root), "no slack from a runs directory")
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
