package se.sensnology.spotnav.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.LocalTime

class PlanningInputsTest {
    private val complete = WidgetSettings(
        area = "SE4",
        vat = true,
        tax = true,
        transfer = true,
        taxMinorUnit = 39.0,
        gridFeeMinorUnit = 25.0,
        chargingPhases = 1,
        chargingAmps = 10,
        consumptionKwhPerMil = 1.8,
        chargingKwh = 20.0,
        driver = PlanDriver.TARGET_SOC,
        maxChargingPeriods = 4,
        showChargingPlan = true,
        useDepartureTime = true,
        departureHour = 6,
        departureMinute = 45,
        chargerProfileId = "local-a"
    )

    @Test fun theLocalAdapterMapsEveryCalculationField() {
        val inputs = LocalPlanningInputs.of(complete)

        assertEquals("SE4", inputs.areaId)
        // The local record keeps one figure per component, so it is at once the user's own value
        // and the effective one (see LocalPlanningInputs).
        val rate = complete.effectiveVatPercent
        assertEquals(FiscalInput(true, rate, rate), inputs.vat)
        assertEquals(FiscalInput(true, 39.0, 39.0), inputs.tax)
        assertEquals(FiscalInput(true, 25.0, 25.0), inputs.transfer)
        assertEquals(1, inputs.phases)
        assertEquals(10, inputs.amps)
        assertEquals(20.0, inputs.requestedEnergyKwh, 0.0)
        assertEquals(1.8, inputs.consumptionKwhPer10Km, 0.0)
        assertEquals(4, inputs.maxPeriods)
        assertEquals(DepartureIntent(true, LocalTime.of(6, 45)), inputs.departure)
        assertEquals(PlanDriver.TARGET_SOC, inputs.driver)
        // The widget's own record carries no target: the screen has already turned it into
        // `chargingKwh` before a plan is computed, and a live reading never travels in calculation
        // inputs.
        assertEquals(null, inputs.targetSocPercent)
    }

    // He fiscal half: one formula, the same numbers
    @Test fun theFiscalFormulaIsTheOneLocalApplyHasAlwaysUsed() {
        val settings = complete.copy(vat = true, tax = true, transfer = true, taxMinorUnit = 39.0, gridFeeMinorUnit = 25.0)
        val inputs = LocalPlanningInputs.of(settings)

        listOf(0.0, 0.5, 1.234, 10.0, 123.456).forEach { localMajor ->
            // The arithmetic this replaced, written out here rather than called: local major units
            // into minor, the two local additions, VAT last.
            var expected = localMajor * 100.0
            expected += 39.0
            expected += 25.0
            expected *= 1.0 + settings.effectiveVatPercent / 100.0

            assertEquals("for $localMajor the migrated formula must equal the one it replaced", expected, inputs.apply(localMajor), 1e-12)
        }

        // And the record's own method is the same code path, not a second copy: with the formula
        // moved, `WidgetSettings.apply` delegates.
        assertEquals(inputs.apply(2.5), settings.apply(2.5), 1e-12)
    }

    @Test fun wholeKwhBecomesTheSameDoubleAndNothingElseMoves() {
        listOf(1, 7, 20, 51).forEach { kwh ->
            val settings = WidgetSettings(chargingKwh = kwh.toDouble())
            val inputs = LocalPlanningInputs.of(settings)

            assertEquals(kwh.toDouble(), inputs.requestedEnergyKwh, 0.0)
            assertEquals(settings.chargingAmps, inputs.amps)
            assertEquals(settings.chargingPhases, inputs.phases)
            assertEquals(settings.maxChargingPeriods, inputs.maxPeriods)
            assertEquals(settings.departureHour, inputs.departure.time.hour)
            assertEquals(settings.departureMinute, inputs.departure.time.minute)
        }
    }

