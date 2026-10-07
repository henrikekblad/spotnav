package se.sensnology.spotnav.ha.dashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.VehicleField
import se.sensnology.spotnav.ha.client.VehicleFieldIssue
import se.sensnology.spotnav.ha.client.VehicleUpdate
import se.sensnology.spotnav.ha.client.WebhookReads
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.ui.settings.PairedOverview
import java.time.Instant
import java.time.ZoneId

/** The car's minimum charge level: read per car, offered only where Home Assistant states it, written as the car's own. */
class MinimumChargeLevelTest {
    private fun twoVehicles(minimum: Any?, sensor: Boolean = true) = DashboardFixtures.dashboard("target_soc_two_vehicles.json") {
        val row = getJSONArray("vehicles").getJSONObject(0)
        row.put("min_percent", minimum ?: JSONObject.NULL)
        if (!sensor) {
            row.put("soc_entity_id", JSONObject.NULL)
            getJSONObject("summary").getJSONObject("vehicles").remove("vehicle_ev6")
        }
    }

    @Test fun theAppAsksForTheMinimumLevelInEveryRequest() {
        assertTrue(WebhookReads.FIELDS.contains("min_soc"))
    }

    @Test fun aRowStatesItsMinimumLevelOrNothing() {
        val dashboard = twoVehicles(30)
        assertEquals(30, dashboard.vehicles[0].minPercent)
        assertTrue(dashboard.vehicles[0].minStated)
        assertNull(dashboard.vehicles[1].minPercent)
        assertTrue("the fixture states it for every car", dashboard.vehicles[1].minStated)
        val older = DashboardFixtures.dashboard("target_soc_two_vehicles.json") {
            val rows = getJSONArray("vehicles")
            for (index in 0 until rows.length()) rows.getJSONObject(index).remove("min_percent")
        }
        assertTrue(older.vehicles.none { it.minStated })
        // A value off the 10-80 steps of 5 reads as none, but the row still states the field.
        val odd = twoVehicles(33)
        assertNull(odd.vehicles[0].minPercent)
        assertTrue(odd.vehicles[0].minStated)
    }

    @Test fun theSettingsCardShowsItAndSaysWhenTheCarHasNoLevel() {
        val cards = PairedOverview.vehicles(twoVehicles(30))
        assertEquals(30, cards[0].minPercent)
        assertTrue(cards[0].minStated)
        assertFalse(cards[0].minNeedsLevel)
        val noSensor = PairedOverview.vehicles(twoVehicles(30, sensor = false))
        assertTrue(noSensor[0].minNeedsLevel)
    }

    @Test fun itIsWrittenAsAWholePercentOrNullForOff() {
        val row = twoVehicles(30).vehicles[0]
        val on = VehicleUpdate.payload(row.id, listOf(VehicleUpdate.single(row, VehicleField.MINIMUM, 45.0)))
        assertEquals(45, on.getJSONObject("changes").get("min_percent"))
        assertEquals(30, on.getJSONObject("expected").get("min_percent"))
        val off = VehicleUpdate.payload(row.id, listOf(VehicleUpdate.single(row, VehicleField.MINIMUM, null)))
        assertEquals(JSONObject.NULL, off.getJSONObject("changes").get("min_percent"))
        assertEquals(listOf(10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80), VehicleUpdate.MINIMUM_LEVELS)
    }

    @Test fun aRefusedLevelIsOutOfRange() {
        val body = JSONObject()
            .put("ok", false).put("error", "spotnav_invalid_value").put("config", JSONObject.NULL).put("vehicle", JSONObject.NULL)
            .put("field_errors", org.json.JSONArray().put(JSONObject().put("field", "min_percent").put("code", "invalid_min_percent")))
        val outcome = VehicleUpdate.answer(400, body.toString()) as VehicleUpdate.Outcome.Refused
        assertEquals(VehicleFieldIssue.OUT_OF_RANGE, outcome.issues[VehicleField.MINIMUM])
    }

    @Test fun theStatusLineIsWordedInEveryLanguage() {
        val now = Instant.parse("2026-09-22T06:00:00Z")
        fun say(language: String) = HaStatusText.line(
            StatusLine("min_soc_charging", mapOf("percent" to 30.0)),
            StatusFormat(language, ZoneId.of("Europe/Stockholm"), "SEK", "kr"),
            now
        )
        assertEquals("Laddar till lägsta nivå (30 %)", say("sv"))
        assertEquals("Charging to the minimum level (30 %)", say("en"))
        for (language in HaStatusWording.LANGUAGES) {
            assertTrue("$language: ${say(language)}", say(language).contains("30") && !say(language).contains('{'))
        }
    }
}
