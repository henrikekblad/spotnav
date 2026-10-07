package se.sensnology.spotnav.ui.settings

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.authority.VisibleAuthority
import se.sensnology.spotnav.ha.settings.HaSettingsEditResult
import se.sensnology.spotnav.ha.settings.PairedSettingsForm
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.SettingsFixtures
import se.sensnology.spotnav.widget.WidgetSettings
import java.util.Locale

class ImmediateSettingsTest {
    private val stored = WidgetSettings(
        area = "SE4", vat = false, tax = false, transfer = false,
        taxMinorUnit = 10.0, gridFeeMinorUnit = 20.0, intervalMinutes = 15, showChargingPlan = false
    )

    private val draft = LocalSettingsDraft(
        area = "SE4", vat = false, tax = false, taxText = "10.0",
        transfer = false, transferText = "20.0", intervalMinutes = 15, showChargingPlan = false
    )

    @Test
    fun aDraftThatSaysWhatIsStoredChangesNothing() {
        val result = ImmediateSettings.apply(stored, draft, paired = false)
        assertEquals(stored, result.settings)
        assertTrue(result.invalid.isEmpty())
    }

    @Test
    fun eachUnpairedControlAppliesOnItsOwn() {
        val result = ImmediateSettings.apply(
            stored, draft.copy(area = "NO1", vat = true, tax = true, transfer = true, intervalMinutes = 60, showChargingPlan = true),
            paired = false
        )
        assertEquals(
            stored.copy(area = "NO1", vat = true, tax = true, transfer = true, intervalMinutes = 60, showChargingPlan = true),
            result.settings
        )
    }

    @Test
    fun aFigureUsesEitherDecimalMarkAndBlankIsZero() {
        assertEquals(ImmediateSettings.figure("36,5"), Figure.Value(36.5))
        assertEquals(ImmediateSettings.figure(" 7.13 "), Figure.Value(7.13))
        assertEquals(ImmediateSettings.figure(""), Figure.Value(0.0))
    }

    @Test
    fun anInvalidFigureIsNotAppliedButTheRestOfTheDraftIs() {
        val result = ImmediateSettings.apply(
            stored, draft.copy(vat = true, taxText = "abc", transferText = "-3"), paired = false
        )
        assertEquals(setOf(FigureField.TAX, FigureField.GRID_FEE), result.invalid)
        // The stored figures stand; the toggle still applied.
        assertEquals(stored.copy(vat = true), result.settings)
    }

    @Test
    fun aNonFiniteFigureIsInvalid() {
        assertEquals(Figure.Invalid, ImmediateSettings.figure("NaN"))
        assertEquals(Figure.Invalid, ImmediateSettings.figure("Infinity"))
    }

    @Test
    fun aBlankAreaNeverReplacesTheStoredOne() {
        assertEquals("SE4", ImmediateSettings.apply(stored, draft.copy(area = ""), paired = false).settings.area)
    }

    @Test
    fun aPairedPhoneTakesOnlyItsOwnTwoFieldsFromTheDraft() {
        val result = ImmediateSettings.apply(
            stored,
            draft.copy(area = "NO1", vat = true, taxText = "abc", intervalMinutes = 60, showChargingPlan = true),
            paired = true
        )
        assertEquals(stored.copy(intervalMinutes = 60, showChargingPlan = true), result.settings)
        assertTrue(result.invalid.isEmpty())
    }

    // --- The paired price dialog ---------------------------------------------------------------

    private val catalogue = listOf(RelayFixtures.se4, RelayFixtures.no1)
    private val record = SettingsFixtures.parsed(
        revision = 5,
        areaId = "SE4",
        overrides = JSONArray().put(
            SettingsFixtures.override(
                areaId = "SE4",
                vat = SettingsFixtures.fiscal(enabled = true, value = 25.0),
                tax = SettingsFixtures.fiscal(enabled = true, value = 36.0),
                transfer = SettingsFixtures.fiscal()
            )
        )
    )

    @Test
    fun theDialogPayloadCarriesTheFormAsTyped() {
        val values = ImmediateSettings.formValues("NO1", true, true, "7,13", false, "")
        assertEquals("NO1", values.areaId)
        assertEquals(7.13, values.taxFigure!!, 0.0)
        assertEquals(null, values.transferFigure)
        val ready = PairedSettingsForm.replacement(record, values, catalogue) as HaSettingsEditResult.Ready
        // One document for the record's revision, with the chosen area's override in it.
        assertEquals(5, ready.settings.revision)
        assertEquals("NO1", ready.settings.areaId)
        val override = ready.settings.overrides.first { it.areaId == "NO1" }
        assertTrue(override.tax.enabled)
        assertEquals(7.13, override.tax.value!!, 0.0)
    }

    @Test
    fun anUnreadableFigureReachesTheContractAsNoFigure() {
        assertEquals(null, ImmediateSettings.formValues("SE4", true, true, "x", false, "").taxFigure)
    }

    @Test
    fun theOverviewStatesTheConfirmedRecord() {
        val overview = PriceOverview.of(record) { id -> catalogue.firstOrNull { it.id == id } }
        assertEquals("SE4 – " + RelayFixtures.se4.name, overview.area)
        assertEquals(FiscalLine.Figure(25.0, "%"), overview.vat)
        assertEquals(FiscalLine.Figure(36.0, "öre/kWh"), overview.tax)
        assertEquals(FiscalLine.Off, overview.transfer)
        assertEquals("36 öre/kWh", PriceOverview.figureText(overview.tax as FiscalLine.Figure, Locale.ENGLISH))
        assertEquals("7,13 øre/kWh", PriceOverview.figureText(FiscalLine.Figure(7.13, "øre/kWh"), Locale("sv")))
    }

    @Test
    fun theLocalOverviewStatesThisPhonesOwnSettingsInTheSameRows() {
        val settings = WidgetSettings(area = "SE4", vat = true, tax = true, taxMinorUnit = 36.0, transfer = false)
        val overview = PriceOverview.ofLocal(settings) { id -> catalogue.firstOrNull { it.id == id } }
        assertEquals("SE4 – " + RelayFixtures.se4.name, overview.area)
        assertEquals(FiscalLine.Figure(25.0, "%"), overview.vat)
        assertEquals(FiscalLine.Figure(36.0, "öre/kWh"), overview.tax)
        assertEquals(FiscalLine.Off, overview.transfer)
        // A fee switched on with no figure is not set.
        val unset = PriceOverview.ofLocal(settings.copy(transfer = true, gridFeeMinorUnit = WidgetSettings.NO_SUGGESTION)) { id ->
            catalogue.firstOrNull { it.id == id }
        }
        assertEquals(FiscalLine.Unset, unset.transfer)
    }

    @Test
    fun aRecordWithoutAnAreaIsNotSet() {
        val overview = PriceOverview.of(SettingsFixtures.parsed(areaId = null)) { null }
        assertEquals(null, overview.area)
        assertEquals(FiscalLine.Off, overview.vat)
    }

    @Test
    fun aReadOnlyAuthorityOffersNoWrite() {
        // The paired card disables its Change button on exactly this answer.
        assertEquals(false, priceControlsEnabled(paired = true, authority = VisibleAuthority.ReadOnlyOffline(null)))
        assertEquals(true, priceControlsEnabled(paired = true, authority = VisibleAuthority.LocalOwner(null)))
    }
}
