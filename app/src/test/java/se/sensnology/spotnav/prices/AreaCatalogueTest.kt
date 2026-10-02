package se.sensnology.spotnav.prices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.app.RelayLog
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.ui.settings.AreaSelectionState
import se.sensnology.spotnav.ui.settings.SettingsAreaController
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AreaCatalogueTest {
    private val store = FakeKeyValueStore()

    private fun areasBody(areas: List<PriceMarket>) = RelayFixtures.areasBody(areas)

    /** The bundled snapshot as the app ships it, read from the source tree. */
    private fun bundledSnapshot(): String {
        val file = File("src/main/assets/${AreaCatalogue.SNAPSHOT_ASSET}")
        assertTrue("the bundled snapshot must exist at ${file.absolutePath}", file.isFile)
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
            good.replace("\"eic\":\"10YTEST---------\",", ""),
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
        val wrong = areasBody(listOf(RelayFixtures.se4)).replace("\"v\":1", "\"v\":2")

        assertTrue(parse(wrong) is CatalogueParse.Invalid)
    }

    @Test
    fun unknownAdditiveFieldsAreAccepted() {
        val withExtras = areasBody(listOf(RelayFixtures.se4))
            .replace("\"v\":1", "\"v\":1,\"something_new\":{\"a\":1}")
            .replace("\"currency\":\"SEK\"", "\"currency\":\"SEK\",\"future_field\":[1,2]")

        val parsed = parse(withExtras)

        assertTrue(parsed is CatalogueParse.Ok)
        assertEquals("SE4", (parsed as CatalogueParse.Ok).catalogue.areas.single().id)
    }

    // storage, priority, and what a failure leaves behind

    @Test
    fun theBundledSnapshotIsUsedOnlyWhenNothingValidIsPersisted() {
        val snapshot = bundledSnapshot()

        // Nothing stored: the snapshot, parsed by the same parser as a fetch.
        val fresh = AreaCatalogue.startupAreas(store, { snapshot })
        assertEquals(20, fresh.size)
        assertTrue(fresh.any { it.id == "NO4" && it.vatPercent == 0.0 })

        // A persisted valid remote document wins over it.
        store.putString(AreaCatalogue.KEY_LAST_GOOD, areasBody(listOf(RelayFixtures.se4)))
        assertEquals(listOf("SE4"), AreaCatalogue.startupAreas(store, { snapshot }).map { it.id })

        store.putString(AreaCatalogue.KEY_LAST_GOOD, "{\"v\":99}")
        assertEquals(20, AreaCatalogue.startupAreas(store, { snapshot }).size)
    }

    @Test
    fun theBundledSnapshotListsTwentyAreasAndPortugalOnTheMadridClock() {
        val areas = AreaCatalogue.startupAreas(store, { bundledSnapshot() })
        assertEquals(20, areas.size)
        assertEquals("Europe/Madrid", areas.single { it.id == "PT" }.tz)
    }

    @Test
    fun aStoredDocumentThatNoLongerValidatesIsReportedThroughTheSink() {
        // The startup path reaches `lastGood`, and the sink it was given has to reach too --
        // otherwise a catalogue silently ignored by a newer build is invisible, which is the one
        // thing a log is for here.
        store.putString(AreaCatalogue.KEY_LAST_GOOD, "{\"v\":99}")
        val messages = mutableListOf<String>()
        val sink = RelayLog { level, message -> messages.add("$level $message") }

        val areas = AreaCatalogue.startupAreas(store, { bundledSnapshot() }, sink)

        assertEquals(20, areas.size)
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("no longer valid"))
    }

    @Test
    fun aRefusedRefreshLeavesTheAcceptedDocumentByteForByte() {
        val accepted = areasBody(listOf(RelayFixtures.se4))
        assertTrue(AreaCatalogue.adopt(store, accepted, 1L) is CatalogueRefresh.Updated)
        assertEquals(accepted, store.rawOrNull(AreaCatalogue.KEY_LAST_GOOD))

        // One malformed entry, and a body that is not JSON at all.
        assertTrue(AreaCatalogue.adopt(store, "{\"v\":1,\"generated\":\"x\",\"areas\":[{}]}", 2L) is CatalogueRefresh.Failed)
        assertTrue(AreaCatalogue.adopt(store, "not json", 3L) is CatalogueRefresh.Failed)

        assertEquals("a refused body must not be persisted", accepted, store.rawOrNull(AreaCatalogue.KEY_LAST_GOOD))
        assertEquals(listOf("SE4"), AreaCatalogue.lastGood(store)!!.map { it.id })
    }

    @Test
    fun anIdenticalDocumentIsUnchanged() {
        val body = areasBody(listOf(RelayFixtures.se4))
        AreaCatalogue.adopt(store, body, 1L)

        val result = AreaCatalogue.refresh(store, { body }, nowMillis = 10_000_000L, force = true)

        assertEquals(CatalogueRefresh.Unchanged, result)
        assertEquals(body, store.rawOrNull(AreaCatalogue.KEY_LAST_GOOD))
    }

    @Test
    fun aFailedFetchKeepsTheAcceptedDocument() {
        val body = areasBody(listOf(RelayFixtures.se4))
        AreaCatalogue.adopt(store, body, 1L)

        val failed = AreaCatalogue.refresh(store, { null }, nowMillis = 10_000_000L, force = true)

        assertTrue(failed is CatalogueRefresh.Failed)
        assertEquals(body, store.rawOrNull(AreaCatalogue.KEY_LAST_GOOD))
        assertEquals(listOf("SE4"), AreaCatalogue.lastGood(store)!!.map { it.id })
    }

    @Test
    fun nothingIsRefreshedBeforeItIsDue() {
        val body = areasBody(listOf(RelayFixtures.se4))
        AreaCatalogue.adopt(store, body, nowMillis = 1_000L)

        assertNull(AreaCatalogue.refresh(store, { body }, nowMillis = 1_000L + 59_000L))
        assertNotNull(AreaCatalogue.refresh(store, { body }, nowMillis = 1_000L + AreaCatalogue.FRESH_FOR_MS))
    }

    // one refresh at a time, and never a blocked local load

    @Test
    fun twoSimultaneousDueRefreshesFetchOnce() {
        val body = areasBody(listOf(RelayFixtures.se4))
        val fetches = AtomicInteger(0)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        // The first caller parks *inside* its fetch, so the second one meets a refresh that is
        // genuinely in flight rather than racing a start.
        val first = Thread {
            AreaCatalogue.refresh(store, {
                fetches.incrementAndGet()
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                body
            }, nowMillis = 1_000L, force = true)
        }
        first.start()
        assertTrue("the first fetch must start", entered.await(5, TimeUnit.SECONDS))

        val second = AreaCatalogue.refresh(store, { fetches.incrementAndGet(); body }, 1_000L, force = true)
        assertEquals(CatalogueRefresh.AlreadyRefreshing, second)

        release.countDown()
        first.join(5_000)
        assertEquals("exactly one fetch for two due callers", 1, fetches.get())
        assertEquals(listOf("SE4"), AreaCatalogue.lastGood(store)!!.map { it.id })
    }

    @Test
    fun aLocalLoadIsNotBlockedByARefreshInFlight() {
        val accepted = areasBody(listOf(RelayFixtures.se4))
        AreaCatalogue.adopt(store, accepted, 1L)

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fetchThread = Thread {
            AreaCatalogue.refresh(store, {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                accepted
            }, nowMillis = 2_000L, force = true)
        }
        fetchThread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        // The main-thread path: it takes no gate at all, so a socket eight seconds away cannot make
        // a widget wait.
        val loaded = AreaCatalogue.startupAreas(store, { bundledSnapshot() })

        assertEquals(listOf("SE4"), loaded.map { it.id })
        release.countDown()
        fetchThread.join(5_000)
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
