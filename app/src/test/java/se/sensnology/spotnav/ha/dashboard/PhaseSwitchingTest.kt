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
 * Phase switching for solar over the webhook: the dashboard's `phase_switching` block (no vendored dashboard carries
 * one, so it is added here in the contract's shape, `{value, writable}`) and `update_phase_switching`, against Home
 * Assistant's own answers.
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

    private fun answer(name: String, status: Int) =
        PhaseSwitchingUpdate.answer(status, se.sensnology.spotnav.testing.HaFixtures.json("webhook/$name.json").toString())

    @Test fun homeAssistantsAnswersAreOutcomesAndFeedbacks() {
        val updated = answer("update_phase_switching_success", 200)
        assertEquals(Outcome.Updated(PhaseSwitching(true, true)), updated)
        assertEquals(PairedVehicles.PhaseSwitchingFeedback(PhaseSwitching(true, true), null, reload = true), PairedVehicles.feedback(updated))
        // Changed elsewhere first: the value Home Assistant has is adopted, and the screen reads again.
        val conflict = answer("update_phase_switching_conflict", 409)
        assertEquals(Outcome.Conflict(PhaseSwitching(true, true)), conflict)
        assertEquals(PairedVehicles.PriorityNotice.CONFLICT, PairedVehicles.feedback(conflict).notice)
        assertTrue(PairedVehicles.feedback(conflict).reload)
        val refused = answer("update_phase_switching_invalid_value", 400)
        assertEquals(Outcome.Refused(PhaseSwitching(true, true)), refused)
        assertEquals(PairedVehicles.PriorityNotice.INVALID, PairedVehicles.feedback(refused).notice)
        // No longer offered for this charger: said, and read again so the row goes.
        val notOffered = answer("update_phase_switching_not_writable", 400)
        assertEquals(Outcome.NotOffered, notOffered)
        assertEquals(PairedVehicles.PhaseSwitchingFeedback(null, PairedVehicles.PriorityNotice.INVALID, reload = true), PairedVehicles.feedback(notOffered))
    }

    @Test fun otherAnswersAreOutcomesToo() {
        assertEquals(Outcome.NotPermitted, PhaseSwitchingUpdate.answer(403, JSONObject().put("ok", false).put("error", "spotnav_not_admin").toString()))
        assertEquals(
            Outcome.NotSupported,
            PhaseSwitchingUpdate.answer(400, JSONObject().put("ok", false).put("error", "Unsupported action").toString())
        )
        assertEquals(Outcome.Failed(null), PhaseSwitchingUpdate.answer(null, null))
        assertEquals(PairedVehicles.PriorityNotice.FAILED, PairedVehicles.feedback(Outcome.Failed("x")).notice)
    }
}
