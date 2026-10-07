package se.sensnology.spotnav.vehicles

import se.sensnology.spotnav.ha.client.VehicleUpdate
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The car's charge target as its editor's slider sets it: 0..100 in whole percent. A car with none stored
 * opens at the target it is planned with, marked as the default, and nothing is written until it is moved.
 */
internal object TargetSlider {
    /** The target a car with none stored is planned with: Home Assistant's default, no higher than its limit. */
    fun default(maxPercent: Double?): Int =
        minOf(VehicleEnergy.DEFAULT_TARGET_SOC_PERCENT, TargetNeed.chargeCeiling(maxPercent))

    /** Where the slider opens: the stored target, whole, or the default. */
    fun opening(current: Double?, maxPercent: Double?): Int =
        current?.roundToInt()?.coerceIn(0, 100) ?: default(maxPercent)

    /** Whether "(default)" follows the value: a target none stored, until the slider is moved. */
    fun showsDefault(current: Double?, moved: Boolean): Boolean = current == null && !moved

    /** What Save writes, or `null` for nothing: never for a slider only drawn, or moved back to what is stored. */
    fun toWrite(current: Double?, moved: Boolean, value: Int): Double? =
        if (!moved || current == value.toDouble()) null else value.toDouble()
}

/**
 * The car's minimum charge level as its editor's slider sets it: Off, then 10..80 in fives, never past the
 * target (Home Assistant's `effective_floor` caps it there too). Also where the minimum shades the plan's
 * target slider.
 */
internal object FloorSlider {
    val STOPS: List<Int?> = listOf<Int?>(null) + VehicleUpdate.MINIMUM_LEVELS
    val LAST: Int = STOPS.size - 1

    private val LOW = VehicleUpdate.MINIMUM_LEVELS.first()
    private val STEP = VehicleUpdate.MINIMUM_LEVELS[1] - LOW

    /** The stop a level stands at: Off is 0, a level between stops goes to the one below it. */
    fun index(value: Int?): Int = if (value == null || value < LOW) 0 else minOf(LAST, (value - LOW) / STEP + 1)

    /** The level at a stop (`null` for Off), clamped to the ends. */
    fun at(index: Int): Int? = STOPS[index.coerceIn(0, LAST)]

    /** The last stop not above [target]: the furthest the minimum can go. */
    fun capIndex(target: Double): Int = if (!target.isFinite()) LAST else index(floor(target).toInt())

    /** A stop moved to, held back at the target. */
    fun clamp(index: Int, target: Double): Int = index.coerceIn(0, capIndex(target))

    /** A minimum to write: [level] `null` turns it off. */
    data class Write(val level: Int?)

    /** What Save writes, or `null` for nothing: never for a slider only drawn, or moved back to what is stored. */
    fun toWrite(current: Int?, moved: Boolean, index: Int): Write? {
        val level = at(index)
        return if (!moved || level == current) null else Write(level)
    }

    /** The minimum's part of a 0..100 track: to [end], its word centred at [label], both fractions of the track. */
    data class Segment(val percent: Int, val end: Float, val label: Float)

    /** The minimum on the plan's target slider, no further than [target]; `null` when there is nothing to shade. */
    fun segment(floor: Int?, target: Double?): Segment? {
        if (floor == null) return null
        val percent = if (target == null || !target.isFinite()) floor else minOf(floor, floor(target).toInt())
        if (percent <= 0) return null
        val end = (percent / 100f).coerceAtMost(1f)
        return Segment(percent, end, end / 2f)
    }

    /** A word with its percent ("min 30 %", "laddmål 50 %"), filled into its template as written. */
    fun markText(template: String, percent: Int, locale: Locale = Locale.ROOT): String =
        String.format(locale, template, percent)
}
