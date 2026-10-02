package se.sensnology.spotnav.ha.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.SettingsFixtures

/** The one deliberate edit, and the full replacement it produces. */
class HaSettingsEditorTest {
    private val confirmed = SettingsFixtures.parsed(
        revision = 7,
        areaId = "SE4",
        overrides = overrides(),
        phases = 3,
        amps = 16,
        requestedKwh = 20.5,
        maxPeriods = 4,
        departureEnabled = true,
        departureTime = "07:30",
        driver = "manual_kwh",
        target = SettingsFixtures.target(vehicleId = "vehicle-1", targetPercent = 80.0)
    )

    @Test fun editingOneFieldOfAnUnsetRecordKeepsTheUnsetFieldsNull() {
        val fresh = SettingsFixtures.parsed(revision = 0, areaId = null, phases = null, amps = null)

        val ready = HaSettingsEditor.replacement(fresh, HaSettingsEdit.MaxPeriods(2)) as HaSettingsEditResult.Ready

        assertNull(ready.settings.phases)
        assertNull(ready.settings.amps)
        assertNull(ready.settings.areaId)
        assertEquals(2, ready.settings.maxPeriods)
        assertEquals(0, ready.settings.revision)
        // Setting one of them states that one only.
        val amps = HaSettingsEditor.replacement(fresh, HaSettingsEdit.Amps(10)) as HaSettingsEditResult.Ready
        assertEquals(10, amps.settings.amps)
        assertNull(amps.settings.phases)
        assertNull(amps.settings.areaId)
    }

    private fun overrides() = org.json.JSONArray().apply {
        put(SettingsFixtures.override(areaId = "SE3", vat = SettingsFixtures.fiscal(true, 12.0)))
        put(
            SettingsFixtures.override(
                areaId = "SE4",
                vat = SettingsFixtures.fiscal(true, null),
                tax = SettingsFixtures.fiscal(true, 0.0),
                transfer = SettingsFixtures.fiscal(false, null)
            )
        )
    }

    private fun ready(edit: HaSettingsEdit): HaPlanningSettings {
        val result = HaSettingsEditor.replacement(confirmed, edit)
        assertTrue("expected a replacement, got " + result, result is HaSettingsEditResult.Ready)
        return (result as HaSettingsEditResult.Ready).settings
    }

    private fun refused(edit: HaSettingsEdit): String {
        val result = HaSettingsEditor.replacement(confirmed, edit)
        assertTrue("expected a refusal, got " + result, result is HaSettingsEditResult.Refused)
        return (result as HaSettingsEditResult.Refused).code
    }

    // Each control's replacement changes only its own field.
    @Test fun everyEditChangesExactlyItsOwnFieldAndKeepsEverythingElse() {
        // One entry per control, with how many record fields that control owns.
        val edits = listOf(
            HaSettingsEdit.Area("SE3") to 1,
            HaSettingsEdit.Phases(1) to 1,
            HaSettingsEdit.Amps(10) to 1,
            HaSettingsEdit.Energy(12.75) to 1,
            HaSettingsEdit.MaxPeriods(2) to 1,
            // Departure is one control over two fields: whether it applies, and the time.
            HaSettingsEdit.Departure(enabled = false, time = "06:15", date = null) to 2,
            // The driver and its target are one statement, so they travel together.
            HaSettingsEdit.Driver(HaSettingsDriver.TARGET_SOC, HaTargetIntent("vehicle-1", 90.0)) to 2,
            HaSettingsEdit.Fiscal("SE4", HaAreaOverrideComponent.TAX, HaFiscalValue(true, 5.0)) to 1
        )

        edits.forEach { (edit, expectedChanges) ->
            val replacement = ready(edit)
            val differences = listOf(
                confirmed.areaId != replacement.areaId,
                confirmed.overrides != replacement.overrides,
                confirmed.phases != replacement.phases,
                confirmed.amps != replacement.amps,
                confirmed.requestedKwh != replacement.requestedKwh,
                confirmed.maxPeriods != replacement.maxPeriods,
                confirmed.departureEnabled != replacement.departureEnabled,
                confirmed.departureTime != replacement.departureTime,
                confirmed.departureDate != replacement.departureDate,
                confirmed.driver != replacement.driver,
                confirmed.target != replacement.target
            ).count { it }
            assertEquals("$edit changed the wrong number of fields", expectedChanges, differences)
            // The revision the edit was built on is carried, not reset.
            assertEquals(7, replacement.revision)
        }
    }

    @Test fun aReplacementBodyNeverStatesTheRevisionAndTheContractReadsItBack() {
        val replacement = ready(HaSettingsEdit.Amps(10))

        val body = HaSettingsCodec.encodeBody(replacement)
        assertFalse("a replacement body never carries revision", body.has("revision"))
        // And the accepted contract reads exactly the value this app would send.
        assertEquals(replacement.copy(revision = 0), HaSettingsCodec.parseBody(body))
    }

    @Test fun theFieldsNoAndroidControlOwnsAreCarriedUnchanged() {
        val replacement = ready(HaSettingsEdit.Energy(21.0))

        assertEquals(confirmed.target, replacement.target)
        assertEquals(confirmed.overrides, replacement.overrides)
    }

