package se.sensnology.spotnav.ui.charging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.DashboardChargingPhases
import se.sensnology.spotnav.testing.HaFixtures
import org.json.JSONObject

class ChargingPhasesTextTest {
    private fun dashboard(name: String) = Dashboard.parse(HaFixtures.json("dashboard/$name.json"))

    @Test fun theBlockIsReadFromTheFixtures() {
        val wired = dashboard("start_idle").chargingPhases!!
        assertEquals(DashboardChargingPhases(phases = 1, charger = 1, vehicle = null, limitedBy = null), wired)
        val limited = dashboard("target_soc_phases_limited_by_vehicle").chargingPhases!!
        assertEquals(DashboardChargingPhases(phases = 1, charger = 3, vehicle = 1, limitedBy = "vehicle"), limited)
        assertEquals(true, limited.limitedByVehicle)
        assertEquals(1, dashboard("target_soc_phases_limited_by_vehicle").vehicles.single().onboardPhases)
    }

    @Test fun aBlockThatIsAbsentOrStatesNoUsableCountIsNotThere() {
        val json = HaFixtures.json("dashboard/start_idle.json")
        json.remove("charging_phases")
        assertNull(Dashboard.parse(json).chargingPhases)
        json.put("charging_phases", JSONObject().put("phases", 2))
        assertNull(Dashboard.parse(json).chargingPhases)
        json.put("charging_phases", "three")
        assertNull(Dashboard.parse(json).chargingPhases)
    }

    @Test fun theCurrentLabelCarriesThePhaseCountWhenKnown() {
        val withPhases = { n: Int -> "Charging current · $n phase" + if (n == 1) "" else "s" }
        assertEquals("Charging current · 3 phases", ChargingPhasesText.currentLabel(3, "Charging current", withPhases))
        assertEquals("Charging current · 1 phase", ChargingPhasesText.currentLabel(1, "Charging current", withPhases))
        assertEquals("Charging current", ChargingPhasesText.currentLabel(null, "Charging current", withPhases))
    }

    @Test fun theHistoryMonthIsAbbreviatedInTheLocale() {
        val october = java.time.LocalDate.of(2026, 10, 3)
        assertEquals("Oct", ChargingPhasesText.monthAbbreviation(october, java.util.Locale.UK))
        assertEquals("okt.", ChargingPhasesText.monthAbbreviation(october, java.util.Locale.forLanguageTag("sv")))
    }
}
