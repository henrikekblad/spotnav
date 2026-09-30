package se.sensnology.spotnav.planning

import se.sensnology.spotnav.chargers.ChargerPhases
import se.sensnology.spotnav.vehicles.VehicleEnergy
import se.sensnology.spotnav.vehicles.VehicleFacts
import se.sensnology.spotnav.vehicles.VehicleStatus
import kotlin.math.ceil

/**
 * What drives a plan: a number of kWh the user enters, or a target state of charge that implies
 * one.
 */
enum class PlanDriver {
    /** The energy control is the input; the state of charge is not involved. */
    KWH,

    /** The target state of charge is the input; the energy figure follows. */
    TARGET_SOC;

    companion object {
        /**
         * The driver a stored value means. Anything unrecognised — an absent preference, or a value
         * this build does not know — is [KWH], never a crash and never a silent switch to
         * target-SoC mode.
         */
        fun of(stored: String?): PlanDriver = entries.firstOrNull { it.name == stored } ?: KWH

        /** The stored form of [driver], the inverse of [of]. */
        fun storedForm(driver: PlanDriver): String = driver.name
    }
}

/**
 * Which plan drivers are available, which one is in force, and what the plan's energy input is
 * because of it — kept free of Android so each rule can be unit tested, like [VehicleFacts],
 * [VehicleEnergy], [ChargerPhases] and [PlanReadout].
 */
object PlanMode {
    /** What a plan can be driven by, given the vehicle (and remembered capacity) at hand. */
    fun availableDrivers(vehicle: VehicleStatus?, rememberedCapacityKwh: Double?): Set<PlanDriver> =
        if (supportsTargetSoc(vehicle, rememberedCapacityKwh)) setOf(PlanDriver.KWH, PlanDriver.TARGET_SOC)
        else setOf(PlanDriver.KWH)

    /**
     * Whether target-SoC mode can work at all: a vehicle whose state of charge is known, and a
     * capacity to convert it with — the vehicle's own reported one, or the one the user entered for
     * it, resolved through [VehicleEnergy.effectiveCapacityKwh] so a caller cannot forget to.
     */
    fun supportsTargetSoc(vehicle: VehicleStatus?, rememberedCapacityKwh: Double?): Boolean {
        if (vehicle == null) return false
        val capacity = VehicleEnergy.effectiveCapacityKwh(vehicle, rememberedCapacityKwh)
        return capacity != null && capacity.isFinite() && capacity > 0.0
    }

    /** The driver in force: [stored] while it is available, otherwise kWh. */
    fun effectiveDriver(stored: PlanDriver, vehicle: VehicleStatus?, rememberedCapacityKwh: Double?): PlanDriver =
        if (stored in availableDrivers(vehicle, rememberedCapacityKwh)) stored else PlanDriver.KWH

    /**
     * The energy the current state of charge needs to reach [targetSocPercent] on this vehicle, in
     * whole kWh, or `null` when there is no capacity to compute from.
     */
    fun derivedEnergyKwh(vehicle: VehicleStatus?, targetSocPercent: Int?, rememberedCapacityKwh: Double?): Int? {
        val selected = vehicle ?: return null
        // VehicleEnergy's own pure functions are what work this out: the target clamped to the
        // vehicle's limit, the capacity from either source, and the energy between the two states
        // of charge.
        val needed = VehicleEnergy
            .suggestedChargingKwh(selected, targetSocPercent, rememberedCapacityKwh)
            ?: return null
        return ceil(needed).toInt()
            .coerceIn(VehicleEnergy.ENERGY_SLIDER_MIN_KWH, VehicleEnergy.ENERGY_SLIDER_MAX_KWH)
    }
}
