package se.sensnology.spotnav.ha.dashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.HomeAssistantCommand
import se.sensnology.spotnav.ha.client.PriceSourceUpdate
import se.sensnology.spotnav.ha.client.PriceSourceUpdate.Outcome
import se.sensnology.spotnav.ha.client.WebhookReads
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.testing.HaFixtures

/**
 * The dashboard's `price_source` block (Home Assistant 1.22). No vendored dashboard carries one (it is sent only to an
 * app that reads it), so the block is taken from Home Assistant's own `choose_price_source` answer and edited here.
 */
class PriceSourceTest {
    /** Home Assistant's own block: two SE4 prices, the same-area question with both entries' fees. */
    private val real: JSONObject get() = HaFixtures.json("webhook/choose_price_source_success.json").getJSONObject("price_source")
    private val fees: JSONObject get() = real.getJSONObject("duplicate").getJSONArray("fees").getJSONObject(0)

    private fun block(duplicate: Any? = JSONObject.NULL) = JSONObject()
        .put("entry_id", "price1")
        .put("title", "Electricity price SE4")
        .put("charger_count", 2)
        .put("choices", JSONArray()
            .put(JSONObject().put("entry_id", "price1").put("title", "Electricity price SE4"))
            .put(JSONObject().put("entry_id", "price2").put("title", "Electricity price SE4 (Home)")))
        .put("writable", true)
        .put("duplicate", duplicate ?: JSONObject.NULL)

    private fun duplicate() = JSONObject()
        .put("area_id", "SE4")
        .put("entry_ids", JSONArray().put("price1").put("price2"))
        .put("titles", JSONArray().put("Electricity price SE4").put("Electricity price SE4 (Home)"))
        .put("fees", JSONArray().put(fees).put(JSONObject.NULL))

    private fun dashboard(raw: Any?) = DashboardFixtures.dashboard { put("price_source", raw) }

    @Test fun theAppAsksForTheBlock() {
        assertTrue(WebhookReads.FIELDS.contains("price_source"))
        val request = JSONObject(HomeAssistantClient.payload(HomeAssistantCommand("dashboard")))
        assertTrue(request.getJSONArray("reads").toString().contains("\"price_source\""))
    }

    @Test fun homeAssistantsOwnBlockIsRead() {
        val source = DashboardPriceSource.parse(real)!!
        assertEquals("price_b", source.entryId)
        assertEquals(2, source.duplicate!!.entries.size)
        assertEquals(source, dashboard(real).priceSource)
    }

    @Test fun aWholeBlockIsRead() {
        val source = DashboardPriceSource.parse(block(duplicate()))!!
        assertEquals("price1", source.entryId)
        assertEquals("Electricity price SE4", source.title)
        assertEquals(2, source.chargerCount)
        assertEquals(listOf("price1", "price2"), source.choices.map { it.entryId })
        assertTrue(source.writable)
        val question = source.duplicate!!
        assertEquals("SE4", question.areaId)
        assertEquals(listOf("Electricity price SE4", "Electricity price SE4 (Home)"), question.entries.map { it.title })
        assertEquals("suggested", question.entries[0].fees!!.vat.policy)
        assertEquals("öre/kWh", question.entries[0].fees!!.tax.unit)
        assertNull(question.entries[1].fees)
        // On the dashboard too, beside everything else.
        assertEquals(source, dashboard(block(duplicate())).priceSource)
    }

    @Test fun noQuestionAndNoChoicesAreRead() {
        val source = DashboardPriceSource.parse(block().put("choices", JSONArray()).put("writable", false))!!
        assertTrue(source.choices.isEmpty())
        assertFalse(source.writable)
        assertNull(source.duplicate)
    }

    @Test fun anAbsentBlockIsNone() {
        assertNull(DashboardFixtures.dashboard().priceSource)
        assertNull(dashboard(JSONObject.NULL).priceSource)
    }

