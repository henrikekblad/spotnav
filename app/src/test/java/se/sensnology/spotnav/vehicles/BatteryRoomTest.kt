package se.sensnology.spotnav.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.testing.HaFixtures

/**
 * The battery's room as Home Assistant states it (`soc.room_kwh`): the kWh slider's top, and where the
 * note that the car ends the charge itself appears (the card's `energySliderMaximum`/`energyAtRoom`).
 */
class BatteryRoomTest {
    private val max = VehicleEnergy.ENERGY_SLIDER_MAX_PROGRESS

    @Test fun theSliderTopsOutAtTheRoomRoundedUpToHalfAKilowattHour() {
        // 3.44 kWh is 3.5 kWh on the slider: progress (3.5 - 1) / 0.5 = 5.
        assertEquals(5, BatteryRoom.energySliderMaxProgress(3.44, 0))
        assertEquals(3.5, VehicleEnergy.energyKwhOfProgress(5), 1e-9)
        assertEquals(5, BatteryRoom.energySliderMaxProgress(3.5, 0))
        // Never below the slider's first step, never above its ordinary top.
        assertEquals(0, BatteryRoom.energySliderMaxProgress(0.0, 0))
        assertEquals(max, BatteryRoom.energySliderMaxProgress(250.0, 0))
    }

    @Test fun withoutARoomTheOrdinaryScaleStays() {
        assertEquals(max, BatteryRoom.energySliderMaxProgress(null, 0))
        assertEquals(max, BatteryRoom.energySliderMaxProgress(Double.NaN, 3))
    }

    @Test fun aStoredAmountAboveTheRoomKeepsItsPlace() {
        val stored = VehicleEnergy.energyProgressNearest(43.5)
        assertEquals(stored, BatteryRoom.energySliderMaxProgress(3.44, stored))
    }

    @Test fun theNoteShowsForAnAmountAtOrAboveTheRoomOnly() {
        assertTrue(BatteryRoom.energyAtRoom(3.5, 3.44))
        assertTrue(BatteryRoom.energyAtRoom(43.5, 3.44))
        assertFalse(BatteryRoom.energyAtRoom(3.0, 3.44))
        assertFalse(BatteryRoom.energyAtRoom(43.5, null))
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
