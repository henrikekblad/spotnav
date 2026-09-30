package se.sensnology.spotnav.ha.pairing

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.HomeAssistantSettings
import java.security.SecureRandom

/** The pairing handshake, read as untrusted input end to end. */
class PairingProtocolTest {
    private val allowed: (String) -> Boolean = { value -> HomeAssistantSettings.isAllowedBaseUrl(value) }

    private val approved = """
        {"status":"approved","url":"http://192.168.1.50:8123",
         "chargers":[{"id":"entry_a","name":"Garage","webhook":"wh-a"}]}
    """.trimIndent()

    // the code

    @Test fun aFreshCodeIsSixDigitsEveryTime() {
        val random = SecureRandom()
        repeat(200) {
            val code = PairingProtocol.newCode(random)
            assertEquals(6, code.length)
            assertTrue(code, code.length == 6 && code.all { it in '0'..'9' })
        }
    }

    // the two request bodies

    @Test fun theRequestBodyCarriesTheVersionActionDeviceAndCode() {
        val body = JSONObject(PairingProtocol.requestPayload("Pixel 8", "123456"))

        assertEquals(1, body.getInt("version"))
        assertEquals("request", body.getString("action"))
        assertEquals("Pixel 8", body.getString("device"))
        assertEquals("123456", body.getString("code"))
    }

    @Test fun thePollBodyCarriesOnlyTheRequestId() {
        val body = JSONObject(PairingProtocol.pollPayload("opaque-id"))

        assertEquals(1, body.getInt("version"))
        assertEquals("poll", body.getString("action"))
        assertEquals("opaque-id", body.getString("request_id"))
    }

    // the request answer

    @Test fun aSuccessfulRequestGivesItsId() {
        assertEquals("abc123", PairingProtocol.requestId("""{"ok":true,"request_id":"abc123"}"""))
    }

    @Test fun aRefusalOrAnUnreadableAnswerGivesNoId() {
        for (payload in listOf(
            """{"ok":false}""",
            """{"ok":"true","request_id":"abc"}""",
            """{"request_id":"abc"}""",
            """{"ok":true,"request_id":{"nested":1}}""",
            """{"ok":true,"request_id":"   "}""",
            """{"ok":true}""",
            "[]",
            "",
            "not json"
        )) {
            assertNull(payload, PairingProtocol.requestId(payload))
        }
    }

    // the poll answers

    @Test fun theThreeTerminalStatesAreReadAsThemselves() {
        assertEquals(PairingPoll.Pending, PairingProtocol.poll("""{"status":"pending"}""", allowed))
        assertEquals(PairingPoll.Denied, PairingProtocol.poll("""{"status":"denied"}""", allowed))
        assertEquals(PairingPoll.Expired, PairingProtocol.poll("""{"status":"expired"}""", allowed))
    }

    @Test fun anApprovalCarriesTheAddressAndEveryCharger() {
        val poll = PairingProtocol.poll(approved, allowed)

        val approval = poll as PairingPoll.Approved
        assertEquals("http://192.168.1.50:8123", approval.baseUrl)
        assertEquals(listOf(PairedCharger("entry_a", "Garage", "wh-a")), approval.chargers)
    }

    @Test fun anApprovalWithNoChargersIsStillAnApproval() {
        // An instance with no chargers is a state, not a failure.
        val poll = PairingProtocol.poll("""{"status":"approved","url":"http://192.168.1.50:8123"}""", allowed)

        assertEquals(emptyList<PairedCharger>(), (poll as PairingPoll.Approved).chargers)
    }

    @Test fun aChargerNeedsAnIdAndAWebhookButNotAName() {
        val payload = """
            {"status":"approved","url":"http://192.168.1.50:8123","chargers":[
              {"name":"No id, no webhook"},
              {"id":"entry_a","name":"Garage"},
              {"id":"entry_b","webhook":"wh-b"},
              {"id":"entry_c","name":{"nested":"object"},"webhook":"wh-c"}
            ]}
        """.trimIndent()

        val chargers = (PairingProtocol.poll(payload, allowed) as PairingPoll.Approved).chargers

        assertEquals(listOf("entry_b", "entry_c"), chargers.map { it.id })
        // A name that is not a string is a missing name: the id stands in.
        assertEquals(listOf("entry_b", "entry_c"), chargers.map { it.name })
    }

