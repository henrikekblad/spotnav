package se.sensnology.spotnav.chart

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.authority.HaPlanningAdapter
import se.sensnology.spotnav.ha.authority.HaPlanningInputs
import se.sensnology.spotnav.ha.authority.HaPresentation
import se.sensnology.spotnav.ha.settings.HaAreaOverrideComponent
import se.sensnology.spotnav.ha.settings.HaFiscalValue
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.planning.FiscalInput
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.SettingsFixtures
import se.sensnology.spotnav.widget.WidgetSettings

/** The chart-only market model: what a price graph knows about a market. */
class ChartMarketTest {
    private val catalogue = listOf(RelayFixtures.se4, RelayFixtures.no1)
    private val se4 = RelayFixtures.se4
    private val local = WidgetSettings(area = "NO1", vat = true, tax = true, taxMinorUnit = 8.0, transfer = true, gridFeeMinorUnit = 5.0)

    private fun override(areaId: String, vat: Double? = null, tax: Double? = null, transfer: Double? = null) =
        SettingsFixtures.override(
            areaId = areaId,
            vat = SettingsFixtures.fiscal(enabled = vat != null, value = vat),
            tax = SettingsFixtures.fiscal(enabled = tax != null, value = tax),
            transfer = SettingsFixtures.fiscal(enabled = transfer != null, value = transfer)
        )

    /** The one place a test unwraps a built market, so a failure says what was built instead. */
    private fun ready(build: ChartMarketBuild): ChartMarket {
        assertTrue("expected a market, got $build", build is ChartMarketBuild.Ready)
        return (build as ChartMarketBuild.Ready).market
    }

    @Test
    fun theLocalAdapterIsTheSameMarketAndTheSameArithmetic() {
        val inputs = LocalPlanningInputs.of(local)
        val market = ChartMarket.of(inputs)

        assertEquals(inputs.areaId, market.areaId)
        assertEquals(inputs.intervalMinutes, market.intervalMinutes)
        assertEquals(inputs.intervalMinutes, market.aggregationMinutes)
        assertEquals(inputs.hourly, market.hourly)
        assertEquals(inputs.vat, market.vat)
        assertEquals(inputs.tax, market.tax)
        assertEquals(inputs.transfer, market.transfer)
        listOf(0.0, 0.25, 1.0, 2.5, 100.0).forEach { price ->
            assertEquals(price.toString(), inputs.apply(price), market.apply(price), 1e-9)
        }
    }

    // A paired record feeds a chart-only market; it never becomes local plan input.
    @Test
    fun aPairedRecordBuildsItsChartMarketWithoutProducingLocalPlanningInputs() {
        val record = SettingsFixtures.parsed(
            revision = 5,
            areaId = "SE4",
            overrides = JSONArray().put(override("SE4", vat = 12.0, tax = 0.0, transfer = 5.5))
        )
        val built = ChartMarket.from(record, areaId = "SE4", intervalMinutes = 60, market = catalogue.first { it.id == "SE4" })
        assertTrue("expected a market, got $built", built is ChartMarketBuild.Ready)
        val market = (built as ChartMarketBuild.Ready).market

        assertEquals("SE4", market.areaId)
        assertTrue(market.hourly)
        assertEquals(60, market.aggregationMinutes)
        assertFalse(local.area == market.areaId)

        assertTrue(
            "paired settings have no local planning inputs",
            !PlanningInputs::class.java.isAssignableFrom(
                HaPlanningAdapter.of(record, catalogue, HaPresentation(60)).javaClass
            )
        )
        assertEquals(HaPlanningInputs.Auto(record), HaPlanningAdapter.of(record, catalogue, HaPresentation(60)))
    }

    @Test
    fun pairedMarketKeepsQuarterHourAndHourlyPresentationIndependentOfSettings() {
        val record = SettingsFixtures.parsed(revision = 6, areaId = "SE4")

        val quarter = ready(ChartMarket.from(record, "SE4", 15, se4))
        val hourly = ready(ChartMarket.from(record, "SE4", 60, se4))

        assertEquals(15, quarter.intervalMinutes)
        assertFalse(quarter.hourly)
        assertEquals(15, quarter.aggregationMinutes)
        assertEquals(60, hourly.intervalMinutes)
        assertTrue(hourly.hourly)
        assertEquals(60, hourly.aggregationMinutes)
        assertEquals(quarter.vat, hourly.vat)
        assertEquals(quarter.tax, hourly.tax)
        assertEquals(quarter.transfer, hourly.transfer)
        assertEquals(quarter.apply(1.0), hourly.apply(1.0), 0.0)
    }

