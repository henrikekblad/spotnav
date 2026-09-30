package se.sensnology.spotnav.vehicles

import kotlin.math.ceil
import kotlin.math.floor

/**
 * The vehicle-related numbers the widget config screen computes, kept free of any Android
 * dependency so each rule can be unit tested directly — including the "never guess" ones, which are
 * the whole reason this is a separate, pure object rather than inline arithmetic in the activity.
 */
object VehicleEnergy {
    /**
     * The `energy` slider's own bounds in `EnergyController` — the same `1..100` its
     * `slider(t(R.string.charging), 1, 100, …)` call uses. Kept here so [energySliderProgress]
     * cannot drift away from that call.
     */
    const val ENERGY_SLIDER_MIN_KWH = 1
    const val ENERGY_SLIDER_MAX_KWH = 100

    /**
     * Where the target-soc slider starts before the user has ever moved it. Stored as `null` until
     * they do, so "not chosen yet" stays distinguishable from "chosen 80".
     */
    const val DEFAULT_TARGET_SOC_PERCENT = 80

    /**
     * How much energy is needed to bring [socPercent] up to [targetSocPercent] on a battery of
     * [batteryCapacityKwh], or `null` when there is no capacity to compute from.
     */
    fun neededKwh(socPercent: Double, targetSocPercent: Double, batteryCapacityKwh: Double?): Double? {
        if (batteryCapacityKwh == null || !batteryCapacityKwh.isFinite() || batteryCapacityKwh <= 0.0) {
            return null
        }
        return ((targetSocPercent - socPercent) / 100.0 * batteryCapacityKwh).coerceAtLeast(0.0)
    }

    /**
     * The `SeekBar.progress` the energy slider needs to represent [neededKwh], using that slider's
     * own `(value - min).coerceIn(0, max - min)` mapping — its `chargingKwh` is `progress + 1`, so
     * a suggestion of 35 kWh is progress 34, never 35.
     */
    fun energySliderProgress(
        neededKwh: Double,
        sliderMinKwh: Int = ENERGY_SLIDER_MIN_KWH,
        sliderMaxKwh: Int = ENERGY_SLIDER_MAX_KWH
    ): Int = ceil(neededKwh).toInt().coerceIn(sliderMinKwh, sliderMaxKwh) - sliderMinKwh

    /**
     * The target state of charge to work with for one vehicle: what the user stored for this
     * profile, else [DEFAULT_TARGET_SOC_PERCENT], never above what the vehicle itself reports as
     * its maximum.
     */
    fun effectiveTargetSocPercent(storedTargetSocPercent: Int?, vehicleMaxPercent: Double?): Int {
        val vehicleCeiling = vehicleMaxPercent
            ?.takeIf { it.isFinite() }
            ?.let { floor(it).toInt().coerceIn(0, 100) }
            ?: 100
        return (storedTargetSocPercent ?: DEFAULT_TARGET_SOC_PERCENT).coerceIn(0, vehicleCeiling)
    }

    /**
     * The capacity to compute with for one vehicle: what Home Assistant reported, else what the
     * user remembered for that vehicle id, else `null` — which is what makes the one-time capacity
     * prompt appear.
     */
    fun effectiveCapacityKwh(vehicle: VehicleStatus, rememberedKwh: Double?): Double? =
        vehicle.batteryCapacityKwh ?: rememberedKwh

    /**
     * The whole suggestion the UI shows for one selected vehicle: its state of charge, the user's
     * target (defaulted and clamped to the vehicle's own limit) and the capacity from either
     * source.
     */
    fun suggestedChargingKwh(
        vehicle: VehicleStatus,
        targetSocPercent: Int?,
        rememberedCapacityKwh: Double?
    ): Double? = neededKwh(
        socPercent = vehicle.socPercent,
        targetSocPercent = effectiveTargetSocPercent(targetSocPercent, vehicle.targetSocPercentMax).toDouble(),
        batteryCapacityKwh = effectiveCapacityKwh(vehicle, rememberedCapacityKwh)
    )
}
