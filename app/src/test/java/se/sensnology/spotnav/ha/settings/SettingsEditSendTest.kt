package se.sensnology.spotnav.ha.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.authority.AuthorityController
import se.sensnology.spotnav.ha.authority.HaPresentation
import se.sensnology.spotnav.ha.authority.WriteOutcome
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.SettingsFixtures.response

/**
 * One value of Settings written through the settings record (the cars at a charger, how the plugged-in
 * car is found, who is notified): one Save writes it, also when Home Assistant moved the revision since
 * the screen read the record -- the same one value is then sent once more, on the record it answered with.
 */
class SettingsEditSendTest {
    private val profileId = "local-a"
    private val cache = ConfirmedSettingsStore(FakeKeyValueStore()) { }
    private val record = HaSettingsCodec.parseResponse(
        response(revision = 5).put("identify_mode", "automatic").put("vehicle_ids", JSONObject.NULL)
    )

    private fun controller(): AuthorityController = AuthorityController(
        profileId = profileId,
        screenGeneration = 1,
        coordinator = null,
        cache = cache,
        catalogue = { listOf(RelayFixtures.se4) },
        presentation = { HaPresentation.QUARTER_HOUR }
    ).also {
        cache.recordOutcome(profileId, SettingsUpdate.Outcome.Updated(record))
        it.seedFromConfirmedRecord()
    }

    private class Transport(var answers: List<(Int, HaPlanningSettings) -> SettingsUpdate.Outcome>) {
        val calls = mutableListOf<Pair<Int, HaPlanningSettings>>()
        fun send(revision: Int, replacement: HaPlanningSettings, done: (SettingsUpdate.Outcome) -> Unit) {
            calls += revision to replacement
            val answer = answers.first()
            answers = answers.drop(1).ifEmpty { listOf(answer) }
            done(answer(revision, replacement))
        }
    }

    @Test fun oneSaveOfTheModeWritesItEvenWhenTheRevisionMovedMeanwhile() {
        val authority = controller()
        val server = record.copy(revision = 7, amps = 8)
        val transport = Transport(listOf(
            { _, _ -> SettingsUpdate.Outcome.Conflict(server) },
            { revision, replacement -> SettingsUpdate.Outcome.Updated(replacement.copy(revision = revision + 1)) }
        ))
        var result: SettingsEditSend.Result? = null
        SettingsEditSend.send(authority, profileId, HaSettingsEdit.Identification(IdentifyMode.ASK, null), transport::send) { result = it }

        assertEquals(2, transport.calls.size)
        assertEquals(7, transport.calls[1].first)
        assertEquals(IdentifyMode.ASK, transport.calls[1].second.identification!!.mode)
        assertEquals("what changed elsewhere is kept", 8, transport.calls[1].second.amps)
        val sent = result as SettingsEditSend.Result.Sent
        assertTrue(sent.answer is SettingsUpdate.Outcome.Updated)
        assertEquals(IdentifyMode.ASK, (sent.outcome as WriteOutcome.Applied).authority.remoteSettings!!.identification!!.mode)
        assertEquals(IdentifyMode.ASK, cache.confirmed(profileId)!!.identification!!.mode)
    }

    @Test fun aSecondConflictInARowIsReportedAndNothingMoreIsSent() {
        val authority = controller()
        val transport = Transport(listOf({ revision, _ -> SettingsUpdate.Outcome.Conflict(record.copy(revision = revision + 2)) }))
        var result: SettingsEditSend.Result? = null
        SettingsEditSend.send(authority, profileId, HaSettingsEdit.Identification(IdentifyMode.OFF, null), transport::send) { result = it }

        assertEquals(2, transport.calls.size)
        assertTrue((result as SettingsEditSend.Result.Sent).answer is SettingsUpdate.Outcome.Conflict)
    }

    @Test fun anAcceptedSaveIsSentOnce() {
        val authority = controller()
        val transport = Transport(listOf({ revision, replacement -> SettingsUpdate.Outcome.Updated(replacement.copy(revision = revision + 1)) }))
        var result: SettingsEditSend.Result? = null
        SettingsEditSend.send(authority, profileId, HaSettingsEdit.Identification(IdentifyMode.ASK, null), transport::send) { result = it }
        assertEquals(1, transport.calls.size)
        assertTrue((result as SettingsEditSend.Result.Sent).answer is SettingsUpdate.Outcome.Updated)
    }
}
