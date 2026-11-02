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
}
