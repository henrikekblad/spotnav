package se.sensnology.spotnav.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the vehicle card shows: which car the card is about, whether its title is a selector, and
 * which rows it has.
 */
class VehicleFactsTest {
    private fun vehicle(
        id: String,
        name: String = "Car $id",
        socPercent: Double = 52.0,
        targetSocPercentMax: Double? = null,
        batteryCapacityKwh: Double? = null
    ) = VehicleStatus(
        id = id,
        name = name,
        socPercent = socPercent,
        targetSocPercentMax = targetSocPercentMax,
        batteryCapacityKwh = batteryCapacityKwh,
        capacitySource = if (batteryCapacityKwh != null) "detected" else "unknown"
    )

    private val one = vehicle("car-one")
    private val other = vehicle("car-two")

    // no vehicle at all

    @Test fun nothingDetectedShowsOnlyTheCarsOwnProperties() {
        val content = VehicleFacts.content(emptyList(), null)

        assertNull(content.vehicle)
        assertFalse(content.selectable)
        // State rows are simply absent -- not placeholders, not "—", not an explanation of why they
        // are missing.
        assertFalse(VehicleFacts.Fact.STATE_OF_CHARGE in content.facts)
        assertFalse(VehicleFacts.Fact.CHARGE_LIMIT in content.facts)
        assertEquals(
            listOf(VehicleFacts.Fact.BATTERY_CAPACITY, VehicleFacts.Fact.CONSUMPTION),
            content.facts
        )
        // And nothing to key a capacity to, so it is not offered for entry.
        assertFalse(content.capacitySettable)
    }

    @Test fun aStateRowIsNeverShownWithoutASelection() {
        // The rule is about the card's state, not about the data happening to be present: with no
        // selection there is no state row at all.
        val content = VehicleFacts.content(listOf(one, other), null)

        assertNull(content.vehicle)
        assertEquals(
            listOf(VehicleFacts.Fact.BATTERY_CAPACITY, VehicleFacts.Fact.CONSUMPTION),
            content.facts
        )
    }

    // one vehicle

    @Test fun oneVehicleIsTheSelectionWithoutStoringAChoice() {
        val content = VehicleFacts.content(listOf(one), null)

        assertEquals(one, content.vehicle)
        assertTrue(VehicleFacts.Fact.STATE_OF_CHARGE in content.facts)
        assertTrue(content.capacitySettable)
    }

    @Test fun oneVehicleNeedsNoSelector() {
        assertFalse(VehicleFacts.content(listOf(one), null).selectable)
        assertFalse(VehicleFacts.content(listOf(one), one.id).selectable)
    }

    @Test fun aStoredChoiceThatIsGoneFallsBackToTheOnlyVehicle() {
        assertEquals(one, VehicleFacts.content(listOf(one), "car-that-left").vehicle)
    }

    // several vehicles

    @Test fun severalVehiclesNeedASelectorAndNoStoredChoiceSelectsNothing() {
        val content = VehicleFacts.content(listOf(one, other), null)

        assertTrue(content.selectable)
        assertNull(content.vehicle)
        assertFalse(VehicleFacts.Fact.STATE_OF_CHARGE in content.facts)
        assertFalse(content.capacitySettable)
    }

    @Test fun severalVehiclesUseTheStoredChoice() {
        val content = VehicleFacts.content(listOf(one, other), other.id)

        assertEquals(other, content.vehicle)
        assertTrue(content.selectable)
        assertTrue(VehicleFacts.Fact.STATE_OF_CHARGE in content.facts)
    }

    @Test fun aStoredChoiceThatIsGoneWithSeveralVehiclesSelectsNothing() {
        // Ambiguous: the card must not pick a car on the user's behalf.
        assertNull(VehicleFacts.content(listOf(one, other), "car-that-left").vehicle)
    }

    // the charge limit is a state, and only ever reported

    @Test fun aChargeLimitRowAppearsOnlyWhenOneIsReported() {
        assertFalse(VehicleFacts.Fact.CHARGE_LIMIT in VehicleFacts.content(listOf(one), null).facts)

        val limited = vehicle("car-limited", targetSocPercentMax = 90.0)
        val content = VehicleFacts.content(listOf(limited), null)

        assertTrue(VehicleFacts.Fact.CHARGE_LIMIT in content.facts)
        assertEquals(90, VehicleFacts.chargeLimit(limited))
        // Reading order: the car's state, then its own properties.
        assertEquals(
            listOf(
                VehicleFacts.Fact.STATE_OF_CHARGE,
                VehicleFacts.Fact.CHARGE_LIMIT,
                VehicleFacts.Fact.BATTERY_CAPACITY,
                VehicleFacts.Fact.CONSUMPTION
            ),
            content.facts
        )
    }

    @Test fun aChargeLimitThatIsNotARealNumberIsNotReported() {
        assertNull(VehicleFacts.chargeLimit(vehicle("nan", targetSocPercentMax = Double.NaN)))
        assertNull(VehicleFacts.chargeLimit(vehicle("inf", targetSocPercentMax = Double.POSITIVE_INFINITY)))
        assertNull(VehicleFacts.chargeLimit(vehicle("none")))
    }

    @Test fun capacityAndConsumptionAreOnTheCardInEveryState() {
        val states = listOf(
            emptyList<VehicleStatus>() to null,
            listOf(one) to null,
            listOf(one) to one.id,
            listOf(one, other) to one.id,
            listOf(one, other) to null
        )

        for ((vehicles, storedId) in states) {
            val content = VehicleFacts.content(vehicles, storedId)

            assertTrue(
                "capacity missing for ${vehicles.size} vehicles, stored=$storedId",
                VehicleFacts.Fact.BATTERY_CAPACITY in content.facts
            )
            assertTrue(
                "consumption missing for ${vehicles.size} vehicles, stored=$storedId",
                VehicleFacts.Fact.CONSUMPTION in content.facts
            )
            // Settable exactly when there is a car to key it to -- the row is present either way.
            assertEquals(content.vehicle != null, content.capacitySettable)
        }
    }
}
