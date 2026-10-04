package se.sensnology.spotnav.push

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PushRelayTest {
    @Test
    fun theRegistrationBodyIsTheContractsExactly() {
        val body = JSONObject(PushRelay.payload("tok:en"))
        assertEquals(setOf("v", "fcm_token"), body.keys().asSequence().toSet())
        assertEquals(1, body.get("v"))
        assertEquals("tok:en", body.get("fcm_token"))
        assertEquals("https://spotnav.sensnology.se/v1/push/register", PushRelay.REGISTER_URL)
    }

    @Test
    fun answersAreRead() {
        assertEquals(PushRelay.Outcome.Registered("abc_-1"), PushRelay.answer(200, """{"v":1,"push_ref":"abc_-1"}"""))
        assertEquals(PushRelay.Outcome.Failed, PushRelay.answer(200, """{"v":2,"push_ref":"abc"}"""))
        assertEquals(PushRelay.Outcome.Failed, PushRelay.answer(200, """{"v":1,"push_ref":""}"""))
        assertEquals(PushRelay.Outcome.Failed, PushRelay.answer(200, "not json"))
        assertEquals(PushRelay.Outcome.Failed, PushRelay.answer(400, """{"error":"invalid"}"""))
        assertEquals(PushRelay.Outcome.RateLimited, PushRelay.answer(429, null))
        assertEquals(PushRelay.Outcome.Disabled, PushRelay.answer(503, """{"error":"push_disabled"}"""))
        assertEquals(PushRelay.Outcome.Failed, PushRelay.answer(503, "<html>maintenance</html>"))
        assertEquals(PushRelay.Outcome.Failed, PushRelay.answer(null, null))
    }
}
