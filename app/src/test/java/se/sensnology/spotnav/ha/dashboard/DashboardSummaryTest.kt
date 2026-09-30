package se.sensnology.spotnav.ha.dashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.DashboardFixtures

/** The `summary` block decodes tolerantly: what is not understood is left out, never a failure. */
class DashboardSummaryTest {
    private fun summary(name: String = "cheapest_direct_site_admin.json", edit: JSONObject.() -> Unit = {}) =
        DashboardFixtures.dashboard(name, edit).summary

    @Test fun theVendoredBlockDecodes() {
        val summary = summary()!!
        assertEquals("cheapest direct admin", summary.charger!!.startStopName)
        assertEquals(DashboardSummary.CurrentPath.NONE, summary.charger!!.currentPath)
        assertEquals(true, summary.charger!!.energyAutomatic)
        assertEquals(25.0, summary.site!!.mainFuseA!!, 0.0)
        assertEquals(DashboardSummary.MeasurementMode.DIRECT, summary.site!!.measurementMode)
        assertNull(summary.site!!.batteryName)
        assertEquals("ev6 battery", summary("target_soc_two_vehicles.json")!!.vehicleSensorNames["vehicle_ev6"])
    }

    @Test fun aDerivedSiteDecodes() {
        assertEquals(
            DashboardSummary.MeasurementMode.DERIVED,
            summary("solar_derived_site.json")!!.site!!.measurementMode
        )
        assertNull(summary("cheapest_no_site.json")!!.site)
    }

    @Test fun aHomeAssistantWithoutTheBlockKeepsToday() {
        assertNull(summary { remove("summary") })
        assertNull(summary { put("summary", JSONObject.NULL) })
        assertNull(summary { put("summary", "text") })
    }

    @Test fun everyCurrentPathDecodes() {
        val expected = mapOf(
            "change_configuration" to DashboardSummary.CurrentPath.CHANGE_CONFIGURATION,
            "number" to DashboardSummary.CurrentPath.NUMBER,
            "easee_dynamic_limit" to DashboardSummary.CurrentPath.EASEE_DYNAMIC_LIMIT,
            "none" to DashboardSummary.CurrentPath.NONE
        )
        for ((code, path) in expected) {
            val charger = summary { getJSONObject("summary").getJSONObject("charger").put("current_path", code) }!!.charger!!
            assertEquals(code, path, charger.currentPath)
        }
    }

    @Test fun unknownOrWrongTypedValuesLeaveThePartOutWithoutFailing() {
        val summary = summary {
            getJSONObject("summary").getJSONObject("charger")
                .put("current_path", "teleport").put("start_stop_name", 5).put("energy_automatic", "yes")
            getJSONObject("summary").getJSONObject("site")
                .put("measurement_mode", "mystery").put("main_fuse_a", "big").put("battery_name", "  ")
            getJSONObject("summary").put("vehicles", JSONObject().put("a", 3).put("b", JSONObject().put("soc_sensor_name", 1)))
        }!!
        val charger = summary.charger!!
        assertNull(charger.currentPath)
        assertNull(charger.startStopName)
        assertNull(charger.energyAutomatic)
        val site = summary.site!!
        assertNull(site.measurementMode)
        assertNull(site.mainFuseA)
        assertNull(site.batteryName)
        assertTrue(summary.vehicleSensorNames.isEmpty())
    }

    @Test fun partsOfTheWrongShapeAreAbsent() {
        val summary = summary {
            getJSONObject("summary").put("charger", "x").put("site", 4).put("vehicles", "none")
        }!!
        assertNull(summary.charger)
        assertNull(summary.site)
        assertTrue(summary.vehicleSensorNames.isEmpty())
    }
}
