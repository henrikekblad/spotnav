package se.sensnology.spotnav.ui.common

import se.sensnology.spotnav.chart.ViewBounds
import kotlin.math.max
import kotlin.math.min

/**
 * The rectangle that answers a tap, when the thing it belongs to is too small to be one on its own.
 */
internal object ActionTarget {
    /**
     * [box] grown to at least [minimum] on each side, centred on the same point, and held inside
     * [allowed].
     */
    fun hitRect(box: ViewBounds, minimum: Float, allowed: ViewBounds): ViewBounds {
        val horizontal = span(box.left, box.right, minimum, allowed.left, allowed.right)
        val vertical = span(box.top, box.bottom, minimum, allowed.top, allowed.bottom)
        return ViewBounds(horizontal.first, vertical.first, horizontal.second, vertical.second)
    }

    /**
     * One axis of it: the target centred on the box, at least [minimum] wide, inside
     * `allowedLo..allowedHi`.
     */
    private fun span(lo: Float, hi: Float, minimum: Float, allowedLo: Float, allowedHi: Float): Pair<Float, Float> {
        val size = max(hi - lo, minimum)
        var start = (lo + hi) / 2f - size / 2f
        if (start + size > allowedHi) start = allowedHi - size
        if (start < allowedLo) start = allowedLo
        return start to min(start + size, allowedHi)
    }
}
