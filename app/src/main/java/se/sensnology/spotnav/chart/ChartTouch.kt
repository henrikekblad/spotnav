package se.sensnology.spotnav.chart

/** A point in the drawn frame's own pixels, where its [ChartMetrics] apply. */
internal data class FramePoint(val x: Float, val y: Float)

/**
 * From a touch on the view to the frame on screen. The frame is drawn at the view's width when it is
 * drawn, but the renderer caps a bitmap's size (an unfolded screen is wider than that cap) and the view
 * may already be another size than the frame it shows; the view fits the frame inside itself, centred
 * and scaled uniformly (`FIT_CENTER`). A touch is therefore mapped by the view's size at the time of the
 * touch and the frame's own size, never by a width remembered from an earlier draw. Android-free.
 */
internal object ChartTouch {
    /**
     * The frame pixel under ([x], [y]) on a view whose content area is [viewWidth] x [viewHeight] starting
     * at ([padLeft], [padTop]), showing a [frameWidth] x [frameHeight] frame; `null` when either has no size.
     */
    fun toFrame(
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
        padLeft: Int = 0,
        padTop: Int = 0
    ): FramePoint? {
        if (viewWidth <= 0 || viewHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) return null
        val scale = minOf(viewWidth / frameWidth.toFloat(), viewHeight / frameHeight.toFloat())
        val offsetX = padLeft + (viewWidth - frameWidth * scale) / 2f
        val offsetY = padTop + (viewHeight - frameHeight * scale) / 2f
        return FramePoint((x - offsetX) / scale, (y - offsetY) / scale)
    }
}
