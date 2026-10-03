package se.sensnology.spotnav.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.vehicles.VehicleStatus

/** What drives the plan, and what the plan's energy input is because of it. */
class PlanModeTest {
    private fun car(socPercent: Double = 52.0, capacityKwh: Double? = null) = VehicleStatus(
        id = "car-one",
        name = "Family car",
        socPercent = socPercent,
        batteryCapacityKwh = capacityKwh,
        capacitySource = if (capacityKwh != null) "detected" else "unknown"
    )

    // the stored mode

    @Test fun anAbsentStoredModeIsKwh() {
        assertEquals(PlanDriver.KWH, PlanDriver.of(null))
    }

    @Test fun anUnrecognisedStoredModeIsKwhToo() {
        assertEquals(PlanDriver.KWH, PlanDriver.of(""))
        assertEquals(PlanDriver.KWH, PlanDriver.of("something-newer"))
    }

    @Test fun aStoredModeRoundTripsThroughItsStoredForm() {
        for (driver in PlanDriver.entries) {
            assertEquals(driver, PlanDriver.of(driver.name))
        }
    }

    // what is offered

    @Test fun withoutAVehicleOnlyKwhIsOffered() {
        assertEquals(setOf(PlanDriver.KWH), PlanMode.availableDrivers(null, 77.0))
        assertFalse(PlanMode.supportsTargetSoc(null, 77.0))
    }

    @Test fun detectionAloneNeverOffersTargetSoc() {
        // The car is detected -- but nothing says how big its battery is, so a percentage cannot be
        // turned into energy. kWh is simply the only mode.
        val detected = car(capacityKwh = null)

        assertEquals(setOf(PlanDriver.KWH), PlanMode.availableDrivers(detected, null))
        assertFalse(PlanMode.supportsTargetSoc(detected, null))
    }

    @Test fun aReportedCapacityOffersBoth() {
        val detected = car(capacityKwh = 77.5)

        assertEquals(setOf(PlanDriver.KWH, PlanDriver.TARGET_SOC), PlanMode.availableDrivers(detected, null))
        assertTrue(PlanMode.supportsTargetSoc(detected, null))
    }

    @Test fun aCapacityTheUserEnteredOffersBoth() {
        val detected = car(capacityKwh = null)

        assertTrue(PlanMode.supportsTargetSoc(detected, 77.5))
    }

    @Test fun aCapacityThatIsNotOneOffersOnlyKwh() {
        val detected = car()

        for (capacity in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse("capacity=$capacity", PlanMode.supportsTargetSoc(detected, capacity))
        }
    }

    // which mode is in force

    @Test fun theStoredModeIsInForceWhileItCanWork() {
        val detected = car(capacityKwh = 77.5)

        assertEquals(PlanDriver.TARGET_SOC, PlanMode.effectiveDriver(PlanDriver.TARGET_SOC, detected, null))
        assertEquals(PlanDriver.KWH, PlanMode.effectiveDriver(PlanDriver.KWH, detected, null))
    }

    @Test fun aStoredModeThatCannotWorkFallsBackToKwh() {
        // The car went away, or its capacity became unknown: the plan is driven by the (visible,
        // editable) energy control instead.
        assertEquals(PlanDriver.KWH, PlanMode.effectiveDriver(PlanDriver.TARGET_SOC, null, null))
        assertEquals(PlanDriver.KWH, PlanMode.effectiveDriver(PlanDriver.TARGET_SOC, car(), null))
    }

    // the derived energy

    @Test fun theDerivedEnergyIsWhatTheTargetNeeds() {
        // 52 % -> 80 % of 77.5 kWh = 21.7 kWh, rounded up to the next half kWh: a plan must deliver at
        // least what the target needs.
        assertEquals(22.0, PlanMode.derivedEnergyKwh(car(socPercent = 52.0, capacityKwh = 77.5), 80, null)!!, 0.0)
        assertEquals(21.5, PlanMode.derivedEnergyKwh(car(socPercent = 52.5, capacityKwh = 77.5), 80, null)!!, 0.0)
    }

    @Test fun theDerivedEnergyNeverExceedsTheEnergyControlsRange() {
        // The energy control is the 1..100 kWh slider, so the derived figure lives in the same
        // range -- the plan's input comes from that slider whichever mode is driving it.
        assertEquals(100.0, PlanMode.derivedEnergyKwh(car(socPercent = 0.0, capacityKwh = 300.0), 100, null)!!, 0.0)
        assertEquals(1.0, PlanMode.derivedEnergyKwh(car(socPercent = 90.0, capacityKwh = 77.5), 90, null)!!, 0.0)
    }

    @Test fun theDerivedEnergyIsNullWhenNothingCanBeDerived() {
        assertNull(PlanMode.derivedEnergyKwh(null, 80, 77.5))
        assertNull(PlanMode.derivedEnergyKwh(car(), 80, null))
    }

    // the plan's energy input
}
