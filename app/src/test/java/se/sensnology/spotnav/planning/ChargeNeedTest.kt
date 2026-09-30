package se.sensnology.spotnav.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.vehicles.VehicleStatus

/** The band a target can be set to, and the honest zero. */
class ChargeNeedTest {
    // the band

    @Test fun theCarsOwnChargeIsTheFloor() {
        assertEquals(52, ChargeNeed.targetRange(52.4, null).currentPercent)
        assertEquals(0, ChargeNeed.targetRange(0.0, null).currentPercent)
        assertEquals(100, ChargeNeed.targetRange(100.0, null).currentPercent)
    }

    @Test fun theFloorRoundsDownSoTheNearestTargetMeansNothingToCharge() {
        // Rounding up would put the nearest reachable target *above* the car's actual level, which
        // asks for a sliver of energy -- and so for exactly the one-slot plan this exists to
        // prevent.
        val range = ChargeNeed.targetRange(52.6, null)

        assertEquals(52, range.currentPercent)
        assertEquals(52, range.clamp(52))
    }

    @Test fun theCarsOwnLimitIsTheCeiling() {
        assertEquals(80, ChargeNeed.targetRange(52.0, 80.0).limitPercent)
        assertEquals(80, ChargeNeed.targetRange(52.0, 80.9).limitPercent)
        // A limit reported above 100 is still a percentage ceiling of 100.
        assertEquals(100, ChargeNeed.targetRange(52.0, 200.0).limitPercent)
        assertEquals(100, ChargeNeed.targetRange(52.0, null).limitPercent)
    }

    @Test fun aCarAboveItsOwnLimitLeavesABandOfOnePoint() {
        // Charging it is not something a target can express: the band collapses to where the car
        // already is.
        val range = ChargeNeed.targetRange(90.0, 80.0)

        assertEquals(80, range.currentPercent)
        assertEquals(80, range.limitPercent)
        // Which is the slider's own two bounds meeting: nothing to drag between.
        assertEquals(range.currentPercent, range.clamp(0))
        assertEquals(range.limitPercent, range.clamp(100))
    }

    @Test fun anUnreadableStateOfChargeDoesNotBreakTheBand() {
        for (soC in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val range = ChargeNeed.targetRange(soC, 80.0)

            assertEquals(0, range.currentPercent)
            assertEquals(80, range.limitPercent)
        }
    }

    // clamping a stored or dragged target

    @Test fun aTargetBelowTheCurrentChargeIsRaisedToIt() {
        val range = ChargeNeed.targetRange(52.4, 80.0)

        assertEquals(52, range.clamp(40))
        assertEquals(52, range.clamp(0))
        assertEquals(52, range.clamp(52))
    }

    @Test fun aTargetAboveTheCarsLimitComesBackDown() {
        val range = ChargeNeed.targetRange(52.0, 80.0)

        assertEquals(80, range.clamp(100))
        assertEquals(80, range.clamp(81))
        assertEquals(70, range.clamp(70))
    }

    // the honest zero

    private fun car(socPercent: Double = 52.0, capacityKwh: Double? = 77.5) = VehicleStatus(
        id = "car-one",
        name = "Family car",
        socPercent = socPercent,
        batteryCapacityKwh = capacityKwh,
        capacitySource = if (capacityKwh != null) "detected" else "unknown"
    )

    @Test fun aTargetAtTheCurrentChargeNeedsNothing() {
        // The remaining honest zero: the slider's floor is still a target, and it asks for no
        // energy.
        assertTrue(ChargeNeed.nothingToCharge(PlanDriver.TARGET_SOC, car(socPercent = 52.0), 52, null))
    }

    @Test fun aTargetBelowTheCurrentChargeNeedsNothingToo() {
        // A stored target older than the car's current charge (it charged since) or a car above its
        // own limit both land here rather than at the floor.
        assertTrue(ChargeNeed.nothingToCharge(PlanDriver.TARGET_SOC, car(socPercent = 52.4), 52, null))
        assertTrue(ChargeNeed.nothingToCharge(PlanDriver.TARGET_SOC, car(socPercent = 90.0), 80, null))
    }

    @Test fun anyTargetAboveTheCurrentChargeHasSomethingToCharge() {
        assertFalse(ChargeNeed.nothingToCharge(PlanDriver.TARGET_SOC, car(socPercent = 52.0), 53, null))
        assertFalse(ChargeNeed.nothingToCharge(PlanDriver.TARGET_SOC, car(socPercent = 52.4), 53, null))
        assertFalse(ChargeNeed.nothingToCharge(PlanDriver.TARGET_SOC, car(socPercent = 0.0), 100, null))
    }

    @Test fun aNeedTooSmallToBeWholeKwhIsStillSomething() {
        assertFalse(ChargeNeed.nothingToCharge(PlanDriver.TARGET_SOC, car(socPercent = 52.4), 53, null))
    }

    @Test fun nothingIsDerivableSoNothingIsClaimed() {
        // No capacity, no vehicle: the mode is not in force and the caller has already fallen back
        // to the energy control.
        assertFalse(ChargeNeed.nothingToCharge(PlanDriver.TARGET_SOC, car(), null, null))
        assertFalse(ChargeNeed.nothingToCharge(PlanDriver.TARGET_SOC, car(capacityKwh = null), 80, null))
        assertFalse(ChargeNeed.nothingToCharge(PlanDriver.TARGET_SOC, null, 80, 77.5))
    }

    @Test fun kwhModeIsUnaffectedEvenWhenTheCarNeedsNothing() {
        // The control's own range starts at 1 kWh, so this cannot arise there; the rule is scoped
        // to the mode that can produce it.
        assertFalse(ChargeNeed.nothingToCharge(PlanDriver.KWH, car(socPercent = 90.0), 80, null))
        assertFalse(ChargeNeed.nothingToCharge(PlanDriver.KWH, null, null, null))
        assertFalse(ChargeNeed.nothingToCharge(PlanDriver.KWH, car(), 80, null))
    }
}
