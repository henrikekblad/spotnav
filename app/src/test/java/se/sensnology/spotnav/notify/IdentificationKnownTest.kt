package se.sensnology.spotnav.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.WebhookReads
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.settings.NotificationEvent
import se.sensnology.spotnav.push.PushRegistration
import se.sensnology.spotnav.push.PushStore
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.testing.FakeKeyValueStore
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

    /** The owner's phone: a choice saved while the question was hidden, and no `identifies.` key. */
    private fun ownersStore(): Pair<FakeKeyValueStore, LocalNotificationStore> {
        val raw = FakeKeyValueStore()
        raw.putString("enabled", "true")
        raw.putString("events", """["plan_stopped","plan_at_risk","charge_complete","plan_installed"]""")
        raw.putString("event_set", "2")
        return raw to LocalNotificationStore(raw)
    }

    @Test fun learningThatHomeAssistantIdentifiesAddsTheQuestionToAChoiceMadeWhileItWasHidden() {
        val (_, store) = ownersStore()
        assertFalse(NotificationEvent.VEHICLE_IDENTIFY in store.events)
        assertTrue(store.observeIdentifies("a", true))
        assertTrue(store.identifies("a"))
        assertEquals(
            setOf(
                NotificationEvent.PLAN_STOPPED, NotificationEvent.PLAN_AT_RISK, NotificationEvent.CHARGE_COMPLETE,
                NotificationEvent.PLAN_INSTALLED, NotificationEvent.VEHICLE_IDENTIFY
            ),
            store.events
        )
        // Told again: nothing to bring up to date.
        assertFalse(store.observeIdentifies("a", true))
    }

    @Test fun aQuestionTurnedOffOnceItWasOfferedStaysOff() {
        val (_, store) = ownersStore()
        store.observeIdentifies("a", true)
        store.events = store.events - NotificationEvent.VEHICLE_IDENTIFY
        // Another charger, or the same one again after an older Home Assistant: the choice stands.
        assertTrue(store.observeIdentifies("b", true))
        assertFalse(store.observeIdentifies("b", true))
        assertTrue(store.observeIdentifies("a", false))
        assertTrue(store.observeIdentifies("a", true))
        assertFalse(NotificationEvent.VEHICLE_IDENTIFY in store.events)
    }

    @Test fun aHomeAssistantThatDoesNotIdentifyLeavesTheChoiceAsItIs() {
        val (_, store) = ownersStore()
        assertFalse(store.observeIdentifies("a", false))
        assertFalse(store.identifies("a"))
        assertFalse(NotificationEvent.VEHICLE_IDENTIFY in store.events)
    }

    @Test fun onceKnownHomeAssistantIsRegisteredAgainWithTheQuestion() {
        val (_, local) = ownersStore()
        val push = PushStore(FakeKeyValueStore()).apply { enabled = true; pushRef = "ref" }
        val calls = mutableListOf<Pair<String, List<String>>>()
        val registration = PushRegistration(
            store = push,
            tokens = object : PushRegistration.Tokens {
                override fun token(): String? = "token"
                override fun delete() {}
            },
            relay = { se.sensnology.spotnav.push.PushRelay.Outcome.Registered("ref") },
            homeAssistant = { id, _, events -> calls += id to events; true },
            local = { PushRegistration.Local.of(local, listOf("a")) }
        )
        registration.sync()
        assertEquals(listOf("a" to listOf("plan_stopped", "plan_at_risk", "charge_complete", "plan_installed")), calls)
        calls.clear()
        local.observeIdentifies("a", true)
        registration.sync()
        assertEquals(
            listOf("a" to listOf("plan_stopped", "plan_at_risk", "charge_complete", "plan_installed", "vehicle_identify")),
            calls
        )
    }
}
