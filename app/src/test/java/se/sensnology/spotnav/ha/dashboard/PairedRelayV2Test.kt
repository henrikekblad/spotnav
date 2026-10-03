package se.sensnology.spotnav.ha.dashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.sensnology.spotnav.chart.ChartMarket
import se.sensnology.spotnav.chart.ChartMarketBuild
import se.sensnology.spotnav.chart.DashboardChart
import se.sensnology.spotnav.ha.settings.HaAreaOverride
import se.sensnology.spotnav.ha.settings.HaAreaOverrideComponent
import se.sensnology.spotnav.ha.settings.HaFiscalValue
import se.sensnology.spotnav.ha.settings.HaSettingsCodec
import se.sensnology.spotnav.ha.settings.HaSettingsEdit
import se.sensnology.spotnav.ha.settings.HaSettingsEditResult
import se.sensnology.spotnav.ha.settings.HaSettingsEditor
import se.sensnology.spotnav.ha.settings.PairedSettingsForm
import se.sensnology.spotnav.ha.settings.SettingsFormValues
import se.sensnology.spotnav.planning.FiscalInput
import se.sensnology.spotnav.planning.FiscalResolution
import se.sensnology.spotnav.prices.AreaSource
import se.sensnology.spotnav.prices.IncludedPart
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.RelayContractVersion
import se.sensnology.spotnav.testing.HaFixtures
import se.sensnology.spotnav.testing.RelayV2Fixtures
import java.time.OffsetDateTime

/**
 * Home Assistant 1.8's contract-v2 facts as a paired charger reads them: the market's calendar,
 * included parts and source, the settings record's read-only `fiscal_included`, and an older Home
 * Assistant that states none of them.
 */
class PairedRelayV2Test {
    private val all = setOf(HaAreaOverrideComponent.VAT, HaAreaOverrideComponent.TAX, HaAreaOverrideComponent.TRANSFER)

    @Before
    fun catalogue() {
        PriceMarkets.replace(RelayV2Fixtures.areas(), RelayContractVersion.V2)
    }

    @After
    fun clear() {
        PriceMarkets.replace(emptyList())
    }

    /** `proposal_ready` moved to London, as Home Assistant 1.8 answers for a Great Britain region. */
    private fun greatBritain(withFiscalIncluded: Boolean = true): JSONObject {
        val json = HaFixtures.json("dashboard/proposal_ready.json")
        json.getJSONObject("market")
            .put("area_id", "GB-C").put("area_name", "GB C – London").put("countries", JSONArray(listOf("GB")))
            .put("timezone", "Europe/London").put("market_timezone", "Europe/Paris")
            .put("currency", "GBP").put("major_unit", "£").put("minor_unit", "p")
            .put("included", JSONArray(listOf("vat", "tax", "grid_fee")))
            .put("source", JSONObject().put("name", "Octopus Energy (Agile)").put("url", "https://octopus.energy/smart/agile/"))
        for (component in listOf("vat", "tax", "transfer")) {
            json.getJSONObject("fiscal").getJSONObject(component).put("policy", "included").put("effective_value", JSONObject.NULL)
        }
        val settings = json.getJSONObject("settings").put("area_id", "GB-C")
        settings.put(
            "overrides",
            JSONArray().put(
                JSONObject().put("area_id", "GB-C")
                    .put("vat", JSONObject().put("enabled", true).put("value", 20.0))
                    .put("tax", JSONObject().put("enabled", true).put("value", 1.5))
                    .put("transfer", JSONObject().put("enabled", false).put("value", JSONObject.NULL))
            )
        )
        if (withFiscalIncluded) settings.put("fiscal_included", JSONArray(listOf("vat", "tax", "transfer")))
        else settings.remove("fiscal_included")
        return json
    }

    @Test
    fun theVendoredAnswersCarryTheNewMarketFieldsAndStillParse() {
        val dashboard = Dashboard.parse(HaFixtures.json("dashboard/proposal_ready.json"))
        assertEquals("Europe/Stockholm", dashboard.market.marketTimezone)
        assertTrue(dashboard.market.included.isEmpty())
        assertNull(dashboard.market.source)
        assertEquals(listOf("SE"), dashboard.market.countries)
        assertFalse(dashboard.market.inGreatBritain)
        assertTrue(dashboard.settings!!.fiscalIncluded.isEmpty())
        // The webhook withholds fiscal_included from an app that does not ask; the answer still reads.
        assertTrue(Dashboard.parse(HaFixtures.json("webhook/dashboard.json")).settings!!.fiscalIncluded.isEmpty())
        val success = HaSettingsCodec.parseResponse(HaFixtures.json("settings/v1/success.json").getJSONObject("settings"))
        assertTrue(success.fiscalIncluded.isEmpty())
    }

    @Test
    fun anOlderHomeAssistantWithoutTheFieldsKeepsTodaysBehaviour() {
        val json = HaFixtures.json("dashboard/proposal_ready.json")
        json.getJSONObject("market").apply { remove("market_timezone"); remove("included"); remove("source") }
        json.getJSONObject("settings").remove("fiscal_included")
        val dashboard = Dashboard.parse(json)
        assertNull(dashboard.market.marketTimezone)
        assertTrue(dashboard.market.included.isEmpty())
        assertTrue(dashboard.settings!!.fiscalIncluded.isEmpty())
        val components = FiscalResolution.forArea(dashboard.settings!!, "SE4", PriceMarkets.find("SE4"))
        assertEquals(FiscalResolution.forArea(dashboard.settings!!.copy(fiscalIncluded = emptySet()), "SE4", PriceMarkets.find("SE4")), components)
    }