    @Test fun anUnreadableBlockIsAsIfAbsentAndNeverRefusesTheDashboard() {
        val unreadable = listOf<JSONObject.() -> Unit>(
            { remove("writable") },
            { put("extra", 1) },
            { put("charger_count", -1) },
            { put("charger_count", 1.5) },
            { put("charger_count", "2") },
            { put("writable", "yes") },
            { put("title", JSONObject.NULL) },
            { put("entry_id", 7) },
            { put("choices", JSONObject()) },
            { getJSONArray("choices").put(JSONObject().put("entry_id", "x")) },
            { getJSONArray("choices").put(JSONObject().put("entry_id", "x").put("title", "y").put("more", 1)) },
            { put("duplicate", "SE4") },
            { put("duplicate", duplicate().put("titles", JSONArray().put("only one"))) },
            { put("duplicate", duplicate().put("entry_ids", JSONArray().put("price1").put(""))) },
            { put("duplicate", duplicate().put("entry_ids", JSONArray().put("price1")).put("titles", JSONArray().put("a")).put("fees", JSONArray().put(JSONObject.NULL))) },
            { put("duplicate", duplicate().put("fees", JSONArray().put(JSONObject().put("vat", 1)).put(JSONObject.NULL))) },
            { put("duplicate", duplicate().put("other", 1)) },
        )
        for ((index, edit) in unreadable.withIndex()) {
            val raw = block(duplicate()).apply(edit)
            assertNull("case $index", DashboardPriceSource.parse(raw))
            val read = dashboard(raw)
            assertNull("case $index", read.priceSource)
            assertNotNull(read.status)
        }
        assertNull(dashboard("not a block").priceSource)
    }
}

/** `choose_price_source` and `answer_price_duplicate` over the webhook, against Home Assistant's own answers. */
class PriceSourceWriteTest {
    private fun answer(name: String, status: Int) =
        PriceSourceUpdate.answer(status, HaFixtures.json("webhook/$name.json").toString())

    @Test fun theRequestsCarryTheirOneSubjectAndTheReads() {
        val choose = PriceSourceUpdate.choosePayload("price2")
        assertEquals("choose_price_source", choose.getString("action"))
        assertEquals("price2", choose.getString("price_entry_id"))
        assertEquals(1, choose.getInt("version"))
        assertTrue(choose.getJSONArray("reads").toString().contains("\"price_source\""))
        val keep = PriceSourceUpdate.duplicatePayload("SE4", "price1")
        assertEquals("answer_price_duplicate", keep.getString("action"))
        assertEquals("SE4", keep.getString("area_id"))
        assertEquals("price1", keep.getString("keep_entry_id"))
        val both = PriceSourceUpdate.duplicatePayload("SE4", null)
        assertTrue(both.has("keep_entry_id"))
        assertTrue(both.isNull("keep_entry_id"))
    }

    @Test fun choosingAPriceAnswersTheBlockReadAgain() {
        val chosen = answer("choose_price_source_success", 200) as Outcome.Updated
        val source = chosen.source!!
        assertEquals("price_b", source.entryId)
        assertEquals("Electricity price SE4 (company)", source.title)
        assertEquals(2, source.chargerCount)
        assertEquals(listOf("price_a", "price_b"), source.choices.map { it.entryId })
        val fees = source.duplicate!!.entries[1].fees!!
        assertEquals("manual", fees.transfer.policy)
        assertEquals(24.5, fees.transfer.effective!!, 0.0)
        assertEquals("%", fees.vat.unit)
        assertEquals(Outcome.Stale, answer("choose_price_source_unknown_entry", 400))
        assertEquals(Outcome.Refused, answer("choose_price_source_invalid_value", 400))
    }

    @Test fun answeringTheQuestionAnswersTheBlockWithoutIt() {
        val answered = answer("answer_price_duplicate_success", 200) as Outcome.Updated
        assertNull(answered.source!!.duplicate)
        assertEquals(Outcome.Stale, answer("answer_price_duplicate_unknown_duplicate", 400))
        assertEquals(Outcome.Stale, answer("answer_price_duplicate_unknown_entry", 400))
        assertEquals(Outcome.Refused, answer("answer_price_duplicate_invalid_value", 400))
    }

    @Test fun otherAnswersAreOutcomesToo() {
        assertEquals(Outcome.NotPermitted, PriceSourceUpdate.answer(403, JSONObject().put("ok", false).put("error", "spotnav_not_admin").toString()))
        // A Home Assistant without the action answers the webhook's own refusal.
        assertEquals(Outcome.NotSupported, PriceSourceUpdate.answer(400, JSONObject().put("ok", false).put("error", "Unsupported action").toString()))
        assertEquals(Outcome.Failed(null), PriceSourceUpdate.answer(null, null))
        assertEquals(Outcome.Failed(null), PriceSourceUpdate.answer(200, "not json"))
    }
}
