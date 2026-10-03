package se.sensnology.spotnav.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.planning.ChargingPlanner
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.prices.PriceMarket
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.OffsetDateTime
import java.util.Locale

/** The settings form's picker and its area-dependent state, without an Activity. */
class SettingsAreaStateTest {

    /** The calculation inputs for these widget settings. */
    private fun inputs(settings: WidgetSettings): PlanningInputs = LocalPlanningInputs.of(settings)
    private val se4 = RelayFixtures.se4
    private val no1 = RelayFixtures.no1
    private val fi = RelayFixtures.fi
    private val catalogue = listOf(se4, no1, fi)

    @org.junit.Before
    fun loadCatalogue() {
        PriceMarkets.replace(catalogue)
    }

    private val countryLabel: (String) -> String = { it }
    private val unavailableLabel: (String) -> String = { "unavailable: $it" }

    private fun rowsFor(selectedId: String?, areas: List<PriceMarket> = catalogue) =
        SettingsAreaController.rows(
            areas, "SE", SettingsAreaController.state(selectedId, areas), countryLabel, unavailableLabel
        )

    /** A machine over [catalogue], with [savedId] as the widget's stored area. */
    private fun picker(savedId: String = "SE4") = AreaPickerState(
        savedId = savedId,
        areas = catalogue,
        region = "SE",
        countryLabel = countryLabel,
        unavailableLabel = unavailableLabel
    )

    private fun AreaPickerView.indexOf(id: String) = rows.indexOfFirst { it.id == id }

    // the three states

    @Test
    fun aSavedIdIsAvailableMissingOrAbsent() {
        assertEquals(AreaSelectionState.Available("SE4"), SettingsAreaController.state("SE4", catalogue))
        assertEquals(AreaSelectionState.Missing("RETIRED"), SettingsAreaController.state("RETIRED", catalogue))
        assertEquals(AreaSelectionState.None, SettingsAreaController.state(null, catalogue))
        assertEquals(AreaSelectionState.None, SettingsAreaController.state("", catalogue))
    }

    @Test
    fun aRecordWithNoAreaShowsNotSetAndNeverThePhonesDefault() {
        val unset = AreaPickerState("", catalogue, "SE", countryLabel, unavailableLabel, notSetLabel = "Not set")
        val view = unset.open()

        assertEquals(AreaPickerRow("Not set", null), view.rows.first())
        assertEquals(0, view.selectedIndex)
        assertEquals(AreaSelectionState.None, view.state)
        assertFalse(view.canSave)
        assertEquals("", unset.selectedId)
        // The placeholder is not a choice, and picking a real area replaces it.
        assertNull(unset.onRowSelected(0, null))
        val picked = unset.onRowSelected(view.indexOf("SE4"), "SE4")!!
        assertEquals(AreaSelectionState.Available("SE4"), picked.state)
        // A picker with an area, or with no such label, never gets the row.
        assertNull(picker().open().rows.firstOrNull { it.label == "Not set" })
        assertNull(AreaPickerState("", catalogue, "SE", countryLabel, unavailableLabel).open().rows.firstOrNull { it.label == "Not set" })
    }

    @Test
    fun onlyAnAvailableAreaMayBeSaved() {
        assertTrue(SettingsAreaController.canSave(AreaSelectionState.Available("SE4")))
        assertFalse(SettingsAreaController.canSave(AreaSelectionState.Missing("RETIRED")))
        assertFalse(SettingsAreaController.canSave(AreaSelectionState.None))
    }

    // rows

    @Test
    fun aMissingSelectionIsShownFirstAndIsNotSelectable() {
        val rows = rowsFor("RETIRED")

        assertTrue(rows.first().label.startsWith("unavailable: RETIRED"))
        assertEquals("a placeholder carries no catalogue id", null, rows.first().id)
        assertTrue(rows.any { it.id == "SE4" })
    }

    @Test
    fun headingsCarryNoIdSoTheyCannotBeSelected() {
        val rows = rowsFor("SE4")

        // Three headings and three areas; the areas carry ids and the headings do not, which is
        // what makes a heading unselectable.
        assertEquals(listOf(null, "SE4", null, "NO1", null, "FI"), rows.map { it.id })
        assertEquals(3, rows.count { it.id == null })
        assertEquals("SE", rows.first().label)
    }

    // refresh, and the identity that must survive it

    @Test
    fun aCatalogueRefreshThatChangesNothingAsksForNoRedraw() {
        val machine = picker("SE4")
        val opened = machine.open()

        val refreshed = machine.onCatalogueRefreshed(catalogue)

        assertFalse("nothing to redraw", refreshed.replaceAdapter)
        assertFalse("a refresh is never a choice", refreshed.userPicked)
        assertEquals(AreaSelectionState.Available("SE4"), refreshed.state)
        assertEquals(opened.selectedIndex, refreshed.selectedIndex)
    }

