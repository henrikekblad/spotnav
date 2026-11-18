package se.sensnology.spotnav.ui.settings

import se.sensnology.spotnav.ha.heat.HeatWrite

/** The offset slider's arithmetic: −3 to +3 °C (the API's range) in half degrees, 13 stops, 0 in the middle. */
internal object OffsetSlider {
    private const val STEP = 0.5

    /** The slider's top position: the number of steps from the lowest value to the highest. */
    fun steps(): Int = Math.round((HeatWrite.OFFSET_MAX - HeatWrite.OFFSET_MIN) / STEP).toInt()

    /** The degrees at a position (0 is −3, [steps] is +3). */
    fun value(progress: Int): Double = HeatWrite.OFFSET_MIN + progress.coerceIn(0, steps()) * STEP

    /** The position of a value: the nearest half degree, kept inside the range. */
    fun progress(value: Double): Int = Math.round((value - HeatWrite.OFFSET_MIN) / STEP).toInt().coerceIn(0, steps())
}
