package se.sensnology.spotnav.prices

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.sensnology.spotnav.app.DistanceUnit
import se.sensnology.spotnav.app.MoneyText
import se.sensnology.spotnav.ha.settings.HaAreaOverride
import se.sensnology.spotnav.ha.settings.HaFiscalValue
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.HaSettingsDriver
import se.sensnology.spotnav.ha.settings.HaSettingsStrategy
import se.sensnology.spotnav.ha.settings.HaTargetIntent
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.testing.RelayV2Fixtures
import se.sensnology.spotnav.ui.settings.AreaSelectionState
import se.sensnology.spotnav.ui.settings.FiscalLine
import se.sensnology.spotnav.ui.settings.PriceOverview
import se.sensnology.spotnav.ui.settings.SettingsAreaController
import se.sensnology.spotnav.widget.WidgetSettings
import java.util.Locale

/**
 * Great Britain in an unpaired app: the included parts locked and added nowhere, money and range as a
 * British reader writes them, the picker's heading, and "Find my region" (Octopus mocked).
 */
class GreatBritainDisplayTest {
    @Before
    fun catalogue() {
        PriceMarkets.replace(RelayV2Fixtures.areas(), RelayContractVersion.V2)
    }

    @After
    fun clear() {
        PriceMarkets.replace(emptyList())
    }

    // included parts

    @Test
    fun anAllInPriceHasNothingAddedWhateverTheBoxesSaid() {
        val chosen = WidgetSettings(area = "GB-C", vat = true, tax = true, transfer = true, taxMinorUnit = 5.0, gridFeeMinorUnit = 3.0)
        assertEquals(12.5, chosen.apply(0.125), 1e-12)
        val inputs = LocalPlanningInputs.of(chosen)
        assertEquals(listOf(false, false, false), listOf(inputs.vat.enabled, inputs.tax.enabled, inputs.transfer.enabled))
        assertEquals(12.5, inputs.apply(0.125), 1e-12)
        // The person's own flags are kept, so an area that includes nothing still gets them.
        val sweden = chosen.copy(area = "SE4")
        assertEquals((12.5 + 5.0 + 3.0) * 1.25, sweden.apply(0.125), 1e-12)
        assertEquals((12.5 + 5.0 + 3.0) * 1.25, LocalPlanningInputs.of(sweden).apply(0.125), 1e-12)
    }

    @Test
    fun aPairedOverviewSaysIncludedInThePrice() {
        val record = HaPlanningSettings(
            revision = 1, areaId = "GB-C",
            overrides = listOf(HaAreaOverride("GB-C", HaFiscalValue(true, 20.0), HaFiscalValue(true, 1.0), HaFiscalValue.OFF)),
            phases = 3, amps = 16, requestedKwh = 10.0, maxPeriods = 1, departureEnabled = false, departureTime = "07:00",
            strategy = HaSettingsStrategy.CHEAPEST, driver = HaSettingsDriver.MANUAL_KWH, target = HaTargetIntent.EMPTY
        )
        val overview = PriceOverview.of(record) { PriceMarkets.find(it) }
        assertEquals("GB C – London", overview.area)
        assertEquals(listOf(FiscalLine.Included, FiscalLine.Included, FiscalLine.Included), listOf(overview.vat, overview.tax, overview.transfer))
        val sweden = PriceOverview.of(record.copy(areaId = "SE4")) { PriceMarkets.find(it) }
        assertEquals(FiscalLine.Off, sweden.vat)
    }

    // money and range

    @Test
    fun poundsAreWrittenAsTheLocaleWritesMoneyAndEveryOtherUnitAsBefore() {
        assertEquals("£1.33", MoneyText.amount(1.3333, "GBP", "£", Locale.UK))
        assertEquals("£1.33", MoneyText.amount(1.3333, "GBP", "£", Locale.US))
        val swedish = MoneyText.amount(1.3333, "GBP", "£", Locale.forLanguageTag("sv-SE"))
        assertTrue(swedish, swedish.startsWith("1,33") && swedish.endsWith("£"))
        assertEquals("34.60 kr", MoneyText.amount(34.6, "SEK", "kr", Locale.US))
        assertEquals("34,60 kr", MoneyText.amount(34.6, "SEK", "kr", Locale.forLanguageTag("sv-SE")))
        assertEquals("2.50 €", MoneyText.amount(2.5, "EUR", "€", Locale.US))
    }

