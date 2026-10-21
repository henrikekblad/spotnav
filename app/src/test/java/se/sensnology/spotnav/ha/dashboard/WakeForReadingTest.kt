package se.sensnology.spotnav.ha.dashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.HomeAssistantCommand
import se.sensnology.spotnav.ha.client.WebhookReads
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
}
