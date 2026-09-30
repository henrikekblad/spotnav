package se.sensnology.spotnav.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import se.sensnology.spotnav.planning.PlanDriver
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.testing.RelayFixtures

class WidgetSettingsTest {
    @org.junit.Before
    fun loadCatalogue() {
        PriceMarkets.replace(listOf(RelayFixtures.se4, RelayFixtures.fi, RelayFixtures.no4))
    }

    @Test fun appliesTaxGridFeeThenVat() {
        val settings = WidgetSettings(area = "SE4", vat = true, tax = true, transfer = true,
            taxMinorUnit = 36.0, gridFeeMinorUnit = 30.0)
        assertEquals((100.0 + 36.0 + 30.0) * 1.25, settings.apply(1.0), 0.0001)
    }

    @Test fun anAreaWithNoVatFigureAddsNothingRatherThanInventingARate() {
        PriceMarkets.replace(listOf(RelayFixtures.se4.copy(vatPercent = null)))

        val settings = WidgetSettings(area = "SE4", vat = true)

        assertEquals(100.0, settings.apply(1.0), 0.0001)
    }

    @Test fun anUnknownAreaAddsNothingAndBorrowsNothing() {
        val settings = WidgetSettings(area = "RETIRED", vat = true, tax = true, transfer = true)

        assertEquals(0.0, settings.effectiveVatPercent, 0.0001)
        assertEquals(100.0, settings.apply(1.0), 0.0001)
    }

    @Test fun usesVatForSelectedMarket() {
        val finnish = WidgetSettings(area = "FI", vat = true)
        val northernNorway = WidgetSettings(area = "NO4", vat = true)
        assertEquals(125.5, finnish.apply(1.0), 0.0001)
        assertEquals(100.0, northernNorway.apply(1.0), 0.0001)
    }


    @Test fun defaultsToKwhDrivingThePlan() {
        assertEquals(PlanDriver.KWH, WidgetSettings().driver)
    }

    @Test fun roundTripsTheDriverThroughItsStoredForm() {
        for (driver in PlanDriver.entries) {
            val settings = WidgetSettings(driver = driver)

            assertEquals(driver, PlanDriver.of(PlanDriver.storedForm(settings.driver)))
        }
    }

    @Test fun anInstallationWithoutAStoredDriverReadsAsKwh() {
        assertEquals(PlanDriver.KWH, PlanDriver.of(null))
    }

    @Test fun aNewWidgetGetsItsDefaultsStoredOnce() {
        val stored = mutableListOf<WidgetSettings>()
        val defaults = WidgetSettings(area = "SE3")

        assertEquals(true, WidgetSettings.seedDefaults(false, { defaults }, { stored += it }))
        assertEquals(listOf(defaults), stored)
    }

    @Test fun aSavedWidgetIsNeverOverwrittenBySeeding() {
        var persisted = false
        var read = false

        val seeded = WidgetSettings.seedDefaults(true, { read = true; WidgetSettings() }, { persisted = true })

        assertEquals(false, seeded)
        assertEquals(false, persisted)
        assertEquals(false, read)
    }

    @Test fun defaultsWithoutAnAreaAreNotStored() {
        var persisted = false

        assertEquals(false, WidgetSettings.seedDefaults(false, { WidgetSettings(area = "") }, { persisted = true }))
        assertEquals(false, persisted)
    }
}
