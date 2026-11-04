package se.sensnology.spotnav.ha.client

import org.junit.Assert.assertEquals
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.DashboardGuest

/** The webhook's `update_guest` and `identify_vehicle` with a guest car, as `docs/api.md` states them. */
class HaGuestActionsTest {
    @Test fun theGuestAmountIsWrittenWithItsVersionAndTheReads() {
        val body = GuestUpdate.payload(12.5)
        assertEquals("update_guest", body.getString("action"))
        assertEquals(1, body.getInt("api_version"))
        assertEquals(12.5, body.getDouble("amount_kwh"), 0.0)
        assertEquals(WebhookReads.FIELDS.size, body.getJSONArray("reads").length())
    }

    @Test fun theAnswerCarriesTheBlockAfterTheWriteOrSaysTheAmountIsOutOfRange() {
        assertEquals(
            GuestUpdate.Outcome.Updated(DashboardGuest(active = false, amountKwh = 12.5, first = false)),
            GuestUpdate.answer(200, """{"api_version": 1, "ok": true, "error": null, "guest": {"active": false, "amount_kwh": 12.5, "first": false}, "action": "update_guest"}""")
        )
        assertEquals(
            GuestUpdate.Outcome.Updated(null),
            GuestUpdate.answer(200, """{"api_version": 1, "ok": true, "error": null, "guest": null, "action": "update_guest"}""")
        )
        assertEquals(
            GuestUpdate.Outcome.OutOfRange,
            GuestUpdate.answer(400, """{"api_version": 1, "ok": false, "error": "spotnav_invalid_value", "action": "update_guest"}""")
        )
        assertEquals(GuestUpdate.Outcome.Failed("Unsupported action"), GuestUpdate.answer(400, """{"ok": false, "error": "Unsupported action"}"""))
        assertEquals(GuestUpdate.Outcome.Failed(null), GuestUpdate.answer(null, null))
    }

    @Test fun aGuestCarIsAnsweredWithItsOwnId() {
        assertEquals("guest", IdentifyVehicle.payload(DashboardGuest.VEHICLE_ID).getString("vehicle_id"))
    }
}
