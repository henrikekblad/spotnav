package se.sensnology.spotnav.prices

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.app.RelayLog
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.ui.settings.AreaSelectionState
import se.sensnology.spotnav.ui.settings.SettingsAreaController
import java.io.File
import java.util.Locale

class AreaCatalogueTest {
    private fun areasBody(areas: List<PriceMarket>) = RelayFixtures.areasBody(areas)

    /** The bundled area list as the app ships it, read from the source tree. */
    private fun bundledSnapshot(): String {
        val file = File("src/main/assets/${AreaCatalogue.BUNDLED_ASSET}")
        assertTrue("the bundled area list must exist at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private fun parse(body: String) = RelayAreasParser.parse(body)

    // what the parser accepts, and what it refuses

    @Test
    fun theThreeMoneyFieldsStayDistinct() {
        val parsed = parse(areasBody(listOf(RelayFixtures.no1)))

        val area = (parsed as CatalogueParse.Ok).catalogue.areas.single()
        assertEquals("NOK", area.currency)
        assertEquals("kr", area.majorUnit)
        assertEquals("øre", area.minorUnit)
        // Three facts, and not one of them derived from another.
        assertEquals(3, setOf(area.currency, area.majorUnit, area.minorUnit).size)
    }

    @Test
    fun bothUnitsAreRequiredIndependently() {
        val full = areasBody(listOf(RelayFixtures.se4))
        assertTrue(parse(full) is CatalogueParse.Ok)

        val withoutMajor = full.replace("\"major_unit\":\"kr\",", "")
        val withoutMinor = full.replace("\"minor_unit\":\"öre\",", "")
        val blankMajor = full.replace("\"major_unit\":\"kr\"", "\"major_unit\":\"  \"")

        assertTrue(parse(withoutMajor) is CatalogueParse.Invalid)
        assertTrue(parse(withoutMinor) is CatalogueParse.Invalid)
        assertTrue(parse(blankMajor) is CatalogueParse.Invalid)
    }

    @Test
    fun explicitZeroVatIsNotTheSameFactAsAnOmittedField() {
        val zero = (parse(areasBody(listOf(RelayFixtures.no4))) as CatalogueParse.Ok).catalogue.areas.single()
        val omitted = (parse(areasBody(listOf(RelayFixtures.no4.copy(vatPercent = null)))) as CatalogueParse.Ok)
            .catalogue.areas.single()

        assertEquals(0.0, zero.vatPercent!!, 0.0)
        assertNull("an omitted VAT figure is not a zero", omitted.vatPercent)
    }

    @Test
    fun aPresentButUnusableFiscalValueRejectsTheWholeDocument() {
        val good = areasBody(listOf(RelayFixtures.se4))
        for (broken in listOf(
            good.replace("\"vat_percent\":25.0", "\"vat_percent\":null"),
            good.replace("\"vat_percent\":25.0", "\"vat_percent\":\"25\""),
            good.replace("\"vat_percent\":25.0", "\"vat_percent\":true"),
            good.replace("\"suggested_tax\":36.0", "\"suggested_tax\":null"),
            good.replace("\"suggested_grid_fee\":30.0", "\"suggested_grid_fee\":null")
        )) {
            assertFixtureChanged(good, broken)
            assertTrue(
                "a present non-number must refuse the document: $broken",
                parse(broken) is CatalogueParse.Invalid
            )
        }
    }

    /** Guards the guard: a fixture that did not actually change would make its test vacuous. */
    private fun assertFixtureChanged(original: String, mutated: String) =
        assertFalse("the fixture did not change", original == mutated)

    @Test
    fun aMultiCountryAreaKeepsEveryCountry() {
        val area = RelayFixtures.area(
            "DE-LU", "EUR", "€", "cent", tz = "Europe/Berlin", countries = listOf("DE", "LU")
        )

        val parsed = (parse(areasBody(listOf(area))) as CatalogueParse.Ok).catalogue.areas.single()

        assertEquals(listOf("DE", "LU"), parsed.countries)
    }

    @Test
    fun duplicateIdsRejectTheWholeDocument() {
        val duplicated = areasBody(listOf(RelayFixtures.se4, RelayFixtures.se4))

        assertTrue(parse(duplicated) is CatalogueParse.Invalid)
    }

    @Test
    fun anUnconstructableTimezoneRejectsTheWholeDocument() {
        val broken = areasBody(listOf(RelayFixtures.se4)).replace("Europe/Stockholm", "Mars/Olympus")

        assertTrue(parse(broken) is CatalogueParse.Invalid)
    }

    @Test
    fun aMissingOrBlankRequiredFieldRejectsTheWholeDocument() {
        val good = areasBody(listOf(RelayFixtures.se4))
        for (broken in listOf(
            good.replace("\"name\":\"SE4 name\",", ""),
            good.replace("\"eic\":\"10YTEST---------\"", "\"eic\":\"  \""),
            good.replace("\"countries\":[\"SE\"]", "\"countries\":[]"),
            good.replace("\"countries\":[\"SE\"]", "\"countries\":[\"  \"]"),
            good.replace("\"tz\":\"Europe/Stockholm\"", "\"tz\":\"\"")
        )) {
            assertFixtureChanged(good, broken)
            assertTrue(parse(broken) is CatalogueParse.Invalid)
        }
    }

    @Test
    fun aWrongVersionIsNotACatalogueAtAll() {
        val good = areasBody(listOf(RelayFixtures.se4))
        for (wrong in listOf(good.replace("\"v\":2", "\"v\":1"), good.replace("\"v\":2", "\"v\":3"))) {
            assertFixtureChanged(good, wrong)
            assertTrue(parse(wrong) is CatalogueParse.Invalid)
        }
    }

    @Test
    fun unknownAdditiveFieldsAreAccepted() {
        val withExtras = areasBody(listOf(RelayFixtures.se4))
            .replace("\"v\":2", "\"v\":2,\"something_new\":{\"a\":1}")
            .replace("\"currency\":\"SEK\"", "\"currency\":\"SEK\",\"future_field\":[1,2]")

        val parsed = parse(withExtras)

        assertTrue(parsed is CatalogueParse.Ok)
        assertEquals("SE4", (parsed as CatalogueParse.Ok).catalogue.areas.single().id)
    }

    // the bundled list

    @Test
    fun theBundledListHasFiftyFourAreasAndPortugalOnTheMadridMarketClock() {
        val held = AreaCatalogue.bundledAreas(bundledSnapshot())
        assertEquals(54, held.size)
        assertTrue(held.any { it.id == "NO4" && it.vatPercent == 0.0 })
        val portugal = held.single { it.id == "PT" }
        assertEquals("Europe/Lisbon", portugal.tz)
        assertEquals("Europe/Madrid", portugal.marketTz)
    }

    @Test
    fun everyBundledAreaButTheGreatBritainRegionsAndPvpcHasAnEntsoeRoute() {
        val areas = AreaCatalogue.bundledAreas(bundledSnapshot())
        // Great Britain (Octopus Agile) and Spain's PVPC are not on ENTSO-E: Home Assistant prices them.
        val unrouted = areas.filter { it.entsoe == null }.map { it.id }
        assertEquals(15, unrouted.size)
        assertTrue(unrouted.toString(), unrouted.all { it.startsWith("GB-") || it == "ES-PVPC" })
        assertEquals(39, areas.count { it.entsoe != null })
        assertEquals(EntsoeRoute("10Y1001A1001A47J"), areas.single { it.id == "SE4" }.entsoe)
        assertEquals(
            EntsoeRoute("10Y1001A1001A82H", "1", listOf("1", "2")),
            areas.single { it.id == "DE-LU" }.entsoe
        )
    }

    @Test
    fun anUnreadableRouteLeavesTheAreaListedWithoutOne() {
        val body = JSONObject(bundledSnapshot())
        val first = body.getJSONArray("areas").getJSONObject(0)
        val id = first.getString("id")
        first.getJSONObject("routes").getJSONObject("entsoe").put("eic", "not an eic")
        val second = body.getJSONArray("areas").getJSONObject(1)
        second.getJSONObject("routes").getJSONObject("entsoe").put("expected_sequences", "1")

        val areas = AreaCatalogue.bundledAreas(body.toString())

        assertEquals(54, areas.size)
        assertNull(areas.single { it.id == id }.entsoe)
        assertNull(areas[1].entsoe)
        assertNotNull(areas[2].entsoe)
    }

    @Test
    fun aMissingOrInvalidBundledListIsNoAreasAndIsReportedThroughTheSink() {
        val messages = mutableListOf<String>()
        val sink = RelayLog { level, message -> messages.add("$level $message") }

        assertTrue(AreaCatalogue.bundledAreas(null, sink).isEmpty())
        assertTrue(AreaCatalogue.bundledAreas("{\"v\":99}", sink).isEmpty())

        assertEquals(2, messages.size)
        assertTrue(messages.last().contains("invalid"))
    }

    // selection: defaults, ordering, grouping, and a retired id

    private val realCatalogue: List<PriceMarket> by lazy {
        (parse(bundledSnapshot()) as CatalogueParse.Ok).catalogue.areas.map(PriceMarket::of)
    }

    @Test
    fun theRegionalDefaultIsDeterministicFromTheRealSnapshot() {
        assertEquals("SE4", AreaSelection.defaultArea(realCatalogue, "SE"))
        assertEquals("NO1", AreaSelection.defaultArea(realCatalogue, "NO"))
        assertEquals("DK1", AreaSelection.defaultArea(realCatalogue, "DK"))
        assertEquals("FI", AreaSelection.defaultArea(realCatalogue, "FI"))
        // An unknown region with no matching area falls back to SE4, and then to
        // the first entry -- never to an id outside the catalogue.
        assertEquals("SE4", AreaSelection.defaultArea(realCatalogue, "ZZ"))
        val withoutSe4 = realCatalogue.filterNot { it.id == "SE4" }
        assertEquals("SE1", AreaSelection.defaultArea(withoutSe4, "SE"))
        assertEquals(withoutSe4.first().id, AreaSelection.defaultArea(withoutSe4, "ZZ"))
        assertNull(AreaSelection.defaultArea(emptyList(), "SE"))
    }

    @Test
    fun thePickerPutsTheLocalRegionFirstAndIsStable() {
        val first = AreaSelection.orderedForPicker(realCatalogue, "NO")
        val second = AreaSelection.orderedForPicker(realCatalogue, "NO")

        assertEquals(first, second)
        assertTrue(first.take(5).all { it.covers("NO") })
        assertEquals(realCatalogue.size, first.size)
    }

    @Test
    fun aMultiCountryAreaAppearsOnceAndUsesTheLocalHeading() {
        val deLu = RelayFixtures.area(
            "DE-LU", "EUR", "€", "cent", tz = "Europe/Berlin", countries = listOf("DE", "LU")
        )
        val areas = listOf(deLu, RelayFixtures.se4)

        val byLuxembourg = AreaSelection.grouped(areas, "LU")
        val bySweden = AreaSelection.grouped(areas, "SE")

        assertEquals(1, byLuxembourg.sumOf { group -> group.second.count { it.id == "DE-LU" } })
        assertEquals("LU", byLuxembourg.first().first)
        assertEquals("SE", bySweden.first().first)
    }

    @Test
    fun aCountryLabelIsNeverBlankAndFallsBackToTheCode() {
        // A runtime's own country names are used when it has them, and the uppercase ISO code when
        // it does not.
        for (code in listOf("SE", "NO", "FI", "DK")) {
            assertTrue(code, AreaSelection.countryLabel(code, java.util.Locale.ENGLISH).isNotBlank())
        }
        // A code the runtime genuinely does not know comes back as the code
        // itself, in any language -- the fallback this function exists for.
        // Deliberately not "ZZ": the JDK reserves that code for "unknown region"
        // and returns a *localised* name for it, which is a fact about the JDK
        // rather than about this function.
        assertEquals("XY", AreaSelection.countryLabel("xy", java.util.Locale.ENGLISH))
        assertEquals("XY", AreaSelection.countryLabel("XY", java.util.Locale.forLanguageTag("fi")))
    }

    @Test
    fun aRetiredIdIsNeverResolvedToAnotherArea() {
        val withoutNo4 = realCatalogue.filterNot { it.id == "NO4" }

        assertNull(withoutNo4.firstOrNull { it.id == "NO4" })
        assertEquals(AreaSelectionState.Missing("NO4"), SettingsAreaController.state("NO4", withoutNo4))
        assertFalse(SettingsAreaController.canSave(SettingsAreaController.state("NO4", withoutNo4)))
    }
}