    @Test fun presentationAndBindingFieldsCannotAffectCalculationEquality() {
        val plain = WidgetSettings(chargingKwh = 20.0, chargerProfileId = "local-a")
        val shown = plain.copy(showChargingPlan = true)
        val otherWidget = plain.copy(chargerProfileId = "local-b", showChargingPlan = true)

        // Nothing a person sees or binds takes part in what a plan is: the same calculation inputs
        // come out of all three.
        assertEquals(LocalPlanningInputs.of(plain), LocalPlanningInputs.of(shown))
        assertEquals(LocalPlanningInputs.of(plain), LocalPlanningInputs.of(otherWidget))

        // And a real calculation input still does.
        assertNotEquals(LocalPlanningInputs.of(plain), LocalPlanningInputs.of(plain.copy(chargingAmps = 16)))
        assertNotEquals(LocalPlanningInputs.of(plain), LocalPlanningInputs.of(plain.copy(chargingKwh = 21.0)))
    }

    @Test fun aZeroFiscalFigureIsNotAnAbsentOne() {
        // Absent: nobody has stated a figure. Zero: somebody stated zero.
        assertNotEquals(FiscalInput(true, null, null), FiscalInput(true, 0.0, 0.0))
        assertNotEquals(FiscalInput(false, null, null), FiscalInput(true, null, null))

        // They differ as *values* even where they add nothing, so a source that reports the
        // difference can carry it.
        val base = LocalPlanningInputs.of(WidgetSettings())
        assertNotEquals(base.copy(tax = FiscalInput(true, null, null)), base.copy(tax = FiscalInput(true, 0.0, 0.0)))
    }

    @Test fun theLocalRecordStatesItsFigureAndUsesIt() {
        // The local record has one representation per figure and no provenance for it, so the
        // adapter sets both fields: what the record states and what the arithmetic uses.
        val settings = WidgetSettings()
        val defaults = LocalPlanningInputs.of(settings)
        assertEquals(FiscalInput(false, 0.0, 0.0), defaults.tax)
        assertEquals(FiscalInput(false, 0.0, 0.0), defaults.transfer)
        // VAT's figure is the *area's* own rate, resolved here, and it is stated as well as used --
        // the record keeps one figure per component and no provenance for it.
        val rate = settings.effectiveVatPercent
        assertEquals(FiscalInput(settings.vat, rate, rate), defaults.vat)
    }

    @Test fun aSuggestedRateIsUsedWhileTheOverrideStaysAbsent() {
        val base = LocalPlanningInputs.of(WidgetSettings())
        val suggested = base.copy(vat = FiscalInput.suggested(25.0))

        // 25 % is what the arithmetic uses ...
        assertEquals(base.apply(1.0) * 1.25, suggested.apply(1.0), 1e-12)
        // ... and the absence of an override is still visible as its own fact, so a screen can say
        // "the area's rate" rather than "your rate".
        assertEquals(null, suggested.vat.overrideValue)
        assertEquals(25.0, suggested.vat.effectiveValue!!, 0.0)
        assertNotEquals(suggested.vat, suggested.vat.copy(overrideValue = 25.0))
    }

    @Test fun aZeroOverrideIsADifferentValueFromAnAbsentOne() {
        val base = LocalPlanningInputs.of(WidgetSettings())
        val zeroOverride = base.copy(vat = FiscalInput(true, 0.0, 0.0))
        val suggested = base.copy(vat = FiscalInput.suggested(25.0))

        assertEquals(base.apply(1.0), zeroOverride.apply(1.0), 1e-12)
        assertNotEquals(zeroOverride, suggested)
        assertNotEquals(zeroOverride.vat.overrideValue, suggested.vat.overrideValue)
    }

