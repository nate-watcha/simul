package dev.nate.uiagent.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CliProjectTest {

    private fun makeProject(): File {
        val root = File.createTempFile("uiagent-proj", "").let { it.delete(); it.mkdirs(); it }
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
        val empty = File.createTempFile("uiagent-empty", "").let { it.delete(); it.mkdirs(); it }
        assertNull(SimulProject.find(empty))
        assertEquals(2, cliMain(arrayOf("run", "--all"), empty))
        assertEquals(2, cliMain(arrayOf("list"), empty))
    }

    @Test
    fun `list and dry-run exit 0`() {
        val root = makeProject()
        assertEquals(0, cliMain(arrayOf("list"), root))
        assertEquals(0, cliMain(arrayOf("run", "--all", "--dry-run"), root))
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
    fun `explicit replay mode with no trace also skips`() {
        val root = makeProject()
        assertEquals(0, cliMain(arrayOf("run", "--all", "--mode", "replay"), root))
    }

    @Test
    fun `tag filtering selects scenarios`() {
        val root = makeProject()
        // a scenario without the smoke tag
        File(root, ".simul/scenarios/other.md").writeText("---\nname: other\ntags: [nightly]\n---\n1. do a thing\n")
        assertEquals(0, cliMain(arrayOf("run", "--tag", "smoke", "--dry-run"), root))
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

    private fun capture(block: () -> Unit): String {
        val out = java.io.ByteArrayOutputStream()
        val old = System.out
        System.setOut(java.io.PrintStream(out, true))
        try { block() } finally { System.setOut(old) }
        return out.toString()
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

        val text = capture { assertEquals(0, cliMain(arrayOf("run", "billing/play-from-detail.md", "--dry-run"), root)) }
        assertTrue("play-from-detail@member (billing/play-from-detail.md@member)" in text, text)
        assertTrue("play-from-detail@guest" in text, text)
        assertTrue("the \"screen:subscribe\" screen should be shown" in text, text)
        assertTrue("the \"btn_login\" button should be shown" in text, text)
    }

    @Test
    fun `--state picks one variant of a states scenario`() {
        val root = statefulProject()
        val text = capture { assertEquals(0, cliMain(arrayOf("run", "--state", "nosub", "--dry-run"), root)) }
        assertTrue("play-from-detail@nosub" in text, text)
        assertTrue("play-from-detail@member" !in text && "play-from-detail@guest" !in text, text)
        assertTrue("browse-greenbook" !in text, "the stateless scenario declares no precondition\n$text")
    }

    @Test
    fun `a broken scenario file fails the batch with its reason instead of crashing`() {
        val root = statefulProject()
        File(root, ".simul/scenarios/bad.md").writeText("---\nname: bad\n---\n1. Tap \"a\"\n   :: member → \"b\"\n")
        assertEquals(2, cliMain(arrayOf("run", "--all", "--dry-run"), root))
        assertEquals(2, cliMain(arrayOf("run", "bad.md", "--dry-run"), root))
    }
}
