package se.sensnology.spotnav.ui.common

/** Where a slider's tick labels go, in the slider's own coordinate system. */
internal object SliderTicks {
    /**
     * The span a thumb's centre travels over, as two x coordinates: [startCenter] at fraction 0 and
     * [endCenter] at fraction 1.
     */
    data class Rail(val startCenter: Float, val endCenter: Float) {
        val travel: Float get() = endCenter - startCenter
    }

    /**
     * The rail a laid-out `SeekBar` draws its thumb on, from the facts the framework's own formula
     * uses, or `null` when there is no rail to lay ticks against: no width, or a thumb wider than
     * the view leaves no travel at all.
     */
    fun rail(viewWidth: Int, paddingLeft: Int, paddingRight: Int, thumbWidth: Int, thumbOffset: Int): Rail? {
        if (viewWidth <= 0) return null
        val available = viewWidth - paddingLeft - paddingRight - thumbWidth + 2 * thumbOffset
        if (available <= 0) return null
        val start = paddingLeft - thumbOffset + thumbWidth / 2f
        return Rail(start, start + available)
    }

    /**
     * The normalized position of [value]: the same one SeekBar gives its progress, `(value - min) /
     * (max - min)`. A degenerate range answers 0 rather than dividing by zero, and the range is
     * clamped so a caller cannot place a tick outside the track.
     */
    fun fraction(value: Int, min: Int, max: Int): Float =
        if (max <= min) 0f else ((value - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f)

    /**
     * The x a value's tick sits at: its fraction along [rail], mirrored when the slider reads right
     * to left, so fraction 0 is where the slider's own thumb puts progress 0.
     */
    fun centerX(value: Int, min: Int, max: Int, rail: Rail, mirrored: Boolean): Float =
        centerAtFraction(fraction(value, min, max), rail, mirrored)

    /** The same, for a fraction that is already known. */
    fun centerAtFraction(fraction: Float, rail: Rail, mirrored: Boolean): Float {
        val along = if (mirrored) 1f - fraction else fraction
        return rail.startCenter + along * rail.travel
    }

    /**
     * Where a label of [labelWidth] starts so that its *centre* is [centerX], clamped only so the
     * label's own rendered bounds stay inside the container.
     */
    fun labelLeft(centerX: Float, labelWidth: Float, containerLeft: Float, containerRight: Float): Float {
        val centred = centerX - labelWidth / 2f
        val furthestRight = containerRight - labelWidth
        return centred.coerceIn(containerLeft, maxOf(containerLeft, furthestRight))
    }
}
