package se.sensnology.spotnav.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.testing.HaFixtures

/**
 * The battery's room as Home Assistant states it (`soc.room_kwh`): the kWh slider's top past it, its
 * "full" mark and its last step, Fill (the card's `energyFillTop`/`energySliderMaximum`).
 */
class BatteryRoomTest {
    private val max = VehicleEnergy.ENERGY_SLIDER_MAX_PROGRESS

    private fun facts(room: Double?, capacity: Double? = 77.0, limit: Double? = 100.0, efficiency: Double? = 0.9) =
        BatteryRoom.FillFacts(roomKwh = room, capacityKwh = capacity, vehicleMaxPercent = limit, efficiency = efficiency)

    @Test fun theTopIsTwiceTheRoomAtLeastThirtyKilowattHoursRoundedUpToHalfAKilowattHour() {
        assertEquals(30.0, BatteryRoom.fillTopKwh(facts(9.5))!!, 1e-9)
        assertEquals(40.5, BatteryRoom.fillTopKwh(facts(20.1))!!, 1e-9)
    }

    @Test fun theTopNeverPassesWhatTheBatteryHoldsToTheCarsOwnLimit() {
        // 77 kWh x 100 % / 0.9 = 85.6 kWh; to a 50 % limit, 42.8 kWh.
        assertEquals(80.0, BatteryRoom.fillTopKwh(facts(40.0))!!, 1e-9)
        assertEquals(86.0, BatteryRoom.fillTopKwh(facts(45.0))!!, 1e-9)
        assertEquals(43.0, BatteryRoom.fillTopKwh(facts(30.0, limit = 50.0))!!, 1e-9)
    }

    @Test fun withoutABatterySizeOnlyTheOrdinaryTopBoundsItAndWithoutARoomThereIsNone() {
        assertEquals(100.0, BatteryRoom.fillTopKwh(facts(60.0, capacity = null))!!, 1e-9)
        assertEquals(100.0, BatteryRoom.fillTopKwh(facts(60.0, efficiency = null))!!, 1e-9)
        assertNull(BatteryRoom.fillTopKwh(facts(null)))
        assertNull(BatteryRoom.fillTopKwh(facts(Double.NaN)))
    }

    @Test fun theSliderEndsAtTheTopAndAStoredAmountAboveItKeepsItsPlace() {
        // 30 kWh is progress (30 - 1) / 0.5 = 58.
        assertEquals(58, BatteryRoom.energySliderMaxProgress(30.0, 0))
        assertEquals(30.0, VehicleEnergy.energyKwhOfProgress(58), 1e-9)
        val stored = VehicleEnergy.energyProgressNearest(50.0)
        assertEquals(stored, BatteryRoom.energySliderMaxProgress(30.0, stored))
        assertEquals(max, BatteryRoom.energySliderMaxProgress(null, 0))
        assertEquals(max, BatteryRoom.energySliderMaxProgress(null, 3))
    }

    @Test fun theFullMarkSitsAtTheRoomAlongTheTrack() {
        // 9.5 kWh on a 1-30 kWh track: (9.5 - 1) / (30 - 1).
        assertEquals((9.5 - 1.0) / (30.0 - 1.0), BatteryRoom.markFraction(9.5, 58)!!.toDouble(), 1e-6)
        assertEquals(0.0, BatteryRoom.markFraction(0.4, 58)!!.toDouble(), 0.0)
        assertEquals(1.0, BatteryRoom.markFraction(80.0, 58)!!.toDouble(), 0.0)
        assertNull(BatteryRoom.markFraction(null, 58))
    }

    @Test fun theLastStepIsFillOnlyAtTheOrdinaryTopAndOnlyWhereHomeAssistantHasTheChoice() {
        assertTrue(BatteryRoom.isFillStep(progress = 58, maxProgress = 58, topKwh = 30.0, supported = true))
        assertFalse(BatteryRoom.isFillStep(progress = 57, maxProgress = 58, topKwh = 30.0, supported = true))
        // A stored amount drawn above the top makes the last step that amount, not Fill.
        assertFalse(BatteryRoom.isFillStep(progress = 98, maxProgress = 98, topKwh = 30.0, supported = true))
        // An older Home Assistant, or no known room.
        assertFalse(BatteryRoom.isFillStep(progress = 58, maxProgress = 58, topKwh = 30.0, supported = false))
        assertFalse(BatteryRoom.isFillStep(progress = max, maxProgress = max, topKwh = null, supported = true))
    }

    @Test fun theCarsOwnLimitIsNamedOnlyBelowAHundredPercent() {
        assertEquals(90, BatteryRoom.limitNamed(90.0))
        assertEquals(80, BatteryRoom.limitNamed(80.6))
        assertNull(BatteryRoom.limitNamed(100.0))
        assertNull(BatteryRoom.limitNamed(null))
    }

    @Test fun theRoomIsWrittenAsTheCardWritesIt() {
        assertEquals("9.5", BatteryRoom.kwhFigure(9.47, java.util.Locale.ENGLISH))
        assertEquals("7,5", BatteryRoom.kwhFigure(7.5, java.util.Locale.forLanguageTag("sv-SE")))
        assertEquals("12", BatteryRoom.kwhFigure(12.0, java.util.Locale.ENGLISH))
        assertEquals("17.3", BatteryRoom.kwhFigure(17.25, java.util.Locale.ENGLISH))
    }

    @Test fun theNoteShowsForATargetAtOrAboveTheCarsOwnLimit() {
        assertTrue(BatteryRoom.targetAtCarLimit(80, 80.0))
        assertTrue(BatteryRoom.targetAtCarLimit(90, 80.6))
        assertFalse(BatteryRoom.targetAtCarLimit(79, 80.0))
        // No limit stated: only 100 % is the car's to end.
        assertTrue(BatteryRoom.targetAtCarLimit(100, null))
        assertFalse(BatteryRoom.targetAtCarLimit(99, null))
    }

    @Test fun theRoomIsReadFromTheDashboardAndAbsentFromAnOlderHomeAssistant() {
        val json = HaFixtures.json("dashboard/target_soc_estimated.json")
        assertEquals(21.33, Dashboard.parse(json).soc!!.roomKwh!!, 1e-9)
        json.getJSONObject("soc").remove("room_kwh")
        assertNull(Dashboard.parse(json).soc!!.roomKwh)
        json.getJSONObject("soc").put("room_kwh", org.json.JSONObject.NULL)
        assertNull(Dashboard.parse(json).soc!!.roomKwh)
    }
}
