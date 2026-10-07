package se.sensnology.spotnav.ha.authority

import org.json.JSONObject
import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerAction
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.HomeAssistantCommand
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.DashboardDecodeException
import se.sensnology.spotnav.ha.settings.HaSettingsCodec
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.testing.SettingsFixtures

import org.junit.Assert.*

/** Paired ownership is accepted only from the one settings contract, carried by the dashboard. */
class HaOwnerContractTest {
    @Test fun aDashboardCarriesTheSettingsRecordWhole() {
        val record = SettingsFixtures.parsed(revision = 9, requestedKwh = 41.6)
        val dashboard = DashboardFixtures.dashboard { put("settings", HaSettingsCodec.encode(record)) }

        assertEquals(record, dashboard.settings)
        assertEquals(HaPlanningInputs.Auto(record), HaPlanningAdapter.of(record, emptyList()))
        val authority = VisibleAuthorityResolver.forRecord(record, HaPlanningInputs.Auto(record), dashboard)
        assertTrue(authority is VisibleAuthority.AutoRemote)
        assertFalse(AuthorityPlan.source(authority) is PlanSource.AndroidCalculates)
    }

    @Test fun aRecordWithAModeOrAnyRetiredKeyIsReadWithoutThatKey() {
        for ((key, value) in listOf("mode" to "external", "allow_estimated_prices" to false, "execution_paused" to false)) {
            val json = DashboardFixtures.json().apply {
                put("settings", SettingsFixtures.response(revision = 7).put(key, value))
            }
            assertEquals(key, SettingsFixtures.parsed(revision = 7), Dashboard.parse(json).settings)
        }
    }

    @Test fun aSettingsRequestStatesOneReplacementAndOneRevision() {
        val record = SettingsFixtures.parsed(revision = 9, requestedKwh = 41.6)
        val request = JSONObject(HomeAssistantClient.payload(HomeAssistantCommand(
            action = "settings", expectedRevision = 9, settingsReplacement = record
        )))
        assertEquals(
            setOf("version", "reads", "action", "expected_revision", "settings"),
            request.keys().asSequence().toSet()
        )
        assertEquals(9, request.getInt("expected_revision"))
        assertEquals(listOf("departure_date", "departure_weekdays", "fiscal_included", "notifications", "fill_to_limit", "vehicle_ids", "identify_mode", "identify_camera", "identification_status", "solar_no_car_status", "min_soc"), List(request.getJSONArray("reads").length()) { request.getJSONArray("reads").getString(it) })
        // The replacement names the date explicitly, null clearing it:
        assertTrue(request.getJSONObject("settings").has("departure_date"))
        assertEquals(7, request.getJSONObject("settings").getJSONArray("departure_weekdays").length())
        assertFalse(request.getJSONObject("settings").has("mode"))
        assertFalse(request.getJSONObject("settings").has("revision"))
        assertEquals(41.6, request.getJSONObject("settings").getDouble("requested_kwh"), 0.0)
    }

    @Test fun commandsCannotCarryAScheduleOrTheVehicleFiguresByType() {
        val names = HomeAssistantCommand::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(
            names.intersect(
                setOf("periods", "start", "end", "energyKwh", "priceArea", "estimated", "powerKw", "targetSocPercent")
            ).isEmpty()
        )
        assertEquals(setOf(ChargerAction.START, ChargerAction.STOP), ChargerAction.entries.toSet())
    }
}
