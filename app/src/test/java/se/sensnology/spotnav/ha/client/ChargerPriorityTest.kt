package se.sensnology.spotnav.ha.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.ChargerPriority
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.testing.HaFixtures
import se.sensnology.spotnav.vehicles.PairedVehicles

/** The charger priority block and its `update_charger_priority` write, against the integration's own fixtures. */
class ChargerPriorityTest {
    private fun webhook(name: String) = HaFixtures.root.resolve("webhook/$name.json").readText()

    @Test fun theDashboardBlockIsDecodedForASiteAdminAndAReader() {
        val admin = Dashboard.parse(HaFixtures.json("dashboard/cheapest_direct_site_admin.json")).chargerPriority!!
        assertTrue(admin.writable)
        assertEquals(ChargerPriority.VALUES, admin.choices)
        val reader = Dashboard.parse(HaFixtures.json("dashboard/cheapest_direct_site_read_only.json")).chargerPriority!!
        assertEquals("normal", reader.value)
        assertFalse(reader.writable)
    }

    @Test fun aChargerOnNoSiteAndAnOlderHomeAssistantShowNoPriority() {
        assertNull(Dashboard.parse(HaFixtures.json("dashboard/cheapest_no_site.json")).chargerPriority)
        val older = HaFixtures.json("dashboard/cheapest_direct_site_admin.json").apply { remove("charger_priority") }
        assertNull(Dashboard.parse(older).chargerPriority)
    }

    @Test fun anUnreadableBlockIsHiddenAndNeverRefusesTheDashboard() {
        for (raw in listOf("text", 3, JSONObject("""{"value":"urgent","choices":[],"writable":true}"""), JSONObject("""{"choices":["first"]}"""))) {
            val json = HaFixtures.json("dashboard/cheapest_direct_site_admin.json").put("charger_priority", raw)
            assertNull(Dashboard.parse(json).chargerPriority)
        }
        val unknownChoice = ChargerPriority.parse(JSONObject("""{"value":"first","choices":["first","urgent"],"writable":true}"""))!!
        assertEquals(listOf("first"), unknownChoice.choices)
    }

    @Test fun theRequestCarriesTheValueLastSeenAndTheNewOne() {
        val body = ChargerPriorityUpdate.payload("normal", "first")
        assertEquals("update_charger_priority", body.getString("action"))
        assertEquals(1, body.getInt("api_version"))
        assertEquals("normal", body.getString("expected"))
        assertEquals("first", body.getString("priority"))
    }

    @Test fun aSuccessAdoptsTheBlockTheAnswerCarries() {
        val outcome = ChargerPriorityUpdate.answer(200, webhook("update_charger_priority_success"))
        assertEquals("first", (outcome as ChargerPriorityUpdate.Outcome.Updated).priority.value)
        assertEquals(PairedVehicles.PriorityFeedback(outcome.priority, null, true), PairedVehicles.feedback(outcome))
    }

    @Test fun aConflictShowsTheStoredValueAndReloads() {
        val outcome = ChargerPriorityUpdate.answer(409, webhook("update_charger_priority_conflict"))
        assertEquals("first", (outcome as ChargerPriorityUpdate.Outcome.Conflict).priority!!.value)
        val feedback = PairedVehicles.feedback(outcome)
        assertEquals(PairedVehicles.PriorityNotice.CONFLICT, feedback.notice)
        assertTrue(feedback.reload)
        assertEquals("first", feedback.adopted!!.value)
    }

    @Test fun invalidNoSiteAndUnsupportedAreTheirOwnOutcomes() {
        assertTrue(ChargerPriorityUpdate.answer(400, webhook("update_charger_priority_invalid_value")) is ChargerPriorityUpdate.Outcome.Refused)
        assertEquals(ChargerPriorityUpdate.Outcome.NoSite, ChargerPriorityUpdate.answer(400, webhook("update_charger_priority_no_site")))
        val unsupported = JSONObject(webhook("update_charger_priority_no_site")).put("error", "spotnav_unsupported_api_version").toString()
        assertEquals(ChargerPriorityUpdate.Outcome.NotSupported, ChargerPriorityUpdate.answer(400, unsupported))
        assertEquals(PairedVehicles.PriorityNotice.NO_SITE, PairedVehicles.feedback(ChargerPriorityUpdate.Outcome.NoSite).notice)
    }

    @Test fun noAnswerOrAnUnreadableOneFails() {
        assertEquals(ChargerPriorityUpdate.Outcome.Failed(null), ChargerPriorityUpdate.answer(null, null))
        assertEquals(ChargerPriorityUpdate.Outcome.Failed(null), ChargerPriorityUpdate.answer(200, "nope"))
        val ok = JSONObject(webhook("update_charger_priority_success")).put("charger_priority", JSONObject.NULL).toString()
        assertTrue(ChargerPriorityUpdate.answer(200, ok) is ChargerPriorityUpdate.Outcome.Failed)
        assertEquals(PairedVehicles.PriorityNotice.FAILED, PairedVehicles.feedback(ChargerPriorityUpdate.Outcome.Failed("x")).notice)
    }
}
