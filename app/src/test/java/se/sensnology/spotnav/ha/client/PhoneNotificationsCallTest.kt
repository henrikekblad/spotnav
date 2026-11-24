package se.sensnology.spotnav.ha.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.HaFixtures

class PhoneNotificationsCallTest {
    private val appId = "A".repeat(43)

    private fun success(): JSONObject = HaFixtures.json("webhook/phone_notifications_success.json")

    @Test
    fun aReadCarriesTheIdAndNothingElse() {
        val body = PhoneNotificationsCall.payload(appId)
        assertEquals(setOf("version", "action", "api_version", "app_id"), body.keys().asSequence().toSet())
        assertEquals("phone_notifications", body.getString("action"))
        assertEquals(1, body.getInt("api_version"))
        assertEquals(appId, body.getString("app_id"))
    }

    @Test
    fun theReferenceIsSentOrClearedOnlyWhenAsked() {
        val set = PhoneNotificationsCall.payload(appId, ref = PhoneNotificationsCall.Ref.Set("ref_1234567890abcdef"))
        assertEquals("ref_1234567890abcdef", set.getString("push_ref"))
        val cleared = PhoneNotificationsCall.payload(appId, ref = PhoneNotificationsCall.Ref.Set(null))
        assertTrue(cleared.has("push_ref"))
        assertTrue(JSONObject(cleared.toString()).isNull("push_ref"))
        assertFalse(PhoneNotificationsCall.payload(appId, ref = PhoneNotificationsCall.Ref.Keep).has("push_ref"))
    }

    @Test
    fun aWriteSendsOnlyWhatChanges() {
        val body = PhoneNotificationsCall.payload(
            appId,
            name = "Pixel 8",
            shows = listOf("plan_stopped", "pool_season"),
            change = PhoneNotificationsCall.Change(on = false, chargers = PhoneNotificationsCall.Chargers.All)
        )
        assertEquals("Pixel 8", body.getString("name"))
        assertEquals(2, body.getJSONArray("shows").length())
        val set = body.getJSONObject("set")
        assertEquals(setOf("on", "chargers"), set.keys().asSequence().toSet())
        assertEquals(false, set.getBoolean("on"))
        assertTrue(JSONObject(body.toString()).getJSONObject("set").isNull("chargers"))

        val only = PhoneNotificationsCall.payload(
            appId,
            change = PhoneNotificationsCall.Change(events = listOf("plan_stopped"), chargers = PhoneNotificationsCall.Chargers.Only(emptyList()))
        ).getJSONObject("set")
        assertEquals(0, only.getJSONArray("chargers").length())
        assertEquals(1, only.getJSONArray("events").length())
        assertFalse(PhoneNotificationsCall.payload(appId, change = PhoneNotificationsCall.Change()).has("set"))
    }

    @Test
    fun homeAssistantsAnswerIsRead() {
        val outcome = PhoneNotificationsCall.answer(200, success().toString())
        val value = (outcome as PhoneNotificationsCall.Outcome.Read).value
        assertEquals("ph_00000000a001", value.phoneId)
        assertEquals("Pixel 8", value.name)
        assertTrue(value.on)
        assertEquals(listOf("plan_stopped", "plan_at_risk", "charge_complete", "vehicle_identify", "pool_season"), value.events)
        assertNull(value.chargers)
        assertEquals("Pixel 8", value.companionName)
        assertFalse(value.instant)
        assertEquals(listOf(PhoneNotifications.Charger("entry_a", "Garage")), value.installationChargers)
        assertEquals(PhoneNotifications.Loads(chargers = 1, pool = false, spa = false, sauna = false), value.loads)
        assertEquals(9, value.allEvents.size)
        assertTrue(value.follows("entry_a"))
        assertTrue(value.wants("plan_stopped"))
        assertFalse(value.wants("plugged_in"))
    }

    @Test
    fun theKeptCopyReadsBackTheSame() {
        val value = (PhoneNotificationsCall.answer(200, success().toString()) as PhoneNotificationsCall.Outcome.Read).value
        assertEquals(value, PhoneNotificationsCall.decode(PhoneNotificationsCall.encode(value)))
        val narrowed = value.copy(chargers = listOf("entry_b"), companionName = null, companionFound = false, on = false)
        assertEquals(narrowed, PhoneNotificationsCall.decode(PhoneNotificationsCall.encode(narrowed)))
        assertFalse(narrowed.follows("entry_a"))
        assertFalse(narrowed.wants("plan_stopped"))
    }

    @Test
    fun theRefusalIsRead() {
        assertEquals(PhoneNotificationsCall.Outcome.Refused, PhoneNotificationsCall.answer(400, HaFixtures.json("webhook/phone_notifications_invalid.json").toString()))
    }

    @Test
    fun anOlderHomeAssistantIsToldApart() {
        assertEquals(PhoneNotificationsCall.Outcome.NotSupported, PhoneNotificationsCall.answer(400, """{"ok":false,"error":"Unsupported action"}"""))
        assertEquals(
            PhoneNotificationsCall.Outcome.NotSupported,
            PhoneNotificationsCall.answer(400, """{"ok":false,"error":"spotnav_unsupported_api_version","action":"phone_notifications"}""")
        )
        // No answer, or another refusal, says nothing about the version: asked again later.
        assertEquals(PhoneNotificationsCall.Outcome.Failed(null), PhoneNotificationsCall.answer(null, null))
        assertEquals(PhoneNotificationsCall.Outcome.Failed(null), PhoneNotificationsCall.answer(200, ""))
        assertEquals(
            PhoneNotificationsCall.Outcome.Failed("spotnav_phones_unavailable"),
            PhoneNotificationsCall.answer(503, """{"ok":false,"error":"spotnav_phones_unavailable","action":"phone_notifications"}""")
        )
    }

    @Test
    fun anUnknownKeyAnywhereIsRefused() {
        fun failed(edit: (JSONObject) -> Unit) {
            val json = success().also(edit)
            assertEquals(PhoneNotificationsCall.Outcome.Failed(null), PhoneNotificationsCall.answer(200, json.toString()))
        }
        failed { it.put("extra", 1) }
        failed { it.getJSONObject("phone").put("extra", 1) }
        failed { it.getJSONObject("phone").getJSONObject("companion").put("service", "mobile_app_x") }
        failed { it.getJSONArray("chargers").getJSONObject(0).put("extra", 1) }
        failed { it.getJSONObject("loads").put("heat", true) }
    }

    @Test
    fun aMissingOrMistypedKeyIsRefused() {
        fun failed(edit: (JSONObject) -> Unit) {
            val json = success().also(edit)
            assertEquals(PhoneNotificationsCall.Outcome.Failed(null), PhoneNotificationsCall.answer(200, json.toString()))
        }
        failed { it.remove("loads") }
        failed { it.getJSONObject("phone").remove("instant") }
        failed { it.getJSONObject("phone").put("on", "true") }
        failed { it.getJSONObject("phone").put("chargers", "entry_a") }
        failed { it.getJSONObject("phone").put("events", org.json.JSONArray().put(1)) }
        failed { it.getJSONObject("loads").put("chargers", 1.5) }
        failed { it.put("api_version", 2) }
        failed { it.put("action", "push_register") }
    }
}
