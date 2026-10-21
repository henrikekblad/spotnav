package se.sensnology.spotnav.ha.dashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.HomeAssistantCommand
import se.sensnology.spotnav.ha.client.VehicleField
import se.sensnology.spotnav.ha.client.VehicleFieldIssue
import se.sensnology.spotnav.ha.client.VehicleUpdate
import se.sensnology.spotnav.ha.client.WebhookReads
import se.sensnology.spotnav.ui.settings.PairedOverview
import se.sensnology.spotnav.testing.DashboardFixtures

/** Waking the car for a reading: read per car, offered only where the car's integration has a wake. */
class WakeForReadingTest {
    private fun twoVehicles(wake: Any?) = DashboardFixtures.dashboard("target_soc_two_vehicles.json") {
        getJSONArray("vehicles").getJSONObject(0).put("wake_for_reading", wake ?: JSONObject.NULL)
    }

    @Test fun theAppAsksForItOnTheDashboardAndOnVehicleWrites() {
        assertTrue(WebhookReads.FIELDS.contains("wake_for_reading"))
        val dashboard = JSONObject(HomeAssistantClient.payload(HomeAssistantCommand("dashboard")))
        assertTrue(dashboard.getJSONArray("reads").toString().contains("\"wake_for_reading\""))
    }

    @Test fun aRowStatesWhetherTheCarIsWoken() {
        assertEquals(true, twoVehicles(true).vehicles[0].wakeForReading)
        assertEquals(false, twoVehicles(false).vehicles[0].wakeForReading)
        // A car with no known wake states null, and a row without the key states nothing: not offered.
        assertNull(twoVehicles(null).vehicles[0].wakeForReading)
        assertNull(twoVehicles(true).vehicles[1].wakeForReading)
    }

    @Test fun anythingButABooleanReadsAsNotOfferedWithoutRefusingTheDashboard() {
        assertNull(twoVehicles("yes").vehicles[0].wakeForReading)
        assertNull(twoVehicles(1).vehicles[0].wakeForReading)
    }

    @Test fun theSettingsCardOffersItOnlyForACarWithAWake() {
        assertEquals(true, PairedOverview.vehicles(twoVehicles(true))[0].wakeForReading)
        assertEquals(false, PairedOverview.vehicles(twoVehicles(false))[0].wakeForReading)
        assertNull(PairedOverview.vehicles(twoVehicles(null))[0].wakeForReading)
    }

    @Test fun itIsWrittenAsABooleanComparedAgainstWhatTheRowShowed() {
        val row = twoVehicles(false).vehicles[0]
        val on = VehicleUpdate.payload(row.id, listOf(VehicleUpdate.single(row, VehicleField.WAKE, VehicleUpdate.wakeValue(true))))
        assertEquals("update_vehicle", on.getString("action"))
        assertEquals(true, on.getJSONObject("changes").get("wake_for_reading"))
        assertEquals(false, on.getJSONObject("expected").get("wake_for_reading"))
        assertTrue(on.getJSONArray("reads").toString().contains("\"wake_for_reading\""))
        val off = VehicleUpdate.payload(row.id, listOf(VehicleUpdate.single(twoVehicles(true).vehicles[0], VehicleField.WAKE, 0.0)))
        assertEquals(false, off.getJSONObject("changes").get("wake_for_reading"))
        assertEquals(true, off.getJSONObject("expected").get("wake_for_reading"))
    }

    @Test fun anUpdatedRowComesBackWithTheNewChoice() {
        val vehicle = DashboardFixtures.json("target_soc_two_vehicles.json")
            .getJSONArray("vehicles").getJSONObject(0).put("wake_for_reading", true)
        val body = JSONObject().put("ok", true).put("error", JSONObject.NULL).put("field_errors", JSONArray()).put("vehicle", vehicle)
        val outcome = VehicleUpdate.answer(200, body.toString()) as VehicleUpdate.Outcome.Updated
        assertEquals(true, outcome.row.wakeForReading)
    }

    private fun refused(code: String): VehicleUpdate.Outcome.Refused {
        val body = JSONObject()
            .put("ok", false).put("error", "spotnav_invalid_value").put("config", JSONObject.NULL).put("vehicle", JSONObject.NULL)
            .put("field_errors", JSONArray().put(JSONObject().put("field", "wake_for_reading").put("code", code)))
        return VehicleUpdate.answer(400, body.toString()) as VehicleUpdate.Outcome.Refused
    }

    @Test fun aRefusalSaysWhetherTheValueOrTheCarWasWrong() {
        assertEquals(VehicleFieldIssue.OUT_OF_RANGE, refused("invalid_wake_for_reading").issues[VehicleField.WAKE])
        assertEquals(VehicleFieldIssue.UNSUPPORTED, refused("wake_unsupported").issues[VehicleField.WAKE])
    }
}
