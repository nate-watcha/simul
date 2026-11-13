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
            ResultJson.Env("4.21.0", "sdk_gphone64", "1.0.1"), setup = ResultJson.Setup("app://$feature", false, state)), dir)
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
        assertEquals(ResultJson.Setup("app://billing", false, "member"), r.setup, "how the scenario is entered")
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
    fun `pages - home as a checklist by screen, a spec page per screen, scenario page with the step timeline, in place or copied`() {
        scenario("2026-10-19", "billing", "coupon", "member", StepStatus.FAILED, listOf(screenA, screenB), video = true)
        scenario("2026-10-19", "billing", "subscribe", "nosub", StepStatus.PASSED, listOf(screenA, screenBNew))
        scenario("2026-10-19", "auth", "login", null, StepStatus.FAILED, emptyList(), setupFailed = true)
        File(runs, "2026-10-19/run.json").writeText(RunSummaryJson.toJson(RunSummary("verify", "replay", "2026-10-19T02:10:00+09:00", 38 * 60_000L, "0.2.0", "4.21.0", "sdk", emptyList(), app = "WatchaPlay")).toString())
        val site = File(root, "site/2026-10-19")

        val built = SitePages.build(File(runs, "2026-10-19"), site)
        assertEquals("실패 1 · 안 돌림 1 · 통과 1", built.counts())

        val home = File(site, "index.html").readText()
        assertTrue("<h1>실패 1</h1>" in home && "안 돌림 1" in home && "통과 1" in home, home.substringAfter("<header"))
        // counted by what the scenarios confirm, and by screen
        assertTrue("확인 3개 중 1개 통과 · 시나리오 3 · 화면 2 · 총 소요 38m00s" in home && "WatchaPlay 4.21.0" in home, home.substringAfter("<header").substringBefore("</header>"))
        // what has to be looked at: where the failure is, the step it stopped on, why — in words — and its frame
        val failure = home.split("<article").first { "쿠폰을 등록한다</a>" in it }.substringBefore("</article>")
        assertTrue("<a href=\"billing/_screen.html#coupon.member\">billing</a>" in failure && "2. Tap the <span class=q>&quot;btn2&quot;</span> button" in failure, failure)
        assertTrue("녹화 때 있던 것이 화면에 없음: toast" in failure && "replay broken" !in failure, failure)
        assertTrue("billing/coupon.member/steps/02.jpg" in failure, "the failure row shows the failed step's frame")
        assertTrue("<code class=e>app://billing</code>" in failure && "member · " in failure, "and how the scenario is entered")
        assertTrue("setup failed: deeplink not handled — 앱 판정이 아니라 환경 문제입니다." in home)
        // the whole run: a row a screen (failures first), its scenarios under it with what each confirms
        val table = home.substringAfter("<table class=scr>")
        assertTrue(table.indexOf("billing/_screen.html") < table.indexOf("auth/_screen.html"), "the screen with a failure first")
        val billing = table.split("<tbody").first { "billing/_screen.html" in it }
        assertTrue("class=\"g failed open\"" in billing && "<td class=r>2</td><td class=r><b class=\"bad\">1/2</b></td>" in billing, billing)
        assertTrue("<i class=\"failed\"></i>" in billing && "<i class=\"passed\"></i>" in billing, "a cell per check")
        assertTrue("<tr class=\"sub failed\">" in billing && "<tr class=\"sub passed\">" in billing && "설정에서 쿠폰 메뉴로 들어간다." in billing, "a scenario without sections is its own check, its prose the sentence")
        assertTrue("id=onlyfail" in home && "id=q" in home)
        assertTrue("어제" !in home && "7일" !in home && "acknowledged" !in home, "no history, no site machinery")

        // a screen's page: where its scenarios start, then each scenario as a line that opens — a failed one open on its steps
        val screen = File(site, "billing/_screen.html")
        assertTrue(screen in built.screens && built.screens.size == 2)
        val spec = screen.readText()
        assertTrue("<h1>billing</h1>" in spec && "<code class=e>app://billing</code><span>member 1</span><span>nosub 1</span>" in spec, spec.substringAfter("<header"))
        assertTrue("시나리오 2 · 확인 2 · <b class=bad>실패 1</b>" in spec)
        assertTrue("<details class=\"ft failed\" id=\"coupon.member\" open><summary>" in spec && "<b class=fn>쿠폰을 등록한다</b>" in spec &&
            "<a class=more href=\"../billing/coupon.member.html\">스텝·영상 ›</a>" in spec, spec.substringAfter("<section"))
        assertTrue("<h3>이 화면에서</h3>" in spec && "<h3>다른 계정으로 보면 <code class=e>nosub</code></h3>" in spec, "the scenarios of another account under that account")
        assertTrue("들어오는 길" !in spec, "no way in of its own and no other screen that walks here: where its scenarios start is in the header")
        assertTrue("<li class=\"failed\">Tap the <span class=q>&quot;btn2&quot;</span> button<br><span class=why>녹화 때 있던 것이 화면에 없음: toast</span>" in spec, spec.substringAfter("<section"))
        assertTrue("<a class=on href=\"../billing/_screen.html\"" in spec && "href=\"../auth/_screen.html\"" in spec, "the other screens beside it")
        assertTrue("_screen.jpg" !in spec, "no recording ffmpeg can read: no entry frame")
        assertTrue("setup failed: deeplink not handled" in File(site, "auth/_screen.html").readText())

        val page = File(site, "billing/coupon.member.html")
        assertTrue(page in built.pages)
        val detail = page.readText()
        assertTrue("<h1>쿠폰을 등록한다</h1>" in detail && "FAILED · 2/2" in detail)
        assertTrue("<video src=\"coupon.member/replay.mp4\"" in detail && File(site, "billing/coupon.member/replay.mp4").isFile)
        assertTrue("data-step=1 data-video-ms=2500" in detail && "data-step=2 data-video-ms=3500" in detail, "each step knows where it is in the mp4")
        assertTrue("\"videoMs\":3500" in detail, "the player script gets the same timeline")
        assertTrue("<span class=segments><i class=\"passed\" data-step=1" in detail && "<i class=\"failed\" data-step=2" in detail)
        assertTrue(":: the &quot;list&quot; item should be shown" in detail, "criterion on its own line")
        assertTrue("+ list<br>+ toast" in detail && "· toast 없음" in detail, "expected vs found")
        assertTrue("CouponRepo: HTTP 422" in detail && "Later: unrelated" !in detail, "logcat ±2s around the failing tap")
        assertTrue("firebase 2</span> w_coupon_apply · user_engagement" in detail && "w_code=X" in detail, "the step's Firebase events, folded under it")
        assertTrue("href=\"../index.html\"" in detail && "href=\"../billing/_screen.html#coupon.member\"" in detail, "back to the run and to its screen")
        assertTrue(File(site, "billing/coupon.member/steps/02.jpg").isFile)
        // a page without a recording shows the step frame instead
        assertTrue("<img class=still src=\"subscribe.nosub/steps/01.jpg\"" in File(site, "billing/subscribe.nosub.html").readText())

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
        assertTrue("<h1>실패 2</h1>" in home2 && "녹화 실패 1</span>" in home2 && "LLM 녹화: step 2 `Tap the &quot;구독&quot; button` — agent error: Error from client: OpenAILLMClient Status code: 500" in home2, home2.substringAfter("<header"))
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
        assertTrue("<video src=\"../record/auth/wish-wall/replay.mp4\"" in detail && "0:00 / 0:13" in detail, "the player is the recording's own mp4, linked where it lies")
        assertTrue("data-step=2 data-video-ms=4500" in detail && "\"thumb\":\"wish-wall/record/02.jpg\"" in detail, "steps seek into it")
        assertTrue(!File(runs, "2026-10-20/auth/wish-wall/record/replay.mp4").exists(), "built in place: not copied")
        // built elsewhere, the mp4 is copied beside the page
        SitePages.build(File(runs, "2026-10-20"), File(root, "elsewhere"))
        assertTrue(File(root, "elsewhere/auth/wish-wall/record/replay.mp4").isFile && "<video src=\"wish-wall/record/replay.mp4\"" in File(root, "elsewhere/auth/wish-wall.html").readText())
        assertTrue("커밋된 trace가 없어 재생하지 않음" in detail && "<details class=replaysec" !in detail)

        // a committed trace that still replays folds under the failed recording — static rows, not the player's
        val login = File(runs, "2026-10-20/auth/login.html").readText()
        assertTrue("녹화 실패 · 1/2</span>" in login && "<details class=replaysec><summary>커밋된 trace의 재생 · PASSED · 2/2" in login, login.substringAfter("<header"))
        assertTrue("<div class=\"step passed static\"><div class=line>" in login && "step passed\" data-step=1" !in login, "the replay's steps do not drive the player")
    }

    @Test
    fun `a scenario with sections counts as its sections - each a check with its sentence, the preparation none`() {
        val dir = File(runs, "2026-11-10/my-other/follow").apply { mkdirs() }
        fun st(n: Int, text: String, status: StepStatus, reason: String? = null) =
            StepReport(n, text, status, if (status == StepStatus.SKIPPED) null else "replay", emptyList(), emptyList(), reason, 500, emptyList(), startedMs = n * 1000L)
        val steps = listOf(
            st(1, "팔로우하고 있지 않으면 \"팔로우\"를 눌러 팔로우한다", StepStatus.PASSED),
            st(2, "\"팔로우 취소\"를 누른다", StepStatus.PASSED), st(3, "\"팔로우\"가 보이는지 확인한다", StepStatus.PASSED),
            st(4, "\"팔로우\"를 누른다", StepStatus.PASSED), st(5, "\"팔로우 취소\"가 보이는지 확인한다", StepStatus.FAILED, "replay broken: evidence not on screen: 팔로우 취소"),
            st(6, "\"팔로우 취소\"를 누른다", StepStatus.SKIPPED),
        )
        val sections = listOf(
            SectionReport("준비", "", StepStatus.PASSED, 1..1, preparation = true),
            SectionReport("팔로우 취소", "\"팔로우 취소\"를 누르면 \"팔로우\"로 바뀐다.", StepStatus.PASSED, 2..3),
            SectionReport("팔로우 추가", "팔로우하지 않은 유저를 팔로우할 수 있다.", StepStatus.FAILED, 4..5),
            SectionReport("다시 취소", "한 번 더 취소할 수 있다.", StepStatus.SKIPPED, 6..6),
        )
        ResultJson.write(ScenarioRunResult("팔로우", StepStatus.FAILED, steps, null, 9000, startedAtMs = 1_794_000_000_000L, reportDir = dir, sections = sections), "replay",
            ResultJson.Meta("my-other", "follow", null, "my-other/follow.md", "팔로우", null, "타인 프로필의 팔로우 버튼은 누를 때마다 바뀐다.", "2026-11-09",
                ResultJson.Env("6.4.0", "sdk", "1.1"), setup = ResultJson.Setup("app://users/42", false, "member")), dir)

        val row = Site.classify(Site.load(File(runs, "2026-11-10"))).single()
        assertEquals(listOf("팔로우 취소" to "PASSED", "팔로우 추가" to "FAILED", "다시 취소" to "SKIPPED"), row.checks.map { it.title to it.status })
        assertEquals(listOf(2, 3), row.checks[0].steps.map { it.n })
        val screen = Site.screens(listOf(row)).single()
        assertEquals("app://users/42" to mapOf("member" to 1), screen.entries.single().let { it.entry to it.states })

        SitePages.build(File(runs, "2026-11-10"), File(runs, "2026-11-10"))
        val home = File(runs, "2026-11-10/index.html").readText()
        assertTrue("확인 3개 중 1개 통과 · 시나리오 1 · 화면 1" in home, home.substringAfter("<header").substringBefore("</header>"))
        assertTrue("<span>›</span><span>팔로우 추가</span>" in home && "<div class=mono>2. <span class=q>&quot;팔로우 취소&quot;</span>가 보이는지 확인한다</div>" in home, "the failure names its section, the step by its number there")
        assertTrue("<b>팔로우 추가</b> 팔로우하지 않은 유저를 팔로우할 수 있다.<br><span class=reason>녹화 때 있던 것이 화면에 없음: 팔로우 취소</span>" in home)
        val spec = File(runs, "2026-11-10/my-other/_screen.html").readText()
        assertTrue("<p class=spd>타인 프로필의 팔로우 버튼은 누를 때마다 바뀐다.</p>" in spec && "<p class=prep>준비 · 팔로우하고 있지 않으면" in spec, spec.substringAfter("<section"))
        assertTrue("<li class=\"passed v\"><span class=q>&quot;팔로우&quot;</span>가 보이는지 확인한다</li>" in spec, "a passed check step is set apart from the steps that get there")
        assertTrue("<b>팔로우 취소</b>&quot;팔로우 취소&quot;를 누르면 &quot;팔로우&quot;로 바뀐다." in spec, "a step's quotes are set apart, a sentence's are not")
        assertTrue("<details class=\"ck skipped\"><summary>" in spec && "<b>다시 취소</b>" in spec, "a section that was not reached is listed, not open")
        assertTrue("<span class=\"fs titles\"><span class=passed>팔로우 취소</span> · <span class=failed>팔로우 추가</span> · <span class=skipped>다시 취소</span></span><span class=ckn>확인 3</span>" in spec,
            "closed, a scenario of several checks is its sections' titles")
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

    @Test
    fun `a check that mentions another screen shows on that screen's page too, and a screen's way in heads its page`() {
        val day = File(runs, "2026-11-12")
        fun st(n: Int, text: String, status: StepStatus, reason: String? = null) =
            StepReport(n, text, status, if (status == StepStatus.SKIPPED) null else "replay", emptyList(), emptyList(), reason, 500, emptyList(), startedMs = n * 1000L)
        fun write(feature: String, name: String, title: String, prose: String, setup: ResultJson.Setup, steps: List<StepReport>, sections: List<SectionReport> = emptyList(), mentions: List<String> = emptyList()) {
            val dir = File(day, "$feature/$name").apply { mkdirs() }
            val status = if (steps.any { it.status == StepStatus.FAILED }) StepStatus.FAILED else StepStatus.PASSED
            ResultJson.write(ScenarioRunResult(title, status, steps, null, 5000, startedAtMs = 1_794_000_000_000L, reportDir = dir, sections = sections), "replay",
                ResultJson.Meta(feature, name, null, "$feature/$name.md", title, null, prose, "2026-11-12", ResultJson.Env("6.4.0", "sdk", "1.1"), setup = setup, mentions = mentions), dir)
        }
        // 나의왓챠-활동: a way in with a step that moves; a scenario that starts from it and walks to 작품 상세
        write("나의왓챠-활동", "_entry", "_entry", "나의 왓챠에서 \"활동\" 탭을 누르면 열린다.", ResultJson.Setup("app://my", false, "member"),
            listOf(st(1, "\"활동\" 탭을 누른다", StepStatus.PASSED), st(2, "\"활동\" 탭이 selected 상태인지 확인한다", StepStatus.PASSED)))
        write("나의왓챠-활동", "posters", "포스터", "포스터를 누르면 그 작품의 @작품 상세로 간다.", ResultJson.Setup("app://my", false, "member", via = "나의왓챠-활동"),
            listOf(st(1, "\"재혼 황후\" 포스터를 누른다", StepStatus.PASSED)), mentions = listOf("작품 상세"))
        // 작품 상세: its address opens it. A comment is added, looked for on the activity tab — not there — and removed all the same.
        write("작품 상세", "_entry", "_entry", "", ResultJson.Setup("app://contents/1", false, "member"), listOf(st(1, "작품 제목이 보이는지 확인한다", StepStatus.PASSED)))
        write("작품 상세", "comment", "코멘트 추가와 삭제", "작품 상세에서 코멘트를 쓰고 지우기.", ResultJson.Setup("app://contents/1", false, "member", via = "작품 상세"),
            listOf(st(1, "\"등록\"을 누른다", StepStatus.PASSED), st(2, "\"방금 쓴 코멘트\"가 보이는지 확인한다", StepStatus.FAILED, "replay broken: evidence not on screen: 방금 쓴 코멘트"),
                st(3, "\"삭제\"를 누른다", StepStatus.PASSED)),
            listOf(SectionReport("추가", "코멘트를 써서 등록하면 보인다.", StepStatus.PASSED, 1..1),
                SectionReport("추가 뒤 활동 탭", "@나의왓챠-활동 코멘트 영역 맨 앞에 나온다.", StepStatus.FAILED, 2..2,
                    open = Scenario.Open(screen = "나의왓챠-활동", appState = "member-empty"), mentions = listOf("나의왓챠-활동")),
                SectionReport("삭제", "코멘트를 지우면 사라진다.", StepStatus.PASSED, 3..3)))
        // 검색: its way in failed, so its scenario was not run — one failure, not two
        write("검색", "_entry", "_entry", "", ResultJson.Setup("app://search", false, "member"), listOf(st(1, "\"검색\" 탭을 누른다", StepStatus.FAILED, "replay broken: evidence not on screen: 최근 검색어")))
        File(day, "run.json").writeText(RunSummaryJson.toJson(RunSummary("verify", "replay", "2026-11-12T02:10:00+09:00", 60_000L, "0.2.0", "6.4.0", "sdk", listOf(
            ScenarioOutcome("검색어 입력", "검색/query.md", emptyList(), "SKIPPED", null, 0, null, false, 0, 0, 2, null, "${Site.WAY_IN_FAILED} '검색' failed: step 1 — replay broken"),
        ), app = "Pedia")).toString())

        val rows = Site.classify(Site.load(day))
        val screens = Site.screens(rows)
        assertEquals(listOf("나의왓챠-활동" to "작품 상세" to false, "작품 상세" to "나의왓챠-활동" to true),
            Site.links(screens).map { it.from.name to it.to to it.look }.sortedBy { it.first.first }, "on foot it is a way there; opened at a place of its own, a look")
        assertTrue(rows.single { it.result.name == "query" }.waitsForEntry)

        SitePages.build(day, day)
        val activity = File(day, "나의왓챠-활동/_screen.html").readText()
        assertTrue("<h3>들어오는 길</h3>" in activity && "<span class=ip>딥링크</span><span class=it>없음</span>" in activity, "a way in with a step that moves: the screen has no address of its own")
        assertTrue("<span class=ip>진입</span><span class=it><code class=e>app://my</code> 를 열고 <span class=q>&quot;활동&quot;</span> 탭을 누른다<span class=st2>이 화면의 시나리오 1개가 여기서 시작한다</span>" in activity,
            activity.substringAfter("들어오는 길").take(900))
        assertTrue("<h3>다른 화면에서 바꾸면 <span class=hn><b class=bad>실패 1</b> · 쓰인 화면에서 센다</span></h3>" in activity)
        assertTrue("href=\"../작품 상세/_screen.html#comment\">작품 상세 › 코멘트 추가와 삭제 › 추가 뒤 활동 탭</a><span class=it><b class=\"mn here\">나의왓챠-활동</b> 코멘트 영역 맨 앞에 나온다." +
            "<br><span class=reason>녹화 때 있던 것이 화면에 없음: 방금 쓴 코멘트</span>" in activity, activity.substringAfter("다른 화면에서 바꾸면").take(900))
        assertTrue("시나리오 1 · 확인 2 · 전부 통과" in activity, "the failure is counted where it is written")
        val own = activity.substringAfter("<h3>이 화면에서</h3>")
        assertTrue("<b class=fn>포스터</b><span class=fs>포스터를 누르면 그 작품의 <a class=mn href=\"../작품 상세/_screen.html\">작품 상세</a>로 간다.</span><span class=ckn>1스텝</span>" in own, own.take(600))
        assertTrue("진입" !in own.substringBefore("<script>"), "the way in is not among the screen's scenarios")

        val content = File(day, "작품 상세/_screen.html").readText()
        assertTrue("<span class=ip>딥링크</span><span class=it><code class=e>app://contents/1</code><span class=st2>이 주소가 바로 연다</span><span class=st2>이 화면의 시나리오 1개가 여기서 시작한다</span>" in content,
            content.substringAfter("들어오는 길").take(600))
        assertTrue(">나의왓챠-활동 › 포스터</a>" in content && "다른 화면에서 바꾸면" !in content, "a check that walked here is a way in")
        assertTrue("<span class=passed>추가</span> · <span class=failed>추가 뒤 활동 탭</span> · <span class=passed>삭제</span><span class=to>→ 나의왓챠-활동</span>" in content)
        assertTrue("<span class=to>나의왓챠-활동의 진입으로 연다</span> <span class=to>member-empty 계정으로</span>" in content && "<details class=\"ck failed\" open>" in content,
            "a check's own setup: where it started and as whom")

        val search = File(day, "검색/_screen.html").readText()
        assertTrue("시나리오 1개를 돌리지 못했다" in search && "<span class=ip>진입</span>" in search, search.substringAfter("들어오는 길").take(600))

        val home = File(day, "index.html").readText()
        assertTrue("<h1>실패 2</h1>" in home && "이 화면의 진입이 실패해 시나리오 1개를 돌리지 못했다." in home, "the way in and the look: the scenario that waited for the way in is not a third")
        assertTrue("안 돌림</span><a href=\"검색/_screen.html#query\"" !in home, "nor a line of its own")
        assertTrue("<span>추가 뒤 활동 탭</span><span class=to>→ 나의왓챠-활동</span>" in home)
        val line = home.split("<tbody").first { "나의왓챠-활동/_screen.html" in it }
        assertTrue("<code class=e>app://my</code> <span class=dim>→ 진입</span>" in line && "<td class=r>1 <b class=bad>· 실패 1</b></td><td class=r>1</td>" in line, line.take(900))
        val steps = File(day, "작품 상세/comment.html").readText()
        assertTrue("@나의왓챠-활동" !in home && "@나의왓챠-활동" !in content && "@나의왓챠-활동" !in steps, "a mention reads as the screen's name wherever it is not a link")
        assertTrue("나의왓챠-활동 코멘트 영역 맨 앞에 나온다." in steps)
    }

    @Test
    fun `nested screens are read as they are laid out - parts down the page, tabs as tabs, opened screens on pages of their own`() {
        val day = File(runs, "2026-11-13")
        fun st(n: Int, text: String, status: StepStatus = StepStatus.PASSED, reason: String? = null) =
            StepReport(n, text, status, "replay", emptyList(), emptyList(), reason, 500, emptyList(), startedMs = n * 1000L)
        fun write(feature: String, name: String, title: String, prose: String, via: String?, steps: List<StepReport>, sections: List<SectionReport> = emptyList(),
                  mentions: List<String> = emptyList(), tabs: List<String> = emptyList(), state: String = "member") {
            val dir = File(day, "$feature/$name").apply { mkdirs() }
            val status = if (steps.any { it.status == StepStatus.FAILED }) StepStatus.FAILED else StepStatus.PASSED
            ResultJson.write(ScenarioRunResult(title, status, steps, null, 5000, startedAtMs = 1_794_000_000_000L, reportDir = dir, sections = sections), "replay",
                ResultJson.Meta(feature, name, null, "$feature/$name.md", title, null, prose, "2026-11-13", ResultJson.Env("6.4.0", "sdk", "1.1"),
                    setup = ResultJson.Setup("app://my", false, state, via = via), mentions = mentions, tabs = tabs), dir)
        }
        // 나의 왓챠: its way in says which of the screens under it are tabs
        write("나의 왓챠", "_entry", "_entry", "나의 왓챠 주소로 바로 열린다.", null, listOf(st(1, "\"프로필 수정\"이 보이는지 확인한다")), tabs = listOf("홈", "활동"))
        write("나의 왓챠", "tabs", "탭 이동", "누르면 그 탭(@나의 왓챠/활동)으로 바뀐다.", "나의 왓챠", listOf(st(1, "\"활동\" 탭을 누른다")), mentions = listOf("나의 왓챠/활동"))
        // a part: no way in of its own, it starts from the screen's
        write("나의 왓챠/프로필 헤더", "link", "링크 열기", "누르면 브라우저로 열린다.", "나의 왓챠", listOf(st(1, "링크를 누른다")))
        // two tabs, each with a scenario from a file of the same name; the second failed
        write("나의 왓챠/홈", "empty", "빈 계정", "안내 하나만 나온다.", "나의 왓챠", listOf(st(1, "안내가 보이는지 확인한다")), state = "member-empty")
        write("나의 왓챠/활동", "_entry", "_entry", "\"활동\" 탭을 누르면 열린다.", null, listOf(st(1, "\"활동\" 탭을 누른다")))
        write("나의 왓챠/활동", "empty", "빈 계정", "영역마다 안내가 나온다.", "나의 왓챠/활동",
            listOf(st(1, "안내가 보이는지 확인한다", StepStatus.FAILED, "replay broken: evidence not on screen: 안내")), state = "member-empty")
        // a screen that opens from it, and looks back at the part
        write("나의 왓챠/프로필 수정", "_entry", "_entry", "프로필 헤더의 \"프로필 수정\"을 누르면 열린다.", null, listOf(st(1, "\"프로필 수정\"을 누른다")))
        write("나의 왓챠/프로필 수정", "link", "링크", "맨 아래의 링크 입력칸.", "나의 왓챠/프로필 수정",
            listOf(st(1, "\"완료\"를 누른다"), st(2, "링크가 보이는지 확인한다")),
            listOf(SectionReport("저장", "완료하면 저장된다.", StepStatus.PASSED, 1..1),
                SectionReport("다시 들어와도 유지", "@나의 왓챠/프로필 헤더의 링크가 바뀌어 있다.", StepStatus.PASSED, 2..2,
                    open = Scenario.Open(screen = "나의 왓챠/프로필 헤더"), mentions = listOf("나의 왓챠/프로필 헤더"))))
        // a screen by itself, as before
        write("작품상세", "rating", "평가", "별을 누르면 저장된다.", null, listOf(st(1, "\"4점\"을 누른다")))

        val screens = Site.screens(Site.classify(Site.load(day)))
        val by = screens.associateBy { it.feature }
        assertEquals(
            mapOf("나의 왓챠" to Site.Place.ROOT, "나의 왓챠/프로필 헤더" to Site.Place.PART, "나의 왓챠/홈" to Site.Place.TAB, "나의 왓챠/활동" to Site.Place.TAB,
                "나의 왓챠/프로필 수정" to Site.Place.OPENS, "작품상세" to Site.Place.ROOT),
            screens.associate { it.feature to it.place }, "named in `tabs:`, a tab; with a way in of its own, opened from it; with neither, a part")
        assertEquals(listOf("홈", "활동"), by.getValue("나의 왓챠").tabs.map { it.label }, "tabs in the order the app shows them")
        assertEquals(listOf("나의 왓챠", "나의 왓챠/프로필 수정", "작품상세"), Site.pages(screens).map { it.feature }, "the root with a failure in it first, then what opens from it")

        SitePages.build(day, day)
        assertTrue(File(day, "나의 왓챠/프로필 수정/_screen.html").isFile && !File(day, "나의 왓챠/활동/_screen.html").exists() && !File(day, "나의 왓챠/프로필 헤더/_screen.html").exists(),
            "a part and a tab are read on the screen's page")
        val my = File(day, "나의 왓챠/_screen.html").readText()
        assertTrue("시나리오 4 · 확인 6 · <b class=bad>실패 1</b>" in my && " · 부분 1 · 탭 2 · 여기서 열리는 화면 1</span>" in my, my.substringAfter("<h1>").take(400))
        assertTrue("이 화면의 시나리오 3개가 여기서 시작한다" in my, "the part's and the first tab's scenarios start from the screen's way in too")
        // the part, read as a screen is — with what another screen changed that shows on it
        val part = my.substringAfter("<section class=part id=\"s:프로필_헤더\">").substringBefore("<section class=\"part tabpart\">")
        assertTrue("<span class=pk>부분</span><h2>프로필 헤더</h2>" in part && "<h3>다른 화면에서 바꾸면" in part && "<h3>이 부분에서</h3>" in part, part.take(600))
        assertTrue("href=\"../나의 왓챠/프로필 수정/_screen.html#link\">나의 왓챠 › 프로필 수정 › 링크 › 다시 들어와도 유지</a><span class=it><b class=\"mn here\">프로필 헤더</b>의 링크가 바뀌어 있다." in part, part.take(900))
        // the screen's own scenarios sit between its parts and its tabs; a mention of a tab goes to the tab
        val own = my.substringAfter("<h3>이 화면에서</h3>").substringBefore("<section class=\"part tabpart\">")
        assertTrue("<b class=fn>탭 이동</b><span class=fs>누르면 그 탭(<a class=mn href=\"../나의 왓챠/_screen.html#s:활동\">활동</a>)으로 바뀐다.</span>" in own, own.take(600))
        // the tabs: a row to click, the failed one open, a panel each
        val tabs = my.substringAfter("<section class=\"part tabpart\">")
        assertTrue("<span class=pk>탭</span><h2>홈 / 활동</h2>" in tabs)
        assertTrue("<button type=button role=tab class=\"passed\" id=\"tab-s:홈\" aria-controls=\"s:홈\" aria-selected=false><span class=tn>홈</span><span class=tc>1/1</span></button>" in tabs, tabs.take(700))
        assertTrue("<button type=button role=tab class=\"failed\" id=\"tab-s:활동\" aria-controls=\"s:활동\" aria-selected=true><span class=tn>활동</span><span class=tc>1/2</span></button>" in tabs)
        assertTrue("<div role=tabpanel id=\"s:홈\" aria-labelledby=\"tab-s:홈\" hidden>" in tabs && "<div role=tabpanel id=\"s:활동\" aria-labelledby=\"tab-s:활동\">" in tabs)
        assertTrue("id=\"홈/empty\"" in tabs && "id=\"활동/empty\"" in tabs && "<h3>이 탭에서</h3>" in tabs, "two scenarios from files of one name are two places on the page")
        assertTrue("나의 왓챠 › 탭 이동</a><span class=it>누르면 그 탭(<b class=\"mn here\">활동</b>)으로 바뀐다." in tabs.substringAfter("id=\"s:활동\""), "the walk to a tab is one of the tab's ways in")
        // the screen that opens from it: a card here, a page there
        assertTrue("<h3>여기서 열리는 화면</h3><div class=opens><a class=\"oc passed\" href=\"../나의 왓챠/프로필 수정/_screen.html\"><i class=\"ic passed\">✓</i><b>프로필 수정</b><span class=\"c\">3/3</span>" +
            "<span class=oh>프로필 헤더의 &quot;프로필 수정&quot;을 누르면 열린다.</span><span class=on>링크</span></a>" in my, my.substringAfter("여기서 열리는 화면").take(500))
        // the list of screens: the roots, and under this one what is nested in it
        val nav = my.substringAfter("<nav class=snav").substringBefore("</nav>")
        assertTrue("<span class=nm>나의 왓챠</span><span class=\"c bad\">8/9</span></a><div class=kids><a class=\"k\" href=\"../나의 왓챠/_screen.html#s:프로필_헤더\"" in nav, nav.take(900))
        assertTrue("<div class=tg><span class=tgl>탭</span><a class=\"k tab\" href=\"../나의 왓챠/_screen.html#s:홈\"" in nav && "<a class=\"k open\" href=\"../나의 왓챠/프로필 수정/_screen.html\"" in nav)
        assertTrue("<span class=nm>작품상세</span><span class=\"c\">1/1</span></a></nav>" in my.substringAfter("<nav class=snav").substringBefore("<div class=doc>"), "another root's line is closed")

        val edit = File(day, "나의 왓챠/프로필 수정/_screen.html").readText()
        assertTrue("<a href=\"../../나의 왓챠/_screen.html\">나의 왓챠</a><span>/</span><b>프로필 수정</b></nav>" in edit, "the screens above it lead back")
        assertTrue("<a class=\"k open cur\" href=\"../../나의 왓챠/프로필 수정/_screen.html\"" in edit && "<h1>프로필 수정</h1>" in edit)
        assertTrue("<span class=to>프로필 헤더의 진입으로 연다</span>" in edit && "<span class=to>→ 프로필 헤더</span>" in edit)

        val home = File(day, "index.html").readText()
        assertTrue("화면 3 · 총 소요" in home, "a page is a screen: the root with its parts and tabs, and the one that opens from it")
        assertTrue("<a href=\"나의 왓챠/_screen.html#활동/empty\">나의 왓챠 › 활동</a><span>›</span><a class=title href=\"나의 왓챠/활동/empty.html\">빈 계정</a>" in home, home.substringAfter("봐야 할 것").take(900))
        val line = home.split("<tbody").first { "\"나의 왓챠/_screen.html\"" in it }
        assertTrue("<td class=r>4</td><td class=r><b class=\"bad\">5/6</b></td>" in line && "<span class=dim>활동 › </span><a href=\"나의 왓챠/활동/empty.html\">빈 계정</a>" in line, line.take(900))
        assertTrue("<td class=r>1</td><td class=r><span class=dim>–</span></td>" in line, "the look from the screen it opens is another page's; the walk between its own tabs is not counted as leaving")
        assertTrue("<span class=sub2>↳</span><a href=\"나의 왓챠/프로필 수정/_screen.html\">나의 왓챠 › 프로필 수정</a>" in home)
        val steps = File(day, "나의 왓챠/활동/empty.html").readText()
        assertTrue("<a href=\"../../나의 왓챠/_screen.html#활동/empty\">나의 왓챠 › 활동</a><span>/</span><b>empty</b>" in steps, "a scenario of a tab leads back to its line in the tab")
    }
}