    @Test
    fun aNewAreaIsOfferedWithoutMovingTheSelection() {
        val machine = picker("NO1")
        machine.open()
        // Inserted *before* the selected area, so a selection kept by index would silently slide
        // onto a different area.
        val no2 = RelayFixtures.area("NO2", "NOK", "kr", "øre", tz = "Europe/Oslo")

        val refreshed = machine.onCatalogueRefreshed(listOf(se4, no2, no1, fi))

        assertTrue(refreshed.replaceAdapter)
        assertEquals(AreaSelectionState.Available("NO1"), refreshed.state)
        assertEquals("NO1", refreshed.rows[refreshed.selectedIndex].id)
        assertTrue(refreshed.rows.any { it.id == "NO2" })
    }

    @Test
    fun aSelectionThatDisappearsBecomesThePlaceholderAndIsNeverReplaced() {
        val machine = picker("NO1")
        machine.open()

        val refreshed = machine.onCatalogueRefreshed(listOf(se4, fi))

        assertTrue(refreshed.replaceAdapter)
        assertEquals(AreaSelectionState.Missing("NO1"), refreshed.state)
        assertEquals("the identity stays, it is not reverted to the saved one", "NO1", machine.selectedId)
        assertEquals("the placeholder is selected, not another area", null, refreshed.rows[refreshed.selectedIndex].id)
        assertFalse(refreshed.canSave)
    }

    @Test
    fun aRefreshIsIdempotentSoARedrawCannotLoop() {
        val machine = picker("SE4")
        machine.open()
        val grown = catalogue + listOf(RelayFixtures.area("SE5", "SEK", "kr", "øre"))

        val once = machine.onCatalogueRefreshed(grown)
        val twice = machine.onCatalogueRefreshed(grown)

        assertTrue(once.replaceAdapter)
        assertFalse("the second identical refresh must not redraw", twice.replaceAdapter)
    }

    // the whole sequence, saved -> unsaved -> retired -> replaced

    /**
     * A person picks NO1 without saving, a refresh retires NO1, and the form must stand for NO1 as
     * unavailable -- not silently fall back to the SE4 that was saved.
     */
    @Test
    fun anUnsavedPickThatARefreshRetiresIsNeverReplacedByTheSavedArea() {
        // A. the widget has SE4 saved
        val machine = picker("SE4")
        val opened = machine.open()
        assertEquals(AreaSelectionState.Available("SE4"), opened.state)

        // B. the person picks NO1 and has not saved it yet
        val picked = machine.onRowSelected(opened.indexOf("NO1"), "NO1")!!
        assertTrue(picked.userPicked)
        assertFalse("the Spinner already moved itself", picked.replaceAdapter)
        assertEquals(AreaSelectionState.Available("NO1"), machine.state)
        assertEquals("NO1", machine.selectedId)

        // C. a catalogue refresh removes NO1
        val refreshed = machine.onCatalogueRefreshed(listOf(se4, fi))

        // D. the form stands for NO1, shown as unavailable
        assertEquals(AreaSelectionState.Missing("NO1"), refreshed.state)
        assertEquals("NO1", machine.selectedId)
        assertEquals("the placeholder, not another area", null, refreshed.rows[refreshed.selectedIndex].id)
        assertTrue(refreshed.rows[refreshed.selectedIndex].label.contains("NO1"))
        assertTrue("the saved area must not come back", machine.selectedId != machine.savedId)

        // E. and it may not be saved as if it worked
        assertFalse(machine.canSave)
        assertFalse(refreshed.canSave)

        // F. the person explicitly chooses FI
        val replaced = machine.onRowSelected(refreshed.indexOf("FI"), "FI")!!
        assertTrue(replaced.userPicked)

        // G.
        assertEquals(AreaSelectionState.Available("FI"), machine.state)
        assertTrue(machine.canSave)
        assertEquals("FI", machine.selectedId)
    }

    // an echo is not a choice

    @Test
    fun aRefreshThatKeepsTheSelectedAreaIsNotAUserChoice() {
        val machine = picker("NO1")
        machine.open()

        // A refresh that adds an unrelated area, leaving the selection in place.
        val no2 = RelayFixtures.area("NO2", "NOK", "kr", "øre", tz = "Europe/Oslo")
        val refreshed = machine.onCatalogueRefreshed(listOf(se4, no2, no1, fi))

        assertFalse("a refresh must not apply fiscal suggestions again", refreshed.userPicked)
        assertEquals(AreaSelectionState.Available("NO1"), refreshed.state)
        assertEquals("NO1", machine.selectedId)
    }

