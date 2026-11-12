package dev.nate.uiagent.device

import dev.nate.uiagent.Defaults
import dev.nate.uiagent.Origin
import dev.nate.uiagent.ProcessFailure
import dev.nate.uiagent.Trace
import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.LogicalLayout

/**
 * The harness core. Owns the grounding map (label -> element -> coordinates), executes
 * gestures through [Device], and answers every action with the LAYOUT DIFF it caused — the
 * tool result IS the verification data.
 *
 * Guards live here, not in the prompt: unknown labels return errors without acting,
 * ambiguous labels return candidates without acting, scroll rounds are capped.
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
    /** Where the device time of this controller's run is accounted (see [DeviceCost]). */
    val meter: DeviceMeter = DeviceMeter(),
) {
    private var grounding: LogicalLayout? = null

    /** One observation, timed: [fast] is the dump without the CLI's idle wait ([Device.observeFast]). */
    private fun dump(fast: Boolean = false): LogicalLayout =
        meter.timed(meter::dump) { if (fast) device.observeFast() else device.observe() }

    private fun pause(ms: Long) = meter.timed(meter::waited) { device.sleep(ms) }

    private fun gesture(act: () -> Unit) = meter.timed(meter::gesture, act)

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

    /**
     * Drop the grounding: the app was started over (a scenario's next section), so the last observation
     * is of a screen that is gone and the next step has to look first.
     */
    fun forget() {
        grounding = null
    }

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
        if (fastObserve) return dump(fast = true)
        val t0 = System.currentTimeMillis()
        var prev = dump()
        repeat(maxSettlePolls) { poll ->
            pause(settleIntervalMs)
            val next = dump()
            if (signature(next.elements) == signature(prev.elements)) return next.also { settled(poll + 2, t0, true) }
            val churn = computeDiff(prev.elements, next.elements)
            (churn.added + churn.removed + churn.changed).forEach { e ->
                churnCounts.merge(diffKey(e), 1, Int::plus)
            }
            prev = next
        }
        // the frame handed on is one of a screen still changing — and the loop cost every poll it had
        meter.unsettled()
        settled(maxSettlePolls + 1, t0, false)
        return prev
    }

    private fun settled(dumps: Int, t0: Long, settled: Boolean) = trace.event("settle") {
        it["dumps"] = dumps.toString()
        it["ms"] = (System.currentTimeMillis() - t0).toString()
        it["settled"] = settled.toString()
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
        gesture {
            if (app != null && pkgs != null && pkgs.isNotEmpty() && app !in pkgs) device.releaseAutomation()
            device.tap(e.center.x, e.center.y)
        }
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

    /**
     * Tap the field, then set its text through the device ([Device.inputText]) — the tap gives it
     * focus and is the fallback target when the field has no resource-id. A refused input
     * (not editable, gone before the set) is a tool error the model sees, not a crashed step.
     *
     * A field is named by its accessible name, and that name is not always on the editable node:
     * a Compose text field holding a value is labelled by the value, and its contentDescription
     * is a text node inside it — beside the heading above the field, which carries the same word
     * (실측 2026-11-12, stone 프로필 수정 "링크": two labels matched, neither takes input). So a
     * name that lands on text goes to the field that text sits in, and of several elements
     * sharing the name the one field is the target. An empty field needs none of this: the
     * adapter gives it the name.
     */
    fun type(target: String, text: String): String = traced("type", "target" to target, "text" to text) {
        val field = when (val r = resolve(target)) {
            is Resolution.NotFound -> return@traced notFoundMsg(target)
            is Resolution.Ambiguous -> r.candidates.mapNotNull(::fieldOf).distinctBy { it.id }.singleOrNull()
                ?: return@traced ambiguousMsg(target, r.candidates)
            is Resolution.Match -> when {
                "focusable" in r.element.interactions -> r.element
                else -> fieldOf(r.element) ?: return@traced when (r.element.kind) {
                    // the fact alone, as in [plainTextGuard]: where the field is is the model's call
                    "label" -> "ERROR: '${handleOf(r.element)}' is text, not an input field, and is not inside one; " +
                        "no input field with that name is on the current screen."
                    else -> "ERROR: '${handleOf(r.element)}' is not focusable; cannot type into it."
                }
            }
        }
        lastResolved = field
        userTap(field)
        pause(300)
        try {
            gesture { device.inputText(text, field.resourceId.takeIf { field.origin == Origin.NATIVE }) }
        } catch (ex: ProcessFailure) {
            val why = ex.stderr.trim().lineSequence().firstOrNull { it.isNotBlank() } ?: "input failed"
            return@traced "ERROR: could not type into '${handleOf(field)}': $why"
        }
        "Typed \"$text\" into '${handleOf(field)}'.\n" + settleAndDiff("type $target")
    }

    /** The editable [e] is, or the nearest one it sits inside; null when it is neither. */
    private fun fieldOf(e: LogicalElement): LogicalElement? {
        val byId = grounding?.elements.orEmpty().associateBy { it.id }
        var at: LogicalElement? = e
        while (at != null) {
            if ("editable" in at.interactions) return at
            at = at.parentId?.let(byId::get)
        }
        return null
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
                gesture { device.swipe(c[0], c[1], c[2], c[3], SwipeGeometry.SWIPE_DURATION_MS) }
                null
            }
        }
    }

    /**
     * Scroll until [target] is on screen. An element the scroll container still cuts at the edge
     * the scroll brings content in from is not there yet: a dump clips bounds to the container,
     * so one that ends exactly on that edge is partly below (beside) the fold, and what belongs
     * with it is wholly out of view — the heading of a text field showing 15px at the bottom,
     * the field under it not on screen, answered "FOUND without scrolling" and the type that
     * followed had nothing to type into (실측 2026-11-12, stone 프로필 수정 "링크"). When the
     * container cannot scroll further, the cut element is all there is and it is found.
     */
    fun scrollToFind(scrollable: String, target: String, direction: String): String =
        traced("scrollToFind", "scrollable" to scrollable, "target" to target, "direction" to direction) {
            fun seen(): List<LogicalElement>? = when (val r = resolve(target)) {
                is Resolution.Match -> listOf(r.element)
                is Resolution.Ambiguous -> r.candidates
                is Resolution.NotFound -> null
            }
            fun inView(found: List<LogicalElement>): Boolean {
                val box = (resolve(scrollable) as? Resolution.Match)?.element
                return found.any { !cutOff(it, box, direction) }
            }
            fun after(rounds: Int) = if (rounds == 0) "without scrolling" else "after $rounds scroll(s) $direction"

            var found = seen()
            if (found != null && inView(found)) return@traced foundMsg(after(0), target, found)
            var rounds = 0
            var stalled = false
            while (rounds < maxScrollRounds) {
                val before = grounding?.elements ?: emptyList()
                scrollOnce(scrollable, direction)?.let { return@traced it } // guard error
                val after = stabilize()
                grounding = after
                if (signature(after.elements) == signature(before)) {
                    stalled = true
                    break
                }
                rounds++
                found = seen()
                if (found != null && inView(found)) return@traced foundMsg(after(rounds), target, found)
            }
            found?.let { return@traced foundMsg(after(rounds), target, it) }
            val why = if (stalled) "the layout stopped changing (end reached)" else "scroll limit reached"
            "NOT_FOUND: '$target' did not appear scrolling $direction in '$scrollable' — $why. " +
                "Try a different direction or scrollable once, or report FAILED."
        }

    /**
     * Is [e] cut by the edge of the scroll container [box] that scrolling [direction] brings
     * content in from? Only an element inside [box] with nothing nested in it: a card or a
     * pager that fills the container reaches its edge without being cut.
     */
    private fun cutOff(e: LogicalElement, box: LogicalElement?, direction: String): Boolean {
        val b = e.bounds ?: return false
        val s = box?.bounds ?: return false
        val all = grounding?.elements.orEmpty()
        if (all.any { it.parentId == e.id }) return false
        val byId = all.associateBy { it.id }
        if (generateSequence(e.parentId?.let(byId::get)) { it.parentId?.let(byId::get) }.none { it.id == box.id }) return false
        return when (direction) {
            "down" -> b.y1 >= s.y1
            "up" -> b.y0 <= s.y0
            "left" -> b.x1 >= s.x1   // "left" reveals what is to the right
            "right" -> b.x0 <= s.x0
            else -> false
        }
    }

    fun back(): String = traced("back") {
        gesture { device.keyBack() }
        "Pressed back.\n" + settleAndDiff("back")
    }

    fun waitForChange(): String = traced("waitForChange") {
        val before = grounding ?: return@traced fullLayout()
        for (i in 1..maxSettlePolls) {
            pause(settleIntervalMs)
            val now = dump()
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
            pause(settleIntervalMs)
            polls++
            val now = dump(fast = fastObserve)
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
