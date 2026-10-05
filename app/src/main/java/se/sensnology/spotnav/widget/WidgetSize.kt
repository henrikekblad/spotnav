package se.sensnology.spotnav.widget

/**
 * The size, in pixels, a widget is actually drawn at. Android states a widget's size as a range: in
 * portrait it is as narrow as the minimum width and as tall as the maximum height; in landscape as wide as
 * the maximum width and as low as the minimum height. Drawing the chart at the maximum of both made it
 * wider than its box in portrait, and the view's `fitXY` then squeezed it sideways. Pure, for the tests.
 */
internal object WidgetSize {
    const val DEFAULT_WIDTH_DP = 320
    const val DEFAULT_HEIGHT_DP = 180

    fun pixels(
        minWidthDp: Int,
        minHeightDp: Int,
        maxWidthDp: Int,
        maxHeightDp: Int,
        portrait: Boolean,
        density: Float
    ): Pair<Int, Int> {
        val widthDp = (if (portrait) minWidthDp else maxWidthDp).takeIf { it > 0 }
            ?: maxOf(minWidthDp, maxWidthDp).takeIf { it > 0 } ?: DEFAULT_WIDTH_DP
        val heightDp = (if (portrait) maxHeightDp else minHeightDp).takeIf { it > 0 }
            ?: maxOf(minHeightDp, maxHeightDp).takeIf { it > 0 } ?: DEFAULT_HEIGHT_DP
        return (widthDp * density).toInt() to (heightDp * density).toInt()
    }
}