    @Test
    fun theRangeIsInMilesInGreatBritainWhateverTheLanguage() {
        // 10 mil is 100 km, 62 miles.
        assertEquals("62 mi", DistanceUnit.text(10.0, "sv", Locale.forLanguageTag("sv-SE"), miles = true))
        assertEquals("62 mi", DistanceUnit.text(10.0, "en", Locale.UK, miles = true))
        assertEquals("10,0 mil", DistanceUnit.text(10.0, "sv", Locale.forLanguageTag("sv-SE")))
        assertEquals("100 km", DistanceUnit.text(10.0, "en", Locale.UK))
        assertTrue(PriceMarkets.find("GB-C")!!.inGreatBritain)
        assertFalse(PriceMarkets.find("PT")!!.inGreatBritain)
    }

    // the picker

    @Test
    fun greatBritainsRegionsAreListedUnderTheirOwnHeading() {
        val rows = SettingsAreaController.rows(
            PriceMarkets.all, "GB", AreaSelectionState.Available("GB-C"),
            countryLabel = { if (it == "GB") "Great Britain" else AreaSelection.countryLabel(it, Locale.UK) },
            unavailableLabel = { it }
        )
        assertEquals("Great Britain", rows[0].label)
        assertNull(rows[0].id)
        assertEquals("GB C – London", rows[1].label)
        assertEquals("GB-C", rows[1].id)
    }

    // find my region

    @Test
    fun aPostcodeIsNormalisedOrRefusedBeforeAnyRequest() {
        assertEquals("SW1A 1AA", GreatBritainRegion.normalized(" sw1a1aa "))
        assertEquals("EC1A 1BB", GreatBritainRegion.normalized("EC1A 1BB"))
        assertEquals("M1 1AE", GreatBritainRegion.normalized("m11ae"))
        assertNull(GreatBritainRegion.normalized("12345"))
        assertNull(GreatBritainRegion.normalized("SW1A"))
        var asked = 0
        val answer = GreatBritainRegion.find("not a postcode", PriceMarkets.all) { asked++; null }
        assertEquals(GreatBritainRegion.Answer.Invalid, answer)
        assertEquals(0, asked)
    }

    @Test
    fun aFoundGroupSelectsTheCatalogueRegionOfThatLetter() {
        var url: String? = null
        val answer = GreatBritainRegion.find("sw1a 1aa", PriceMarkets.all) {
            url = it
            """{"count":1,"next":null,"previous":null,"results":[{"group_id":"_C"}]}"""
        }
        assertEquals(GreatBritainRegion.Answer.Found(listOf("GB-C")), answer)
        assertEquals("https://api.octopus.energy/v1/industry/grid-supply-points/?postcode=SW1A1AA", url)
        // Octopus's API, never the relay.
        assertFalse(url!!.contains("sensnology"))
    }

    @Test
    fun aGroupTheCatalogueDoesNotListIsNotFoundAndAFailureIsUnavailable() {
        assertEquals(
            GreatBritainRegion.Answer.NotFound,
            GreatBritainRegion.find("M1 1AE", PriceMarkets.all) { """{"results":[{"group_id":"_G"}]}""" }
        )
        assertEquals(
            GreatBritainRegion.Answer.NotFound,
            GreatBritainRegion.find("M1 1AE", PriceMarkets.all) { """{"results":[]}""" }
        )
        assertEquals(GreatBritainRegion.Answer.Unavailable, GreatBritainRegion.find("M1 1AE", PriceMarkets.all) { null })
        assertEquals(GreatBritainRegion.Answer.Unavailable, GreatBritainRegion.find("M1 1AE", PriceMarkets.all) { "<html>" })
        assertEquals(GreatBritainRegion.Answer.Unavailable, GreatBritainRegion.find("M1 1AE", PriceMarkets.all) { "{}" })
        // Two groups, each once, in Octopus's order; an I or an O is no group.
        assertEquals(
            listOf("GB-C", "GB-A"),
            GreatBritainRegion.regionsFrom("""{"results":[{"group_id":"_C"},{"group_id":"_A"},{"group_id":"_C"},{"group_id":"_I"},{"group_id":"_O"}]}""")
        )
    }

    @Test
    fun theLookupIsOfferedOnlyWhereGreatBritainIsAChoice() {
        val gb = PriceMarkets.find("GB-C")
        val se4 = PriceMarkets.find("SE4")
        assertTrue(GreatBritainRegion.offered(PriceMarkets.all, gb, "SE"))
        assertTrue(GreatBritainRegion.offered(PriceMarkets.all, se4, "GB"))
        assertFalse(GreatBritainRegion.offered(PriceMarkets.all, se4, "SE"))
        assertFalse(GreatBritainRegion.offered(PriceMarkets.all.filterNot { it.inGreatBritain }, se4, "GB"))
    }
}
