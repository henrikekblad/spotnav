package se.sensnology.spotnav.prices

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.RelayV2Fixtures

/** Contract v2 as the relay wrote it: the area list (the bundled list's shape) and its day files. */
class RelayContractV2Test {
    private fun parsedAreas(body: String): List<RelayArea>? =
        (RelayAreasParser.parse(body) as? CatalogueParse.Ok)?.catalogue?.areas

    private fun gbRaw(): JSONObject {
        val areas = JSONObject(RelayV2Fixtures.read("areas-v2.json")).getJSONArray("areas")
        return (0 until areas.length()).map { areas.getJSONObject(it) }.first { it.getString("id") == "GB-C" }
    }

    private fun withAreas(vararg areas: JSONObject): String =
        JSONObject(RelayV2Fixtures.read("areas-v2.json")).put("areas", JSONArray(areas.toList())).toString()

    @Test
    fun readsGreatBritainAndPortugalWithTheirNewProperties() {
        val gb = RelayV2Fixtures.area("GB-C")
        assertEquals("GB C – London", gb.name)
        assertEquals("Europe/London", gb.tz)
        assertEquals("Europe/Paris", gb.marketTz)
        assertEquals("GBP", gb.currency)
        assertEquals("£", gb.majorUnit)
        assertEquals("p", gb.minorUnit)
        assertEquals(setOf(IncludedPart.VAT, IncludedPart.TAX, IncludedPart.GRID_FEE), gb.included)
        assertEquals(AreaSource("Octopus Energy (Agile)", "https://octopus.energy/smart/agile/"), gb.source)
        assertNull(gb.vatPercent)
        assertNull(gb.suggestedTax)
        assertTrue(gb.inGreatBritain)
        // The relay's own name already says the region; the id is not repeated.
        assertEquals("GB C – London", gb.selectorLabel)

        val pt = RelayV2Fixtures.area("PT")
        assertEquals(listOf("Europe/Lisbon", "Europe/Madrid"), listOf(pt.tz, pt.marketTz))
        assertTrue(pt.included.isEmpty())
        assertEquals("ENTSO-E Transparency Platform", pt.source?.name)
        assertEquals("PT – Portugal", pt.selectorLabel)
        // No market_tz means one calendar.
        val se4 = RelayV2Fixtures.area("SE4")
        assertEquals("Europe/Stockholm", se4.marketTz)
        assertEquals("SE4 – Malmö", se4.selectorLabel)
        assertTrue(!se4.inGreatBritain)
        // GB has no EIC in v2, and that is not a fault.
        assertNull(parsedAreas(RelayV2Fixtures.read("areas-v2.json"))!!.first { it.id == "GB-C" }.eic)
    }

    @Test
    fun skipsAnEntryItCannotUseAndKeepsTheRest() {
        val unusable = listOf(
            gbRaw().put("id", "GB-X").put("included", JSONArray(listOf("vat", "standing_charge"))),
            gbRaw().put("id", "GB-X").put("included", JSONArray(listOf("vat", "vat"))),
            gbRaw().put("id", "GB-X").put("included", "vat"),
            gbRaw().put("id", "GB-X").apply { remove("source") },
            gbRaw().put("id", "GB-X").put("source", JSONObject().put("name", "Octopus").put("url", "javascript:alert(1)")),
            gbRaw().put("id", "GB-X").put("source", JSONObject().put("name", "").put("url", "https://octopus.energy/")),
            gbRaw().put("id", "GB-X").put("market_tz", "Mars/Olympus"),
            gbRaw().put("id", "GB-X").put("eic", " "),
            gbRaw().put("id", "A".repeat(33))
        )
        for (bad in unusable) {
            assertEquals(bad.toString(), listOf("GB-C"), parsedAreas(withAreas(bad, gbRaw()))?.map { it.id })
        }
        assertEquals(2, parsedAreas(withAreas(gbRaw(), gbRaw().put("id", "A".repeat(32))))?.size)
        // A duplicate still refuses the list whole, and a v1 list is not read at all.
        assertTrue(RelayAreasParser.parse(withAreas(gbRaw(), gbRaw())) is CatalogueParse.Invalid)
        assertTrue(RelayAreasParser.parse(JSONObject(RelayV2Fixtures.read("areas-v2.json")).put("v", 1).toString()) is CatalogueParse.Invalid)
    }

    @Test
    fun aGreatBritainDayFileIsReadOnTheParisCalendarAtThirtyMinutesWithTheGbpRate() {
        val parsed = RelayDayParser.parse(RelayV2Fixtures.read("GB-C_2026-10-04.json"), "GB-C", "Europe/Paris", "GBP", "2026-10-04")
        val document = (parsed as DayParse.Ok).document
        assertEquals(listOf(30, 48), listOf(document.resMinutes, document.prices.size))
        assertEquals(0.8712, document.fxRate, 0.0)
        // Its own tz (London) is the display zone; the calendar it must match is market_tz.
        assertTrue(
            RelayDayParser.parse(RelayV2Fixtures.read("GB-C_2026-10-04.json"), "GB-C", "Europe/London", "GBP", "2026-10-04")
                is DayParse.Invalid
        )
        val points = RelayDayPoints.points(document, java.time.ZoneId.of("Europe/London"))
        assertEquals(96, points.size)
        // Two quarter-hour points per half-hour, at the same price.
        assertEquals(points[0].pricePerKwh, points[1].pricePerKwh, 0.0)
        // Paris 01:00 is London midnight, where the writer's Agile day is 9.5 p.
        val londonMidnight = points.first { it.start.toInstant() == java.time.OffsetDateTime.parse("2026-10-04T00:00:00+01:00").toInstant() }
        assertEquals(9.5, londonMidnight.pricePerKwh * 100, 0.01)
    }

    @Test
    fun aPortugalFileWrittenForV1StillReadsUnderTheV2Area() {
        // Every v1 zone's file states its market calendar as `tz`: Portugal's says Madrid.
        val pt = RelayV2Fixtures.area("PT")
        val parsed = RelayDayParser.parse(RelayV2Fixtures.read("PT_2026-10-04.json"), "PT", pt.marketTz, pt.currency, "2026-10-04")
        assertTrue(parsed is DayParse.Ok)
    }
}
