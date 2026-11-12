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
}