    @Test fun allThreeComponentsTakeTheSameShape() {
        val base = LocalPlanningInputs.of(WidgetSettings())

        // A component whose figure came from the catalogue, stated with no override, behaves
        // exactly like one the person stated, for tax and transfer too.
        val suggestedTax = base.copy(tax = FiscalInput.suggested(39.0))
        val statedTax = base.copy(tax = FiscalInput(true, 39.0, 39.0))
        assertEquals(statedTax.apply(1.0), suggestedTax.apply(1.0), 1e-12)
        assertEquals(base.apply(1.0) + 39.0, suggestedTax.apply(1.0), 1e-12)

        val suggestedTransfer = base.copy(transfer = FiscalInput.suggested(25.0))
        assertEquals(base.apply(1.0) + 25.0, suggestedTransfer.apply(1.0), 1e-12)
    }

    @Test fun aComponentAddsNothingWithoutAnEffectiveFigureAndNeverWhenDisabled() {
        val base = LocalPlanningInputs.of(WidgetSettings())

        // Enabled with no resolved figure: nothing to add, and nothing invented.
        assertEquals(base.apply(1.0), base.copy(tax = FiscalInput(true, null, null)).apply(1.0), 0.0)
        assertEquals(base.apply(1.0), base.copy(tax = FiscalInput(true, 39.0, null)).apply(1.0), 0.0)
        assertEquals(base.apply(1.0), base.copy(vat = FiscalInput(true, null, null)).apply(1.0), 0.0)
        assertEquals(base.apply(1.0), base.copy(vat = FiscalInput(true, 25.0, null)).apply(1.0), 0.0)

        // Disabled never affects a price, whatever it carries.
        assertEquals(base.apply(1.0), base.copy(tax = FiscalInput(false, 39.0, 39.0)).apply(1.0), 0.0)
        assertEquals(base.apply(1.0), base.copy(vat = FiscalInput(false, 25.0, 25.0)).apply(1.0), 0.0)
    }

