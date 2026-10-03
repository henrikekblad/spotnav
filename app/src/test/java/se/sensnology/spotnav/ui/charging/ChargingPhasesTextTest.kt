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

    private fun line(block: DashboardChargingPhases, amps: Int?) = ChargingPhasesText.line(
        block, amps,
        charges = { "Charges on ${block.phases} phase" + if (block.phases == 1) "" else "s" },
        nominal = { kw -> "nominal ≈ %.1f kW".format(java.util.Locale.ROOT, kw) },
        limitedByVehicle = "Limited by the car."
    )

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

    @Test fun theLineStatesThePhasesAndTheNominalPowerOfTheCurrent() {
        assertEquals("Charges on 3 phases · nominal ≈ 6.9 kW", line(DashboardChargingPhases(3, 3, null, null), 10))
        assertEquals("Charges on 1 phase · nominal ≈ 2.3 kW", line(DashboardChargingPhases(1, 1, null, null), 10))
        assertEquals("Charges on 3 phases", line(DashboardChargingPhases(3, 3, null, null), null))
    }

    @Test fun theCarsLimitIsNamedWhenItSetsTheCount() {
        assertEquals(
            "Charges on 1 phase · nominal ≈ 3.7 kW. Limited by the car.",
            line(DashboardChargingPhases(1, 3, 1, "vehicle"), 16)
        )
    }
}
