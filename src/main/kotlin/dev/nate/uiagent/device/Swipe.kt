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

    /** Swipe endpoints [x1, y1, x2, y2] for scrolling [dir] with [target] as the picked element. */
    fun swipeCoords(target: LogicalElement, dir: String, all: List<LogicalElement>): IntArray {
        val vertical = dir == "up" || dir == "down"

        val region: Bounds =
            if (vertical) {
                all.filter { "scrollable" in it.interactions && it.bounds?.contains(target.center) == true }
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

    private fun synthetic(c: Point) = Bounds(maxOf(0, c.x - 300), maxOf(0, c.y - 500), c.x + 300, c.y + 500)

    private fun Bounds.contains(p: Point) = p.x in x0..x1 && p.y in y0..y1
}
