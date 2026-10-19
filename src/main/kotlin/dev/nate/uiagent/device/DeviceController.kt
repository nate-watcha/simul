package dev.nate.uiagent.device

import dev.nate.uiagent.Defaults
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
    private val maxScrollRounds: Int = Defaults.SCROLL_LIMIT,
    private val listener: ActionListener? = null,
    /** Package of the app under test; null = unknown (every screen then counts as the app's). */
    private val app: String? = null,
) {
    private var grounding: LogicalLayout? = null

    /**
     * Single-dump observations: skip the "two identical consecutive observations" settle
     * confirmation, saving one ~1s `android layout` per action. Only the replay happy path
     * turns this on — an observation may then catch a mid-transition frame, so replay polls
     * with [waitUntil] for the screen it expects (target groundable, evidence present) before
     * declaring a failure. LLM mode keeps full stabilization (diff quality depends on it).
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
        if (fastObserve) return device.observeFast()
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
        val diff = computeDiff(before.elements, after.elements, volatileKeys)
        // SPA re-renders churn identity-less elements, so diffs on web-merged observations are
        // noise; return the full merged observation instead. An empty diff is not
        // noise though: it is the NO CHANGE sentinel the prompt rules key on (a tap on a page
        // title went unanswered and the model never learned it was a no-op, 실측 결제 페이지).
        if ((before.hasWeb || after.hasWeb) && !diff.isEmpty) {
            trace.event("webObserve") { it["action"] = action; it["elements"] = after.elements.size.toString() }
            return "(web content: full observation)\n" + renderFullLayout(after.elements) + noteSuffix(after)
        }
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

    /** Resolve a model-provided handle against the current grounding map. */
    fun resolve(target: String): Resolution {
        val elements = (grounding ?: return Resolution.NotFound).elements
        val t = target.trim()

        parseSyntheticHandle(t)?.let { p ->
            elements.firstOrNull { it.center == p }?.let { return Resolution.Match(it) }
            // handle from a stale observation: accept the nearest element within a small radius
            elements.minByOrNull { it.center.dist2(p) }
                ?.takeIf { it.center.dist2(p) <= 50 * 50 }
                ?.let { return Resolution.Match(it) }
            return Resolution.NotFound
        }

        val matches = elements.filter { it.matchesExact(t) }.ifEmpty { elements.filter { it.matchesLoose(t) } }
        return when {
            matches.isEmpty() -> Resolution.NotFound
            matches.size == 1 -> Resolution.Match(matches[0])
            // duplicate labels: a single clickable among them is the intended target
            matches.count { "clickable" in it.interactions } == 1 ->
                Resolution.Match(matches.first { "clickable" in it.interactions })
            else -> Resolution.Ambiguous(matches)
        }
    }

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

    /**
     * scrollToFind result when several elements match. A search is complete once the target is on
     * screen, so this is FOUND, not AMBIGUOUS: the tap-oriented "Repeat the action with one of" wording
     * read as an instruction to tap a handle and made find-only steps overreach (실측, 패키지 셀 2개).
     */
    private fun foundMsg(how: String, target: String, candidates: List<LogicalElement>) = buildString {
        append("FOUND $how")
        if (candidates.size == 1) {
            append(":\n").append(elementJson(candidates.single()))
        } else {
            append(": ${candidates.size} elements match '$target', in screen order:\n")
            candidates.take(8).forEach { append("- ").append(syntheticHandle(it)).append(' ').append(elementJson(it)).append('\n') }
        }
    }.trimEnd()

    // ---------------------------------------------------------------- actions

    fun tap(target: String): String = traced("tap", "target" to target) {
        when (val r = resolve(target)) {
            is Resolution.NotFound -> notFoundMsg(target)
            is Resolution.Ambiguous -> ambiguousMsg(target, r.candidates)
            is Resolution.Match -> {
                if (!fastObserve) plainTextGuard(r.element)?.let { return@traced it }
                lastResolved = r.element
                userTap(r.element)
                "Tapped '${handleOf(r.element)}'.\n" + settleAndDiff("tap $target")
            }
        }
    }

    /**
     * A tap on a window of another package than the app under test (Google Play's purchase
     * sheet, a system dialog) is injected with no automation client attached
     * ([Device.releaseAutomation]): Play drops its "Subscribe" tap while the `android layout`
     * server holds a UiAutomation connection — the same `input tap` lands once the server is
     * stopped (실측 2026-10-19, kloud billing-subscribe-declined; other taps on that sheet were
     * fine). In-app taps keep the server warm; a foreign tap costs the next dump its restart.
     */
    private fun userTap(e: LogicalElement) {
        val pkgs = grounding?.packages
        if (app != null && pkgs != null && pkgs.isNotEmpty() && app !in pkgs) device.releaseAutomation()
        device.tap(e.center.x, e.center.y)
    }

    /**
     * Plain text is not a control. A text with no interactions and no clickable/checkable ancestor
     * (a page title, a heading, a price) is refused without acting — the model otherwise taps
     * whatever label shares a word with the command, gets NO CHANGE, and tries the next one
     * (실측: 결제 페이지, 5회 연속). A text nested in a clickable card or
     * button is fine: the tap lands on its host. Replay (fastObserve) skips the guard and
     * reproduces whatever worked when it was recorded.
     */
    private fun plainTextGuard(e: LogicalElement): String? {
        if (e.interactions.isNotEmpty()) return null
        val all = grounding?.elements ?: return null
        val byId = all.associateBy { it.id }
        var p = e.parentId?.let(byId::get)
        while (p != null) {
            if (p.interactions.any { it == "clickable" || it == "checkable" }) return null
            p = p.parentId?.let(byId::get)
        }
        // The fact alone. What to do next (scroll, another element, FAILED) is the model's call
        // under the prompt rules; a harness-side "scroll down" nudge was tried and dropped — it
        // reads as an instruction on every screen, including ones where the control is simply
        // named differently.
        return "ERROR: '${handleOf(e)}' is plain text (no interactions); tapping it does nothing."
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
                userTap(r.element)
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
            when (val r = resolve(target)) {
                is Resolution.Match -> return@traced foundMsg("without scrolling", target, listOf(r.element))
                is Resolution.Ambiguous -> return@traced foundMsg("without scrolling", target, r.candidates)
                is Resolution.NotFound -> {}
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
                    is Resolution.Match -> return@traced foundMsg("after $round scroll(s) $direction", target, listOf(r.element))
                    is Resolution.Ambiguous -> return@traced foundMsg("after $round scroll(s) $direction", target, r.candidates)
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
     * Re-observe (single dumps, [settleIntervalMs] apart, at most [maxSettlePolls]) until
     * [condition] holds on the current screen, advancing the grounding with every poll. The
     * replay wait: replay knows what the next screen must contain (the target to ground, the
     * step's evidence), so it continues on the first frame that has it instead of paying for
     * a "two identical frames" settle — and a slow load that never settles within the dump's
     * own idle window still passes as soon as the content lands. Returns whether the condition
     * held before the budget ran out; the grounding is the last frame either way.
     */
    fun waitUntil(condition: (List<LogicalElement>) -> Boolean): Boolean {
        var polls = 0
        var met = false
        while (polls < maxSettlePolls && !met) {
            device.sleep(settleIntervalMs)
            polls++
            val now = if (fastObserve) device.observeFast() else device.observe()
            grounding = now
            met = condition(now.elements)
        }
        trace.event("waitUntil") {
            it["polls"] = polls.toString()
            it["met"] = met.toString()
            it["elements"] = (grounding?.elements?.size ?: 0).toString()
        }
        return met
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

/**
 * Harness-side observer of tool executions (record & replay, screenshots). Sees every tool
 * call the controller executes with its structured args; [after] additionally gets the element
 * that tap/type grounded to (null for other tools, guard errors, and ambiguity).
 */
interface ActionListener {
    fun before(tool: String, args: List<Pair<String, String>>) {}
    fun after(tool: String, args: List<Pair<String, String>>, resolved: LogicalElement?, result: String) {}
}
