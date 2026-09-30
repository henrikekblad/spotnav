package se.sensnology.spotnav.ha.dashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.ha.authority.AuthorityController
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.testing.FakeKeyValueStore

/** The dashboard's `charge_progress`: */
class ChargeProgressTest {
    private fun progress(state: String) = ChargeProgress(
        state = state,
        reason = "suspended_ev_zero_current",
        since = "2026-09-27T12:00:00+00:00"
    )

    private fun block(
        state: Any? = "vehicle_not_requesting_current",
        reason: Any? = "suspended_ev_zero_current",
        since: Any? = "2026-09-27T12:00:00+00:00",
        extraKey: String? = null,
        omit: String? = null
    ): JSONObject {
        val json = JSONObject()
        if (omit != "state") json.put("state", state ?: JSONObject.NULL)
        if (omit != "reason") json.put("reason", reason ?: JSONObject.NULL)
        if (omit != "since") json.put("since", since ?: JSONObject.NULL)
        if (extraKey != null) json.put(extraKey, "anything")
        return json
    }

    // the decoder

    @Test
    fun `accepts the integration's own block`() {
        val parsed = ChargeProgressContract.of(block())

        assertNotNull(parsed)
        assertEquals("vehicle_not_requesting_current", parsed!!.state)
        assertEquals("suspended_ev_zero_current", parsed.reason)
        assertEquals("2026-09-27T12:00:00+00:00", parsed.since)
    }

    @Test
    fun `accepts a null since as an observation that has not begun`() {
        val parsed = ChargeProgressContract.of(block(state = "normal", since = null))

        assertNotNull(parsed)
        assertNull(parsed!!.since)
    }

    @Test
    fun `refuses every variant that is not the block, and never throws`() {
        val refused = listOf(
            null,
            block(omit = "state"),
            block(omit = "reason"),
            block(omit = "since"),
            block(extraKey = "sampled_at"),
            block(state = "SuspendedEV"),
            block(state = "pending"),
            block(state = ""),
            block(state = 7),
            block(state = null),
            block(reason = ""),
            block(reason = 7),
            block(reason = null),
            block(reason = "x".repeat(ChargeProgressContract.MAX_REASON_LENGTH + 1)),
            block(since = 1759000000),
            block(since = "yesterday"),
            block(since = "2026-09-27T12:00:00"),
            block(since = "")
        )

        for (variant in refused) {
            assertNull("accepted $variant", ChargeProgressContract.of(variant))
        }
    }

    // the one presentation decision

    @Test
    fun `only the vehicle-side state produces the advisory`() {
        assertTrue(ChargeProgressContract.advisory(progress("vehicle_not_requesting_current")))
        assertFalse(ChargeProgressContract.advisory(progress("normal")))
        assertFalse(ChargeProgressContract.advisory(progress("unknown")))
        assertFalse(ChargeProgressContract.advisory(null))
    }

    // the dashboard document

    /**
     * A whole dashboard document (Home Assistant's own fixture) with its `charge_progress` replaced
     * by [chargeProgress], or removed when that is blank.
     */
    private fun read(chargeProgress: String): Dashboard = Dashboard.parse(
        DashboardFixtures.json("stop_charging.json").apply {
            if (chargeProgress.isBlank()) remove("charge_progress") else put("charge_progress", JSONObject(chargeProgress))
        }
    )

    @Test
    fun `the dashboard carries the observation, and one without it carries none`() {
        val document = read(
            """{"state": "vehicle_not_requesting_current", "reason": "suspended_ev_zero_current", """ +
                """"since": "2026-09-27T12:00:00+00:00"}"""
        )

        assertNotNull(document.chargeProgress)
        assertEquals("vehicle_not_requesting_current", document.chargeProgress!!.state)
        assertEquals("suspended_ev_zero_current", document.chargeProgress!!.reason)
        assertEquals("2026-09-27T12:00:00+00:00", document.chargeProgress!!.since)

        // No field, and therefore nothing said -- never a default.
        assertNull(read("").chargeProgress)
    }

    @Test
    fun `a malformed block leaves every other fact in the answer intact`() {
        val document = read(
            """{"state": "SuspendedEV", "reason": "suspended_ev_zero_current", """ +
                """"since": null, "sampled_at": "2026-09-27T12:00:00+00:00"}"""
        )

        assertNull(document.chargeProgress)
        assertTrue(document.chargingEnabled)
        assertEquals("entry_a", document.chargerId)
    }

    // nothing about the vehicle is durable

    @Test
    fun `an older answer cannot restore an advisory a newer one cleared`() {
        val stillObserving = read(
            """{"state": "vehicle_not_requesting_current", "reason": "suspended_ev_zero_current", """ +
                """"since": "2026-09-27T12:00:00+00:00"}"""
        )
        val cleared = read(
            """{"state": "normal", "reason": "current_flowing", "since": null}"""
        )

        assertTrue(ChargeProgressContract.advisory(stillObserving.chargeProgress))
        assertFalse(ChargeProgressContract.advisory(cleared.chargeProgress))
    }

    @Test
    fun `the advisory never reaches the stored profile record`() {
        val backing = FakeKeyValueStore()
        val store = ChargerProfileStore(backing) { }
        val profile = ChargerProfile(
            localId = "local-a",
            displayName = "Garage",
            baseUrl = "https://spotnav.example.invalid:8123",
            webhookId = "webhook-secret"
        )
        store.upsertProfile(profile)

        val document = read(
            """{"state": "vehicle_not_requesting_current", "reason": "suspended_ev_zero_current", """ +
                """"since": "2026-09-27T12:00:00+00:00"}"""
        )
        assertEquals("vehicle_not_requesting_current", document.chargeProgress!!.state)
        store.updateFromDashboard(profile.localId, document)

        // The dashboard may write identity and detection facts and nothing else.
        val raw = backing.rawOrNull("state").orEmpty() + store.getProfile(profile.localId).toString()
        assertFalse(raw.contains("vehicle_not_requesting_current"))
        assertFalse(raw.contains("suspended_ev_zero_current"))
    }
}
