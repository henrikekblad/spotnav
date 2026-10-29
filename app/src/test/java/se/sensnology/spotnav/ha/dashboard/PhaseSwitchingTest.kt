package se.sensnology.spotnav.ha.dashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.HomeAssistantCommand
import se.sensnology.spotnav.ha.client.PhaseSwitchingUpdate
import se.sensnology.spotnav.ha.client.PhaseSwitchingUpdate.Outcome
import se.sensnology.spotnav.ha.client.WebhookReads
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.vehicles.PairedVehicles

/**
 * Phase switching for solar over the webhook: the dashboard's `phase_switching` block and `update_phase_switching`.
 * Home Assistant's own fixtures for them do not exist yet; the blocks and answers here follow the contract
 * (`{value, writable}`; `{ok, phase_switching, action}`, a stale `expected` refused with `spotnav_conflict`).
 */
class PhaseSwitchingTest {
    private fun dashboard(raw: Any?) = DashboardFixtures.dashboard { put("phase_switching", raw) }

    @Test fun theAppAsksForTheBlock() {
        assertTrue(WebhookReads.FIELDS.contains("phase_switching"))
        val request = JSONObject(HomeAssistantClient.payload(HomeAssistantCommand("dashboard")))
        assertTrue(request.getJSONArray("reads").toString().contains("\"phase_switching\""))
    }

    @Test fun theBlockIsReadLeniently() {
        assertEquals(PhaseSwitching(true, true), dashboard(JSONObject().put("value", true).put("writable", true)).phaseSwitching)
        assertEquals(PhaseSwitching(false, false), dashboard(JSONObject().put("value", false).put("writable", false)).phaseSwitching)
        // Writable only when it says so.
        assertEquals(PhaseSwitching(true, false), dashboard(JSONObject().put("value", true)).phaseSwitching)
        // Not offered, or not readable: no row, and the dashboard is read all the same.
        assertNull(DashboardFixtures.dashboard().phaseSwitching)
        assertNull(dashboard(JSONObject().put("value", "on").put("writable", true)).phaseSwitching)
        assertNull(dashboard(true).phaseSwitching)
    }

    @Test fun theWriteStatesTheNewValueAndTheOneShown() {
        val body = PhaseSwitchingUpdate.payload(expected = false, enabled = true)
        assertEquals("update_phase_switching", body.getString("action"))
        assertEquals(1, body.getInt("version"))
        assertEquals(true, body.get("enabled"))
        assertEquals(false, body.get("expected"))
        assertTrue(body.getJSONArray("reads").toString().contains("\"phase_switching\""))
    }

    @Test fun eachAnswerIsAnOutcomeAndAFeedback() {
        fun body(ok: Boolean, error: String? = null, value: Boolean? = true) = JSONObject()
            .put("ok", ok).put("error", error ?: JSONObject.NULL).put("action", "update_phase_switching")
            .put("phase_switching", value?.let { JSONObject().put("value", it).put("writable", true) } ?: JSONObject.NULL)
            .toString()
        val updated = PhaseSwitchingUpdate.answer(200, body(true))
        assertEquals(Outcome.Updated(PhaseSwitching(true, true)), updated)
        assertEquals(PairedVehicles.PhaseSwitchingFeedback(PhaseSwitching(true, true), null, reload = true), PairedVehicles.feedback(updated))
        val conflict = PhaseSwitchingUpdate.answer(409, body(false, "spotnav_conflict", value = false))
        assertEquals(Outcome.Conflict(PhaseSwitching(false, true)), conflict)
        assertEquals(PairedVehicles.PriorityNotice.CONFLICT, PairedVehicles.feedback(conflict).notice)
        assertTrue(PairedVehicles.feedback(conflict).reload)
        val refused = PhaseSwitchingUpdate.answer(400, body(false, "spotnav_invalid_value", value = false))
        assertEquals(PairedVehicles.PriorityNotice.INVALID, PairedVehicles.feedback(refused).notice)
        assertEquals(Outcome.NotPermitted, PhaseSwitchingUpdate.answer(403, body(false, "spotnav_not_admin", value = null)))
        assertEquals(
            Outcome.NotSupported,
            PhaseSwitchingUpdate.answer(400, JSONObject().put("ok", false).put("error", "Unsupported action").toString())
        )
        assertEquals(Outcome.Failed(null), PhaseSwitchingUpdate.answer(null, null))
        assertEquals(PairedVehicles.PriorityNotice.FAILED, PairedVehicles.feedback(Outcome.Failed("x")).notice)
    }
}
