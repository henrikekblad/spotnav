package se.sensnology.spotnav.ha.dashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.HaFixtures

class SetupChecklistTest {
    private fun dashboard(items: String, dismiss: Boolean = true): Dashboard {
        val json = JSONObject(HaFixtures.root.resolve("dashboard/start_idle.json").readText())
        json.getJSONObject("charger").getJSONObject("capabilities").put("dismiss_setup_item", dismiss)
        json.put("setup", JSONObject("""{"items": $items}"""))
        return Dashboard.parse(json)
    }

    @Test fun theRowForTheAppItselfIsNeverShownAndTheRestKeepTheCardsOrder() {
        val d = dashboard("""[{"id": "app", "dismissible": true}, {"id": "phone", "dismissible": true}, {"id": "vehicle", "dismissible": true}]""")
        assertEquals(listOf("vehicle", "phone"), SetupChecklist.rows(d).map { it.id })
        assertEquals(emptyList<SetupRow>(), SetupChecklist.rows(null))
    }

    @Test fun aRowIsDismissedOnlyWhereItMayBeAndHomeAssistantAnswersTheAction() {
        val d = dashboard("""[{"id": "car_level", "dismissible": false}, {"id": "site", "dismissible": true}]""")
        assertFalse(SetupChecklist.canDismiss(d, SetupRow("car_level", false)))
        assertTrue(SetupChecklist.canDismiss(d, SetupRow("site", true)))
        assertFalse(SetupChecklist.canDismiss(dashboard("[]", dismiss = false), SetupRow("site", true)))
    }

    @Test fun theDemandChargesGridPowerRowSitsAfterTheForecastAsOnTheCard() {
        val d = dashboard("""[{"id": "phone", "dismissible": true}, {"id": "demand_grid_power", "dismissible": false}, {"id": "solar_forecast", "dismissible": true}]""")
        assertEquals(listOf("solar_forecast", "demand_grid_power", "phone"), SetupChecklist.rows(d).map { it.id })
        assertFalse(SetupChecklist.canDismiss(d, SetupRow("demand_grid_power", false)))
    }

    @Test fun theGridSignRowsSitAfterTheSiteWithTheOptionThatFixesThem() {
        val d = dashboard(
            """[{"id": "solar_forecast", "dismissible": true},
                {"id": "grid_current_sign", "dismissible": false, "field": "site_current_signed", "value": false},
                {"id": "grid_power_sign", "dismissible": false, "field": "grid_power_inverted", "value": true},
                {"id": "site", "dismissible": true}]"""
        )
        val rows = SetupChecklist.rows(d)
        assertEquals(listOf("site", "grid_power_sign", "grid_current_sign", "solar_forecast"), rows.map { it.id })
        assertEquals(SetupRow("grid_power_sign", false, "grid_power_inverted", true), rows[1])
        assertEquals(SetupRow("grid_current_sign", false, "site_current_signed", false), rows[2])
        assertEquals(SetupRow("site", true), rows[0])
        assertFalse(SetupChecklist.canDismiss(d, rows[1]))
    }

    @Test fun aGridSignRowNamingItsOptionWronglyIsStillShown() {
        val d = dashboard("""[{"id": "grid_power_sign", "dismissible": false, "field": 3, "value": "no"}]""")
        assertEquals(listOf(SetupRow("grid_power_sign", false)), SetupChecklist.rows(d))
    }
}
