package se.sensnology.spotnav.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerPhases
import se.sensnology.spotnav.vehicles.VehicleEnergy
import se.sensnology.spotnav.vehicles.VehicleFacts
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The plan card's two readout decisions: how the departure row reads, and whether the plan can
 * finish before the departure the user set.
 */
class PlanReadoutTest {
    private val thursdayEvening = OffsetDateTime.of(2026, 9, 17, 22, 0, 0, 0, ZoneOffset.ofHours(2))

    // how the row reads

    @Test fun aDepartureThatIsOffReadsAsNotSet() {
        val reading = PlanReadout.departureReading(enabled = false, hour = 7, minute = 30)

        assertFalse(reading.isSet)
        assertNull(reading.time())
    }

    @Test fun aDepartureThatIsOnReadsItsTime() {
        val reading = PlanReadout.departureReading(enabled = true, hour = 7, minute = 30)

        assertTrue(reading.isSet)
        assertEquals("07:30", reading.time())
    }

    @Test fun theTimeIsPaddedSoReadingsLineUp() {
        assertEquals("00:05", PlanReadout.departureReading(enabled = true, hour = 0, minute = 5).time())
        assertEquals("23:00", PlanReadout.departureReading(enabled = true, hour = 23, minute = 0).time())
    }

    @Test fun theReadingHoldsTheTimeSeparatelyFromWhetherItIsSet() {
        // The state keeps the time while the departure is off -- only the *reading* goes away -- so
        // switching it back on restores the time the user last chose rather than a default.
        val off = PlanReadout.departureReading(enabled = false, hour = 7, minute = 30)

        assertEquals(7, off.hour)
        assertEquals(30, off.minute)
        assertFalse(off.isSet)
    }

    // whether the plan misses it

    @Test fun withNoDepartureThePlanNeverMissesIt() {
        assertFalse(
            PlanReadout.missesDeparture(thursdayEvening, durationMinutes = 600, enabled = false, hour = 7, minute = 0)
        )
    }

    @Test fun aPlanThatFinishesBeforeTheDepartureDoesNotMissIt() {
        // 22:00 for two hours ends at midnight, and the departure (07:00 has already passed at
        // 22:00, so it means tomorrow morning) is at 07:00.
        assertFalse(
            PlanReadout.missesDeparture(thursdayEvening, durationMinutes = 120, enabled = true, hour = 7, minute = 0)
        )
    }

    @Test fun aPlanThatFinishesAfterTheDepartureMissesIt() {
        // 22:00 for two hours runs past a 23:00 departure.
        assertTrue(
            PlanReadout.missesDeparture(thursdayEvening, durationMinutes = 120, enabled = true, hour = 23, minute = 0)
        )
    }

    @Test fun aDepartureAlreadyPassedTodayMeansTomorrows() {
        // 06:00 today is in the past at 22:00, so the departure in force is tomorrow's 06:00: nine
        // hours of charging from 22:00 ends at 07:00, which misses it...
        assertTrue(
            PlanReadout.missesDeparture(thursdayEvening, durationMinutes = 9 * 60, enabled = true, hour = 6, minute = 0)
        )
        // ...while two hours ends exactly at midnight, well before tomorrow's 06:00 -- proving the
        // departure used was tomorrow's and not a time already in the past.
        assertFalse(
            PlanReadout.missesDeparture(thursdayEvening, durationMinutes = 120, enabled = true, hour = 6, minute = 0)
        )
    }

    @Test fun aDepartureAtTheVeryStartOfTheWindowMeansTomorrows() {
        // The rule is `<=`: a departure equal to the window's first slot is not "now", it is the
        // next occurrence of that time.
        assertFalse(
            PlanReadout.missesDeparture(thursdayEvening, durationMinutes = 60, enabled = true, hour = 22, minute = 0)
        )
        assertTrue(
            PlanReadout.missesDeparture(thursdayEvening, durationMinutes = 25 * 60, enabled = true, hour = 22, minute = 0)
        )
    }
}
