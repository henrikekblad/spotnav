package se.sensnology.spotnav.prices

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.RelayV2Fixtures

/** Contract v2 as the relay writes it: the area list, the index, a day file, and the fallback to v1. */
class RelayContractV2Test {
    private fun parsedAreas(body: String, version: Int): List<RelayArea>? =
        (RelayAreasParser.parse(body, version) as? CatalogueParse.Ok)?.catalogue?.areas

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
        assertTrue(gb.usesMiles)
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
        assertTrue(!se4.usesMiles)
        // GB has no EIC in v2, and that is not a fault.
        assertNull(parsedAreas(RelayV2Fixtures.read("areas-v2.json"), 2)!!.first { it.id == "GB-C" }.eic)
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
            assertEquals(bad.toString(), listOf("GB-C"), parsedAreas(withAreas(bad, gbRaw()), 2)?.map { it.id })
        }
        assertEquals(2, parsedAreas(withAreas(gbRaw(), gbRaw().put("id", "A".repeat(32))), 2)?.size)
        // A duplicate still refuses the list whole, and a v2 list is not a v1 list.
        assertTrue(RelayAreasParser.parse(withAreas(gbRaw(), gbRaw()), 2) is CatalogueParse.Invalid)
        assertTrue(RelayAreasParser.parse(RelayV2Fixtures.read("areas-v2.json"), 1) is CatalogueParse.Invalid)
        assertTrue(RelayAreasParser.parse(RelayV2Fixtures.read("areas-v1.json"), 2) is CatalogueParse.Invalid)
    }

    @Test
    fun readsAV1ListAsOneCalendarNothingIncludedAndNoSource() {
        val pt = parsedAreas(RelayV2Fixtures.read("areas-v1.json"), 1)!!.first { it.id == "PT" }
        assertEquals(listOf("Europe/Madrid", "Europe/Madrid"), listOf(pt.tz, pt.marketTz))
        assertTrue(pt.included.isEmpty())
        assertNull(pt.source)
        // A v1 list's area without an EIC is still refused, as before.
        val noEic = JSONObject(RelayV2Fixtures.read("areas-v1.json"))
        noEic.getJSONArray("areas").getJSONObject(0).remove("eic")
        assertEquals(listOf("SE4"), parsedAreas(noEic.toString(), 1)?.map { it.id })
        // And v2 properties in a v1 list are ignored, never read.
        val stray = JSONObject(RelayV2Fixtures.read("areas-v1.json"))
        stray.getJSONArray("areas").getJSONObject(0).put("market_tz", "Europe/Paris").put("included", JSONArray(listOf("vat")))
        val read = parsedAreas(stray.toString(), 1)!!.first { it.id == "PT" }
        assertEquals("Europe/Madrid", read.marketTz)
        assertTrue(read.included.isEmpty())
    }

    @Test
    fun theV2IndexStatesGreatBritainAtThirtyMinutesAndSkipsAResolutionItCannotPlan() {
        val index = RelayIndexParser.parse(RelayV2Fixtures.read("index-v2.json"), 2)!!
        assertEquals(setOf("2026-10-04", "2026-10-05"), index.daysByArea["GB-C"])
        assertEquals(30, index.resByArea["GB-C"])
        assertNull(RelayIndexParser.parse(RelayV2Fixtures.read("index-v2.json"), 1))
        assertNull(RelayIndexParser.parse(RelayV2Fixtures.read("index-v1.json"), 2))

        val body = JSONObject(RelayV2Fixtures.read("index-v2.json"))
        body.getJSONObject("areas").getJSONObject("GB-C").put("res", 20)
        assertEquals(setOf("PT", "SE4"), RelayIndexParser.parse(body.toString(), 2)!!.daysByArea.keys)
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

    // the catalogue: v2 first, v1 on a 404 or an unreadable v2 list

    @Test
    fun theCatalogueAdoptsV2AndHoldsItsVersion() {
        val store = FakeKeyValueStore()
        val result = AreaCatalogue.refresh(
            store, { error("v1 must not be asked for") }, nowMillis = 10_000_000L, force = true,
            fetchV2 = { RelayFetch.Body(RelayV2Fixtures.read("areas-v2.json")) }
        )
        assertTrue(result is CatalogueRefresh.Updated)
        assertEquals(RelayContractVersion.V2, (result as CatalogueRefresh.Updated).version)
        val held = AreaCatalogue.lastGoodVersioned(store)!!
        assertEquals(RelayContractVersion.V2, held.version)
        assertTrue(held.areas.any { it.id == "GB-C" })
    }

    @Test
    fun aRelayWithoutV2FallsBackToV1() {
        val store = FakeKeyValueStore()
        val v1 = RelayV2Fixtures.read("areas-v1.json")
        val result = AreaCatalogue.refresh(store, { v1 }, nowMillis = 10_000_000L, force = true, fetchV2 = { RelayFetch.NotFound })
        assertEquals(RelayContractVersion.V1, (result as CatalogueRefresh.Updated).version)
        assertEquals(listOf("PT", "SE4"), AreaCatalogue.lastGood(store)!!.map { it.id })
    }

    @Test
    fun anUnreadableV2ListFallsBackToV1() {
        val store = FakeKeyValueStore()
        val v1 = RelayV2Fixtures.read("areas-v1.json")
        val result = AreaCatalogue.refresh(store, { v1 }, nowMillis = 10_000_000L, force = true, fetchV2 = { RelayFetch.Body("{\"v\":3}") })
        assertEquals(RelayContractVersion.V1, (result as CatalogueRefresh.Updated).version)
    }

    @Test
    fun aFailedV2RequestKeepsAHeldV2ListRatherThanSteppingBack() {
        val store = FakeKeyValueStore()
        AreaCatalogue.refresh(store, { null }, 10_000_000L, true, fetchV2 = { RelayFetch.Body(RelayV2Fixtures.read("areas-v2.json")) })
        val v1 = RelayV2Fixtures.read("areas-v1.json")
        val result = AreaCatalogue.refresh(store, { v1 }, 20_000_000L, true, fetchV2 = { RelayFetch.Failed })
        assertTrue(result is CatalogueRefresh.Failed)
        assertEquals(RelayContractVersion.V2, AreaCatalogue.lastGoodVersioned(store)!!.version)
        // A relay that really went back to v1 (404) is followed, and the v2 list is retired.
        val back = AreaCatalogue.refresh(store, { v1 }, 30_000_000L, true, fetchV2 = { RelayFetch.NotFound })
        assertEquals(RelayContractVersion.V1, (back as CatalogueRefresh.Updated).version)
        assertNull(store.getString(AreaCatalogue.KEY_LAST_GOOD_V2))
        assertEquals(RelayContractVersion.V1, AreaCatalogue.lastGoodVersioned(store)!!.version)
    }

    @Test
    fun anIdenticalV2ListIsUnchanged() {
        val store = FakeKeyValueStore()
        val body = RelayV2Fixtures.read("areas-v2.json")
        AreaCatalogue.refresh(store, { null }, 10_000_000L, true, fetchV2 = { RelayFetch.Body(body) })
        assertEquals(
            CatalogueRefresh.Unchanged,
            AreaCatalogue.refresh(store, { null }, 20_000_000L, true, fetchV2 = { RelayFetch.Body(body) })
        )
    }

    @Test
    fun aHeldV1ListStillStartsTheApp() {
        // An install from before contract v2 holds only `last_good_v1`.
        val store = FakeKeyValueStore()
        store.putString(AreaCatalogue.KEY_LAST_GOOD, RelayFixtures.areasBody(listOf(RelayFixtures.se4)))
        val held = AreaCatalogue.startup(store, { null })
        assertEquals(RelayContractVersion.V1, held.version)
        assertEquals(listOf("SE4"), held.areas.map { it.id })
        assertNotNull(AreaCatalogue.lastGood(store))
    }
}
