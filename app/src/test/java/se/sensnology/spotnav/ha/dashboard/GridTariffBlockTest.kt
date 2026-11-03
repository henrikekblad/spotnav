package se.sensnology.spotnav.ha.dashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.GridTariffUpdate
import se.sensnology.spotnav.ha.client.WebhookReads
import se.sensnology.spotnav.testing.HaFixtures

/** The dashboard's `grid_tariff` block and the `update_grid_tariff` body, as `docs/api.md` ("Grid tariff") states them. */
class GridTariffBlockTest {
    /** The example of `docs/api.md`. */
    private val example = """
        {"entry_id": "price_a", "write_revision": 3,
         "transfer_percent": {"policy": "manual", "explicit_value": 5.0, "effective_value": 5.0, "unit": "%"},
         "transfer_high": {"policy": "off", "explicit_value": 40.0, "effective_value": null, "unit": "öre/kWh"},
         "high_load_window": {"days": "weekdays", "from_month": 11, "to_month": 3, "from_hour": 7, "to_hour": 19},
         "writable": true}
    """
    private val block get() = DashboardGridTariff.parse(JSONObject(example))!!

    @Test fun theBlockIsReadStrictly() {
        assertEquals(5.0, block.transferPercent.effectiveValue!!, 0.0)
        assertEquals(HighLoadWindow("weekdays", 11, 3, 7, 19), block.highLoadWindow)
        assertNull(block.demand)
        assertFalse(block.included)
        assertNull(DashboardGridTariff.parse(JSONObject(example).put("extra", 1)))
        // A manual fee without a figure, or equal hours, is no block.
        assertNull(DashboardGridTariff.parse(JSONObject(example).apply { getJSONObject("transfer_percent").put("effective_value", JSONObject.NULL) }))
        assertNull(DashboardGridTariff.parse(JSONObject(example).apply { getJSONObject("high_load_window").put("to_hour", 7) }))
    }

    @Test fun aDashboardAskedForItCarriesIt() {
        assertTrue("grid_tariff" in WebhookReads.FIELDS)
        val json = HaFixtures.json("webhook/dashboard.json").put("grid_tariff", JSONObject(example))
        assertEquals(3, Dashboard.parse(json).gridTariff!!.writeRevision)
        assertNull(Dashboard.parse(HaFixtures.json("webhook/dashboard.json")).gridTariff)
    }

    @Test fun aChangeKeepsTheOtherPartsAsStoredAndNamesNoDemandChargeBefore24() {
        val percent = block.bodyWithPercent(null)
        assertEquals(setOf("transfer_percent", "transfer_high", "high_load_window"), percent.keys().asSequence().toSet())
        assertEquals(false, percent.getJSONObject("transfer_percent").getBoolean("enabled"))
        assertEquals(5.0, percent.getJSONObject("transfer_percent").getDouble("value"), 0.0)
        assertEquals(false, percent.getJSONObject("transfer_high").getBoolean("enabled"))
        assertEquals(40.0, percent.getJSONObject("transfer_high").getDouble("value"), 0.0)
        // The one dialog's Save: the fee on and its hours, in one write.
        val high = block.bodyWithHighLoad(55.0, HighLoadWindow("mon_sat", 1, 12, 22, 6))
        assertEquals(true, high.getJSONObject("transfer_high").getBoolean("enabled"))
        assertEquals(55.0, high.getJSONObject("transfer_high").getDouble("value"), 0.0)
        assertEquals("mon_sat", high.getJSONObject("high_load_window").getString("days"))
        assertEquals(22, high.getJSONObject("high_load_window").getInt("from_hour"))
        assertEquals(5.0, high.getJSONObject("transfer_percent").getDouble("value"), 0.0)
        // Remove: the fee off with its figure kept, and the hours cleared, in one write.
        val removed = block.bodyWithoutHighLoad()
        assertEquals(false, removed.getJSONObject("transfer_high").getBoolean("enabled"))
        assertEquals(40.0, removed.getJSONObject("transfer_high").getDouble("value"), 0.0)
        assertTrue(removed.isNull("high_load_window"))
        assertEquals(true, removed.getJSONObject("transfer_percent").getBoolean("enabled"))
    }

    @Test fun aWriteAnswersTheBlockAgainAConflictOrARefusal() {
        val updated = GridTariffUpdate.answer(200, """{"ok": true, "error": null, "field_errors": [], "grid_tariff": $example, "action": "update_grid_tariff"}""")
        assertEquals(3, (updated as GridTariffUpdate.Outcome.Updated).block!!.writeRevision)
        assertTrue(GridTariffUpdate.answer(409, """{"ok": false, "error": "revision_conflict", "field_errors": [], "grid_tariff": $example}""") is GridTariffUpdate.Outcome.Conflict)
        assertEquals(GridTariffUpdate.Outcome.Refused, GridTariffUpdate.answer(400, """{"ok": false, "error": "invalid_fiscal", "field_errors": [{"field": "transfer_high", "code": "invalid_value"}], "grid_tariff": null}"""))
        val payload = GridTariffUpdate.payload(3, block.storedBody())
        assertEquals("update_grid_tariff", payload.getString("action"))
        assertEquals(3, payload.getInt("expected_write_revision"))
    }
}
