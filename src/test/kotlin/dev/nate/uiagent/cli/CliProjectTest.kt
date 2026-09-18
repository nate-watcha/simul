package dev.nate.uiagent.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

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
        assertEquals("http://localhost:8080", p.config.llmUrl)
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
}
