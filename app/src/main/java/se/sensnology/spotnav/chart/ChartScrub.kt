package se.sensnology.spotnav.chart

import kotlin.math.abs
import kotlin.math.hypot

/**
 * What the finger on the graph is doing. An [UNDECIDED] gesture is a tap candidate, a [SCRUB] owns the
 * rest of the gesture, and a [SCROLL] belongs to the enclosing page.
 */
internal enum class ChartGesture { UNDECIDED, SCRUB, SCROLL }

/**
 * The graph's gesture decisions, kept Android-free so the timing can be tested. A swipe that starts on
 * the graph must still scroll the page, while a sideways drag across it must not.
 */
internal object ChartScrub {
    /**
     * What [gesture] becomes after the finger moved by [dx],[dy] from touch-down, given touch [slop].
     * A decided gesture stays decided. A diagonal tie goes to the page, since a page that refuses to
     * scroll feels worse than a scrub nobody meant.
     */
    fun onMove(gesture: ChartGesture, dx: Float, dy: Float, slop: Float): ChartGesture = when {
        gesture != ChartGesture.UNDECIDED -> gesture
        hypot(dx, dy) < slop -> ChartGesture.UNDECIDED
        abs(dx) > abs(dy) -> ChartGesture.SCRUB
        else -> ChartGesture.SCROLL
    }

    /**
     * The finger's x clamped to the plot's span, so the first and last intervals (half an interval in
     * from the edges) stay reachable.
     */
    fun clampedX(x: Float, left: Float, right: Float): Float = x.coerceIn(left, right)

}
