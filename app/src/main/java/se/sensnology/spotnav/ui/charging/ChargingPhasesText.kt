package se.sensnology.spotnav.ui.charging

import se.sensnology.spotnav.ha.dashboard.DashboardChargingPhases
import se.sensnology.spotnav.planning.ChargingPlanner

/**
 * The read-only phases line of a paired charger: "Charges on 3 phases · nominal ≈ 6.9 kW", with
 * the car's limit named when the vehicle, not the wiring, sets the count. Kept apart from the view
 * so it is tested directly, like [se.sensnology.spotnav.chargers.ChargerPhases].
 */
internal object ChargingPhasesText {
    /** The nominal power of [amps] on [phases], in kW: the figure the line states. */
    fun nominalKw(amps: Int, phases: Int): Double = ChargingPlanner.powerKw(amps, phases)

    /**
     * The line for [block] at [amps] (`null` while no current is stated, which leaves the power
     * out), from the words [charges] ("Charges on 3 phases"), [nominal] (its power) and
     * [limitedByVehicle].
     */
    fun line(
        block: DashboardChargingPhases,
        amps: Int?,
        charges: () -> String,
        nominal: (Double) -> String,
        limitedByVehicle: String
    ): String {
        val head = listOfNotNull(charges(), amps?.let { nominal(nominalKw(it, block.phases)) }).joinToString(" · ")
        return if (block.limitedByVehicle) "$head. $limitedByVehicle" else head
    }
}
