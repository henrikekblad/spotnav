package se.sensnology.spotnav.ha.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.HaFixtures

class PushRegisterTest {
    @Test
    fun theBodyCarriesTheReferenceAndTheEvents() {
        val body = PushRegister.payload("ref_-1", listOf("plan_stopped", "charge_complete"))
        assertEquals(setOf("version", "action", "api_version", "push_ref", "events"), body.keys().asSequence().toSet())
        assertEquals(1, body.get("version"))
        assertEquals("push_register", body.get("action"))
        assertEquals("ref_-1", body.get("push_ref"))
        assertEquals(listOf("plan_stopped", "charge_complete"), body.getJSONArray("events").let { list -> (0 until list.length()).map { list.getString(it) } })
    }

    @Test
    fun aNullReferenceClears() {
        val body = PushRegister.payload(null, emptyList())
        assertTrue(body.isNull("push_ref"))
        assertTrue(body.has("push_ref"))
        assertEquals(0, body.getJSONArray("events").length())
        assertTrue(JSONObject(body.toString()).isNull("push_ref"))
    }

    @Test
    fun onlyAnOkEnvelopeIsAccepted() {
        assertTrue(PushRegister.accepted(200, """{"ok":true,"action":"push_register"}"""))
        assertFalse(PushRegister.accepted(200, """{"ok":false,"error":"x"}"""))
        assertFalse(PushRegister.accepted(400, """{"ok":false,"error":"Unsupported action"}"""))
        assertFalse(PushRegister.accepted(200, "not json"))
        assertFalse(PushRegister.accepted(null, null))
    }

    @Test
    fun homeAssistantsOwnAnswersAreRead() {
        assertTrue(PushRegister.accepted(200, HaFixtures.json("webhook/push_register_success.json").toString()))
        assertFalse(PushRegister.accepted(400, HaFixtures.json("webhook/push_register_invalid.json").toString()))
    }
}
