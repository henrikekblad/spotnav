package se.sensnology.spotnav.vehicles

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.testing.DashboardFixtures

/**
 * The car on a paired charger's card: "EV6 · 89 % → 93 %" in target mode, only the level in kWh mode,
 * "≈" before an estimated level and "–" for none; the same words in the charger drop-down's rows.
 */
class PairedCarLineTest {
    private val percent: (Int) -> String = { "$it %" }

    private fun dashboard(edit: JSONObject.() -> Unit = {}): Dashboard =
        DashboardFixtures.dashboard("target_soc_two_vehicles.json", edit)

    @Test fun inTargetModeTheLineGoesFromTheLevelToTheTarget() {
        val levels = PairedCarLine.levels(dashboard(), "vehicle_ev6")
        assertEquals(PairedCarLine.Levels(now = 40, estimated = false, target = 80, targetMode = true), levels)
        assertEquals("40 % → 80 %", PairedCarLine.levelsText(levels, percent))
    }

    @Test fun inKwhModeItShowsOnlyTheLevel() {
        val kwh = dashboard { getJSONObject("settings").put("driver", "manual_kwh") }
        assertEquals("40 %", PairedCarLine.levelsText(PairedCarLine.levels(kwh, "vehicle_ev6"), percent))
    }

    @Test fun anEstimatedLevelIsMarkedAndNoLevelIsADash() {
        val estimated = dashboard { getJSONObject("soc").put("estimated", true) }
        assertEquals("≈ 40 % → 80 %", PairedCarLine.levelsText(PairedCarLine.levels(estimated, "vehicle_ev6"), percent))
        val none = dashboard { getJSONObject("soc").put("value", JSONObject.NULL) }
        assertEquals("– → 80 %", PairedCarLine.levelsText(PairedCarLine.levels(none, "vehicle_ev6"), percent))
        val noneKwh = dashboard {
            getJSONObject("soc").put("value", JSONObject.NULL)
            getJSONObject("settings").put("driver", "manual_kwh")
        }
        assertNull(PairedCarLine.levelsText(PairedCarLine.levels(noneKwh, "vehicle_ev6"), percent))
    }

    @Test fun anotherCarsLevelIsItsRowsAndItsTargetItsOwn() {
        val levels = PairedCarLine.levels(dashboard(), "vehicle_niro")
        assertEquals(55, levels.now)
        assertFalse(levels.estimated)
    }

    @Test fun theDropDownRowSaysTheChargersCarAndItsLevels() {
        assertEquals("EV6 · 40 % → 80 %", PairedCarLine.summary(dashboard(), percent))
        val unknown = dashboard {
            put("target_vehicle_id", JSONObject.NULL)
            getJSONObject("soc").put("vehicle_id", JSONObject.NULL)
            getJSONObject("settings").getJSONObject("target").put("vehicle_id", JSONObject.NULL)
        }
        assertNull(PairedCarLine.summary(unknown, percent))
    }

    @Test fun theCarLineFollowsHomeAssistantsCarNotOneSavedInTheApp() {
        val switched = dashboard {
            put("target_vehicle_id", "vehicle_niro")
            getJSONObject("soc").put("vehicle_id", "vehicle_niro").put("vehicle_name", "Niro").put("value", 55.0)
        }
        val line = VehicleIdentification.carLine(switched)!!
        assertEquals("vehicle_niro", line.vehicleId)
        assertTrue(PairedCarLine.summary(switched, percent)!!.startsWith("Niro"))
    }

    @Test fun thePlanningCardsNowIsMarkedWhenEstimated() {
        val estimated = dashboard { getJSONObject("soc").put("estimated", true) }
        assertTrue(PairedTarget.facts(estimated.soc!!, estimated.vehicles, null).estimated)
        assertFalse(PairedTarget.facts(dashboard().soc!!, dashboard().vehicles, null).estimated)
    }
}
