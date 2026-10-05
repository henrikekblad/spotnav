package se.sensnology.spotnav.vehicles

import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil

/**
 * The battery's room, as Home Assistant states it (the dashboard's `soc.room_kwh`, Home Assistant
 * 1.9): the wall energy the car can still take, to its own charge limit (else 100 %). Home Assistant
 * caps a manual amount at it and leaves the end of that charge to the car, and so does a target at or
 * above the car's own limit. With it known the kWh slider reaches past it, marks "full" there and ends
 * in "Fill" (`fill_to_limit`): the card's `energyFillTop` and `energySliderMaximum`, rule for rule.
 * Pure, so each rule is unit tested directly.
 */
internal object BatteryRoom {
    private const val EPSILON = 1e-9

    /** The least top of a slider that reaches past the room. */
    private const val FILL_MIN_TOP_KWH = 30.0

    /** The facts of the dashboard's `soc` block the slider's top is worked out from. */
    data class FillFacts(
        val roomKwh: Double?,
        val capacityKwh: Double?,
        val vehicleMaxPercent: Double?,
        val efficiency: Double?
    )

    /**
     * The kWh slider's top with the battery's room known: `min(capacity to the car's limit, max(30 kWh,
     * 2 x room))`, rounded up to the slider's half kWh, never past 100 kWh nor below the room's own
     * step. The capacity to the limit is the wall energy of the whole battery to the car's own charge
     * limit (else 100 %); without a battery size (or an efficiency) only the 100 kWh bound applies.
     * The last step is "Fill". `null` without a room.
     */
    fun fillTopKwh(facts: FillFacts): Double? {
        val room = facts.roomKwh?.takeIf { it.isFinite() } ?: return null
        val capacity = facts.capacityKwh
        val efficiency = facts.efficiency
        val toLimit = if (capacity != null && capacity > 0.0 && efficiency != null && efficiency > 0.0) {
            capacity * TargetNeed.chargeCeiling(facts.vehicleMaxPercent) / 100.0 / efficiency
        } else {
            VehicleEnergy.ENERGY_SLIDER_MAX_KWH
        }
        val wanted = minOf(VehicleEnergy.ENERGY_SLIDER_MAX_KWH, toLimit, maxOf(FILL_MIN_TOP_KWH, 2 * room))
        return minOf(
            VehicleEnergy.ENERGY_SLIDER_MAX_KWH,
            maxOf(stepUp(wanted), stepUp(room), VehicleEnergy.ENERGY_SLIDER_MIN_KWH)
        )
    }

    private fun stepUp(kwh: Double): Double =
        ceil(kwh / VehicleEnergy.ENERGY_SLIDER_STEP_KWH - EPSILON) * VehicleEnergy.ENERGY_SLIDER_STEP_KWH

    /** The slider position of a top ([fillTopKwh]), or the ordinary last step without one. */
    fun topProgress(topKwh: Double?): Int =
        topKwh?.takeIf { it.isFinite() }?.let { VehicleEnergy.energyProgressNearest(it) }
            ?: VehicleEnergy.ENERGY_SLIDER_MAX_PROGRESS

    /**
     * The kWh slider's last step: the top ([fillTopKwh]) when there is one, else the ordinary top. A
     * value already further up (a stored amount above it) keeps its place: the slider never moves a
     * value nobody touched.
     */
    fun energySliderMaxProgress(topKwh: Double?, currentProgress: Int): Int =
        maxOf(topProgress(topKwh), currentProgress.coerceIn(0, VehicleEnergy.ENERGY_SLIDER_MAX_PROGRESS))

    /** Where the "full" mark sits along a track of [maxProgress] steps, 0..1; `null` without a room. */
    fun markFraction(roomKwh: Double?, maxProgress: Int): Float? {
        val room = roomKwh?.takeIf { it.isFinite() } ?: return null
        if (maxProgress <= 0) return 0f
        val steps = (room - VehicleEnergy.ENERGY_SLIDER_MIN_KWH) / VehicleEnergy.ENERGY_SLIDER_STEP_KWH
        return (steps / maxProgress).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * Whether a slider position is "Fill": the last step of a slider whose top is the ordinary one (not a
     * stored amount drawn above it), on a record that has `fill_to_limit` ([supported]).
     */
    fun isFillStep(progress: Int, maxProgress: Int, topKwh: Double?, supported: Boolean): Boolean =
        supported && topKwh != null && maxProgress == topProgress(topKwh) && progress >= maxProgress

    /** The car's own limit as the help line names it: whole percent, and only below 100 %. */
    fun limitNamed(vehicleMaxPercent: Double?): Int? =
        TargetNeed.chargeCeiling(vehicleMaxPercent).takeIf { it < 100 }

    /** An amount as the card writes the room: at most one decimal, halves up, in [locale]. */
    fun kwhFigure(kwh: Double, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 1
            roundingMode = RoundingMode.HALF_UP
        }.format(kwh)

    /** Whether a target is at or above the car's own charge limit (100 % when it states none). */
    fun targetAtCarLimit(targetPercent: Int, vehicleMaxPercent: Double?): Boolean =
        targetPercent >= TargetNeed.chargeCeiling(vehicleMaxPercent)
}
