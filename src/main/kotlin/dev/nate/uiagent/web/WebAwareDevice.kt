package dev.nate.uiagent.web

import dev.nate.uiagent.Bounds
import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.LogicalLayout
import dev.nate.uiagent.Origin
import dev.nate.uiagent.depthsOf
import dev.nate.uiagent.device.Device
import dev.nate.uiagent.pruneReparent
import kotlin.math.abs

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
        val composedOfWeb = webComposition(webLabels)
        val keptNative = pruneReparent(native.elements) { e ->
            !(rect.containsCenter(e) && "scrollable" !in e.interactions && when {
                e.resourceId != null -> e.resourceId in webIds
                e.label != null -> e.label in webLabels || e.label.take(80) in webLabels || composedOfWeb(e.label)
                else -> true // anonymous non-scrollable node inside the webview: a11y scaffolding
            })
        }
        // The page's elements nest under the native WebView node. Its resource-id can vanish once
        // Chrome projects a11y nodes, so the fallback anchor is the native scrollable whose bounds
        // are the WebView rect (실측: the payment page's WebView had no id but kept its bounds).
        // Web siblings keep reading order; the rest is the native tree's order.
        val web = readingOrder(webElements)
        val hostIdx = keptNative.indexOfFirst(::isWebViewNode).takeIf { it >= 0 }
            ?: keptNative.indexOfFirst { "scrollable" in it.interactions && it.bounds?.let { b -> sameRect(b, rect) } == true }
        val combined = if (hostIdx < 0) keptNative + web else {
            val depths = depthsOf(keptNative)
            var end = hostIdx + 1
            while (end < keptNative.size && depths[end] > depths[hostIdx]) end++
            val hostId = keptNative[hostIdx].id
            keptNative.subList(0, end) + web.map { it.copy(parentId = hostId) } + keptNative.subList(end, keptNative.size)
        }
        // Re-id in pre-order; parents precede children, so the old→new map is ready when needed.
        val newId = HashMap<Int, Int>()
        val merged = combined.mapIndexed { i, e ->
            if (e.origin == Origin.NATIVE) newId[e.id] = i
            e.copy(id = i, parentId = e.parentId?.let { newId[it] })
        }
        return LogicalLayout(merged, webNote = WebLayout.scrollHint(page), webOverflow = WebLayout.overflow(page))
    }

    /**
     * Chrome's a11y projection concatenates sibling spans without separators ("할인소장11,000 캐시
     * 7,700 캐시" for four web texts), so label equality misses it. A native label that splits
     * entirely into web labels (whitespace ignored) is that projection.
     */
    private fun webComposition(webLabels: Set<String>): (String) -> Boolean {
        val tokens = webLabels.map { it.filterNot(Char::isWhitespace) }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return { false }
        return { label ->
            val s = label.filterNot(Char::isWhitespace)
            if (s.isEmpty()) false else {
                val memo = HashMap<Int, Boolean>()
                fun from(i: Int): Boolean = i == s.length || memo.getOrPut(i) {
                    tokens.any { t -> s.startsWith(t, i) && from(i + t.length) }
                }
                from(0)
            }
        }
    }

    /** Same on-screen rect, every edge within 8 px (the WebView node itself, not an enclosing scroller). */
    private fun sameRect(a: Bounds, b: Bounds): Boolean =
        abs(a.x0 - b.x0) <= 8 && abs(a.y0 - b.y0) <= 8 && abs(a.x1 - b.x1) <= 8 && abs(a.y1 - b.y1) <= 8

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

    companion object {
    /**
         * Reading order for web elements: top-to-bottom by row, left-to-right within a row. A plain
         * (center.y, center.x) sort scrambles rows whose members differ in font size by a pixel or
         * two (a struck-out price listed after the price next to it — 실측). Elements whose center
         * falls inside the running vertical band shared by the row's members join that row.
         */
            internal fun readingOrder(elements: List<LogicalElement>): List<LogicalElement> {
            val rows = mutableListOf<MutableList<LogicalElement>>()
            var bandTop = 0
            var bandBottom = -1
            for (e in elements.sortedBy { it.center.y }) {
                val top = e.bounds?.y0 ?: e.center.y
                val bottom = e.bounds?.y1 ?: e.center.y
                if (rows.isNotEmpty() && e.center.y in bandTop..bandBottom) {
                    rows.last() += e
                    bandTop = maxOf(bandTop, top)
                    bandBottom = minOf(bandBottom, bottom)
                } else {
                    rows += mutableListOf(e)
                    bandTop = top
                    bandBottom = bottom
                }
            }
            return rows.flatMap { row -> row.sortedWith(compareBy({ it.bounds?.x0 ?: it.center.x }, { it.center.y })) }
        }
    }
}