    @Test
    fun suggestedAbsentZeroAndDecimalFiscalFiguresStayDistinct() {
        // An explicit zero is a figure: it is drawn as one.
        val zero = SettingsFixtures.parsed(
            revision = 1, areaId = "SE4", overrides = JSONArray().put(override("SE4", tax = 0.0))
        )
        val zeroMarket = ready(ChartMarket.from(zero, "SE4", 15, se4))
        assertEquals(HaFiscalValue(enabled = true, value = 0.0), HaFiscalValue(true, zeroMarket.tax.overrideValue))
        assertEquals(0.0, zeroMarket.tax.effectiveValue!!, 0.0)

        val suggested = SettingsFixtures.parsed(
            revision = 1,
            areaId = "SE4",
            overrides = JSONArray().put(
                SettingsFixtures.override(
                    areaId = "SE4", vat = SettingsFixtures.fiscal(enabled = true, value = null)
                )
            )
        )
        val suggestedMarket = ready(ChartMarket.from(suggested, "SE4", 15, se4))
        assertEquals(null, suggestedMarket.vat.overrideValue)
        assertEquals(se4.vatPercent, suggestedMarket.vat.effectiveValue)

        // A decimal stays a decimal, to the digit the charger stated.
        val decimal = SettingsFixtures.parsed(
            revision = 1, areaId = "SE4", overrides = JSONArray().put(override("SE4", transfer = 5.5))
        )
        assertEquals(5.5, ready(ChartMarket.from(decimal, "SE4", 15, se4)).transfer.effectiveValue!!, 0.0)
    }

    @Test
    fun anEnabledComponentWithNoFigureAndNoSuggestionMakesNoMarket() {
        fun recordWith(
            vat: Any? = "off",
            tax: Any? = "off",
            transfer: Any? = "off"
        ): HaPlanningSettings = SettingsFixtures.parsed(
            revision = 1,
            areaId = "SE4",
            overrides = JSONArray().put(
                SettingsFixtures.override(
                    areaId = "SE4",
                    vat = when (vat) {
                        "off" -> SettingsFixtures.fiscal(enabled = false, value = null)
                        "none" -> SettingsFixtures.fiscal(enabled = true, value = null)
                        else -> SettingsFixtures.fiscal(enabled = true, value = vat)
                    },
                    tax = when (tax) {
                        "off" -> SettingsFixtures.fiscal(enabled = false, value = null)
                        "none" -> SettingsFixtures.fiscal(enabled = true, value = null)
                        else -> SettingsFixtures.fiscal(enabled = true, value = tax)
                    },
                    transfer = when (transfer) {
                        "off" -> SettingsFixtures.fiscal(enabled = false, value = null)
                        "none" -> SettingsFixtures.fiscal(enabled = true, value = null)
                        else -> SettingsFixtures.fiscal(enabled = true, value = transfer)
                    }
                )
            )
        )

        assertEquals(
            ChartMarketBuild.Incomplete(listOf(HaAreaOverrideComponent.VAT)),
            ChartMarket.from(recordWith(vat = "none"), "SE4", 15, null)
        )
        assertEquals(
            ChartMarketBuild.Incomplete(listOf(HaAreaOverrideComponent.TAX)),
            ChartMarket.from(recordWith(tax = "none"), "SE4", 15, null)
        )
        assertEquals(
            ChartMarketBuild.Incomplete(listOf(HaAreaOverrideComponent.TRANSFER)),
            ChartMarket.from(recordWith(transfer = "none"), "SE4", 15, null)
        )
        assertEquals(
            "several unresolved components are all named, in a stable order",
            ChartMarketBuild.Incomplete(
                listOf(HaAreaOverrideComponent.VAT, HaAreaOverrideComponent.TAX, HaAreaOverrideComponent.TRANSFER)
            ),
            ChartMarket.from(recordWith(vat = "none", tax = "none", transfer = "none"), "SE4", 15, null)
        )
        assertEquals(
            listOf(HaAreaOverrideComponent.TAX, HaAreaOverrideComponent.TRANSFER),
            (ChartMarket.from(recordWith(tax = "none", transfer = "none"), "SE4", 15, null) as ChartMarketBuild.Incomplete).unresolved
        )

        assertTrue(ChartMarket.from(recordWith(vat = "none"), "SE4", 15, se4) is ChartMarketBuild.Ready)
        assertTrue(ChartMarket.from(recordWith(vat = "none"), "SE4", 15, se4.copy(vatPercent = null)) is ChartMarketBuild.Incomplete)
        assertTrue(ChartMarket.from(recordWith(vat = 0.0), "SE4", 15, null) is ChartMarketBuild.Ready)
        assertTrue(ChartMarket.from(recordWith(vat = 12.5), "SE4", 15, null) is ChartMarketBuild.Ready)
        // And a disabled component is valid however little it says.
        assertTrue(ChartMarket.from(recordWith(), "SE4", 15, null) is ChartMarketBuild.Ready)
    }

    @Test
    fun vatIsAppliedLast() {
        val market = ChartMarket(
            areaId = "SE4",
            intervalMinutes = 15,
            vat = FiscalInput(enabled = true, overrideValue = 25.0, effectiveValue = 25.0),
            tax = FiscalInput(enabled = true, overrideValue = 10.0, effectiveValue = 10.0),
            transfer = FiscalInput(enabled = true, overrideValue = 10.0, effectiveValue = 10.0)
        )
        assertEquals(150.0, market.apply(1.0), 1e-9)
        assertFalse("the model is not a plan", market.toString().contains("amps"))
    }

    @Test
    fun aMarketWithoutAnAreaOrAKnownResolutionIsNotAValue() {
        assertThrows(IllegalArgumentException::class.java) {
            ChartMarket("", 15, FiscalInput.OFF, FiscalInput.OFF, FiscalInput.OFF)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChartMarket("SE4", 30, FiscalInput.OFF, FiscalInput.OFF, FiscalInput.OFF)
        }
    }
}
