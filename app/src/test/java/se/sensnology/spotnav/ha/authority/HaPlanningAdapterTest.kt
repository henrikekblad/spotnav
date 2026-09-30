package se.sensnology.spotnav.ha.authority

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.settings.HaFiscalValue
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.planning.FiscalInput
import se.sensnology.spotnav.planning.FiscalResolution
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.SettingsFixtures

/** The paired Home Assistant boundary: it is authoritative, never local planning input. */
class HaPlanningAdapterTest {
    private val catalogue = listOf(RelayFixtures.se4, RelayFixtures.no1)

    private fun record(
        areaId: Any? = "SE4",
        overrides: List<org.json.JSONObject> = emptyList(),
        phases: Any? = 1,
        amps: Any? = 10,
        requestedKwh: Any? = 20.5,
        departureTime: Any? = "06:45",
        driver: Any? = "manual_kwh"
    ): HaPlanningSettings = SettingsFixtures.parsed(
        revision = 4,
        areaId = areaId,
        overrides = JSONArray().apply { overrides.forEach(::put) },
        phases = phases,
        amps = amps,
        requestedKwh = requestedKwh,
        departureTime = departureTime,
        driver = driver
    )

    @Test
    fun everyPairedRecordIsReturnedWholeAsAutoAndNeverAsLocalPlanningInputs() {
        val complete = record()
        val sparse = record(areaId = null, phases = null, amps = null, requestedKwh = 20.5)

        listOf(complete, sparse).forEach { paired ->
            val result = HaPlanningAdapter.of(paired, catalogue, HaPresentation.QUARTER_HOUR)
            assertEquals(HaPlanningInputs.Auto(paired), result)
            assertTrue(
                "paired adaptation must not produce local calculation inputs",
                !PlanningInputs::class.java.isAssignableFrom(result.javaClass)
            )
            assertSame("the authoritative settings value travels whole", paired, (result as HaPlanningInputs.Auto).record)
        }
    }

    @Test
    fun catalogueAndPresentationCannotTurnPairedAutoIntoLocalPlanningInputs() {
        val paired = record()

        val quarter = HaPlanningAdapter.of(paired, catalogue, HaPresentation.QUARTER_HOUR)
        val hourly = HaPlanningAdapter.of(paired, emptyList(), HaPresentation.HOURLY)
        val unsupportedPresentation = HaPlanningAdapter.of(paired, catalogue, HaPresentation(30))

        assertEquals(HaPlanningInputs.Auto(paired), quarter)
        assertEquals(HaPlanningInputs.Auto(paired), hourly)
        assertEquals(HaPlanningInputs.Auto(paired), unsupportedPresentation)
        assertTrue(!PlanningInputs::class.java.isAssignableFrom(hourly.javaClass))
        assertTrue(!PlanningInputs::class.java.isAssignableFrom(unsupportedPresentation.javaClass))
    }

    @Test
    fun resolverPreservesOffSuggestedCustomZeroAndMissingAsDistinctStates() {
        val off = FiscalResolution.component(HaFiscalValue.OFF, suggestion = 25.0)
        val suggested = FiscalResolution.component(HaFiscalValue(enabled = true, value = null), suggestion = 25.0)
        val customZero = FiscalResolution.component(HaFiscalValue(enabled = true, value = 0.0), suggestion = 25.0)
        val customDecimal = FiscalResolution.component(HaFiscalValue(enabled = true, value = 7.13), suggestion = 25.0)
        val missing = FiscalResolution.component(HaFiscalValue(enabled = true, value = null), suggestion = null)

        assertEquals(FiscalInput.OFF, off.input)
        assertEquals(false, off.unresolved)
        assertEquals(FiscalInput.suggested(25.0), suggested.input)
        assertEquals(null, suggested.input.overrideValue)
        assertEquals(FiscalInput(enabled = true, overrideValue = 0.0, effectiveValue = 0.0), customZero.input)
        assertEquals(FiscalInput(enabled = true, overrideValue = 7.13, effectiveValue = 7.13), customDecimal.input)
        assertEquals(FiscalInput.OFF, missing.input)
        assertEquals(true, missing.unresolved)
    }

    @Test
    fun onlyTheNamedAreaOverrideAndItsSuggestionResolveTheFiscalValues() {
        val se4 = RelayFixtures.se4
        val paired = record(
            areaId = "SE4",
            overrides = listOf(
                SettingsFixtures.override(
                    areaId = "NO1",
                    vat = SettingsFixtures.fiscal(enabled = true, value = 12.0),
                    tax = SettingsFixtures.fiscal(enabled = true, value = 7.13)
                ),
                SettingsFixtures.override(
                    areaId = "SE4",
                    vat = SettingsFixtures.fiscal(enabled = true, value = null),
                    tax = SettingsFixtures.fiscal(enabled = true, value = 0.0)
                )
            )
        )

        val resolved = FiscalResolution.forArea(paired, "SE4", se4)

        assertEquals(FiscalInput.suggested(se4.vatPercent), resolved.vat.input)
        assertEquals(FiscalInput(enabled = true, overrideValue = 0.0, effectiveValue = 0.0), resolved.tax.input)
        assertEquals(FiscalInput.OFF, resolved.transfer.input)
        assertTrue(resolved.isComplete)
    }
}
