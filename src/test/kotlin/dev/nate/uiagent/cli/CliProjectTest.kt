package dev.nate.uiagent.cli

import dev.nate.uiagent.captureStdout
import dev.nate.uiagent.tempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CliProjectTest {

    private fun makeProject(): File {
        val root = tempDir()
        File(root, ".simul/scenarios/subscription").mkdirs()
        File(root, ".simul/config.yaml").writeText(
            """
            agentVersion: ">=0.2"
            app: com.example.app
            llm:
              url: http://localhost:8080
            defaults:
              maxTurns: 12
              scrollLimit: 3
            """.trimIndent()
        )
        File(root, ".simul/scenarios/subscription/browse-greenbook.md").writeText(
            """
            ---
            name: browse-greenbook
            tags: [smoke, subscription]
            setup:
              deeplink: myapp://tab/subscription
            ---
            1. 구독 탭에서 "그린북" 콘텐츠를 찾아 클릭해
            2. Verify 콘텐츠 상세 화면에 "재생하기" 버튼이 보이는지 확인
            """.trimIndent()
        )
        return root
    }

    @Test
    fun `project is discovered from a nested cwd`() {
        val root = makeProject()
        val nested = File(root, "app/src/main").apply { mkdirs() }
        val p = assertNotNull(SimulProject.find(nested))
        assertEquals(root.canonicalFile, p.appRoot.canonicalFile)
        assertEquals("com.example.app", p.config.app)
        assertEquals("http://localhost:8080", p.config.llm.url)
        assertNull(p.versionWarning(), "0.2.0 satisfies >=0.2")
    }

    @Test
    fun `no project found returns null and cli errors`() {
        val empty = tempDir()
        assertNull(SimulProject.find(empty))
        assertEquals(2, cliMain(arrayOf("run", "--all"), empty))
        assertEquals(2, cliMain(arrayOf("list"), empty))
    }

    @Test
    fun `auto mode with no trace skips with exit 0 - CI invariant`() {
        val root = makeProject()
        assertEquals(0, cliMain(arrayOf("run", "--all"), root))
        // scenario untouched: still no trace, no reports of an actual run
        val p = SimulProject.find(root)!!
        assertEquals(false, p.traceFileFor(p.scenarios().single()).exists())
    }

    @Test
    fun `--version prints the version without needing a project or a device`() {
        val empty = tempDir()
        val text = captureStdout { assertEquals(0, cliMain(arrayOf("--version"), empty)) }
        assertEquals("simul $AGENT_VERSION", text.trim())
        assertEquals(text, captureStdout { assertEquals(0, cliMain(arrayOf("-v"), empty)) })
    }

    @Test
    fun `version requirement above the binary warns`() {
        val root = makeProject()
        File(root, ".simul/config.yaml").writeText("agentVersion: \">=9.9\"\n")
        val p = SimulProject.find(root)!!
        assertNotNull(p.versionWarning())
    }

    // ------------------------------------------------------------------ states

    private fun statefulProject(): File {
        val root = makeProject()
        File(root, ".simul/scenarios/billing/play-from-detail.md").apply { parentFile.mkdirs() }.writeText(
            """
            ---
            name: play-from-detail
            tags: [player]
            states: [member, nosub, guest]
            setup:
              launch: true
            ---
            1. Tap the "btn_play" button
               :: member → the "screen:player" screen should be shown
               :: nosub → the "screen:subscribe" screen should be shown
               :: guest → the "btn_login" button should be shown
            """.trimIndent()
        )
        return root
    }

    @Test
    fun `a states scenario runs once per state, each keyed on its own trace file`() {
        val root = statefulProject()
        val p = SimulProject.find(root)!!
        val md = File(p.scenariosDir, "billing/play-from-detail.md")
        val variants = p.scenarioVariants().filter { it.first == md }.map { it.second }
        assertEquals(listOf("play-from-detail@member", "play-from-detail@nosub", "play-from-detail@guest"), variants.map { it.name })
        assertEquals(
            File(p.scenariosDir, "billing/play-from-detail@member.trace.json"),
            p.traceFileFor(md, variants[0]),
        )
        assertEquals(File(p.scenariosDir, "billing/play-from-detail.trace.json"), p.traceFileFor(md), "stateless naming is unchanged")
        assertEquals("billing/play-from-detail.md@member", scenarioPath(p, md, variants[0]))
        assertEquals("billing/play-from-detail@member.trace.json", traceRelPath("billing/play-from-detail.md@member"))
        assertEquals("checkout/coupon.trace.json", traceRelPath("checkout/coupon.md"))

        val text = captureStdout { assertEquals(0, cliMain(arrayOf("run", "billing/play-from-detail.md", "--dry-run"), root)) }
        assertTrue("play-from-detail@member (billing/play-from-detail.md@member)" in text, text)
        assertTrue("play-from-detail@guest" in text, text)
        assertTrue("the \"screen:subscribe\" screen should be shown" in text, text)
        assertTrue("the \"btn_login\" button should be shown" in text, text)
    }

    @Test
    fun `--state picks one variant of a states scenario`() {
        val root = statefulProject()
        val text = captureStdout { assertEquals(0, cliMain(arrayOf("run", "--state", "nosub", "--dry-run"), root)) }
        assertTrue("play-from-detail@nosub" in text, text)
        assertTrue("play-from-detail@member" !in text && "play-from-detail@guest" !in text, text)
        assertTrue("browse-greenbook" !in text, "the stateless scenario declares no precondition\n$text")
    }

    @Test
    fun `a trace file names the one run it is the trace of`() {
        val root = statefulProject()
        val dir = File(root, ".simul/scenarios")
        listOf("billing/play-from-detail@nosub", "billing/play-from-detail", "billing/gone", "subscription/browse-greenbook")
            .forEach { File(dir, "$it.trace.json").writeText("{}") }

        val text = captureStdout {
            assertEquals(0, cliMain(arrayOf("run", ".simul/scenarios/billing/play-from-detail@nosub.trace.json", "subscription/browse-greenbook.trace.json", "--dry-run"), root))
        }
        assertTrue("play-from-detail@nosub (billing/play-from-detail.md@nosub)" in text && "browse-greenbook (subscription/browse-greenbook.md)" in text, text)
        assertTrue("play-from-detail@member" !in text && "play-from-detail@guest" !in text, "the other states are not this trace's\n$text")

        assertEquals(2, cliMain(arrayOf("run", "billing/play-from-detail.trace.json", "--dry-run"), root), "a stateless trace beside a `states:` scenario is nobody's")
        assertEquals(2, cliMain(arrayOf("run", "billing/gone.trace.json", "--dry-run"), root), "a trace whose scenario is gone")
        assertEquals(2, reportCommand(listOf("billing/play-from-detail@nosub.trace.json", "billing/play-from-detail.md"), root), "record or replay-only, not both in one report")
    }

    @Test
    fun `a broken scenario file fails the batch with its reason instead of crashing`() {
        val root = statefulProject()
        File(root, ".simul/scenarios/bad.md").writeText("---\nname: bad\n---\n1. Tap \"a\"\n   :: member → \"b\"\n")
        assertEquals(2, cliMain(arrayOf("run", "--all", "--dry-run"), root))
        assertEquals(2, cliMain(arrayOf("run", "bad.md", "--dry-run"), root))
    }

    @Test
    fun `a scenario beside its screen's way in starts from it, and the way in runs ahead of it`() {
        val root = makeProject()
        val dir = File(root, ".simul/scenarios/나의왓챠-활동").also { it.mkdirs() }
        File(dir, "_entry.md").writeText("---\nsetup:\n  appState: member\n  deeplink: app://my\n---\n나의 왓챠에서 \"활동\" 탭을 누르면 열린다.\n1. \"활동\" 탭을 누른다\n")
        File(dir, "posters.md").writeText("---\nname: 포스터\n---\n1. \"재혼 황후\" 포스터를 누른다\n")
        File(dir, "empty.md").writeText("---\nname: 빈 계정\nsetup:\n  appState: member-empty\n---\n1. \"아직 평가한 작품이 없어요\"가 보이는지 확인한다\n")
        File(dir, "other.md").writeText("---\nname: 타인\nsetup:\n  appState: member\n  deeplink: app://users/1\n---\n1. \"활동\" 탭을 누른다\n")
        File(dir, "0-first.md").writeText("1. 스텝\n")
        val p = SimulProject(root)

        val posters = p.load(File(dir, "posters.md"))
        assertEquals("나의왓챠-활동", posters.via)
        assertEquals(Scenario.Setup(deeplink = "app://my", launch = false, appState = "member"), posters.setup, "it opens what the way in opens, as the account the way in names")
        assertEquals("member-empty", p.load(File(dir, "empty.md")).setup?.appState, "an account of its own is kept")
        assertEquals("app://my", p.load(File(dir, "empty.md")).setup?.deeplink)
        val other = p.load(File(dir, "other.md"))
        assertEquals(null to "app://users/1", other.via to other.setup?.deeplink, "a scenario with its own address opens the app itself")
        assertEquals(null to "나의왓챠-활동 진입", p.load(File(dir, "_entry.md")).let { it.via to it.name }, "unnamed, a way in is called by its screen")
        assertEquals(listOf("_entry.md", "0-first.md"), p.scenarios().filter { it.parentFile == dir }.take(2).map { it.name }, "the way in is first in its directory")

        val entry = p.entryOf("나의왓챠-활동")!!
        assertEquals(listOf("\"활동\" 탭을 누른다"), entry.steps)
        assertEquals("member" to null, entry.precondition to entry.trace, "not recorded yet")
        assertNull(p.entryOf("billing"), "a screen without the file has no way in of its own")

        // a recording of one scenario records the way in first when its trace is missing; a replay leaves it to fail by itself
        val target = listOf(File(dir, "posters.md") to posters)
        assertEquals(listOf("_entry.md", "posters.md"), withEntries(p, target, RunMode.LLM).map { it.first.name })
        assertEquals(listOf("posters.md"), withEntries(p, target, RunMode.REPLAY).map { it.first.name })
        assertEquals(0, cliMain(arrayOf("run", "나의왓챠-활동/posters.md", "--dry-run"), root))

        // a way in opens the app and is plain steps
        File(dir, "_entry.md").writeText("1. \"활동\" 탭을 누른다\n")
        assertFailsWith<IllegalArgumentException> { p.load(File(dir, "posters.md")) }
        File(dir, "_entry.md").writeText("---\nsetup:\n  deeplink: app://my\n---\n## 가\n1. 스텝\n")
        assertFailsWith<IllegalArgumentException> { p.load(File(dir, "_entry.md")) }
    }

    @Test
    fun `mentions are resolved against the project's screens, and a scenario or a section may start from another screen's way in`() {
        val root = makeProject()
        val activity = File(root, ".simul/scenarios/나의왓챠-활동").also { it.mkdirs() }
        File(activity, "_entry.md").writeText("---\nsetup:\n  appState: member\n  deeplink: app://my\n---\n1. \"활동\" 탭을 누른다\n")
        val edit = File(root, ".simul/scenarios/프로필 수정").also { it.mkdirs() }
        File(edit, "link.md").writeText("""
            ---
            name: 링크
            setup:
              appState: member
              deeplink: app://edit
            ---
            링크를 고치면 @나의왓챠-활동에도 보인다.

            ## 저장
            1. "완료"를 누른다

            ## 다시 들어와도 유지

            setup:
              entry: 나의왓챠-활동
              appState: member-empty

            @나의왓챠-활동의 링크가 바뀌어 있고, @프로필 수정의 입력칸에도 그대로다.
            1. "링크"가 보이는지 확인한다
        """.trimIndent())
        val issue = File(root, ".simul/scenarios/이슈").also { it.mkdirs() }
        File(issue, "from-activity.md").writeText("---\nname: 활동에서 시작\nsetup:\n  entry: 나의왓챠-활동\n---\n1. 스텝\n")
        val p = SimulProject(root)

        val link = p.load(File(edit, "link.md"))
        assertEquals(listOf("나의왓챠-활동"), link.mentions)
        assertEquals(listOf(emptyList(), listOf("나의왓챠-활동", "프로필 수정")), link.sections.map { it.mentions }, "a name with a space, a particle right after: cut by the screens there are")
        assertEquals(Scenario.Open(screen = "나의왓챠-활동", appState = "member-empty"), link.sections[1].open)
        assertEquals(listOf("_entry.md", "link.md"), withEntries(p, listOf(File(edit, "link.md") to link), RunMode.LLM).map { it.first.name }, "the way in a section starts from is recorded first")

        // the file's setup names the screen: it starts as a scenario beside that screen's way in does
        val fromActivity = p.load(File(issue, "from-activity.md"))
        assertEquals("나의왓챠-활동" to "app://my", fromActivity.via to fromActivity.setup?.deeplink)
        assertEquals("member", fromActivity.setup?.appState)
        assertEquals(0, cliMain(arrayOf("run", "프로필 수정/link.md", "--dry-run"), root))

        // an `@` that names no screen, and an entry that names a screen without a way in, are refused
        File(issue, "typo.md").writeText("@나의왓챠-활돋에도 보인다.\n\n1. 스텝\n")
        assertContains(assertFailsWith<IllegalArgumentException> { p.load(File(issue, "typo.md")) }.message!!, "@나의왓챠-활돋에도 names no screen")
        File(issue, "typo.md").writeText("---\nsetup:\n  entry: 프로필 수정\n---\n1. 스텝\n")
        assertContains(assertFailsWith<IllegalArgumentException> { p.load(File(issue, "typo.md")) }.message!!, "has no _entry.md")
    }

    @Test
    fun `screens nest as directories do - a part starts from the screen's way in, and the screen's own file says what it is made of`() {
        val root = makeProject()
        val my = File(root, ".simul/scenarios/나의 왓챠")
        for (d in listOf("프로필 헤더", "홈", "활동", "프로필 수정")) File(my, d).mkdirs()
        File(my, "_entry.md").writeText("---\nsetup:\n  appState: member\n  deeplink: app://my\n---\n1. \"프로필 수정\"이 보이는지 확인한다\n")
        // what the screen is made of, top to bottom: not a scenario
        File(my, "_screen.md").writeText("---\nparts: [프로필 헤더, [홈, 활동]]\n---\n")
        File(my, "tabs.md").writeText("---\nname: 탭 이동\n---\n누르면 그 탭(@나의 왓챠/활동)으로 바뀐다.\n\n1. \"활동\" 탭을 누른다\n")
        // a part: no way in of its own
        File(my, "프로필 헤더/link.md").writeText("---\nname: 링크 열기\n---\n1. 링크를 누른다\n")
        // a tab that is open at once, and one that is a tap away
        File(my, "홈/empty.md").writeText("---\nname: 빈 계정\nsetup:\n  appState: member-empty\n---\n1. 안내가 보이는지 확인한다\n")
        File(my, "활동/_entry.md").writeText("---\nsetup:\n  appState: member\n  deeplink: app://my\n---\n1. \"활동\" 탭을 누른다\n")
        File(my, "활동/posters.md").writeText("---\nname: 포스터\n---\n1. 포스터를 누른다\n")
        // a screen that opens from it, looking back at a part of it
        File(my, "프로필 수정/_entry.md").writeText("---\nsetup:\n  appState: member\n  deeplink: app://my\n---\n1. \"프로필 수정\"을 누른다\n")
        File(my, "프로필 수정/link.md").writeText("---\nname: 링크\n---\n## 저장\n1. \"완료\"를 누른다\n\n## 다시 들어와도 유지\n\nsetup:\n  entry: 나의 왓챠/프로필 헤더\n\n@나의 왓챠/프로필 헤더의 링크가 바뀌어 있다.\n1. 링크가 보이는지 확인한다\n")
        val p = SimulProject(root)

        assertEquals(listOf("나의 왓챠", "나의 왓챠/프로필 수정", "나의 왓챠/프로필 헤더", "나의 왓챠/홈", "나의 왓챠/활동").sorted(), p.screens().filter { it.startsWith("나의 왓챠") }.sorted())
        assertEquals(ScreenLayout(listOf(ScreenLayout.Slot(part = "프로필 헤더"), ScreenLayout.Slot(tabs = listOf("홈", "활동")))), p.layoutOf("나의 왓챠"))
        assertEquals(mapOf("나의 왓챠" to p.layoutOf("나의 왓챠")), p.layoutsFor("나의 왓챠/활동"), "a result carries the layouts of the screens above its own")
        assertTrue(p.scenarios().none { it.name == "_screen.md" }, "it is not a scenario")
        assertNull(p.layoutOf("나의 왓챠/활동"))

        val part = p.load(File(my, "프로필 헤더/link.md"))
        assertEquals("나의 왓챠" to "app://my", part.via to part.setup?.deeplink, "a part has no way in to write: it starts from the screen's")
        val home = p.load(File(my, "홈/empty.md"))
        assertEquals("나의 왓챠" to "member-empty", home.via to home.setup?.appState, "the way in of the nearest screen above, as its own account")
        assertEquals("나의 왓챠/활동", p.load(File(my, "활동/posters.md")).via, "its own way in comes first")
        assertEquals(listOf("나의 왓챠/활동"), p.load(File(my, "tabs.md")).mentions, "a nested screen is named by its path")

        // a section may start from a nested screen: its way in is the one that screen starts from
        val link = p.load(File(my, "프로필 수정/link.md"))
        assertEquals(Scenario.Open(screen = "나의 왓챠/프로필 헤더"), link.sections[1].open)
        assertEquals(listOf("나의 왓챠/프로필 헤더"), link.sections[1].mentions)
        assertEquals("app://my", p.entryOf("나의 왓챠/활동")?.setup?.deeplink)
        assertEquals("나의 왓챠" to "나의 왓챠/_entry.md", p.entryOf("나의 왓챠/프로필 헤더")?.let { it.screen to it.file }, "the way in to a part is its screen's")
        assertNull(p.entryOf("나의 왓챠/없는 화면"))
        assertEquals(listOf("_entry.md", "_entry.md", "link.md"), withEntries(p, listOf(File(my, "프로필 수정/link.md") to link), RunMode.LLM).map { it.first.name },
            "its own way in and the one its section starts from are recorded first")
        assertEquals(0, cliMain(arrayOf("run", "나의 왓챠/홈/empty.md", "--dry-run"), root))

        // a part is a directory: a scenario file of that name is refused, with where it goes
        File(my, "_screen.md").writeText("---\nparts: [tabs, 프로필 헤더, [홈, 활동]]\n---\n")
        assertContains(assertFailsWith<IllegalArgumentException> { p.layoutOf("나의 왓챠") }.message!!, "tabs.md is a scenario: a part is a directory, put it in one (tabs/…)")
        // and it is mentioned as the screen it is
        assertTrue("나의 왓챠/프로필 헤더" in p.screens() && "나의 왓챠/tabs" !in p.screens())
        File(my, "_screen.md").writeText("---\nparts: [프로필 헤더, [홈, 활동]]\n---\n")
        File(my, "홈/look.md").writeText("---\nname: 둘러보기\n---\n@나의 왓챠/tabs 로도 온다.\n\n1. 스텝\n")
        assertContains(assertFailsWith<IllegalArgumentException> { p.load(File(my, "홈/look.md")) }.message!!, "@나의 왓챠/tabs names no screen")
        File(my, "홈/look.md").delete()

        // a part or a tab is a directory under the screen; the file is not run; the line is no longer the way in's
        File(my, "_screen.md").writeText("---\nparts: [프로필 헤더, [홈, 좋아요]]\n---\n")
        assertContains(assertFailsWith<IllegalArgumentException> { p.load(File(my, "홈/empty.md")) }.message!!, "parts 좋아요")
        File(my, "_screen.md").writeText("---\nparts: [프로필 헤더, [홈, 활동]]\n---\n")
        assertContains(assertFailsWith<IllegalArgumentException> { p.load(File(my, "_screen.md")) }.message!!, "not a scenario")
        File(my, "_entry.md").writeText("---\ntabs: [홈, 활동]\nsetup:\n  deeplink: app://my\n---\n1. 스텝\n")
        assertContains(assertFailsWith<IllegalArgumentException> { p.load(File(my, "_entry.md")) }.message!!, "_screen.md")
    }

    @Test
    fun `a screen in several modes - a file of one mode says so in its name and starts from that mode's way in, a common one from the first mode's`() {
        val root = makeProject()
        val profile = File(root, ".simul/scenarios/프로필")
        for (d in listOf("상단 바", "프로필 헤더", "홈/이번 주", "좋아요/좋아한 인물", "취향 매칭")) File(profile, d).mkdirs()
        File(profile, "_screen.md").writeText("---\nmodes: [본인, 타인]\nparts: [상단 바, 프로필 헤더, [취향 매칭, 홈, 좋아요]]\n---\n")
        fun entry(address: String, step: String) = "---\nsetup:\n  appState: member\n  deeplink: $address\n---\n1. $step\n"
        File(profile, "_entry@본인.md").writeText(entry("app://my", "\"프로필 수정\"이 보이는지 확인한다"))
        File(profile, "_entry@타인.md").writeText(entry("app://users/1", "\"팔로우\"가 보이는지 확인한다"))
        // parts: there in both modes, with what each mode alone has and what both share
        File(profile, "상단 바/알림@본인.md").writeText("---\nname: 알림\n---\n1. \"알림\"을 누른다\n")
        File(profile, "상단 바/차단@타인.md").writeText("1. \"더보기\"를 누른다\n")
        File(profile, "프로필 헤더/링크 열기.md").writeText("---\nname: 링크 열기\n---\n1. 링크를 누른다\n")
        // a tab both modes have, each a tap away; the same check in each mode
        File(profile, "홈/_entry@본인.md").writeText(entry("app://my", "\"프로필 홈\" 탭을 누른다"))
        File(profile, "홈/_entry@타인.md").writeText(entry("app://users/1", "\"프로필 홈\" 탭을 누른다"))
        File(profile, "홈/이번 주/헤더@본인.md").writeText("---\nname: 헤더\n---\n하단 내비게이션의 캘린더로 간다.\n\n1. \"전체 보기\"를 누른다\n")
        File(profile, "홈/이번 주/헤더@타인.md").writeText("---\nname: 헤더\n---\n이 유저의 캘린더 탭으로 간다.\n\n1. \"전체 보기\"를 누른다\n")
        File(profile, "홈/이번 주/날짜.md").writeText("---\nname: 날짜\n---\n1. 날짜를 누른다\n")
        // a tab only one mode has: its way in is that mode's alone, and nothing under it says the mode again
        File(profile, "좋아요/_entry@본인.md").writeText(entry("app://my", "\"좋아요\" 탭을 누른다"))
        File(profile, "좋아요/좋아한 인물/행.md").writeText("---\nname: 행\n---\n1. 행을 누른다\n")
        File(profile, "취향 매칭/_entry@타인.md").writeText(entry("app://users/1", "\"취향 매칭\" 탭을 누른다"))
        val p = SimulProject(root)

        assertEquals(listOf("본인", "타인"), p.layoutOf("프로필")?.modes)
        assertEquals(listOf("본인", "타인"), p.modesOf("프로필/홈/이번 주"), "a place of a screen with modes is in those modes")
        assertEquals(emptyList(), p.modesOf("billing"))
        assertEquals(listOf("_entry@본인.md", "_entry@타인.md"), p.scenarios().filter { it.parentFile == profile }.map { it.name }, "each mode's way in, ahead of the scenarios")

        // the way in of a mode: named by its screen and mode, and it is what a file of that mode starts from
        val other = p.load(File(profile, "_entry@타인.md"))
        assertEquals(listOf("프로필 타인 진입", "타인", null), listOf(other.name, other.mode, other.via))
        val block = p.load(File(profile, "상단 바/차단@타인.md"))
        assertEquals(listOf("차단", "타인", "프로필@타인", "app://users/1"), listOf(block.name, block.mode, block.via, block.setup?.deeplink), "unnamed, it is called by its file without the mode")
        assertEquals("프로필@본인" to "본인", p.load(File(profile, "상단 바/알림@본인.md")).let { it.via to it.mode })
        assertEquals("프로필/홈@타인" to "타인", p.load(File(profile, "홈/이번 주/헤더@타인.md")).let { it.via to it.mode }, "the nearest way in above, of its mode")
        // a file common to the modes runs once, in the first of them
        assertEquals("프로필@본인" to null, p.load(File(profile, "프로필 헤더/링크 열기.md")).let { it.via to it.mode })
        assertEquals("프로필/홈@본인" to null, p.load(File(profile, "홈/이번 주/날짜.md")).let { it.via to it.mode })
        // a place only one mode has: its scenarios are of that mode without saying so
        assertEquals("프로필/좋아요@본인" to "본인", p.load(File(profile, "좋아요/좋아한 인물/행.md")).let { it.via to it.mode })

        // an entry is looked up by key: with a mode that mode's, without one the first that is there
        assertEquals("프로필/홈@타인" to "프로필/홈/_entry@타인.md", p.entryOf("프로필/홈@타인")?.let { it.screen to it.file })
        assertEquals("프로필/홈@본인", p.entryOf("프로필/홈/이번 주")?.screen)
        assertEquals("프로필/취향 매칭@타인", p.entryOf("프로필/취향 매칭")?.screen, "the only mode it has")
        assertEquals("app://users/1", p.entryOf("프로필/프로필 헤더@타인")?.setup?.deeplink)

        // each file has its trace beside it, and a trace names its file back
        val trace = p.traceFileFor(File(profile, "홈/이번 주/헤더@타인.md"))
        assertEquals("헤더@타인.trace.json", trace.name)
        assertEquals(File(profile, "홈/이번 주/헤더@타인.md").absoluteFile to null, p.scenarioOfTrace(trace))
        assertEquals("날짜.md" to "member", p.scenarioOfTrace(File(profile, "홈/이번 주/날짜@member.trace.json")).let { it.first.name to it.second })

        // a recording records the way in of the mode it starts from first
        val header = File(profile, "홈/이번 주/헤더@타인.md")
        assertEquals(listOf("_entry@타인.md", "헤더@타인.md"), withEntries(p, listOf(header to p.load(header)), RunMode.LLM).map { it.first.name })
        assertEquals(0, cliMain(arrayOf("run", "--all", "--dry-run"), root))

        // a mode in a name is one of the screen's; a place a mode does not have holds no scenario of it
        File(profile, "상단 바/설정@타안.md").writeText("1. 스텝\n")
        assertContains(assertFailsWith<IllegalArgumentException> { p.load(File(profile, "상단 바/설정@타안.md")) }.message!!, "`@타안` is not a mode of its screen (modes: 본인, 타인)")
        File(profile, "상단 바/설정@타안.md").delete()
        File(profile, "좋아요/빈 계정@타인.md").writeText("1. 스텝\n")
        assertContains(assertFailsWith<IllegalArgumentException> { p.load(File(profile, "좋아요/빈 계정@타인.md")) }.message!!, "has no way in for the mode '타인'")
        File(profile, "좋아요/빈 계정@타인.md").delete()
        val plain = File(root, ".simul/scenarios/billing").apply { mkdirs() }
        File(plain, "coupon@타인.md").writeText("1. 스텝\n")
        assertContains(assertFailsWith<IllegalArgumentException> { p.load(File(plain, "coupon@타인.md")) }.message!!, "has a `modes:` line")
        File(profile, "_screen.md").writeText("---\nmodes: 본인, 타인\n---\n")
        assertContains(assertFailsWith<IllegalArgumentException> { p.layoutOf("프로필") }.message!!, "`modes:` is a list on its line")
    }

    @Test
    fun `a mode's values - a common file that names one runs once in each mode on that mode's data, with a trace each`() {
        val root = makeProject()
        val profile = File(root, ".simul/scenarios/프로필")
        for (d in listOf("프로필 헤더", "홈/취향분석", "좋아요")) File(profile, d).mkdirs()
        File(profile, "_screen.md").writeText("---\nmodes:\n  본인:\n    주인: 구름\n  타인:\n    주인: WATCHA\nparts: [프로필 헤더, [홈, 좋아요]]\n---\n")
        fun entry(address: String, step: String) = "---\nsetup:\n  appState: member\n  deeplink: $address\n---\n1. $step\n"
        File(profile, "_entry@본인.md").writeText(entry("app://my", "\"프로필 수정\"이 보이는지 확인한다"))
        File(profile, "_entry@타인.md").writeText(entry("app://users/1", "\"\$주인\"이 보이는지 확인한다"))
        File(profile, "홈/_entry@본인.md").writeText(entry("app://my", "\"프로필 홈\" 탭을 누른다"))
        File(profile, "홈/_entry@타인.md").writeText(entry("app://users/1", "\"프로필 홈\" 탭을 누른다"))
        File(profile, "좋아요/_entry@본인.md").writeText(entry("app://my", "\"좋아요\" 탭을 누른다"))
        // the same in every mode, whoever's profile it is: once
        File(profile, "프로필 헤더/소개.md").writeText("소개 글은 \"더보기\"로 펼친다.\n\n1. \"더보기\"를 누른다\n")
        // the same behaviour on the mode's data — said in the prose alone, or in a step
        File(profile, "프로필 헤더/팔로워 목록.md").writeText("누르면 \$주인의 팔로워 목록으로 간다.\n\n1. \"팔로워\"를 누른다\n")
        File(profile, "홈/취향분석/헤더.md").writeText("\"전체 보기\"를 누르면 \$주인의 취향분석 상세로 간다.\n\n1. \"취향분석 전체 보기\"를 누른다\n2. \"\$주인님의 취향분석\"이 보이는지 확인한다\n")
        // of one mode: its file's, or its place's — that mode's value, one run
        File(profile, "프로필 헤더/팔로우@타인.md").writeText("\$주인을 팔로우한다.\n\n1. \"\$주인\" 옆의 \"팔로우\"를 누른다\n")
        File(profile, "좋아요/행.md").writeText("1. \"\$주인님이 좋아한 인물\"이 보이는지 확인한다\n")
        val p = SimulProject(root)

        assertEquals(listOf("본인", "타인"), p.layoutOf("프로필")?.modes, "the modes are the block's, in its order")
        assertEquals(mapOf("본인" to mapOf("주인" to "구름"), "타인" to mapOf("주인" to "WATCHA")), p.modeDataOf("프로필/홈/취향분석"), "a place of the screen has its values")

        assertEquals(listOf(null), p.load(File(profile, "프로필 헤더/소개.md")).variants().map { it.run }, "a file that names no value runs once")
        val followers = p.load(File(profile, "프로필 헤더/팔로워 목록.md")).variants()
        assertEquals(listOf("팔로워 목록@본인", "팔로워 목록@타인"), followers.map { it.name })
        assertEquals(listOf("프로필@본인" to "app://my", "프로필@타인" to "app://users/1"), followers.map { it.via to it.setup?.deeplink }, "each from its mode's way in")
        assertEquals(listOf("본인", "타인"), followers.map { it.run })
        assertTrue(followers.all { it.perMode && it.description == "누르면 \$주인의 팔로워 목록으로 간다." }, "the prose keeps the name: it is the line of spec")
        assertEquals(listOf(mapOf("주인" to "구름"), mapOf("주인" to "WATCHA")), followers.map { it.data })

        val headerMd = File(profile, "홈/취향분석/헤더.md")
        val header = p.load(headerMd).variants()
        assertEquals(listOf("프로필/홈@본인", "프로필/홈@타인"), header.map { it.via })
        assertEquals(listOf("\"구름님의 취향분석\"이 보이는지 확인한다", "\"WATCHA님의 취향분석\"이 보이는지 확인한다"), header.map { it.steps[1] }, "a step is run with the value in place")
        assertEquals(listOf("헤더@본인.trace.json", "헤더@타인.trace.json"), header.map { p.traceFileFor(headerMd, it).name })
        assertEquals(headerMd.absoluteFile to "타인", p.scenarioOfTrace(p.traceFileFor(headerMd, header[1])))
        assertEquals(header[1], p.load(headerMd).variant("타인"))
        assertEquals("프로필/홈/취향분석/헤더.md@타인", scenarioPath(p, headerMd, header[1]))

        val follow = p.load(File(profile, "프로필 헤더/팔로우@타인.md"))
        assertEquals(listOf("\"WATCHA\" 옆의 \"팔로우\"를 누른다", "타인", null, false), listOf(follow.steps[0], follow.mode, follow.run, follow.perMode), "a file of one mode is filled with that mode's")
        val liked = p.load(File(profile, "좋아요/행.md"))
        assertEquals(listOf("\"구름님이 좋아한 인물\"이 보이는지 확인한다", "본인", 1), listOf(liked.steps[0], liked.mode, liked.variants().size), "a place one mode has is that mode")
        assertEquals("\"WATCHA\"이 보이는지 확인한다", p.load(File(profile, "_entry@타인.md")).steps[0], "a mode's way in may name its values too")

        assertEquals(5 + 7, p.scenarioVariants().count { it.first.path.contains("프로필") }, "five ways in, and 1 + 2 + 2 + 1 + 1 runs")
        // a trace names one run; the whole project still lays out
        p.traceFileFor(headerMd, header[1]).writeText("{}")
        assertEquals(0, cliMain(arrayOf("run", p.traceFileFor(headerMd, header[1]).path, "--dry-run"), root))
        assertEquals(0, cliMain(arrayOf("run", "--all", "--dry-run"), root))

        // where a name ends: the longest of the screen's names — and a Latin one where Latin letters do
        assertEquals("구름님의 A, \$ownerName, \$없는값", ModeData.fill("\$주인님의 \$owner, \$ownerName, \$없는값", mapOf("주인" to "구름", "owner" to "A")))
        assertEquals("주인의 목록" to listOf("주인"), ModeData.plain("\$주인의 목록", listOf("주인")) to ModeData.used("\$주인의 목록", listOf("주인", "코드")))

        // what cannot be: its own address, states on top of modes, a name its `values:` has too, modes that differ in names
        fun refused(name: String, text: String): String {
            val md = File(profile, "프로필 헤더/$name.md").apply { writeText(text) }
            return assertFailsWith<IllegalArgumentException> { p.load(md) }.message!!.also { md.delete() }
        }
        assertContains(refused("own", "---\nsetup:\n  deeplink: app://x\n---\n\$주인의 것.\n\n1. 스텝\n"), "it cannot open the app itself")
        assertContains(refused("states", "---\nstates: [member, guest]\n---\n\$주인의 것.\n\n1. 스텝\n"), "`states:` would run each of those again")
        File(profile, "_screen.md").writeText("---\nmodes:\n  본인:\n    owner: 구름\n  타인:\n    owner: WATCHA\n---\n")
        assertContains(refused("values", "---\nvalues:\n  owner: [a, b]\n---\n1. \"\$owner\"를 입력한다\n"), "its `values:` cannot name it too")
        File(profile, "_screen.md").writeText("---\nmodes:\n  본인:\n    주인: 구름\n  타인:\n    이름: WATCHA\n---\n")
        assertContains(assertFailsWith<IllegalArgumentException> { p.layoutOf("프로필") }.message!!, "every mode has the same values (주인); 타인 has 이름")
        File(profile, "_screen.md").writeText("---\nmodes:\n  본인:\n  타인:\n    주인: WATCHA\n---\n")
        assertContains(assertFailsWith<IllegalArgumentException> { p.layoutOf("프로필") }.message!!, "under `modes:` each mode is a line")
    }
}