    @Test
    fun aGreatBritainAnswerLocksEveryIncludedPart() {
        val dashboard = Dashboard.parse(greatBritain())
        assertEquals("Europe/Paris", dashboard.market.marketTimezone)
        assertEquals(setOf(IncludedPart.VAT, IncludedPart.TAX, IncludedPart.GRID_FEE), dashboard.market.included)
        assertEquals(AreaSource("Octopus Energy (Agile)", "https://octopus.energy/smart/agile/"), dashboard.market.source)
        assertEquals(all, dashboard.market.fiscalIncluded)
        assertTrue(dashboard.market.inGreatBritain)
        assertEquals(all, dashboard.settings!!.fiscalIncluded)

        // Whatever the override says, nothing is added and nothing is unresolved.
        val record = dashboard.settings!!
        val components = FiscalResolution.forArea(record, "GB-C", null)
        assertTrue(components.isComplete)
        assertEquals(listOf(FiscalInput.OFF, FiscalInput.OFF, FiscalInput.OFF), listOf(components.vat.input, components.tax.input, components.transfer.input))
        val market = (ChartMarket.from(record, "GB-C", 15, null) as ChartMarketBuild.Ready).market
        assertEquals(12.5, market.apply(0.125), 1e-12)
    }

    @Test
    fun theMarketsIncludedListLocksARecordTheWebhookWithheldItFrom() {
        val dashboard = Dashboard.parse(greatBritain(withFiscalIncluded = false))
        assertEquals(all, dashboard.settings!!.fiscalIncluded)
    }

    @Test
    fun includedPartsAreNeverSentBackAsEdits() {
        val record = Dashboard.parse(greatBritain()).settings!!
        val stored = record.overrides.first { it.areaId == "GB-C" }
        // The locked boxes read checked; the form must not turn that into an edit of the override.
        val values = SettingsFormValues("GB-C", vat = true, tax = true, taxFigure = 9.0, transfer = true, transferFigure = 4.0)
        val replacement = (PairedSettingsForm.replacement(record, values, PriceMarkets.all) as HaSettingsEditResult.Ready).settings
        assertEquals(stored, replacement.overrides.first { it.areaId == "GB-C" })
        val body = HaSettingsCodec.encodeBody(replacement)
        assertFalse(body.has("fiscal_included"))
        // One component's edit of a locked part changes nothing either.
        val edit = HaSettingsEdit.Fiscal("GB-C", HaAreaOverrideComponent.TAX, HaFiscalValue(true, 99.0))
        val edited = (HaSettingsEditor.replacement(record, edit) as HaSettingsEditResult.Ready).settings
        assertEquals(stored, edited.overrides.first { it.areaId == "GB-C" })
        // An area that includes nothing is edited as before.
        val se4 = PairedSettingsForm.replacement(record, values.copy(areaId = "SE4"), PriceMarkets.all) as HaSettingsEditResult.Ready
        assertEquals(
            HaAreaOverride("SE4", HaFiscalValue(true, 25.0), HaFiscalValue(true, 9.0), HaFiscalValue(true, 4.0)),
            se4.settings.overrides.first { it.areaId == "SE4" }
        )
    }

    @Test
    fun theStoredCopyKeepsTheReadOnlyFact() {
        val record = Dashboard.parse(greatBritain()).settings!!
        val stored = HaSettingsCodec.encode(record)
        assertEquals(listOf("vat", "tax", "transfer"), List(stored.getJSONArray("fiscal_included").length()) { stored.getJSONArray("fiscal_included").getString(it) })
        assertEquals(record, HaSettingsCodec.parseStored(stored))
        // A record that includes nothing is stored exactly as before.
        val plain = Dashboard.parse(HaFixtures.json("dashboard/proposal_ready.json")).settings!!
        assertFalse(HaSettingsCodec.encode(plain).has("fiscal_included"))
    }

    @Test
    fun aPairedChartDrawsHalfHoursAsQuarters() {
        val json = greatBritain()
        val intervals = JSONArray()
        var start = OffsetDateTime.parse("2026-09-22T00:00:00+01:00")
        repeat(48) { index ->
            val end = start.plusMinutes(30)
            intervals.put(
                JSONObject().put("start", start.toString()).put("end", end.toString()).put("day", "2026-09-22")
                    .put("duration_minutes", 30).put("raw_price", 10.0 + index).put("effective_price", 10.0 + index)
                    .put("proposal_planned", false).put("installed_planned", false)
            )
            start = end
        }
        json.getJSONObject("prices").put("intervals", intervals).put("interval_count", 48).put("resolution_minutes", 30)
        val dashboard = Dashboard.parse(json)
        val noon = OffsetDateTime.parse("2026-09-22T12:20:00+01:00").toInstant()
        val chart = DashboardChart.build(dashboard, 15, now = noon)!!
        assertEquals(96, chart.prices.today.size)
        assertEquals(chart.prices.today[0].pricePerKwh, chart.prices.today[1].pricePerKwh, 0.0)
        assertEquals("00:15", chart.prices.today[1].start.toLocalTime().toString())
        assertEquals("+01:00", chart.prices.today[0].start.offset.toString())
    }
}
