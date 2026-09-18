package dev.nate.uiagent.device

import dev.nate.uiagent.Trace
import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.LogicalLayout

/**
 * The harness core. Owns the grounding map (label -> element -> coordinates), executes
 * gestures through [Device], and answers every action with the LAYOUT DIFF it caused — the
 * tool result IS the verification data.
 *
 * Guards live here, not in the prompt: unknown labels return errors without acting,
 * ambiguous labels return candidates without acting, scroll rounds are capped, non-ASCII
 * typing is rejected.
 */
class DeviceController(
    private val device: Device,
    private val trace: Trace = Trace.DISABLED,
    private val settleIntervalMs: Long = 400,
    private val maxSettlePolls: Int = 7,
    private val maxScrollRounds: Int = 3,
    private val listener: ActionListener? = null,
) {
    private var grounding: LogicalLayout? = null

    /**
     * Single-dump observations: skip the "two identical consecutive observations" settle
     * confirmation, saving one ~3.5s `android layout` per action. Only the replay happy path
     * turns this on — an observation may then catch a mid-transition frame, so replay retries
     * failures once after an explicit [restabilize]. LLM mode keeps full stabilization (diff
     * quality depends on it).
     */
    var fastObserve: Boolean = false

    /** Keys seen changing on their own during settle polling, and how often. ≥2 = volatile. */
    private val churnCounts = mutableMapOf<String, Int>()
    val volatileKeys: Set<String> get() = churnCounts.filterValues { it >= 2 }.keys

    /** Elements of the last observation — the harness-side view of the current screen. */
    val currentElements: List<LogicalElement> get() = grounding?.elements ?: emptyList()

    var verdict: Verdict? = null
        private set

    /** Clear the verdict so the same controller can execute another isolated step. */
    fun resetVerdict() {
        verdict = null
    }

    data class Verdict(val status: String, val reason: String)

    // ---------------------------------------------------------------- observation

    /** Full model-facing layout; also (re)establishes the grounding map. */
    fun fullLayout(): String {
        val l = stabilize()
        grounding = l
        trace.event("observe") { it["elements"] = l.elements.size.toString() }
        return renderFullLayout(l.elements) + noteSuffix(l)
    }

    /** Web-channel annotation (e.g. "WebView present but not inspectable") for tool results. */
    private fun noteSuffix(l: LogicalLayout): String = l.webNote?.let { "\nNOTE: $it" } ?: ""

    /**
     * Poll until two consecutive observations look identical (screen settled), up to
     * [maxSettlePolls]. Keys that keep changing between polls with no action in between are
     * auto-changing content (carousels) and get suppressed from future diffs.
     */
    private fun stabilize(): LogicalLayout {
        if (fastObserve) return device.observe()
        var prev = device.observe()
        repeat(maxSettlePolls) {
            device.sleep(settleIntervalMs)
            val next = device.observe()
            if (signature(next.elements) == signature(prev.elements)) return next
            val churn = computeDiff(prev.elements, next.elements)
            (churn.added + churn.removed + churn.changed).forEach { e ->
                churnCounts.merge(diffKey(e), 1, Int::plus)
            }
            prev = next
        }
        return prev
    }

    /** Settle after an action, diff against the previous grounding, advance the grounding. */
    private fun settleAndDiff(action: String): String {
        val before = grounding ?: return fullLayout()
        val after = stabilize()
        grounding = after
        // SPA re-renders churn identity-less elements, so diffs on web-merged observations are
        // noise; return the full merged observation instead (plan M2).
        if (before.hasWeb || after.hasWeb) {
            trace.event("webObserve") { it["action"] = action; it["elements"] = after.elements.size.toString() }
            return "(web content: full observation)\n" + renderFullLayout(after.elements) + noteSuffix(after)
        }
        val diff = computeDiff(before.elements, after.elements, volatileKeys)
        val rendered = renderDiff(diff)
        trace.event("diff") {
            it["action"] = action
            it["added"] = diff.added.size.toString()
            it["removed"] = diff.removed.size.toString()
            it["changed"] = diff.changed.size.toString()
        }
        return rendered + noteSuffix(after)
    }

    // ---------------------------------------------------------------- resolution

    sealed interface Resolution {
        data class Match(val element: LogicalElement) : Resolution
        data class Ambiguous(val candidates: List<LogicalElement>) : Resolution
        data object NotFound : Resolution
    }

    private val syntheticRegex = Regex("@x(-?\\d+)y(-?\\d+)")

    /** Resolve a model-provided handle against the current grounding map. */
    fun resolve(target: String): Resolution {
        val elements = (grounding ?: return Resolution.NotFound).elements
        val t = target.trim()

        syntheticRegex.matchEntireOrNull(t)?.let { m ->
            val (x, y) = m.destructured.let { (a, b) -> a.toInt() to b.toInt() }
            elements.firstOrNull { it.center.x == x && it.center.y == y }?.let { return Resolution.Match(it) }
            // handle from a stale observation: accept the nearest element within a small radius
            elements.minByOrNull { sq(it.center.x - x) + sq(it.center.y - y) }
                ?.takeIf { sq(it.center.x - x) + sq(it.center.y - y) <= 50 * 50 }
                ?.let { return Resolution.Match(it) }
            return Resolution.NotFound
        }

        val exact = elements.filter { e ->
            e.label?.equals(t, ignoreCase = true) == true ||
                e.label?.split(" / ")?.any { it.equals(t, ignoreCase = true) } == true ||
                e.resourceId?.equals(t, ignoreCase = true) == true
        }
        val matches = exact.ifEmpty {
            elements.filter { it.label?.contains(t, ignoreCase = true) == true }
        }
        return when {
            matches.isEmpty() -> Resolution.NotFound
            matches.size == 1 -> Resolution.Match(matches[0])
            // duplicate labels: a single clickable among them is the intended target
            matches.count { "clickable" in it.interactions } == 1 ->
                Resolution.Match(matches.first { "clickable" in it.interactions })
            else -> Resolution.Ambiguous(matches)
        }
    }

    private fun sq(v: Int) = v * v

    private fun notFoundMsg(target: String): String {
        val webHint = if (grounding?.hasWeb == true)
            " If it is web content, it may be inside a cross-origin iframe (not observable)." else ""
        return "ERROR: no element matching '$target' on the current screen.$webHint " +
            "Use scrollToFind to search for it, or layout() to re-observe."
    }

    private fun ambiguousMsg(target: String, candidates: List<LogicalElement>) = buildString {
        append("AMBIGUOUS: ${candidates.size} elements match '$target'. Repeat the action with one of:\n")
        candidates.take(8).forEach { append("- ").append(syntheticHandle(it)).append(' ').append(elementJson(it)).append('\n') }
    }.trimEnd()

    // ---------------------------------------------------------------- actions

    fun tap(target: String): String = traced("tap", "target" to target) {
        when (val r = resolve(target)) {
            is Resolution.NotFound -> notFoundMsg(target)
            is Resolution.Ambiguous -> ambiguousMsg(target, r.candidates)
            is Resolution.Match -> {
                lastResolved = r.element
                device.tap(r.element.center.x, r.element.center.y)
                "Tapped '${handleOf(r.element)}'.\n" + settleAndDiff("tap $target")
            }
        }
    }

    fun type(target: String, text: String): String = traced("type", "target" to target, "text" to text) {
        if (text.any { it.code > 127 })
            return@traced "ERROR: non-ASCII text is not supported. The command cannot be completed if it requires typing this text."
        when (val r = resolve(target)) {
            is Resolution.NotFound -> notFoundMsg(target)
            is Resolution.Ambiguous -> ambiguousMsg(target, r.candidates)
            is Resolution.Match -> {
                if ("focusable" !in r.element.interactions)
                    return@traced "ERROR: '${handleOf(r.element)}' is not focusable; cannot type into it."
                lastResolved = r.element
                device.tap(r.element.center.x, r.element.center.y)
                device.sleep(300)
                device.inputText(text)
                "Typed \"$text\" into '${handleOf(r.element)}'.\n" + settleAndDiff("type $target")
            }
        }
    }

    fun scroll(scrollable: String, direction: String): String =
        traced("scroll", "scrollable" to scrollable, "direction" to direction) {
        scrollOnce(scrollable, direction)?.let { return@traced it } // guard error
        "Scrolled $direction.\n" + settleAndDiff("scroll $scrollable $direction")
    }

    /** Perform one scroll gesture. Returns an error message, or null on success. */
    private fun scrollOnce(scrollable: String, direction: String): String? {
        if (direction !in setOf("up", "down", "left", "right"))
            return "ERROR: invalid direction '$direction' (use up|down|left|right)."
        return when (val r = resolve(scrollable)) {
            is Resolution.NotFound -> notFoundMsg(scrollable)
            is Resolution.Ambiguous -> ambiguousMsg(scrollable, r.candidates)
            is Resolution.Match -> {
                if ("scrollable" !in r.element.interactions)
                    return "ERROR: '${handleOf(r.element)}' is not scrollable."
                val all = grounding?.elements ?: emptyList()
                val c = SwipeGeometry.swipeCoords(r.element, direction, all)
                device.swipe(c[0], c[1], c[2], c[3], SwipeGeometry.SWIPE_DURATION_MS)
                null
            }
        }
    }

    fun scrollToFind(scrollable: String, target: String, direction: String): String =
        traced("scrollToFind", "scrollable" to scrollable, "target" to target, "direction" to direction) {
            (resolve(target) as? Resolution.Match)?.let {
                return@traced "FOUND without scrolling:\n${elementJson(it.element)}"
            }
            var stalled = false
            for (round in 1..maxScrollRounds) {
                val before = grounding?.elements ?: emptyList()
                scrollOnce(scrollable, direction)?.let { return@traced it } // guard error
                val after = stabilize()
                grounding = after
                if (signature(after.elements) == signature(before)) {
                    stalled = true
                    break
                }
                when (val r = resolve(target)) {
                    is Resolution.Match -> return@traced "FOUND after $round scroll(s) $direction:\n${elementJson(r.element)}"
                    is Resolution.Ambiguous -> return@traced "FOUND after $round scroll(s) $direction (multiple matches):\n" +
                        ambiguousMsg(target, r.candidates)
                    is Resolution.NotFound -> {} // keep scrolling
                }
            }
            val why = if (stalled) "the layout stopped changing (end reached)" else "scroll limit reached"
            "NOT_FOUND: '$target' did not appear scrolling $direction in '$scrollable' — $why. " +
                "Try a different direction or scrollable once, or report FAILED."
        }

    fun back(): String = traced("back") {
        device.keyBack()
        "Pressed back.\n" + settleAndDiff("back")
    }

    fun waitForChange(): String = traced("waitForChange") {
        val before = grounding ?: return@traced fullLayout()
        for (i in 1..maxSettlePolls) {
            device.sleep(settleIntervalMs)
            val now = device.observe()
            if (signature(now.elements) != signature(before.elements)) {
                val settled = stabilize()
                grounding = settled
                if (before.hasWeb || settled.hasWeb) {
                    return@traced "(web content: full observation)\n" +
                        renderFullLayout(settled.elements) + noteSuffix(settled)
                }
                val diff = computeDiff(before.elements, settled.elements, volatileKeys)
                return@traced renderDiff(diff) + noteSuffix(settled)
            }
        }
        "NO CHANGE — the screen did not change while waiting."
    }

    /**
     * Force a fully stabilized re-observation and advance the grounding, regardless of
     * [fastObserve]. The replay retry path: when a fast observation grounded nothing, the
     * screen may simply not have settled yet.
     */
    fun restabilize() {
        val was = fastObserve
        fastObserve = false
        try {
            grounding = stabilize()
        } finally {
            fastObserve = was
        }
        trace.event("restabilize") { it["elements"] = (grounding?.elements?.size ?: 0).toString() }
    }

    /** The layout() tool: same as [fullLayout] but recorded as a tool call in the trace. */
    fun layoutTool(): String = traced("layout") { fullLayout() }

    fun report(status: String, reason: String): String = traced("report", "status" to status, "reason" to reason) {
        val s = status.trim().uppercase()
        if (s != "PASSED" && s != "FAILED")
            return@traced "ERROR: status must be PASSED or FAILED."
        verdict = Verdict(s, reason)
        "Verdict recorded: $s."
    }

    // ---------------------------------------------------------------- plumbing

    /** Set by tap/type when resolution succeeds, so the listener sees what was grounded. */
    private var lastResolved: LogicalElement? = null

    private inline fun traced(tool: String, vararg args: Pair<String, String>, body: () -> String): String {
        trace.event("toolCall") { it["tool"] = tool; it["args"] = args.joinToString("|") { p -> p.second } }
        listener?.before(tool, args.toList())
        lastResolved = null
        val result = body()
        trace.event("toolResult") { it["tool"] = tool; it["result"] = result.take(2000) }
        listener?.after(tool, args.toList(), lastResolved, result)
        return result
    }
}

private fun Regex.matchEntireOrNull(s: String): MatchResult? = matchEntire(s)

/**
 * Harness-side observer of tool executions (record & replay, screenshots). Sees every tool
 * call the controller executes with its structured args; [after] additionally gets the element
 * that tap/type grounded to (null for other tools, guard errors, and ambiguity).
 */
interface ActionListener {
    fun before(tool: String, args: List<Pair<String, String>>) {}
    fun after(tool: String, args: List<Pair<String, String>>, resolved: LogicalElement?, result: String) {}
}