    @Test
    fun aDeferredCallbackForTheRowWeInstalledIsNotAUserChoice() {
        val machine = picker("SE4")
        val opened = machine.open()

        // Android may deliver this after the code that caused it has returned -- which is why the
        // check is the row's identity and not a flag that would already be false by then.
        assertNull(machine.onRowSelected(opened.selectedIndex, "SE4"))
        assertEquals(AreaSelectionState.Available("SE4"), machine.state)
        assertEquals("SE4", machine.selectedId)

        // The same holds after a refresh, when the index may have moved.
        val refreshed = machine.onCatalogueRefreshed(catalogue + listOf(RelayFixtures.area("SE5", "SEK", "kr", "øre")))
        assertNull(machine.onRowSelected(refreshed.selectedIndex, "SE4"))
        assertEquals(AreaSelectionState.Available("SE4"), machine.state)
    }

    @Test
    fun aHeadingOrPlaceholderSelectionChangesNothingAtAll() {
        val machine = picker("RETIRED")
        val opened = machine.open()
        val placeholder = opened.rows.indexOfFirst { it.id == null }
        assertTrue(placeholder >= 0)

        assertNull("the placeholder cannot be chosen", machine.onRowSelected(placeholder, null))
        assertNull("nor can a heading", machine.onRowSelected(opened.rows.lastIndex, null))
        assertEquals(AreaSelectionState.Missing("RETIRED"), machine.state)
        assertEquals("RETIRED", machine.selectedId)
        assertFalse(machine.canSave)
    }

    /** The guarantee that a refresh cannot lose an edit is structural, and this pins it: */
    @Test
    fun thePickerCarriesNoFormValuesAtAll() {
        assertEquals(
            listOf("canSave", "replaceAdapter", "rows", "selectedIndex", "state", "userPicked"),
            AreaPickerView::class.java.declaredFields.map { it.name }.sorted()
        )
        // The whole of the machine's state.
        assertEquals(
            listOf(
                "areas", "countryLabel", "installedIndex", "installedRows", "notSetLabel",
                "region", "savedId", "selectedId", "unavailableLabel"
            ),
            AreaPickerState::class.java.declaredFields.map { it.name }.sorted()
        )
        val formWords = listOf("tax", "fee", "vat", "interval", "suggest", "previous", "suppress")
        for (field in AreaPickerState::class.java.declaredFields) {
            assertTrue(
                "no form value may live in the picker: ${field.name}",
                formWords.none { field.name.lowercase().contains(it) }
            )
        }
    }

    // both intervals, for every source resolution

    @Test
    fun theQuarterHourViewStaysAvailableForAnHourlySourceDay() {
        // NO1's day document is published at res = 60, and the repository lays those positions out
        // on the quarter-hour grid; the option that shows them must not be withdrawn because the
        // *source* is coarse.
        assertTrue(PresentationIntervals.isAvailable(PresentationIntervals.QUARTER_HOUR_MINUTES, sourceResMinutes = 60))
        assertTrue(PresentationIntervals.isAvailable(PresentationIntervals.HOUR_MINUTES, sourceResMinutes = 60))
        // An unknown resolution narrows nothing either.
        assertTrue(PresentationIntervals.isAvailable(PresentationIntervals.QUARTER_HOUR_MINUTES, sourceResMinutes = null))
    }

    @Test
    fun everyOfferedIntervalIsAvailableForEverySourceResolution() {
        for (minutes in PresentationIntervals.all) {
            for (source in listOf(null, 15, 60, 1440)) {
                assertTrue("$minutes minutes vs source $source", PresentationIntervals.isAvailable(minutes, source))
            }
        }
        assertEquals(listOf(15, 60), PresentationIntervals.all)
        // A resolution the app has never offered is still not on offer:
        assertFalse(PresentationIntervals.isAvailable(30, sourceResMinutes = 30))
    }

    // fiscal suggestion states

    @Test
    fun theFourFiscalStatesAreDistinguished() {
        assertEquals(FiscalSuggestion.BOTH, FiscalSuggestions.of(se4))
        assertEquals(FiscalSuggestion.TAX_ONLY, FiscalSuggestions.of(no1))
        assertEquals("Finland publishes a tax figure and no grid fee too", FiscalSuggestion.TAX_ONLY, FiscalSuggestions.of(fi))
        assertEquals(FiscalSuggestion.GRID_ONLY, FiscalSuggestions.of(se4.copy(suggestedTax = null)))
        assertEquals(
            FiscalSuggestion.NEITHER,
            FiscalSuggestions.of(se4.copy(suggestedTax = null, suggestedGridFee = null))
        )
        assertEquals(FiscalSuggestion.NEITHER, FiscalSuggestions.of(null))
    }

