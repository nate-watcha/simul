package dev.nate.uiagent.web

import dev.nate.uiagent.Bounds
import dev.nate.uiagent.json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures below are captured from a real emulator during the M0 spike (docs/webview-spike.md). */
class CdpClientParseTest {

    private val procNetUnix = """
        Num       RefCount Protocol Flags    Type St Inode Path
        0000000000000000: 00000002 00000000 00010000 0001 01 116982 @webview_devtools_remote_8716
        0000000000000000: 00000002 00000000 00010000 0001 01  12345 /dev/socket/property_service
        0000000000000000: 00000003 00000000 00000000 0001 03 116982 @webview_devtools_remote_8716
        0000000000000000: 00000002 00000000 00010000 0001 01  99999 @chrome_devtools_remote
    """.trimIndent()

    @Test
    fun `parses webview sockets, deduplicated, ignoring chrome`() {
        assertEquals(listOf("webview_devtools_remote_8716"), CdpClient.parseWebViewSockets(procNetUnix))
    }

    @Test
    fun `socket pid extraction`() {
        assertEquals("8716", CdpClient.socketPid("webview_devtools_remote_8716"))
        assertNull(CdpClient.socketPid("chrome_devtools_remote"))
    }

    // Real GET /json body (spike): description is a nested JSON *string*.
    private val pagesJson = """
        [ {
           "description": "{\"attached\":true,\"empty\":false,\"height\":1072,\"never_attached\":false,\"screenX\":0,\"screenY\":160,\"visible\":true,\"width\":720}",
           "devtoolsFrontendUrl": "https://chrome-devtools-frontend.appspot.com/serve_rev/@fa19f0/inspector.html?ws=localhost:9222/devtools/page/A7F394D9",
           "faviconUrl": "https://staging.example.com/favicon.ico",
           "id": "A7F394D9",
           "title": "데모",
           "type": "page",
           "url": "https://staging.example.com/webview/payment/choose_grade",
           "webSocketDebuggerUrl": "ws://localhost:9222/devtools/page/A7F394D9"
        }, {
           "id": "SW1", "type": "service_worker", "title": "sw", "url": "https://x/sw.js",
           "webSocketDebuggerUrl": "ws://localhost:9222/devtools/page/SW1"
        } ]
    """.trimIndent()

    @Test
    fun `parses page targets with rect and visibility from description`() {
        val targets = CdpClient.parseTargets(pagesJson, localPort = 40123)
        assertEquals(1, targets.size) // service_worker dropped
        val t = targets[0]
        assertEquals("A7F394D9", t.id)
        assertEquals("https://staging.example.com/webview/payment/choose_grade", t.url)
        // ws url is rebuilt against OUR forwarded port, not the advertised one
        assertEquals("ws://localhost:40123/devtools/page/A7F394D9", t.webSocketUrl)
        assertEquals(Bounds(0, 160, 720, 1232), t.rect)
        assertEquals(true, t.visible)
        assertEquals(true, t.attached)
    }

    @Test
    fun `missing description yields null rect, not a crash`() {
        val targets = CdpClient.parseTargets("""[{"id":"X","type":"page","url":"u","title":"t"}]""", 1)
        assertEquals(1, targets.size)
        assertNull(targets[0].rect)
        assertNull(targets[0].visible)
    }

    @Test
    fun `malformed json body yields no targets`() {
        assertTrue(CdpClient.parseTargets("not json", 1).isEmpty())
    }

    // ------------------------------------------------------------ evaluate reply

    private fun reply(s: String) = json.parseToJsonElement(s).jsonObject

    @Test
    fun `ok evaluate reply`() {
        val r = CdpClient.parseEvalReply(reply("""{"id":1,"result":{"result":{"type":"string","value":"complete"}}}"""))
        assertEquals(CdpClient.Eval.Ok("complete"), r)
    }

    @Test
    fun `js exception surfaces as Failed`() {
        val r = CdpClient.parseEvalReply(
            reply("""{"id":1,"result":{"exceptionDetails":{"text":"Uncaught ReferenceError"},"result":{"type":"object"}}}""")
        )
        assertIs<CdpClient.Eval.Failed>(r)
        assertTrue(r.reason.contains("ReferenceError"))
    }

    @Test
    fun `undefined result is Failed, not a crash`() {
        val r = CdpClient.parseEvalReply(reply("""{"id":1,"result":{"result":{"type":"undefined"}}}"""))
        assertIs<CdpClient.Eval.Failed>(r)
    }
}
