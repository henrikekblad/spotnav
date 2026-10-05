package se.sensnology.spotnav.ha.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.HomeAssistantCommand
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.HaFixtures
import se.sensnology.spotnav.testing.SettingsFixtures.response

/**
 * The settings record's `fill_to_limit` ("Fill", the kWh slider's last step): asked for, read, kept
 * through every other edit, set and cleared only by an amount edit, and absent from an older Home
 * Assistant (the card's `decodeSettingsRecord`/`encodeBody`/`replacementFor`).
 */
class FillToLimitCodecTest {
    private fun withFill(fill: Any = true, revision: Int = 3): JSONObject =
        response(revision = revision).put("fill_to_limit", fill)

    private fun ready(record: HaPlanningSettings, edit: HaSettingsEdit): HaPlanningSettings =
        (HaSettingsEditor.replacement(record, edit) as HaSettingsEditResult.Ready).settings

    @Test fun theVendoredAnswersStateItAsOff() {
        val record = HaSettingsCodec.parseResponse(HaFixtures.json("settings/v1/success.json").getJSONObject("settings"))
        assertEquals(false, record.fillToLimit)
        val dashboard = Dashboard.parse(HaFixtures.json("dashboard/target_soc_estimated.json"))
        assertEquals(false, dashboard.settings!!.fillToLimit)
    }

    @Test fun anAnswerWithItIsReadAndSentBackAsRead() {
        val record = HaSettingsCodec.parseResponse(withFill())
        assertEquals(true, record.fillToLimit)
        assertTrue(HaSettingsCodec.encodeBody(record).getBoolean("fill_to_limit"))
        assertEquals(record, HaSettingsCodec.parseBody(HaSettingsCodec.encodeBody(record)).copy(revision = record.revision))
        // The stored copy keeps it and reads back equal.
        assertEquals(record, HaSettingsCodec.parseStored(HaSettingsCodec.encode(record)))
    }

    @Test fun anAnswerWithoutItStatesNothingAndWritesNothingBack() {
        val record = HaSettingsCodec.parseResponse(response(revision = 3))
        assertNull(record.fillToLimit)
        assertFalse(HaSettingsCodec.encodeBody(record).has("fill_to_limit"))
        assertFalse(HaSettingsCodec.encode(record).has("fill_to_limit"))
    }

    @Test fun aValueThatIsNotABooleanIsRefusedWithTheContractsCode() {
        val refusal = assertThrows(HaSettingsFormatException::class.java) {
            HaSettingsCodec.parseResponse(withFill(fill = "yes"))
        }
        assertEquals("invalid_energy", refusal.code)
    }

    @Test fun anAmountEditSetsItAtTheLastStepAndClearsItElsewhere() {
        val off = HaSettingsCodec.parseResponse(withFill(fill = false))
        val filled = ready(off, HaSettingsEdit.Energy(30.0, fill = true))
        assertEquals(true, filled.fillToLimit)
        assertEquals(30.0, filled.requestedKwh, 0.0)
        val on = HaSettingsCodec.parseResponse(withFill(fill = true))
        val amount = ready(on, HaSettingsEdit.Energy(12.0))
        assertEquals(false, amount.fillToLimit)
        assertEquals(12.0, amount.requestedKwh, 0.0)
    }

    @Test fun everyOtherEditKeepsTheStoredChoice() {
        val on = HaSettingsCodec.parseResponse(withFill(fill = true))
        assertEquals(true, ready(on, HaSettingsEdit.Amps(10)).fillToLimit)
        assertEquals(true, ready(on, HaSettingsEdit.MaxPeriods(2)).fillToLimit)
        assertEquals(true, ready(on, HaSettingsEdit.Strategy(HaSettingsStrategy.HYBRID)).fillToLimit)
        assertEquals(true, ready(on, HaSettingsEdit.Driver(HaSettingsDriver.TARGET_SOC, HaTargetIntent(null, 80.0))).fillToLimit)
    }

    @Test fun anOlderHomeAssistantIsNeverOfferedTheField() {
        val older = HaSettingsCodec.parseResponse(response(revision = 3))
        val edited = ready(older, HaSettingsEdit.Energy(30.0, fill = true))
        assertNull(edited.fillToLimit)
        assertFalse(HaSettingsCodec.encodeBody(edited).has("fill_to_limit"))
    }

    @Test fun everyRequestAsksForTheField() {
        for (command in listOf(HomeAssistantCommand("dashboard"), HomeAssistantCommand("get_settings"))) {
            val reads = JSONObject(HomeAssistantClient.payload(command)).getJSONArray("reads")
            assertTrue((0 until reads.length()).any { reads.getString(it) == "fill_to_limit" })
        }
    }

    @Test fun theStoreTakesTheFieldStatedForTheFirstTimeAtTheSameRevisionAndNeverLosesIt() {
        val store = ConfirmedSettingsStore(FakeKeyValueStore()) { }
        val without = HaSettingsCodec.parseResponse(response(revision = 3))
        store.record("p", without)
        val with = HaSettingsCodec.parseResponse(withFill(fill = true))
        assertTrue(store.record("p", with) is ConfirmedSettingsStore.Merge.Stored)
        assertEquals(with, store.confirmed("p"))
        // A copy that does not state it leaves the stored choice.
        assertTrue(store.record("p", without) is ConfirmedSettingsStore.Merge.Unchanged)
        assertEquals(with, store.confirmed("p"))
        // Another choice at the same revision is still a disagreement.
        val other = HaSettingsCodec.parseResponse(withFill(fill = false))
        assertTrue(store.record("p", other) is ConfirmedSettingsStore.Merge.EqualRevisionMismatch)
    }
}