    // Explicit null and 0.0 stay themselves through a UI edit.
    @Test fun anExplicitFiscalZeroAndAnExplicitNullSurviveAComponentEdit() {
        val zeroed = ready(
            HaSettingsEdit.Fiscal("SE4", HaAreaOverrideComponent.TAX, HaFiscalValue(enabled = true, value = 0.0))
        )
        assertEquals(
            HaFiscalValue(enabled = true, value = 0.0),
            HaSettingsEditor.fiscalFor(zeroed, HaAreaOverrideComponent.TAX, "SE4")
        )

        val cleared = ready(
            HaSettingsEdit.Fiscal("SE4", HaAreaOverrideComponent.VAT, HaFiscalValue(enabled = true, value = null))
        )
        assertEquals(
            HaFiscalValue(enabled = true, value = null),
            HaSettingsEditor.fiscalFor(cleared, HaAreaOverrideComponent.VAT, "SE4")
        )
        assertNotEquals(
            "null is not zero",
            HaFiscalValue(enabled = true, value = 0.0),
            HaSettingsEditor.fiscalFor(cleared, HaAreaOverrideComponent.VAT, "SE4")
        )
    }

    @Test fun editingOneAreasComponentLeavesEveryOtherComponentAndAreaAlone() {
        val replacement = ready(
            HaSettingsEdit.Fiscal("SE4", HaAreaOverrideComponent.TRANSFER, HaFiscalValue(true, 30.0))
        )

        // The same area's other components keep their states...
        assertEquals(
            HaFiscalValue(enabled = true, value = null),
            HaSettingsEditor.fiscalFor(replacement, HaAreaOverrideComponent.VAT, "SE4")
        )
        assertEquals(
            HaFiscalValue(enabled = true, value = 0.0),
            HaSettingsEditor.fiscalFor(replacement, HaAreaOverrideComponent.TAX, "SE4")
        )
        // ...and the other area's figure is untouched -- it is that area's money.
        assertEquals(
            HaFiscalValue(enabled = true, value = 12.0),
            HaSettingsEditor.fiscalFor(replacement, HaAreaOverrideComponent.VAT, "SE3")
        )
        assertEquals(2, replacement.overrides.size)
    }

    @Test fun anAreaWithNoOverrideGetsOneWithItsOtherComponentsOff() {
        val replacement = ready(
            HaSettingsEdit.Fiscal("NO1", HaAreaOverrideComponent.VAT, HaFiscalValue(true, 25.0))
        )
        val override = replacement.overrides.single { it.areaId == "NO1" }

        assertEquals(HaFiscalValue(enabled = true, value = 25.0), override.vat)
        assertEquals(HaFiscalValue.OFF, override.tax)
        assertEquals(HaFiscalValue.OFF, override.transfer)
        // An area with no override reads as all-off rather than as invented defaults.
        assertEquals(
            HaFiscalValue.OFF,
            HaSettingsEditor.fiscalFor(replacement, HaAreaOverrideComponent.TAX, "DK1")
        )
    }

    @Test fun aValueTheContractRefusesComesBackAsItsOwnStableCode() {
        assertEquals("invalid_amps", refused(HaSettingsEdit.Amps(0)))
        assertEquals("invalid_amps", refused(HaSettingsEdit.Amps(81)))
        assertEquals("invalid_phases", refused(HaSettingsEdit.Phases(2)))
        assertEquals("invalid_energy", refused(HaSettingsEdit.Energy(0.0)))
        assertEquals("invalid_periods", refused(HaSettingsEdit.MaxPeriods(9)))
        assertEquals("invalid_area", refused(HaSettingsEdit.Area("")))
        assertEquals("invalid_departure", refused(HaSettingsEdit.Departure(true, "25:00", null)))
    }

    @Test fun aTargetDrivenRecordWithoutATargetIsStillWhatTheContractAllows() {
        // The codec permits it; the *adapter* is what names it incomplete.
        val replacement = ready(HaSettingsEdit.Driver(HaSettingsDriver.TARGET_SOC, HaTargetIntent.EMPTY))

        assertEquals(HaSettingsDriver.TARGET_SOC, replacement.driver)
        assertNull(replacement.target.targetPercent)
    }

    @Test fun aConflictIsNeverReplayedAutomaticallyAtTheReturnedRevision() {
        // The rule the screen consults after every write.
        val edit = HaSettingsEdit.Amps(10)
        val current = SettingsFixtures.parsed(revision = 9, areaId = "SE4", amps = 32)

        assertNull(HaSettingsEditor.nextEdit(edit, SettingsUpdate.Outcome.Conflict(current)))
        assertNull(HaSettingsEditor.nextEdit(edit, SettingsUpdate.Outcome.Updated(current)))
        assertNull(HaSettingsEditor.nextEdit(edit, SettingsUpdate.Outcome.CommittedButReconcileFailed(current)))
        assertNull(HaSettingsEditor.nextEdit(edit, SettingsUpdate.Outcome.NotCommitted(current)))
        assertNull(HaSettingsEditor.nextEdit(edit, SettingsUpdate.Outcome.Invalid("invalid_amps", current)))
        assertNull(HaSettingsEditor.nextEdit(edit, SettingsUpdate.Outcome.Unavailable))
    }

    @Test fun changingOnlyTheDriverToKwhKeepsTheStoredTarget() {
        val known = HaTargetIntent("vehicle-1", 70.0)
        assertEquals(known, known.forDriver(HaSettingsDriver.MANUAL_KWH, 55.0))
        assertEquals(
            HaTargetIntent("vehicle-1", 55.0),
            known.forDriver(HaSettingsDriver.TARGET_SOC, 55.0)
        )
        assertEquals(HaTargetIntent.EMPTY, HaTargetIntent.EMPTY.forDriver(HaSettingsDriver.MANUAL_KWH, 55.0))
    }
}
