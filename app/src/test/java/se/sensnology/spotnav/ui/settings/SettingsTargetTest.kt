package se.sensnology.spotnav.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsTargetTest {
    @Test fun theRowsChangedInThisAppOpenSettingsAtTheirOwnRow() {
        assertEquals(SettingsTarget.PHASE_SWITCHING, SettingsTarget.ofSetupRow("phase_switching", forecastChoosable = false))
        assertEquals(SettingsTarget.PHONES, SettingsTarget.ofSetupRow("phone", forecastChoosable = false))
        assertEquals(SettingsTarget.SOLAR_FORECAST, SettingsTarget.ofSetupRow("solar_forecast", forecastChoosable = true))
    }

    @Test fun aForecastThatCannotBeChosenYetIsAddedInHomeAssistantFirst() {
        assertNull(SettingsTarget.ofSetupRow("solar_forecast", forecastChoosable = false))
    }

    @Test fun theOtherRowsHaveNoRowInSettings() {
        for (id in listOf("vehicle", "car_level", "site", "app", "something_new")) {
            assertNull(id, SettingsTarget.ofSetupRow(id, forecastChoosable = true))
        }
    }

    @Test fun everyRowHasItsOwnTag() {
        assertEquals(SettingsTarget.entries.size, SettingsTarget.entries.map { it.tag }.toSet().size)
    }

    @Test fun theScrollLeavesRoomAboveTheRowAndNeverGoesAboveTheTop() {
        assertEquals(976, SettingsTarget.scrollY(1000, 24))
        assertEquals(0, SettingsTarget.scrollY(10, 24))
        assertEquals(0, SettingsTarget.scrollY(0, 24))
    }
}
