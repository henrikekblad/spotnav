package se.sensnology.spotnav.vehicles

import kotlin.math.ceil

/**
 * The battery's room, as Home Assistant states it (the dashboard's `soc.room_kwh`, Home Assistant
 * 1.9): the wall energy the car can still take, to its own charge limit (else 100 %). Home Assistant
 * caps a manual amount at it and leaves the end of that charge to the car, and so does a target at or
 * above the car's own limit. Pure, so each rule is unit tested directly.
 */
internal object BatteryRoom {
    private const val EPSILON = 1e-9

    /**
     * The kWh slider's last step: the room rounded up to the slider's half kWh when Home Assistant
     * states one (never more than the ordinary top), else the ordinary top. A value already further up
     * (a stored amount above the room) keeps its place: the slider never moves a value nobody touched.
     */
    fun energySliderMaxProgress(roomKwh: Double?, currentProgress: Int): Int {
        if (roomKwh == null || !roomKwh.isFinite()) return VehicleEnergy.ENERGY_SLIDER_MAX_PROGRESS
        val steps = ceil((roomKwh - VehicleEnergy.ENERGY_SLIDER_MIN_KWH) / VehicleEnergy.ENERGY_SLIDER_STEP_KWH - EPSILON)
            .toInt()
            .coerceIn(0, VehicleEnergy.ENERGY_SLIDER_MAX_PROGRESS)
        return maxOf(steps, currentProgress.coerceIn(0, VehicleEnergy.ENERGY_SLIDER_MAX_PROGRESS))
    }

    /** Whether an amount is all the battery has room for, so the car ends the charge; `false` without a room. */
    fun energyAtRoom(kwh: Double, roomKwh: Double?): Boolean =
        roomKwh != null && roomKwh.isFinite() && kwh.isFinite() && kwh >= roomKwh - EPSILON

    /** Whether a target is at or above the car's own charge limit (100 % when it states none). */
    fun targetAtCarLimit(targetPercent: Int, vehicleMaxPercent: Double?): Boolean =
        targetPercent >= TargetNeed.chargeCeiling(vehicleMaxPercent)
}
