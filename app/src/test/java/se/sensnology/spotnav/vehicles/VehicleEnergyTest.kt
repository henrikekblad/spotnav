package se.sensnology.spotnav.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleEnergyTest {
    private val car = VehicleStatus(
        id = "vehicle-one",
        name = "Family car",
        socPercent = 42.5,
        targetSocPercentMax = 80.0,
        batteryCapacityKwh = 77.5,
        capacitySource = "detected"
    )

    // neededKwh

    @Test fun neededKwhIsNullWhenTheCapacityIsUnknown() {
        assertNull(VehicleEnergy.neededKwh(42.5, 80.0, null))
    }

    @Test fun neededKwhIsNeverNegativeWhenAlreadyAboveTheTarget() {
        assertEquals(0.0, VehicleEnergy.neededKwh(91.0, 80.0, 60.0)!!, 1e-9)
        assertEquals(0.0, VehicleEnergy.neededKwh(80.0, 80.0, 60.0)!!, 1e-9)
    }

    @Test fun neededKwhScalesWithCapacityAndTargetDelta() {
        // 37.5 percentage points of a 77.5 kWh battery: 0.375 * 77.5.
        assertEquals(29.0625, VehicleEnergy.neededKwh(42.5, 80.0, 77.5)!!, 1e-9)
        assertEquals(38.75, VehicleEnergy.neededKwh(30.0, 80.0, 77.5)!!, 1e-9)
    }

    @Test fun neededKwhTreatsANonPositiveOrNonFiniteCapacityAsUnknown() {
        assertNull(VehicleEnergy.neededKwh(20.0, 80.0, 0.0))
        assertNull(VehicleEnergy.neededKwh(20.0, 80.0, -60.0))
        assertNull(VehicleEnergy.neededKwh(20.0, 80.0, Double.NaN))
        assertNull(VehicleEnergy.neededKwh(20.0, 80.0, Double.POSITIVE_INFINITY))
    }

    // --- energySliderProgress: the energy slider's own (value - min) mapping -

    @Test fun energySliderProgressRoundsUpAndSubtractsTheSlidersMinimum() {
        // ceil(34.5) = 35 kWh, minus the slider's min of 1 → progress 34.
        assertEquals(34, VehicleEnergy.energySliderProgress(34.5))
        // A whole kWh reproduces exactly what slider(1, 100, 20, …) does today.
        assertEquals(19, VehicleEnergy.energySliderProgress(20.0))
    }

    @Test fun energySliderProgressNeverGoesOutsideTheSlidersRange() {
        assertEquals(0, VehicleEnergy.energySliderProgress(0.2))
        assertEquals(0, VehicleEnergy.energySliderProgress(0.0))
        assertEquals(0, VehicleEnergy.energySliderProgress(-5.0))
        assertEquals(99, VehicleEnergy.energySliderProgress(150.0))
        assertEquals(99, VehicleEnergy.energySliderProgress(100.0))
    }

    // effectiveTargetSocPercent

    @Test fun effectiveTargetSocPercentDefaultsToEightyPercent() {
        assertEquals(80, VehicleEnergy.effectiveTargetSocPercent(null, null))
        assertEquals(80, VehicleEnergy.effectiveTargetSocPercent(null, 100.0))
    }

    @Test fun effectiveTargetSocPercentNeverExceedsTheVehiclesOwnLimit() {
        assertEquals(70, VehicleEnergy.effectiveTargetSocPercent(80, 70.0))
        assertEquals(70, VehicleEnergy.effectiveTargetSocPercent(null, 70.0))
        // Never rounded up past the limit the vehicle itself reported.
        assertEquals(79, VehicleEnergy.effectiveTargetSocPercent(null, 79.5))
    }

    @Test fun effectiveTargetSocPercentKeepsAStoredValueBelowTheLimit() {
        assertEquals(60, VehicleEnergy.effectiveTargetSocPercent(60, 70.0))
        assertEquals(100, VehicleEnergy.effectiveTargetSocPercent(100, 100.0))
    }

    // effectiveCapacityKwh

    @Test fun effectiveCapacityPrefersWhatHomeAssistantReported() {
        assertEquals(77.5, VehicleEnergy.effectiveCapacityKwh(car, 50.0)!!, 1e-9)
    }

    @Test fun effectiveCapacityFallsBackToWhatTheUserRemembered() {
        val unknownCapacity = car.copy(batteryCapacityKwh = null, capacitySource = "unknown")
        assertEquals(64.0, VehicleEnergy.effectiveCapacityKwh(unknownCapacity, 64.0)!!, 1e-9)
        assertNull(VehicleEnergy.effectiveCapacityKwh(unknownCapacity, null))
    }

    // suggestedChargingKwh: the whole suggestion the UI shows

    @Test fun suggestedChargingKwhUsesTheVehiclesOwnLimitAndReportedCapacity() {
        val limited = car.copy(targetSocPercentMax = 70.0)
        // Target defaults to 80 %, but this vehicle only accepts 70 %.
        assertEquals(21.3125, VehicleEnergy.suggestedChargingKwh(limited, null, null)!!, 1e-9)
    }

    @Test fun suggestedChargingKwhUsesTheRememberedCapacityWhenTheVehicleReportsNone() {
        val unknownCapacity = car.copy(batteryCapacityKwh = null, capacitySource = "unknown")
        // 37.5 % of a remembered 50 kWh battery.
        assertEquals(18.75, VehicleEnergy.suggestedChargingKwh(unknownCapacity, 80, 50.0)!!, 1e-9)
    }

    @Test fun suggestedChargingKwhIsNullWithoutAnyCapacityToComputeFrom() {
        val unknownCapacity = car.copy(batteryCapacityKwh = null, capacitySource = "unknown")
        assertNull(VehicleEnergy.suggestedChargingKwh(unknownCapacity, 80, null))
    }

    @Test fun suggestedChargingKwhIsZeroForAVehicleAlreadyAtOrAboveItsTarget() {
        val charged = car.copy(socPercent = 95.0)
        assertEquals(0.0, VehicleEnergy.suggestedChargingKwh(charged, 80, null)!!, 1e-9)
    }
}
