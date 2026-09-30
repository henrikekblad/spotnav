package se.sensnology.spotnav.vehicles

import se.sensnology.spotnav.chargers.ChargerCapabilities
import se.sensnology.spotnav.chargers.ChargerProfile

/**
 * What the vehicle card is showing, and what its two Home Assistant controls are — the card's own
 * state, as arithmetic.
 */
object VehicleCardState {
    /**
     * One `/status` answer's snapshot: the vehicles the instance reported, and the capabilities
     * that arrived beside them.
     */
    data class Shot(
        val vehicles: List<VehicleStatus>,
        val capabilities: ChargerCapabilities?
    )

    /** Nothing fetched yet: no vehicles, and no capability guessed from an absent field. */
    val NOTHING_FETCHED = Shot(emptyList(), null)

    /** The snapshot after one fetch, where `null` means the fetch did not succeed. */
    fun next(previous: Shot, fetched: Shot?): Shot = fetched ?: previous

    /** What the card's two controls are. */
    data class Controls(val refresh: VehicleRefresh.Control, val limit: ChargeLimit.Control)

    /**
     * The card's controls, from the live snapshot, the effective vehicle and whether either request
     * is in flight.
     */
    fun controls(
        shot: Shot,
        vehicle: VehicleStatus?,
        refreshInFlight: Boolean,
        limitInFlight: Boolean
    ): Controls = Controls(
        refresh = VehicleRefresh.control(
            offered = VehicleRefresh.offered(shot.capabilities),
            hasSelectedVehicle = vehicle != null,
            inFlight = refreshInFlight
        ),
        limit = ChargeLimit.control(
            offered = ChargeLimit.offered(shot.capabilities, vehicle),
            inFlight = limitInFlight
        )
    )
}
