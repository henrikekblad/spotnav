package se.sensnology.spotnav.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.WebhookReads
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.vehicles.VehicleIdentification

/**
 * Whether a charger's Home Assistant identifies cars, as this phone learns it from the dashboard it
 * reads, and what that does to this phone's own events and to what Home Assistant is registered with.
 */
class IdentificationKnownTest {
    /** The settings fields Home Assistant's webhook withholds unless a request reads them (`webhook.py`). */
    private val withheld = listOf(
        "departure_date", "departure_weekdays", "fiscal_included", "notifications", "fill_to_limit", "vehicle_ids",
        "identify_mode", "identify_camera"
    )

    /** The dashboard as Home Assistant's webhook answers a request reading [reads]. */
    private fun webhookDashboard(reads: List<String>): Dashboard =
        DashboardFixtures.dashboard("target_soc_two_vehicles.json") {
            put("ok", true).put("action", "dashboard")
            val settings = getJSONObject("settings")
            withheld.filter { it !in reads }.forEach { settings.remove(it) }
        }

    @Test fun homeAssistantAdvertisesIdentificationThroughTheSettingsFieldsTheAppReads() {
        // The record states identify_mode and vehicle_ids; the per-car sources are on each vehicle row.
        val raw = DashboardFixtures.json("target_soc_two_vehicles.json").getJSONObject("settings")
        assertTrue(raw.has("identify_mode"))
        assertTrue(raw.has("vehicle_ids"))
        assertTrue(VehicleIdentification.advertised(webhookDashboard(WebhookReads.FIELDS)))
        // A request that does not read them is a Home Assistant that does not identify, for this app.
        assertFalse(VehicleIdentification.advertised(webhookDashboard(emptyList())))
    }
}
