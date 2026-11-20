package se.sensnology.spotnav.ui.settings

import se.sensnology.spotnav.ha.heat.HeatWrite

/** A slider's arithmetic in half degrees from [min] to [max]; position 0 is [min]. */
internal open class StepSlider(private val min: Double, private val max: Double) {
    /** The slider's top position: the number of steps from the lowest value to the highest. */
    fun steps(): Int = Math.round((max - min) / STEP).toInt()

    /** The degrees at a position (0 is the lowest, [steps] the highest). */
    fun value(progress: Int): Double = min + progress.coerceIn(0, steps()) * STEP

    /** The position of a value: the nearest half degree, kept inside the range. */
    fun progress(value: Double): Int = Math.round((value - min) / STEP).toInt().coerceIn(0, steps())

    private companion object {
        const val STEP = 0.5
    }
}

/** The offset slider: −3 to +3 °C (the API's range), 13 stops, 0 in the middle. */
internal object OffsetSlider : StepSlider(HeatWrite.OFFSET_MIN, HeatWrite.OFFSET_MAX)

/** The away setback's slider: 1 to 6 °C, 11 stops. */
internal object AwaySlider : StepSlider(HeatWrite.AWAY_DELTA_MIN, HeatWrite.AWAY_DELTA_MAX)
