package se.sensnology.spotnav.planning

import se.sensnology.spotnav.chargers.ChargerPhases
import se.sensnology.spotnav.vehicles.VehicleEnergy
import se.sensnology.spotnav.vehicles.VehicleStatus
import kotlin.math.floor

/** What a target state of charge can ask for, and what the plan does when the answer is "nothing". */
object ChargeNeed {
    /**
     * The band of targets one vehicle can be planned to: from the charge it has now
     * ([currentPercent]) up to the highest charge it will accept ([limitPercent], from the car
     * itself, or 100 % when it reports none).
     */
    data class TargetRange(val currentPercent: Int, val limitPercent: Int) {
        /** [percent] pulled into the band: never below the car's charge, never above its limit. */
        fun clamp(percent: Int): Int = percent.coerceIn(currentPercent, limitPercent)
    }

    /** The band for a car [socPercent] full, whose own limit is [vehicleMaxPercent]. */
    fun targetRange(socPercent: Double, vehicleMaxPercent: Double?): TargetRange {
        // The ceiling is VehicleEnergy's own rule for "never above what the vehicle reports" (read
        // with the highest possible target, so all it does is bound it).
        val limit = VehicleEnergy.effectiveTargetSocPercent(HIGHEST_TARGET_PERCENT, vehicleMaxPercent)
        val current = if (socPercent.isFinite()) floor(socPercent).toInt() else 0
        return TargetRange(currentPercent = current.coerceIn(0, limit), limitPercent = limit)
    }

    /**
     * Whether the plan should decline to plan: target-SoC mode is driving it and the energy the
     * chosen target implies is zero, so the car needs nothing.
     */
    fun nothingToCharge(
        driver: PlanDriver,
        vehicle: VehicleStatus?,
        targetSocPercent: Int?,
        rememberedCapacityKwh: Double?
    ): Boolean {
        if (driver != PlanDriver.TARGET_SOC) return false
        val selected = vehicle ?: return false
        val needed = VehicleEnergy
            .suggestedChargingKwh(selected, targetSocPercent, rememberedCapacityKwh)
            ?: return false
        return needed <= 0.0
    }

    private const val HIGHEST_TARGET_PERCENT = 100

    /** The band for a *paired* charger: the whole scale, 0-100, with no floor at the current charge. */
    val pairedRange = TargetRange(currentPercent = 0, limitPercent = HIGHEST_TARGET_PERCENT)
}
