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
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.HaFixtures
import se.sensnology.spotnav.testing.SettingsFixtures.response

/**
 * The settings record's `vehicle_ids` and `identify_mode` (vehicle identification): withheld from an
 * app that does not ask, so this app asks; read when stated, sent back as read, changed only by the
 * identification edit, and absent from an older Home Assistant.
 */
class IdentificationSettingsCodecTest {
    private fun withIdentification(mode: Any? = "automatic", ids: Any? = JSONObject.NULL, revision: Int = 3): JSONObject =
        response(revision = revision).put("identify_mode", mode).put("vehicle_ids", ids)

    private fun ready(record: HaPlanningSettings, edit: HaSettingsEdit): HaPlanningSettings =
        (HaSettingsEditor.replacement(record, edit) as HaSettingsEditResult.Ready).settings

    @Test fun everyRequestAsksForBothFields() {
        for (command in listOf(HomeAssistantCommand("dashboard"), HomeAssistantCommand("settings"), HomeAssistantCommand("start"))) {
            val reads = JSONObject(HomeAssistantClient.payload(command)).getJSONArray("reads")
            val names = List(reads.length()) { reads.getString(it) }
            assertTrue(names.containsAll(listOf("vehicle_ids", "identify_mode")))
        }
    }

    @Test fun theVendoredAnswersStateEveryCarAndAutomatic() {
        val record = HaSettingsCodec.parseResponse(HaFixtures.json("settings/v1/success.json").getJSONObject("settings"))
        assertEquals(HaIdentificationSettings(IdentifyMode.AUTOMATIC, null), record.identification)
        val dashboard = Dashboard.parse(HaFixtures.json("dashboard/target_soc_two_vehicles.json"))
        assertEquals(HaIdentificationSettings(IdentifyMode.AUTOMATIC, null), dashboard.settings!!.identification)
    }

    @Test fun eachModeAndAListAreReadAndSentBackAsRead() {
        for ((wire, mode) in listOf("automatic" to IdentifyMode.AUTOMATIC, "ask" to IdentifyMode.ASK, "off" to IdentifyMode.OFF)) {
            val record = HaSettingsCodec.parseResponse(withIdentification(mode = wire, ids = JSONArray().put("vehicle_ev6")))
            assertEquals(HaIdentificationSettings(mode, listOf("vehicle_ev6")), record.identification)
            val body = HaSettingsCodec.encodeBody(record)
            assertEquals(wire, body.getString("identify_mode"))
            assertEquals("vehicle_ev6", body.getJSONArray("vehicle_ids").getString(0))
            assertEquals(record, HaSettingsCodec.parseStored(HaSettingsCodec.encode(record)))
        }
        val every = HaSettingsCodec.encodeBody(HaSettingsCodec.parseResponse(withIdentification()))
        assertTrue(every.has("vehicle_ids") && every.isNull("vehicle_ids"))
    }

    @Test fun anAnswerWithoutThemStatesNothingAndWritesNothingBack() {
        val record = HaSettingsCodec.parseResponse(response(revision = 3))
        assertNull(record.identification)
        assertFalse(HaSettingsCodec.encodeBody(record).has("identify_mode"))
        assertFalse(HaSettingsCodec.encodeBody(record).has("vehicle_ids"))
        assertFalse(HaSettingsCodec.encode(record).has("identify_mode"))
    }

    @Test fun anAnswerThisAppCannotReadIsNotStatedAndNeverRefusesTheRecord() {
        for (record in listOf(
            withIdentification(mode = "always_ask"),
            withIdentification(ids = JSONArray()),
            withIdentification(ids = JSONArray().put("a").put("a")),
            withIdentification(ids = "vehicle_ev6"),
            response(revision = 3).put("identify_mode", "automatic")
        )) {
            assertNull(HaSettingsCodec.parseResponse(record).identification)
        }
    }

    @Test fun aBodyWithABadListOrModeIsRefusedWithTheContractsCode() {
        val body = HaSettingsCodec.encodeBody(HaSettingsCodec.parseResponse(withIdentification()))
        for (bad in listOf(
            JSONObject(body.toString()).put("identify_mode", "always_ask"),
            JSONObject(body.toString()).put("vehicle_ids", JSONArray()),
            JSONObject(body.toString()).put("vehicle_ids", JSONArray().put("a").put("a")),
            JSONObject(body.toString()).put("vehicle_ids", JSONArray().put(""))
        )) {
            val refusal = assertThrows(HaSettingsFormatException::class.java) { HaSettingsCodec.parseBody(bad) }
            assertEquals("invalid_vehicles", refusal.code)
        }
    }

    @Test fun theIdentificationEditWritesBothAndEveryOtherEditKeepsThem() {
        val record = HaSettingsCodec.parseResponse(withIdentification(ids = JSONArray().put("vehicle_ev6").put("vehicle_niro")))
        val edited = ready(record, HaSettingsEdit.Identification(IdentifyMode.ASK, listOf("vehicle_niro")))
        assertEquals(HaIdentificationSettings(IdentifyMode.ASK, listOf("vehicle_niro")), edited.identification)
        val everyCar = ready(record, HaSettingsEdit.Identification(IdentifyMode.OFF, null))
        assertEquals(HaIdentificationSettings(IdentifyMode.OFF, null), everyCar.identification)
        assertEquals(record.identification, ready(record, HaSettingsEdit.Amps(10)).identification)
        assertEquals(record.identification, ready(record, HaSettingsEdit.MaxPeriods(2)).identification)
    }

    @Test fun theEditIsRefusedForARecordWithoutTheFieldsOrWithNoCar() {
        val older = HaSettingsCodec.parseResponse(response(revision = 3))
        assertEquals(
            HaSettingsEditResult.Refused("invalid_vehicles"),
            HaSettingsEditor.replacement(older, HaSettingsEdit.Identification(IdentifyMode.ASK, null))
        )
        val record = HaSettingsCodec.parseResponse(withIdentification())
        assertEquals(
            HaSettingsEditResult.Refused("invalid_vehicles"),
            HaSettingsEditor.replacement(record, HaSettingsEdit.Identification(IdentifyMode.ASK, emptyList()))
        )
    }

    @Test fun theStoredCopyTakesThemWhenTheyAreFirstStatedAndNeverLosesThem() {
        val store = ConfirmedSettingsStore(FakeKeyValueStore())
        store.record("p", HaSettingsCodec.parseResponse(response(revision = 3)))
        store.record("p", HaSettingsCodec.parseResponse(withIdentification(mode = "ask")))
        assertEquals(IdentifyMode.ASK, store.confirmed("p")!!.identification!!.mode)
        // A copy at the same revision that does not state them never takes them away.
        store.record("p", HaSettingsCodec.parseResponse(response(revision = 3)))
        assertEquals(IdentifyMode.ASK, store.confirmed("p")!!.identification!!.mode)
    }
}
