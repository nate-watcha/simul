package dev.nate.uiagent.device

import dev.nate.uiagent.Bounds
import dev.nate.uiagent.LogicalElement
import dev.nate.uiagent.Point

/**
 * Swipe endpoint geometry for scroll gestures, validated on real devices.
 *
 * Screens stack scrollables whose bounds overlap: a tall outer vertical list, a top banner
 * pager, and short horizontal rails (e.g. a "TOP 100" row). Two lessons from real devices:
 *   - Pick the *tallest* focusable scrollable containing the picked element's center. Height
 *     separates the real vertical list from short horizontal rails and non-focusable pagers.
 *   - Use ONE LONG stroke from near the region bottom to near the top (not a short center swipe).
 *     A long vertical drag that starts on a horizontal rail still bubbles to the parent list via
 *     nested scrolling — exactly how a human scroll works — whereas a short stroke barely moves
 *     and can be swallowed by the child. For "down" the finger starts low, so it naturally avoids
 *     the top banner; no special banner dodging is needed. Padding/duration are tunable below.
 */
object SwipeGeometry {

    // Vertical stroke runs from START_PCT to END_PCT of the region height (measured from top).
    // START at 68% (not 90%+) keeps ACTION_DOWN above a bottom promo/CTA overlay; END at 10%
    // makes the stroke long enough to bubble a horizontal rail's drag to the parent list.
    // Same fractions reused for horizontal (measured across the width). Tune here.
    const val SWIPE_START_PCT = 68
    const val SWIPE_END_PCT = 10

    // Swipe duration (ms). Longer reads as a controlled scroll; shorter as a fling.
    const val SWIPE_DURATION_MS = 500

    /**
     * The area a scroll gesture on [target] works in: for a vertical scroll the tallest focusable
     * scrollable under the picked element's centre (see the class comment), for a horizontal one
     * the picked rail itself.
     */
    fun region(target: LogicalElement, vertical: Boolean, all: List<LogicalElement>): Bounds =
        if (vertical) {
            all.filter { "scrollable" in it.interactions && it.bounds?.let { b -> target.center in b } == true }
                .maxWithOrNull(
                    compareBy(
                        { if ("focusable" in it.interactions) 1 else 0 },
                        { it.bounds!!.height },
                    )
                )?.bounds
                ?: target.bounds
                ?: synthetic(target.center)
        } else {
            target.bounds ?: synthetic(target.center)
        }

    /** Swipe endpoints [x1, y1, x2, y2] for scrolling [dir] with [target] as the picked element. */
    fun swipeCoords(target: LogicalElement, dir: String, all: List<LogicalElement>): IntArray {
        val vertical = dir == "up" || dir == "down"
        val region = region(target, vertical, all)

        if (vertical) {
            val cx = region.centerX
            val h = region.height
            val hi = region.y0 + h * SWIPE_START_PCT / 100
            val lo = region.y0 + h * SWIPE_END_PCT / 100
            // "down" reveals lower content: finger travels from the lower point up to the top.
            return if (dir == "down") intArrayOf(cx, hi, cx, lo) else intArrayOf(cx, lo, cx, hi)
        } else {
            val cy = region.centerY
            val w = region.width
            val hi = region.x0 + w * SWIPE_START_PCT / 100
            val lo = region.x0 + w * SWIPE_END_PCT / 100
            // "left" reveals content to the right: finger travels from the right point to the left.
            return if (dir == "left") intArrayOf(hi, cy, lo, cy) else intArrayOf(lo, cy, hi, cy)
        }
    }

    // A drag that moves content by a set distance has to end without a fling: `input swipe` moves at
    // a constant speed and lifts at that speed, so the speed is what is left over as a fling. At this
    // speed the fling adds about as much as the touch slop takes away at the start.
    const val DRAG_PX_PER_SEC = 400
    const val DRAG_MIN_MS = 400

    /**
     * Endpoints [x1, y1, x2, y2] of a drag in [region] that moves its content by [off] px along the
     * axis: positive brings what is below (right of) the middle up (left) by that much. The finger
     * goes down where a scroll gesture's does — the [SWIPE_START_PCT] line, clear of a bottom
     * overlay — when it travels up, and in the middle when it travels down: the far end of the
     * region is where a pinned bar lies over the content.
     */
    fun dragCoords(region: Bounds, vertical: Boolean, off: Int): IntArray {
        val (lo, size) = if (vertical) region.y0 to region.height else region.x0 to region.width
        val from = if (off > 0) lo + size * SWIPE_START_PCT / 100 else lo + size / 2
        val to = from - off
        return if (vertical) intArrayOf(region.centerX, from, region.centerX, to)
        else intArrayOf(from, region.centerY, to, region.centerY)
    }

    fun dragDurationMs(off: Int): Int = maxOf(DRAG_MIN_MS, kotlin.math.abs(off) * 1000 / DRAG_PX_PER_SEC)

    private fun synthetic(c: Point) = Bounds(maxOf(0, c.x - 300), maxOf(0, c.y - 500), c.x + 300, c.y + 500)
}
