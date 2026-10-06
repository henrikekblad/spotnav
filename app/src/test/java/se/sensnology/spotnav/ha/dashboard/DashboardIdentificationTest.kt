package se.sensnology.spotnav.ha.dashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.DashboardFixtures

/**
 * The dashboard's additive `identification` block and the vehicle rows' `identification` and
 * `target_percent` (`docs/api.md`, "Vehicle identification"): read leniently, so an older Home
 * Assistant without them, or a block this app cannot read, leaves today's behaviour.
 */
class DashboardIdentificationTest {
    private fun asking(): JSONObject = JSONObject()
        .put("state", "asking")
        .put("method", "assumed")
        .put("vehicle_id", "vehicle_ev6")
        .put("since", "2026-10-06T17:00:00+00:00")
        .put(
            "candidates", JSONArray()
                .put(JSONObject().put("vehicle_id", "vehicle_ev6").put("name", "EV6").put("likely", true))
                .put(JSONObject().put("vehicle_id", "vehicle_niro").put("name", "Niro").put("likely", false))
        )
        .put(
            "evidence", JSONArray()
                .put(
                    JSONObject().put("vehicle_id", "vehicle_ev6")
                        .put(
                            "plug", JSONObject().put("entity_id", "binary_sensor.ev6_plugged_in").put("state", "on")
                                .put("changed", "2026-10-06T16:59:00+00:00").put("reported", "2026-10-06T16:59:00+00:00")
                        )
                        .put("location", JSONObject.NULL)
                        .put("verdict", "plugged_in")
                )
                .put(
                    JSONObject().put("vehicle_id", "vehicle_niro")
                        .put("plug", JSONObject.NULL)
                        .put("location", JSONObject().put("entity_id", "device_tracker.niro").put("home", false).put("reported", "2026-10-06T17:00:30+00:00"))
                        .put("verdict", "away")
                )
        )

    @Test fun theVendoredDashboardsStateNoIdentificationAndTheirRowsTheirTargets() {
        val dashboard = DashboardFixtures.dashboard("target_soc_two_vehicles.json")
        assertNull(dashboard.identification)
        assertEquals(listOf(80.0, null), dashboard.vehicles.map { it.targetPercent })
        assertTrue(dashboard.vehicles.all { it.targetStated })
        val sources = dashboard.vehicles.first().identification!!
        assertNull(sources.plug!!.entityId)
        assertFalse(sources.plug!!.chosen)
        assertEquals(emptyList<IdentificationEntity>(), sources.location!!.candidates)
    }

    @Test fun anOlderHomeAssistantWithoutTheBlocksReadsAsBefore() {
        val dashboard = DashboardFixtures.dashboard("target_soc_two_vehicles.json") {
            remove("identification")
            val rows = getJSONArray("vehicles")
            for (index in 0 until rows.length()) {
                rows.getJSONObject(index).remove("identification")
                rows.getJSONObject(index).remove("target_percent")
            }
        }
        assertNull(dashboard.identification)
        assertTrue(dashboard.vehicles.none { it.targetStated })
        assertTrue(dashboard.vehicles.all { it.identification == null && it.targetPercent == null })
    }

    @Test fun anOpenQuestionIsReadWithItsCandidatesInOrderAndTheirEvidence() {
        val dashboard = DashboardFixtures.dashboard("target_soc_two_vehicles.json") { put("identification", asking()) }
        val block = dashboard.identification!!
        assertEquals(DashboardIdentification.State.ASKING, block.state)
        assertEquals(DashboardIdentification.Method.ASSUMED, block.method)
        assertEquals("vehicle_ev6", block.vehicleId)
        assertEquals(listOf("vehicle_ev6", "vehicle_niro"), block.candidates.map { it.vehicleId })
        assertEquals(listOf(true, false), block.candidates.map { it.likely })
        assertEquals("plugged_in", block.verdictFor("vehicle_ev6"))
        assertEquals("away", block.verdictFor("vehicle_niro"))
        assertNull(block.verdictFor("vehicle_other"))
    }

