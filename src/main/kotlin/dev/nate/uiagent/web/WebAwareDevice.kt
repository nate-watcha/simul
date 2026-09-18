package dev.nate.uiagent.web

import dev.nate.uiagent.Bounds
import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.LogicalLayout
import dev.nate.uiagent.device.Device

/**
 * Device decorator that merges WebView content (via CDP) into native observations. Gestures
 * pass through untouched — web elements already carry screen-space coordinates, so tap/type
 * run through the same `adb shell input` path (plan invariant: the model never distinguishes
 * web from native).
 *
 * Discovery runs on every observation: the native WebView container node cannot be relied on
 * as a gate (its resource-id disappears once Chrome starts populating accessibility nodes —
 * seen on-device in M4). A visible CDP page target is the merge signal; on native-only screens
 * discovery just finds nothing. Every web failure degrades to the native-only layout, with a
 * [LogicalLayout.webNote] when a WebView is clearly present but uninspectable, so the model
 * can report a grounded FAILED instead of crashing (release-build guard, plan M3).
 */
class WebAwareDevice(
    private val delegate: Device,
    private val cdp: CdpClient,
    private val appId: String?,
    private val loadWaitMs: Long = 5_000,
    private val loadPollMs: Long = 500,
) : Device by delegate {

    override fun observe(): LogicalLayout {
        val native = delegate.observe()
        val discovery = cdp.discover(appId)
        // Only on-screen pages merge. A dismissed WebView can linger in /json as a dying
        // target (seen on-device in M4) — falling back to it would pollute native screens
        // with stale web content. Unknown visibility (no description) is allowed through;
        // such targets lack a rect and merge only via native webview bounds.
        val target = discovery.targets.firstOrNull { it.visible != false && it.attached != false }
        if (target == null) {
            // No debuggable page. Only screens that clearly host a WebView get the guard note.
            return if (native.elements.any(::isWebViewNode))
                LogicalLayout(
                    native.elements,
                    webNote = "WebView present but not inspectable (release build or debugging disabled)" +
                        (discovery.failure?.let { " — $it" } ?: ""),
                )
            else native
        }

        awaitLoaded(target)

        val eval = cdp.evaluate(target, WebLayout.EXTRACT_JS)
        val page = (eval as? CdpClient.Eval.Ok)?.value?.let(WebLayout::parsePage)
            ?: return LogicalLayout(
                native.elements,
                webNote = "web content observation failed" +
                    ((eval as? CdpClient.Eval.Failed)?.reason?.let { " — $it" } ?: "") +
                    " (showing native layout only)",
            )
        val rect = target.rect
            ?: native.elements.firstOrNull(::isWebViewNode)?.bounds
            ?: return LogicalLayout(native.elements, webNote = "web content observation failed — WebView bounds unknown")

        val webElements = WebLayout.toLogicalElements(page, rect)

        // Chrome lazily projects the web DOM into the accessibility tree, so native nodes
        // inside the WebView rect can duplicate the CDP elements — partial (no checked state,
        // detached labels), recognizable by a shared DOM id or label. Only those duplicates
        // and anonymous scaffolding are dropped: a native overlay ON TOP of the WebView (e.g.
        // an exit-confirmation dialog, seen in M4) also sits inside the rect but shares
        // nothing with the page, and the model must see it. Scrollables stay: the container
        // is the gesture target for scrolling web pages.
        val webIds = webElements.mapNotNull { it.resourceId }.toHashSet()
        val webLabels = webElements.mapNotNull { it.label }.toHashSet()
        val keptNative = native.elements.filterNot { e ->
            rect.containsCenter(e) && "scrollable" !in e.interactions && when {
                e.resourceId != null -> e.resourceId in webIds
                e.label != null -> e.label in webLabels || e.label.take(80) in webLabels
                else -> true // anonymous non-scrollable node inside the webview: a11y scaffolding
            }
        }
        val merged = (keptNative + webElements)
            .sortedWith(compareBy({ it.center.y }, { it.center.x }))
            .mapIndexed { i, e -> e.copy(id = i) }
        return LogicalLayout(merged)
    }

    private fun Bounds.containsCenter(e: LogicalElement): Boolean =
        e.center.x in x0..x1 && e.center.y in y0..y1

    /** Fold page loading into wait-for-stable: poll readyState up to [loadWaitMs]. */
    private fun awaitLoaded(target: CdpClient.Target) {
        val deadline = System.currentTimeMillis() + loadWaitMs
        while (System.currentTimeMillis() < deadline) {
            val rs = cdp.readyState(target) ?: return // eval failing: let the extraction call report it
            if (rs == "complete") return
            delegate.sleep(loadPollMs)
        }
    }

    private fun isWebViewNode(e: LogicalElement): Boolean =
        e.resourceId?.contains("webview", ignoreCase = true) == true
}
