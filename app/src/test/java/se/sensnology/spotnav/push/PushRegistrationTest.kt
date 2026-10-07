package se.sensnology.spotnav.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.FakeKeyValueStore

class PushRegistrationTest {
    private class Fakes {
        val store = PushStore(FakeKeyValueStore())
        var token: String? = "token-1"
        var deleted = 0
        val relayCalls = mutableListOf<String>()
        var relayAnswer: (String) -> PushRelay.Outcome = { PushRelay.Outcome.Registered("ref-for-$it") }
        val haCalls = mutableListOf<Triple<String, String?, List<String>>>()
        val unreachable = mutableSetOf<String>()
        var local = PushRegistration.Local(enabled = true, profiles = listOf("a", "b"), events = listOf("plan_stopped", "charge_complete"))

        val registration = PushRegistration(
            store = store,
            tokens = object : PushRegistration.Tokens {
                override fun token(): String? = token
                override fun delete() { deleted++ }
            },
            relay = { relayCalls += it; relayAnswer(it) },
            homeAssistant = { id, ref, events -> haCalls += Triple(id, ref, events); id !in unreachable },
            local = { local }
        )
    }

    @Test
    fun turningOnRegistersTheTokenAndTellsEveryPairedHomeAssistant() {
        val f = Fakes()
        assertEquals(PushRegistration.Result.ON, f.registration.enable())
        assertEquals(listOf("token-1"), f.relayCalls)
        assertTrue(f.store.enabled)
        assertEquals("ref-for-token-1", f.store.pushRef)
        val events = listOf("plan_stopped", "charge_complete")
        assertEquals(
            listOf(Triple("a", "ref-for-token-1", events), Triple("b", "ref-for-token-1", events)),
            f.haCalls
        )
        // Nothing changed: nothing is sent again.
        f.registration.sync()
        assertEquals(2, f.haCalls.size)
    }

    @Test
    fun aRelayWithoutPushLeavesItOffAndTellsNobody() {
        val f = Fakes()
        f.relayAnswer = { PushRelay.Outcome.Disabled }
        assertEquals(PushRegistration.Result.SERVER_OFF, f.registration.enable())
        assertFalse(f.store.enabled)
        assertNull(f.store.pushRef)
        assertTrue(f.haCalls.isEmpty())
        assertEquals(1, f.deleted)
    }

    @Test
    fun eachRelayRefusalHasItsOwnResult() {
        val f = Fakes()
        f.relayAnswer = { PushRelay.Outcome.RateLimited }
        assertEquals(PushRegistration.Result.RATE_LIMITED, f.registration.enable())
        f.relayAnswer = { PushRelay.Outcome.Failed }
        assertEquals(PushRegistration.Result.FAILED, f.registration.enable())
        f.token = null
        assertEquals(PushRegistration.Result.NO_TOKEN, f.registration.enable())
        assertFalse(f.store.enabled)
    }

    @Test
    fun aNewTokenIsRegisteredAgainAndHandedToEveryHomeAssistant() {
        val f = Fakes()
        f.registration.enable()
        f.haCalls.clear()
        f.registration.onNewToken("token-2")
        assertEquals(listOf("token-1", "token-2"), f.relayCalls)
        assertEquals(listOf("a", "b"), f.haCalls.map { it.first })
        assertTrue(f.haCalls.all { it.second == "ref-for-token-2" })
    }

    @Test
    fun aNewTokenWhileOffIsIgnored() {
        val f = Fakes()
        f.registration.onNewToken("token-2")
        assertTrue(f.relayCalls.isEmpty())
        assertTrue(f.haCalls.isEmpty())
    }

    @Test
    fun turningOffClearsEveryHomeAssistantAndDeletesTheToken() {
        val f = Fakes()
        f.registration.enable()
        f.haCalls.clear()
        f.registration.disable()
        assertEquals(listOf(Triple("a", null, emptyList<String>()), Triple("b", null, emptyList())), f.haCalls)
        assertEquals(1, f.deleted)
        assertFalse(f.store.enabled)
        assertNull(f.store.pushRef)
        f.registration.sync()
        assertEquals(2, f.haCalls.size)
    }

    @Test
    fun aHomeAssistantThatWasNotReachedIsToldLater() {
        val f = Fakes()
        f.unreachable += "b"
        f.registration.enable()
        f.haCalls.clear()
        f.unreachable.clear()
        f.registration.sync()
        assertEquals(listOf(Triple("b", "ref-for-token-1", listOf("plan_stopped", "charge_complete"))), f.haCalls)
    }

    @Test
    fun changedEventsAreSentAgain() {
        val f = Fakes()
        f.registration.enable()
        f.haCalls.clear()
        f.local = f.local.copy(events = listOf("plugged_in"))
        f.registration.sync()
        assertEquals(listOf(Triple("a", "ref-for-token-1", listOf("plugged_in")), Triple("b", "ref-for-token-1", listOf("plugged_in"))), f.haCalls)
    }

    @Test
    fun thisPhonesOwnNotificationsOffClearsTheReferenceUntilTheyAreBack() {
        val f = Fakes()
        f.registration.enable()
        f.haCalls.clear()
        f.local = f.local.copy(enabled = false)
        f.registration.sync()
        assertTrue(f.haCalls.all { it.second == null })
        assertEquals(2, f.haCalls.size)
        f.haCalls.clear()
        f.local = f.local.copy(enabled = true)
        f.registration.sync()
        assertTrue(f.haCalls.all { it.second == "ref-for-token-1" })
        assertEquals(2, f.haCalls.size)
    }

    @Test
    fun aNewPairingIsToldAndAnUnpairedOneForgotten() {
        val f = Fakes()
        f.registration.enable()
        f.haCalls.clear()
        f.local = f.local.copy(profiles = listOf("b", "c"))
        f.registration.sync()
        assertEquals(listOf("c"), f.haCalls.map { it.first })
        assertNull(f.store.sent("a"))
        assertEquals(setOf("b", "c"), f.store.known)
    }

    @Test
    fun theQuestionWhichCarIsPluggedInGoesOnlyToAHomeAssistantThatIdentifies() {
        val f = Fakes()
        f.local = PushRegistration.Local(
            enabled = true, profiles = listOf("a", "b"), events = listOf("plan_stopped", "vehicle_identify"), identifying = setOf("b")
        )
        assertEquals(PushRegistration.Result.ON, f.registration.enable())
        // A Home Assistant without identification refuses an event it does not know.
        assertEquals(
            listOf(Triple("a", "ref-for-token-1", listOf("plan_stopped")), Triple("b", "ref-for-token-1", listOf("plan_stopped", "vehicle_identify"))),
            f.haCalls
        )
        // Once "a" is found to identify, it is told again.
        f.local = f.local.copy(identifying = setOf("a", "b"))
        f.registration.sync()
        assertEquals(Triple("a", "ref-for-token-1", listOf("plan_stopped", "vehicle_identify")), f.haCalls.last())
        assertEquals(3, f.haCalls.size)
    }
}