    @Test fun eachStateAndMethodIsRead() {
        for ((wire, state) in listOf(
            "waiting" to DashboardIdentification.State.WAITING,
            "asking" to DashboardIdentification.State.ASKING,
            "decided" to DashboardIdentification.State.DECIDED
        )) {
            assertEquals(state, DashboardIdentification.parse(asking().put("state", wire))!!.state)
        }
        for ((wire, method) in listOf(
            "plug_sensor" to DashboardIdentification.Method.PLUG_SENSOR,
            "location" to DashboardIdentification.Method.LOCATION,
            "answered" to DashboardIdentification.Method.ANSWERED,
            "manual" to DashboardIdentification.Method.MANUAL,
            "assumed" to DashboardIdentification.Method.ASSUMED
        )) {
            assertEquals(method, DashboardIdentification.parse(asking().put("method", wire))!!.method)
        }
        // A method this app does not know says nothing of how the car was decided.
        assertNull(DashboardIdentification.parse(asking().put("method", "telepathy"))!!.method)
    }

    @Test fun aBlockThisAppCannotReadIsNoBlockAndNeverRefusesTheDashboard() {
        assertNull(DashboardIdentification.parse(JSONObject.NULL))
        assertNull(DashboardIdentification.parse("asking"))
        assertNull(DashboardIdentification.parse(asking().put("state", "pondering")))
        assertNull(DashboardIdentification.parse(asking().put("candidates", "EV6")))
        val dashboard = DashboardFixtures.dashboard("target_soc_two_vehicles.json") { put("identification", 42) }
        assertNull(dashboard.identification)
        // A candidate without an id is left out; one without a name is named by its id.
        val block = DashboardIdentification.parse(
            asking().put(
                "candidates", JSONArray()
                    .put(JSONObject().put("name", "Ghost"))
                    .put(JSONObject().put("vehicle_id", "vehicle_niro"))
            ).put("evidence", "nothing")
        )!!
        assertEquals(listOf(IdentificationCandidate("vehicle_niro", "vehicle_niro", false)), block.candidates)
        assertEquals(emptyList<IdentificationEvidence>(), block.evidence)
    }

    @Test fun aVehicleRowsSourcesAreReadWithTheirCandidates() {
        val sources = JSONObject()
            .put(
                "plug", JSONObject().put("entity_id", "binary_sensor.ev6_plugged_in").put("name", "EV6 Plugged in")
                    .put("chosen", false).put("candidates", JSONArray().put(JSONObject().put("entity_id", "binary_sensor.ev6_plugged_in").put("name", "EV6 Plugged in")))
            )
            .put("location", JSONObject().put("entity_id", JSONObject.NULL).put("name", JSONObject.NULL).put("chosen", true).put("candidates", JSONArray()))
        val dashboard = DashboardFixtures.dashboard("target_soc_two_vehicles.json") {
            getJSONArray("vehicles").getJSONObject(0).put("identification", sources)
            getJSONArray("vehicles").getJSONObject(1).put("identification", "garbled").put("target_percent", "ninety")
        }
        val plug = dashboard.vehicles[0].identification!!.plug!!
        assertEquals("binary_sensor.ev6_plugged_in", plug.entityId)
        assertEquals("EV6 Plugged in", plug.name)
        assertEquals(listOf(IdentificationEntity("binary_sensor.ev6_plugged_in", "EV6 Plugged in")), plug.candidates)
        assertTrue(dashboard.vehicles[0].identification!!.location!!.chosen)
        assertNull(dashboard.vehicles[1].identification)
        // A target that is not a percent reads as none, but the row still states the field.
        assertNull(dashboard.vehicles[1].targetPercent)
        assertTrue(dashboard.vehicles[1].targetStated)
    }
}
