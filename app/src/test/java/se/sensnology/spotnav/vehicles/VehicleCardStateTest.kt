package se.sensnology.spotnav.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerCapabilities
import se.sensnology.spotnav.chargers.ChargerProfile

/** The vehicle card's live snapshot, and the two Home Assistant controls drawn from it. */
class VehicleCardStateTest {
    private fun vehicle(id: String = "car-1", limit: Double? = 80.0) = VehicleStatus(
        id = id,
        name = "Car $id",
        socPercent = 42.0,
        targetSocPercentMax = limit
    )

    private fun shot(capabilities: ChargerCapabilities?, vehicles: List<VehicleStatus> = listOf(vehicle())) =
        VehicleCardState.Shot(vehicles, capabilities)

    private fun controls(shot: VehicleCardState.Shot, vehicle: VehicleStatus?, refreshInFlight: Boolean = false, limitInFlight: Boolean = false) =
        VehicleCardState.controls(shot, vehicle, refreshInFlight, limitInFlight)

    @Test fun anUnknownCapabilityHidesTheReReadAction() {
        // Before any successful status there is nothing to support the action with, and an absent
        // field is not a `true`.
        val nothing = VehicleCardState.NOTHING_FETCHED
        assertFalse(controls(nothing, vehicle()).refresh.visible)
        assertEquals(null, nothing.capabilities)
        assertTrue(nothing.vehicles.isEmpty())
    }

    @Test fun aLiveTrueWithAVehicleShowsTheActionImmediately() {
        val shown = VehicleCardState.next(NOTHING, shot(ChargerCapabilities(refreshVehicle = true)))

        val refresh = controls(shown, vehicle()).refresh
        assertTrue(refresh.visible)
        assertTrue(refresh.enabled)
    }

    @Test fun aLiveFalseHidesAnActionThatWasJustShowing() {
        val showing = VehicleCardState.next(NOTHING, shot(ChargerCapabilities(refreshVehicle = true)))
        assertTrue(controls(showing, vehicle()).refresh.visible)

        // The same update path, with a status that says the integration cannot: no rebuild, no
        // second fetch, no waiting for the screen to be recreated.
        val hidden = VehicleCardState.next(showing, shot(ChargerCapabilities(refreshVehicle = false)))
        assertFalse(controls(hidden, vehicle()).refresh.visible)
    }

    @Test fun aLiveNullHidesAnActionThatWasJustShowing() {
        val showing = VehicleCardState.next(NOTHING, shot(ChargerCapabilities(refreshVehicle = true)))

        assertFalse(controls(VehicleCardState.next(showing, shot(ChargerCapabilities(refreshVehicle = false))), vehicle()).refresh.visible)
        assertFalse(controls(VehicleCardState.next(showing, shot(null)), vehicle()).refresh.visible)
    }

    @Test fun noEffectiveVehicleLeavesTheActionDrawnButUnpressable() {
        // The capability is what decides whether the action is drawn at all; the vehicle decides
        // whether it can be pressed.
        val shown = VehicleCardState.next(NOTHING, shot(ChargerCapabilities(refreshVehicle = true)))

        val refresh = controls(shown, null).refresh
        assertTrue(refresh.visible)
        assertFalse(refresh.enabled)
    }

    @Test fun anInFlightRequestStaysVisibleAndUnpressableUntilItReturns() {
        val shown = VehicleCardState.next(NOTHING, shot(ChargerCapabilities(refreshVehicle = true)))

        val running = controls(shown, vehicle(), refreshInFlight = true).refresh
        assertTrue("the spin needs something to spin", running.visible)
        assertFalse("a second press would spend the rate limit twice", running.enabled)
    }

    @Test fun aFailedFetchKeepsTheLastSuccessfulSnapshot() {
        val successful = VehicleCardState.next(
            NOTHING,
            shot(ChargerCapabilities(refreshVehicle = true), listOf(vehicle("car-1")))
        )

        // A failure is handed over as `null`: the vehicles the reader is still looking at, and the
        // capability that came with them, stay exactly as they were.
        val afterFailure = VehicleCardState.next(successful, null)

        assertEquals(successful, afterFailure)
        assertTrue(controls(afterFailure, vehicle()).refresh.visible)
    }

    @Test fun theChargeLimitRowFollowsTheSameLiveSnapshot() {
        // The neighbour that already read the live capability keeps doing so, and now reads it from
        // the same snapshot the re-read control does -- so the two cannot drift apart on the
        // question they share.
        val withLimit = VehicleCardState.next(
            NOTHING,
            shot(ChargerCapabilities(refreshVehicle = true, setChargeLimit = true))
        )
        assertTrue(controls(withLimit, vehicle()).limit.visible)

        val withoutLimit = VehicleCardState.next(withLimit, shot(ChargerCapabilities(setChargeLimit = false)))
        val after = controls(withoutLimit, vehicle())
        assertFalse(after.limit.visible)
        assertFalse("and the re-read action went with the same status", after.refresh.visible)
    }

    @Test fun theLimitRowIsDisabledWhileAWriteIsOnItsWay() {
        val shown = VehicleCardState.next(NOTHING, shot(ChargerCapabilities(setChargeLimit = true)))

        val limit = controls(shown, vehicle(), limitInFlight = true).limit
        assertTrue(limit.visible)
        assertFalse(limit.enabled)
    }

    @Test fun aVehicleWithoutALiveLimitOffersNoLimitRowEvenWhenAdvertised() {
        // Both halves of that rule live in [ChargeLimit.offered]; this pins that the snapshot path
        // reaches it intact.
        val shown = VehicleCardState.next(NOTHING, shot(ChargerCapabilities(setChargeLimit = true)))

        assertFalse(controls(shown, vehicle(limit = null)).limit.visible)
    }

    private companion object {
        val NOTHING = VehicleCardState.NOTHING_FETCHED
    }
}