    @Test fun anApprovalAddressingSomewhereWeMayNotTalkToIsNotAnApproval() {
        // The address decides where the webhook ids in this answer will be sent.
        val payload = """{"status":"approved","url":"http://example.com:8123","chargers":[]}"""

        assertEquals(PairingPoll.Unusable, PairingProtocol.poll(payload, allowed))
        assertEquals(
            PairingPoll.Unusable,
            PairingProtocol.poll("""{"status":"approved","chargers":[]}""", allowed)
        )
        assertEquals(
            PairingPoll.Unusable,
            PairingProtocol.poll("""{"status":"approved","url":{"nested":1}}""", allowed)
        )
    }

    @Test fun anAnswerThisAppCannotReadIsUnusableRatherThanPending() {
        for (payload in listOf(
            """{"status":"something-new"}""",
            """{"status":{"nested":1}}""",
            """{}""",
            """[]""",
            """""",
            "not json",
            "<html>502</html>"
        )) {
            assertEquals(payload, PairingPoll.Unusable, PairingProtocol.poll(payload, allowed))
        }
    }

    // the loop

    @Test fun pendingKeepsAskingAtTheFixedInterval() {
        assertEquals(PairingStep.KeepPolling(2_000L), PairingProtocol.next(0L, PairingPoll.Pending))
        assertEquals(PairingStep.KeepPolling(2_000L), PairingProtocol.next(299_999L, PairingPoll.Pending))
    }

    @Test fun fiveMinutesOfPendingIsWhereAskingStops() {
        assertEquals(PairingStep.Stop(PairingStop.TimedOut), PairingProtocol.next(300_000L, PairingPoll.Pending))
        assertTrue(PairingProtocol.timedOut(300_000L))
        assertTrue(!PairingProtocol.timedOut(299_999L))
    }

    @Test fun theTimeoutIsTheOnlyThingThatOutlastsAPendingAnswer() {
        // A terminal answer stops immediately, however little time has passed.
        assertEquals(
            PairingStep.Stop(PairingStop.Denied),
            PairingProtocol.next(0L, PairingPoll.Denied)
        )
        assertEquals(
            PairingStep.Stop(PairingStop.Expired),
            PairingProtocol.next(1L, PairingPoll.Expired)
        )
        assertEquals(
            PairingStep.Stop(PairingStop.Unusable),
            PairingProtocol.next(1L, PairingPoll.Unusable)
        )
    }

    @Test fun anApprovalStopsTheLoopWithWhatItCarries() {
        val step = PairingProtocol.next(4_000L, PairingProtocol.poll(approved, allowed))

        val stop = (step as PairingStep.Stop).reason as PairingStop.Approved
        assertEquals("http://192.168.1.50:8123", stop.baseUrl)
        assertEquals(listOf(PairedCharger("entry_a", "Garage", "wh-a")), stop.chargers)
    }

    @Test fun noAnswerAtAllIsUnreachableWhileGarbageIsStillUnusable() {
        assertEquals(PairingPoll.Unreachable, PairingProtocol.poll(null, allowed))
        assertEquals(PairingPoll.Unusable, PairingProtocol.poll("<html>oops</html>", allowed))
        assertEquals(
            PairingStep.Stop(PairingStop.Unusable),
            PairingProtocol.next(1L, PairingProtocol.poll("not json", allowed), 0L)
        )
    }

    @Test fun lostPollsKeepAskingAndAnApprovalAfterThemStillLands() {
        val lost = PairingProtocol.poll(null, allowed)
        assertEquals(PairingStep.KeepPolling(2_000L), PairingProtocol.next(2_000L, lost, 0L))
        assertEquals(PairingStep.KeepPolling(2_000L), PairingProtocol.next(30_000L, lost, 28_000L))
        assertEquals(PairingStep.KeepPolling(2_000L), PairingProtocol.next(59_999L, lost, 59_999L))

        val step = PairingProtocol.next(62_000L, PairingProtocol.poll(approved, allowed), 0L)
        assertTrue((step as PairingStep.Stop).reason is PairingStop.Approved)
    }

    @Test fun sixtySecondsOfLostPollsInARowStopsAsUnreachable() {
        assertEquals(
            PairingStep.Stop(PairingStop.Unreachable),
            PairingProtocol.next(70_000L, PairingPoll.Unreachable, 60_000L)
        )
    }

    @Test fun lostPollsDoNotOutlastTheFiveMinuteTimeout() {
        assertEquals(
            PairingStep.Stop(PairingStop.TimedOut),
            PairingProtocol.next(300_000L, PairingPoll.Unreachable, 10_000L)
        )
    }
}