    @Test fun aFiscalFigureMustBeARealNumber() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.5).forEach { figure ->
            assertThrows(IllegalArgumentException::class.java) { FiscalInput(true, figure, null) }
            assertThrows(IllegalArgumentException::class.java) { FiscalInput(true, null, figure) }
            assertThrows(IllegalArgumentException::class.java) { FiscalInput(false, figure, figure) }
        }
    }

    @Test fun nonsenseInputsAreRefusedRatherThanClamped() {
        val valid = LocalPlanningInputs.of(WidgetSettings())

        listOf(
            "an empty area" to { valid.copy(areaId = " ") },
            "two phases" to { valid.copy(phases = 2) },
            "no amps" to { valid.copy(amps = 0) },
            "negative amps" to { valid.copy(amps = -6) },
            "zero energy" to { valid.copy(requestedEnergyKwh = 0.0) },
            "negative energy" to { valid.copy(requestedEnergyKwh = -1.0) },
            "non-finite energy" to { valid.copy(requestedEnergyKwh = Double.NaN) },
            "infinite energy" to { valid.copy(requestedEnergyKwh = Double.POSITIVE_INFINITY) },
            "zero consumption" to { valid.copy(consumptionKwhPer10Km = 0.0) },
            "non-finite consumption" to { valid.copy(consumptionKwhPer10Km = Double.NaN) },
            "no periods" to { valid.copy(maxPeriods = 0) },
            "nine periods" to { valid.copy(maxPeriods = 9) },
            "a negative VAT figure" to { valid.copy(vat = FiscalInput(true, -1.0, -1.0)) },
            "negative fee" to { valid.copy(tax = FiscalInput(true, -0.5, -0.5)) },
            "a non-finite fee" to { valid.copy(transfer = FiscalInput(true, Double.NaN, Double.NaN)) },
            "a target above 100" to { valid.copy(targetSocPercent = 101.0) },
            "a negative target" to { valid.copy(targetSocPercent = -1.0) }
        ).forEach { (what, build) ->
            assertThrows("$what must be refused, not repaired", IllegalArgumentException::class.java) { build() }
        }
    }

    @Test fun theLocalBoundaryResolvesStoredNonsenseInsteadOfCrashing() {
        // These are the coercions the calculation has always applied, moved to the storage boundary
        // where they belong: the model itself refuses rather than repairs, and every value a screen
        // can write passes through unchanged.
        val stored = WidgetSettings(
            chargingKwh = 0.0,
            chargingAmps = 0,
            chargingPhases = 2,
            consumptionKwhPerMil = 0.0,
            maxChargingPeriods = 12,
            departureHour = 25,
            departureMinute = 99,
            tax = true,
            taxMinorUnit = Double.NaN,
            useDepartureTime = true
        )

        val inputs = LocalPlanningInputs.of(stored)

        assertEquals(1.0, inputs.requestedEnergyKwh, 0.0)
        assertEquals(1, inputs.amps)
        assertEquals(3, inputs.phases)
        assertEquals(0.1, inputs.consumptionKwhPer10Km, 1e-12)
        assertEquals(8, inputs.maxPeriods)
        assertEquals(LocalTime.of(23, 59), inputs.departure.time)
        // A figure that is not a number is not a figure: absent, never added.
        assertEquals(FiscalInput(true, null, null), inputs.tax)
        assertEquals(inputs.apply(1.0), LocalPlanningInputs.of(stored.copy(tax = false)).apply(1.0), 0.0)
    }

    // The contract's target is a finite decimal, and it stays one.
    @Test fun aDecimalTargetIsKeptExactlyAsTheContractStatesIt() {
        val base = LocalPlanningInputs.of(WidgetSettings())
        val target = base.copy(targetSocPercent = 80.5)

        assertEquals(80.5, target.targetSocPercent!!, 0.0)
        assertNotEquals(80.0, target.targetSocPercent!!, 0.0)
        assertNotEquals(81.0, target.targetSocPercent!!, 0.0)
        // The local adapter states no target: the screen has already turned it into
        // requested energy, and the field exists for the source that carries the contract's own
        // decimal.
        assertEquals(null, base.targetSocPercent)
        assertEquals(0.0, base.copy(targetSocPercent = 0.0).targetSocPercent!!, 0.0)
        assertEquals(100.0, base.copy(targetSocPercent = 100.0).targetSocPercent!!, 0.0)
    }

    @Test fun aTargetOutsideTheContractsBandOrNotANumberIsRefused() {
        val base = LocalPlanningInputs.of(WidgetSettings())

        listOf(-0.1, 100.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { target ->
            assertThrows("$target must be refused", IllegalArgumentException::class.java) {
                base.copy(targetSocPercent = target)
            }
        }
    }

    // The empty-area edge: a real state, not a crash.
    @Test fun aWidgetWithNoAreaHasNoCalculationInputsRatherThanAFabricatedOne() {
        // `WidgetSettings.load` gives `area = ""` when the catalogue is empty and the widget has
        // never stored an area.
        val unconfigured = WidgetSettings(area = "", chargingKwh = 20.0)

        assertEquals(null, LocalPlanningInputs.ofOrNull(unconfigured))
        // The pure model still refuses it: the boundary answering "unavailable" must not be the
        // thing that fabricates an area.
        assertThrows(IllegalArgumentException::class.java) { LocalPlanningInputs.of(unconfigured) }
        // A configured widget is unaffected.
        assertEquals("SE4", LocalPlanningInputs.ofOrNull(WidgetSettings(area = "SE4"))!!.areaId)
    }

    @Test fun theRecordsOwnArithmeticNeedsNoValidPlanInputs() {
        // The record's façade asks only about a price and three figures: a widget whose area, amps,
        // energy, consumption, periods and departure hour are all unusable still prices a relay
        // price, and does not throw.
        val broken = WidgetSettings(
            area = "",
            chargingAmps = 0,
            chargingKwh = 0.0,
            consumptionKwhPerMil = 0.0,
            maxChargingPeriods = 0,
            departureHour = 99,
            tax = true,
            taxMinorUnit = 39.0,
            vat = true
        )

        assertEquals(139.0, broken.apply(1.0), 1e-9)
        assertEquals(0.0, WidgetSettings(area = "").apply(0.0), 0.0)
    }
}
