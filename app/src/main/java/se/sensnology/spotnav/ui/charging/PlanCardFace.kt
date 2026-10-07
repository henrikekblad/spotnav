package se.sensnology.spotnav.ui.charging

import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.planning.PlanDriver
import se.sensnology.spotnav.planning.PlanMode
import se.sensnology.spotnav.vehicles.PairedTarget
import se.sensnology.spotnav.vehicles.VehicleIdentification
import se.sensnology.spotnav.vehicles.VehicleStatus

/**
 * The planning card's heading and its "Charge by" row, decided without Android: the title names the
 * car Home Assistant plans for, and the row offers the target only where it can work.
 */
internal object PlanCardFace {
    /** The chooser's choices, in the Home Assistant card's order: energy, then the target. */
    val CHARGE_BY_CHOICES = listOf(PlanDriver.KWH, PlanDriver.TARGET_SOC)

    /** The car Home Assistant plans for, by name; `null` for the phone's own plan or no known car. */
    fun plannedCarName(paired: Dashboard?): String? = paired?.let { VehicleIdentification.plannedName(it) }

    /** "Planning for EV6" from [forCarTemplate] while a planned car is known, else [plain]. */
    fun title(plain: String, forCarTemplate: String, paired: Dashboard?): String =
        plannedCarName(paired)?.let { String.format(forCarTemplate, it) } ?: plain

    /**
     * What the plan can be driven by. A paired charger follows the Home Assistant card's own rule: the
     * target when a charge-level source resolves, or when the record already stands on it (then it is
     * shown as it is, and can still be undone). The phone's own plan needs a car with a capacity.
     */
    fun availableDrivers(
        paired: Dashboard?,
        stored: PlanDriver,
        vehicle: VehicleStatus?,
        rememberedCapacityKwh: Double?
    ): Set<PlanDriver> = when {
        paired == null -> PlanMode.availableDrivers(vehicle, rememberedCapacityKwh)
        PairedTarget.available(paired) || stored == PlanDriver.TARGET_SOC -> setOf(PlanDriver.KWH, PlanDriver.TARGET_SOC)
        else -> setOf(PlanDriver.KWH)
    }

    /** The driver in force: [stored] while it is available, otherwise energy. */
    fun effectiveDriver(stored: PlanDriver, available: Set<PlanDriver>): PlanDriver =
        if (stored in available) stored else PlanDriver.KWH

    /** The row is there only when there is a choice to make, as the toggle before it was. */
    fun chargeByShown(available: Set<PlanDriver>): Boolean = PlanDriver.TARGET_SOC in available
}
