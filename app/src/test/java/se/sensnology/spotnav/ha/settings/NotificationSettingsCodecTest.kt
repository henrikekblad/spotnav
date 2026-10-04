package se.sensnology.spotnav.ha.settings

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.HomeAssistantCommand
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.HaFixtures
import se.sensnology.spotnav.testing.SettingsFixtures.response

/** The settings record's `notifications` field (Home Assistant 1.9): read, written back, and absent. */
class NotificationSettingsCodecTest {
    private fun withNotifications(
        targets: List<String> = listOf("mobile_app_pixel_8"),
        events: List<String> = listOf("charge_complete", "plan_stopped"),
        url: Any? = "/lovelace/garage",
        available: JSONArray = JSONArray()
            .put(JSONObject().put("service", "mobile_app_pixel_8").put("name", "Pixel 8"))
            .put(JSONObject().put("service", "mobile_app_ipad").put("name", "iPad"))
    ): JSONObject = response(revision = 3).put(
        "notifications",
        JSONObject()
            .put("targets", JSONArray(targets))
            .put("events", JSONArray(events))
            .put("url", url ?: JSONObject.NULL)
            .put("available", available)
    )

    @Test fun anAnswerWithTheFieldIsReadWithItsPhonesAndEventsInTheContractsOrder() {
        val record = HaSettingsCodec.parseResponse(withNotifications())
        val notifications = record.notifications!!
        assertEquals(listOf("mobile_app_pixel_8"), notifications.targets)
        assertEquals(listOf("plan_stopped", "charge_complete"), notifications.events)
        assertEquals("/lovelace/garage", notifications.url)
        assertEquals(
            listOf(HaNotifyService("mobile_app_pixel_8", "Pixel 8"), HaNotifyService("mobile_app_ipad", "iPad")),
            notifications.available
        )
    }

    @Test fun anAnswerWithoutTheFieldStatesNothingAndWritesNothingBack() {
        val record = HaSettingsCodec.parseResponse(response(revision = 3))
        assertNull(record.notifications)
        assertFalse(HaSettingsCodec.encodeBody(record).has("notifications"))
        assertFalse(HaSettingsCodec.encode(record).has("notifications"))
    }

    @Test fun theVendoredSuccessAnswerStatesTheDefaults() {
        val record = HaSettingsCodec.parseResponse(HaFixtures.json("settings/v1/success.json").getJSONObject("settings"))
        val notifications = record.notifications!!
        assertEquals(emptyList<String>(), notifications.targets)
        assertEquals(NotificationEvent.DEFAULTS.map { it.wire }, notifications.events)
        assertNull(notifications.url)
    }

    @Test fun theBodyCarriesTheChoiceButNeverThePhonesThatExist() {
        val record = HaSettingsCodec.parseResponse(withNotifications())
        val body = HaSettingsCodec.encodeBody(record).getJSONObject("notifications")
        assertEquals(setOf("targets", "events", "url"), body.keys().asSequence().toSet())
        // The stored copy keeps them, and reads back equal.
        val stored = HaSettingsCodec.encode(record)
        assertTrue(stored.getJSONObject("notifications").has("available"))
        assertEquals(record, HaSettingsCodec.parseStored(stored))
    }

    @Test fun anEventThisAppDoesNotKnowIsKeptThroughAnEdit() {
        val record = HaSettingsCodec.parseResponse(withNotifications(events = listOf("future_event", "plan_stopped")))
        assertEquals(listOf("plan_stopped", "future_event"), record.notifications!!.events)
        val ready = HaSettingsEditor.replacement(
            record, HaSettingsEdit.Notifications(listOf("mobile_app_ipad"), listOf("charge_complete", "future_event"))
        ) as HaSettingsEditResult.Ready
        val edited = ready.settings.notifications!!
        assertEquals(listOf("mobile_app_ipad"), edited.targets)
        assertEquals(listOf("charge_complete", "future_event"), edited.events)
        // The tap path and the phones that exist are the record's own.
        assertEquals("/lovelace/garage", edited.url)
        assertEquals(record.notifications!!.available, edited.available)
        assertEquals(record.revision, ready.settings.revision)
    }

    @Test fun anotherEditKeepsTheChoiceAsItIs() {
        val record = HaSettingsCodec.parseResponse(withNotifications())
        val ready = HaSettingsEditor.replacement(record, HaSettingsEdit.Amps(10)) as HaSettingsEditResult.Ready
        assertEquals(record.notifications, ready.settings.notifications)
    }

    @Test fun anUnreadableFieldInAnAnswerHidesTheSectionInsteadOfRefusingTheRecord() {
        val record = HaSettingsCodec.parseResponse(response(revision = 3).put("notifications", "on"))
        assertNull(record.notifications)
        val tooMany = HaSettingsCodec.parseResponse(withNotifications(targets = (1..11).map { "mobile_app_$it" }))
        assertNull(tooMany.notifications)
    }

    @Test fun aBodyWithABadChoiceIsRefusedWithTheContractsCode() {
        val body = HaSettingsCodec.encodeBody(HaSettingsCodec.parseResponse(withNotifications()))
        body.getJSONObject("notifications").put("targets", JSONArray(listOf("Not a service")))
        val refusal = assertThrows(HaSettingsFormatException::class.java) { HaSettingsCodec.parseBody(body) }
        assertEquals("invalid_notifications", refusal.code)
    }

    @Test fun everyRequestAsksForTheField() {
        val payload = JSONObject(HomeAssistantClient.payload(HomeAssistantCommand("dashboard")))
        val reads = payload.getJSONArray("reads")
        assertTrue((0 until reads.length()).any { reads.getString(it) == "notifications" })
    }

    @Test fun theStoreTakesTheSameRevisionAgainWhenOnlyThePhonesOrTheFieldsPresenceChanged() {
        val store = ConfirmedSettingsStore(FakeKeyValueStore()) { }
        val without = HaSettingsCodec.parseResponse(response(revision = 3))
        store.record("p", without)
        val with = HaSettingsCodec.parseResponse(withNotifications())
        assertTrue(store.record("p", with) is ConfirmedSettingsStore.Merge.Stored)
        assertEquals(with, store.confirmed("p"))
        // A copy that does not state the field leaves the stored choice.
        assertTrue(store.record("p", without) is ConfirmedSettingsStore.Merge.Unchanged)
        assertEquals(with, store.confirmed("p"))
        // A new phone at the same revision is taken.
        val morePhones = HaSettingsCodec.parseResponse(withNotifications(available = JSONArray()))
        assertTrue(store.record("p", morePhones) is ConfirmedSettingsStore.Merge.Stored)
        // A different choice at the same revision is still a disagreement.
        val other = HaSettingsCodec.parseResponse(withNotifications(targets = emptyList()))
        assertTrue(store.record("p", other) is ConfirmedSettingsStore.Merge.EqualRevisionMismatch)
    }
}
