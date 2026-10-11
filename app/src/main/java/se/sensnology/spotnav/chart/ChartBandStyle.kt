package se.sensnology.spotnav.chart

import kotlin.math.sqrt

/**
 * How a plan band is painted, Android-free so the values and the hatch geometry are testable.
 *
 * Today's bands are solid at the kind's fill opacity ([solidAlpha]), the installed schedule stronger than
 * a proposal so a running window and the waiting proposal after it differ. A band on any other day is a
 * 45-degree "/" hatch in the band colour with no fill between the lines. Every value is the Home Assistant
 * card's `CROSS_PLAN_BAND_STYLE`, its CSS pixels read as dp (spacing perpendicular to the lines); the
 * installed schedule's hatch is denser and stronger than a proposal's.
 */
internal object ChartBandStyle {
    /** Today's solid fill opacity per kind. */
    const val INSTALLED_FILL_OPACITY = 0.16f
    const val PROPOSAL_FILL_OPACITY = 0.07f

    /** Today's solid fill for [kind], out of 255. */
    fun solidAlpha(kind: ChartBandKind): Int = Math.round(
        when (kind) {
            ChartBandKind.INSTALLED -> INSTALLED_FILL_OPACITY
            ChartBandKind.PROPOSAL -> PROPOSAL_FILL_OPACITY
        } * 255f
    )

    /** Degrees clockwise from vertical: lines run from lower left to upper right. */
    const val HATCH_ANGLE_DEGREES = 45

    internal data class Hatch(val spacingDp: Float, val widthDp: Float, val opacity: Float) {
        val alpha: Int get() = Math.round(opacity * 255f)
    }

    val INSTALLED_HATCH = Hatch(spacingDp = 4f, widthDp = 1.25f, opacity = 0.5f)
    val PROPOSAL_HATCH = Hatch(spacingDp = 6f, widthDp = 1f, opacity = 0.3f)

    fun hatch(kind: ChartBandKind): Hatch = when (kind) {
        ChartBandKind.INSTALLED -> INSTALLED_HATCH
        ChartBandKind.PROPOSAL -> PROPOSAL_HATCH
    }

    /** One hatch line, from its lower-left end to its upper-right end, in pixels. */
    internal data class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    /**
     * The "/" lines that cover the rectangle, anchored to [originX] so neighbouring bands share one
     * pattern; each line's ends are the rectangle's own edges or beyond, so the caller clips to it.
     * [spacingPx] is perpendicular to the lines; at 45 degrees it is `spacing * sqrt(2)` along x.
     */
    fun lines(left: Float, top: Float, right: Float, bottom: Float, spacingPx: Float, originX: Float = 0f): List<Line> {
        if (right <= left || bottom <= top || spacingPx <= 0f) return emptyList()
        val step = spacingPx * sqrt(2f)
        val height = bottom - top
        // A line starts at (x, bottom) and ends at (x + height, top); it touches the rectangle when
        // x + height >= left and x <= right.
        val first = originX + Math.floor(((left - height - originX) / step).toDouble()).toFloat() * step
        val found = mutableListOf<Line>()
        var x = first
        while (x <= right) {
            if (x + height >= left) found += Line(x, bottom, x + height, top)
            x += step
        }
        return found
    }
}
