package se.sensnology.spotnav.prices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.ui.settings.AreaSelectionState
import se.sensnology.spotnav.ui.settings.SettingsAreaController

/**
 * The two zones the relay publishes last -- the Netherlands and Germany-Luxembourg -- seen from the
 * client's side.
 */
class NewZonesCompatibilityTest {
    private val netherlands = PriceMarket(
        id = "NL",
        countries = listOf("NL"),
        name = "Netherlands",
        tz = "Europe/Amsterdam",
        currency = "EUR",
        majorUnit = "\u20ac",
        minorUnit = "cent"
    )

    private val germanyLuxembourg = PriceMarket(
        id = "DE-LU",
        countries = listOf("DE", "LU"),
        name = "Germany\u2013Luxembourg",
        tz = "Europe/Berlin",
        currency = "EUR",
        majorUnit = "\u20ac",
        minorUnit = "cent"
    )

    private fun parse(body: String) = RelayAreasParser.parse(body)

    @Test
    fun aZoneThatSpansTwoCountriesKeepsBothOfThem() {
        val parsed = parse(RelayFixtures.areasBody(listOf(germanyLuxembourg)))
        val area = (parsed as CatalogueParse.Ok).catalogue.areas.single()
        assertEquals("DE-LU", area.id)
        assertEquals(listOf("DE", "LU"), area.countries)
        assertEquals("Europe/Berlin", area.tz)
        // No fiscal figure is published for it, and absent is not zero: the picker offers no
        // suggestion and the reader types their own.
        assertNull(area.vatPercent)
        assertNull(area.suggestedTax)
        assertNull(area.suggestedGridFee)
    }

    @Test
    fun theEuroLabelsComeFromTheCatalogueAndNotFromTheCurrency() {
        // What the screens print is the unit the *number* is in, and it travels with the area: an
        // applied price is in the minor unit, a plan total in the major one.
        assertEquals("cent/kWh", netherlands.appliedPriceUnit)
        assertEquals("cent/kWh", germanyLuxembourg.appliedPriceUnit)
        assertEquals("\u20ac", netherlands.majorUnit)

        // The proof that nothing derives them from the ISO code: a euro zone whose labels said
        // something else prints what it was told.
        val unusual = PriceMarket(
            id = "XX", countries = listOf("XX"), name = "Unusual", tz = "Europe/Oslo",
            currency = "EUR", majorUnit = "kr", minorUnit = "\u00f8re"
        )
        assertEquals("kr", unusual.majorUnit)
        assertEquals("\u00f8re/kWh", unusual.appliedPriceUnit)
    }

    @Test
    fun aCatalogueLargerThanTheBundledSnapshotIsAccepted() {
        // The snapshot this build ships has twelve zones.
        val known = listOf(
            RelayFixtures.se4, RelayFixtures.no1, RelayFixtures.no4, RelayFixtures.fi,
            RelayFixtures.dk1, netherlands, germanyLuxembourg
        )
        // Seven fixtures above, so eleven more make the eighteen the relay publishes.
        val grown = known + (1..11).map { index ->
            PriceMarket(
                id = "X$index", countries = listOf("X$index"), name = "Zone $index",
                tz = "Europe/Oslo", currency = "EUR", majorUnit = "\u20ac", minorUnit = "cent"
            )
        }
        assertEquals(18, grown.size)

        val parsed = parse(RelayFixtures.areasBody(grown))
        val areas = (parsed as CatalogueParse.Ok).catalogue.areas
        assertEquals(18, areas.size)
        // Every one of them is addressable, including the two that joined last.
        assertEquals("NL", areas.single { it.id == "NL" }.id)
        assertEquals("DE-LU", areas.single { it.id == "DE-LU" }.id)
    }

    /**
     * What this checks is **selectability**: both zones are offerable, both pass the save gate, and
     * a zone that is not in the catalogue is not savable at all.
     */
    @Test
    fun theNewZonesAreOfferedAndPassTheSaveGate() {
        val areas = listOf(netherlands, germanyLuxembourg)
        for (id in listOf("NL", "DE-LU")) {
            assertEquals(AreaSelectionState.Available(id), SettingsAreaController.state(id, areas))
            assertTrue(SettingsAreaController.canSave(AreaSelectionState.Available(id)))
        }
        // A zone that is not in the catalogue is not selectable: a choice that cannot be priced
        // must not be savable.
        assertEquals(
            AreaSelectionState.Missing("EE"),
            SettingsAreaController.state("EE", areas)
        )
    }
}