    // area money: the code, the display unit and the scale

    @Test
    fun theMoneyFactsNameEveryAreaAndNeverBorrowAnothers() {
        assertEquals(AreaMoneyFacts.Available("SE4", "SEK", "kr"), AreaMoney.of("SE4", se4))
        assertEquals(AreaMoneyFacts.Available("NO1", "NOK", "kr"), AreaMoney.of("NO1", no1))
        assertEquals(AreaMoneyFacts.Available("FI", "EUR", "€"), AreaMoney.of("FI", fi))
        // An unavailable area keeps its own id and gets neither code nor unit -- never another
        // area's.
        assertEquals(AreaMoneyFacts.Unavailable("RETIRED"), AreaMoney.of("RETIRED", null))
    }

    @Test
    fun theIsoCodeAndTheDisplayUnitAreSeparateFacts() {
        val swedish = AreaMoney.of("SE4", se4) as AreaMoneyFacts.Available
        val norwegian = AreaMoney.of("NO1", no1) as AreaMoneyFacts.Available

        assertEquals("SEK", swedish.currency)
        assertEquals("NOK", norwegian.currency)
        assertNotEquals(swedish.currency, norwegian.currency)
        // The display unit is identical for both, which is exactly why it cannot be the identity
        // fact the footer answers with.
        assertEquals(se4.majorUnit, no1.majorUnit)
    }

    @Test
    fun aPlanTotalIsLabelledWithTheMajorUnitAndNotTheIsoCode() {
        // The ISO code is the footer's identity fact, and putting it beside a number is what showed
        // `ca 3,95 SEK` where the money is `kr`.
        assertEquals("3.95 kr", CostLabel.amount(3.95, AreaMoney.of("SE4", se4), Locale.US))
        assertEquals("3.95 kr", CostLabel.amount(3.95, AreaMoney.of("NO1", no1), Locale.US))
        assertEquals("3.95 €", CostLabel.amount(3.95, AreaMoney.of("FI", fi), Locale.US))
    }

    @Test
    fun thePlanTotalScaleAndLabelAgreeEndToEnd() {
        // The pair, checked against each other rather than against a literal:
        val start = OffsetDateTime.parse("2026-09-12T18:00:00+02:00")
        val prices = (0 until 96).map { index -> PricePoint(start.plusMinutes(index * 15L), 1.0) }
        val settings = WidgetSettings(area = se4.id, chargingPhases = 1, chargingAmps = 10, chargingKwh = 4.0)
        val plan = ChargingPlanner.calculate(PriceResult(prices, emptyList(), 0), inputs(settings), start)!!

        assertEquals("a major total is the energy at 1 kr/kWh", plan.energyKwh, plan.cost, 0.01)
        val shown = CostLabel.amount(plan.cost, AreaMoney.of(se4.id, se4), Locale.US)!!
        assertEquals("${"%.2f".format(Locale.US, plan.cost)} kr", shown)
    }

    @Test
    fun anUnavailableAreaHasNeitherAUnitNorACurrency() {
        val facts = AreaMoney.of("RETIRED", null)

        assertEquals(AreaMoneyFacts.Unavailable("RETIRED"), facts)
        // Nothing to label a number with, so the caller shows its own unavailable state instead of
        // a figure with no unit.
        assertNull(CostLabel.amount(3.95, facts, Locale.US))
    }

    @Test
    fun theChartAndTableKeepTheMinorUnitBecauseApplyReturnsMinorUnits() {
        // The pairing item 1 is about:
        val cases = listOf(
            Triple(se4, 0.45, "öre/kWh") to 45.0,
            Triple(no1, 0.45, "øre/kWh") to 45.0,
            Triple(fi, 0.05, "cent/kWh") to 5.0
        )
        for ((triple, expectedShown) in cases) {
            val (area, localMajor, label) = triple
            val shown = WidgetSettings(area = area.id).apply(localMajor)

            assertEquals(area.id, expectedShown, shown, 0.0001)
            assertEquals(area.id, label, area.appliedPriceUnit)
            // The number is exactly `local major x 100`, and the label names the hundredth unit --
            // so the two cannot drift apart silently.
            assertEquals(area.id, localMajor * 100, shown, 0.0001)
            assertEquals(area.id, 100.0, shown / localMajor, 0.0001)
        }
    }

    @Test
    fun theMajorUnitIsStillCarriedForUnscaledValues() {
        assertEquals("kr", se4.majorUnit)
        assertEquals("€", fi.majorUnit)
        assertEquals("kr/kWh", se4.majorUnit + "/kWh")
        assertEquals("€/kWh", fi.majorUnit + "/kWh")
    }
}
