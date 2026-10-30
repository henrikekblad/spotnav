package se.sensnology.spotnav.ha.dashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.HomeAssistantCommand
import se.sensnology.spotnav.ha.client.WebhookReads
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.testing.HaFixtures

/**
 * The dashboard's `prices.provider` block, asked for with the `reads` value `price_provider`: read from Home
 * Assistant's own dashboards (which carry it, being the card's) and its `price_days` answers, and leniently, as
 * the card reads it.
 */
class PriceProviderTest {
    private fun block(notice: Any? = JSONObject.NULL) = JSONObject()
        .put("key", "nordpool")
        .put("name", "Nord Pool")
        .put("url", "https://www.nordpoolgroup.com/")
        .put("notice", notice ?: JSONObject.NULL)

    @Test fun theAppAsksForTheBlock() {
        assertTrue(WebhookReads.FIELDS.contains("price_provider"))
        val request = JSONObject(HomeAssistantClient.payload(HomeAssistantCommand("dashboard")))
        assertTrue(request.getJSONArray("reads").toString().contains("\"price_provider\""))
    }

    @Test fun homeAssistantsOwnDashboardIsRead() {
        val provider = DashboardFixtures.dashboard("start_idle.json").prices.provider!!
        assertEquals("relay", provider.key)
        assertEquals("SpotNav relay", provider.name)
        assertEquals("https://spotnav.sensnology.se", provider.url)
        assertEquals(DashboardPriceProvider.Notice("add_nordpool", emptyMap()), provider.notice)
    }

    @Test fun aDashboardWithoutAnAreaOrForAnAppThatDidNotAskHasNone() {
        assertNull(DashboardFixtures.dashboard("no_settings.json").prices.provider)
        // The webhook's own capture was made without `reads: price_provider`: the block is left out.
        assertNull(Dashboard.parse(HaFixtures.json("webhook/dashboard.json")).prices.provider)
    }

    @Test fun thePriceDaysAnswersCarryTheSameShape() {
        val nordpool = DashboardPriceProvider.parse(HaFixtures.json("webhook/price_days_nordpool_fi.json").get("provider"))!!
        assertEquals(DashboardPriceProvider("nordpool", "Nord Pool", "https://www.nordpoolgroup.com/", null), nordpool)
        val gb = DashboardPriceProvider.parse(HaFixtures.json("webhook/price_days_relay_gb.json").get("provider"))!!
        assertEquals("source_missing", gb.notice?.code)
    }

    @Test fun aNoticeCarriesItsParameters() {
        val read = DashboardPriceProvider.parse(
            block(JSONObject().put("code", "octopus_region_mismatch").put("params", JSONObject().put("region", "GB-C")))
        )!!
        assertEquals(DashboardPriceProvider.Notice("octopus_region_mismatch", mapOf("region" to "GB-C")), read.notice)
    }

    @Test fun noSourceAtAllIsANullKey() {
        val read = DashboardPriceProvider.parse(
            JSONObject().put("key", JSONObject.NULL).put("name", JSONObject.NULL).put("url", JSONObject.NULL)
                .put("notice", JSONObject().put("code", "source_missing").put("params", JSONObject()))
        )!!
        assertNull(read.key)
        assertEquals("source_missing", read.notice?.code)
    }

    @Test fun anAddressThatIsNotTheWebIsDropped() {
        assertNull(DashboardPriceProvider.parse(block().put("url", "javascript:alert(1)"))!!.url)
        assertNull(DashboardPriceProvider.parse(block().put("name", JSONObject.NULL))!!.url)
    }

    @Test fun anythingElseIsNoBlockAndNeverFailsTheDashboard() {
        val bad = listOf(
            "not an object",
            JSONArray(),
            block().put("extra", 1),
            JSONObject().put("key", "nordpool").put("name", "Nord Pool").put("url", "https://www.nordpoolgroup.com/"),
            block().put("key", 3),
            block("add_nordpool"),
            block(JSONObject().put("code", "add_nordpool")),
            block(JSONObject().put("code", "add_nordpool").put("params", JSONObject()).put("x", 1)),
            block(JSONObject().put("code", 1).put("params", JSONObject())),
            block(JSONObject().put("code", "octopus_region_mismatch").put("params", JSONObject().put("region", 3)))
        )
        bad.forEachIndexed { index, raw ->
            assertNull("case $index", DashboardPriceProvider.parse(raw))
            val dashboard = DashboardFixtures.dashboard { getJSONObject("prices").put("provider", raw) }
            assertNull("case $index", dashboard.prices.provider)
        }
    }
}
