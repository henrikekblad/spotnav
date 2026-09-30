package se.sensnology.spotnav.chart

/** A view's box in touch-event coordinates; plain numbers so the decision is testable without Android. */
internal data class ViewBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    /** Whether the point is inside; half-open like the framework's hit test, so a zero-sized box holds nothing. */
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

/**
 * Whether a touch that begins at a point puts the chart's readout away.
 *
 * A touch anywhere except the chart or its own readout dismisses the selection; those two handle
 * their own taps (see `PlanChartView`). Android-free so the rule is testable.
 */
internal object ChartDismissal {
    /**
     * [selectionActive] is whether there is anything to put away, [callout] the readout's box or
     * `null` while hidden, and [x],[y] the point the gesture began at.
     */
    fun clearsSelection(
        selectionActive: Boolean,
        chart: ViewBounds,
        callout: ViewBounds?,
        x: Float,
        y: Float
    ): Boolean = selectionActive && !chart.contains(x, y) && callout?.contains(x, y) != true

    /**
     * Whether the end of the gesture puts the readout away. The decision is taken at the first point
     * but acted on only at the end, because removing the readout at `ACTION_DOWN` would shift the
     * controls under a touch already in progress.
     *
     * [beganOnGeneration] is the screen generation the gesture began on, or `null` when it was the
     * chart's own or there was nothing to clear; [generation] is the screen's rebuild counter. A
     * rebuild mid-gesture leaves nothing to clear.
     */
    fun clearsOnUp(beganOnGeneration: Int?, generation: Int): Boolean =
        beganOnGeneration != null && beganOnGeneration == generation
}
