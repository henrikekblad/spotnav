package se.sensnology.spotnav.chargers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The charger card's phase readout: what the card shows when the charger itself reports phases, and
 * what the user may do about it.
 */
class ChargerPhasesTest {
    // nothing reported

    @Test fun nothingReportedLeavesTheStoredChoiceUnmarked() {
        for (stored in listOf(1, 3)) {
            val display = ChargerPhases.display(stored, null)

            assertEquals(stored, display.phases)
            assertEquals(ChargerPhases.Status.STORED, display.status)
            assertNull(display.detectedPhases)
            assertNull(display.adoptablePhases)
        }
    }

    // detection agrees

    @Test fun detectionThatAgreesIsMarkedAsReadFromTheCharger() {
        val onePhase = ChargerPhases.display(1, 1)
        assertEquals(ChargerPhases.Status.DETECTED, onePhase.status)
        assertEquals(1, onePhase.phases)
        assertEquals(1, onePhase.detectedPhases)
        // Nothing to adopt: it already is what the plan uses.
        assertNull(onePhase.adoptablePhases)

        val threePhase = ChargerPhases.display(3, 3)
        assertEquals(ChargerPhases.Status.DETECTED, threePhase.status)
        assertEquals(3, threePhase.phases)
        assertEquals(3, threePhase.detectedPhases)
        assertNull(threePhase.adoptablePhases)
    }

    // detection disagrees

    @Test fun detectionThatDisagreesIsSurfacedAndOfferedForAdoption() {
        val detectedThree = ChargerPhases.display(1, 3)

        assertEquals(ChargerPhases.Status.CONFLICT, detectedThree.status)
        // Never silently applied: the plan still uses the stored one.
        assertEquals(1, detectedThree.phases)
        assertEquals(3, detectedThree.detectedPhases)
        assertEquals(3, detectedThree.adoptablePhases)

        val detectedOne = ChargerPhases.display(3, 1)

        assertEquals(ChargerPhases.Status.CONFLICT, detectedOne.status)
        assertEquals(3, detectedOne.phases)
        assertEquals(1, detectedOne.detectedPhases)
        assertEquals(1, detectedOne.adoptablePhases)
    }

    @Test fun aTwoPhaseChargerDisagreesWithBothAndCannotBeAdopted() {
        // Two phases are real (the integration reports them from explicit metadata), and they agree
        // with neither of the app's own values, so they are shown as a disagreement with nothing to
        // adopt: this app plans one phase or three.
        for (stored in listOf(1, 3)) {
            val display = ChargerPhases.display(stored, 2)

            assertEquals(ChargerPhases.Status.CONFLICT, display.status)
            assertEquals(stored, display.phases)
            assertEquals(2, display.detectedPhases)
            assertNull(display.adoptablePhases)
        }
    }

    @Test fun aDetectionOutsideTheAppsOwnModelIsNeverAdoptable() {
        val display = ChargerPhases.display(3, 5)

        assertEquals(ChargerPhases.Status.CONFLICT, display.status)
        assertEquals(3, display.phases)
        assertEquals(5, display.detectedPhases)
        assertNull(display.adoptablePhases)
    }

    // the invariant the whole design rests on

    @Test fun displayNeverMovesTheValueThePlanIsBuiltFrom() {
        for (stored in listOf(1, 3)) {
            for (detected in listOf(null, 1, 2, 3)) {
                assertEquals(
                    "stored=$stored detected=$detected",
                    stored,
                    ChargerPhases.display(stored, detected).phases
                )
            }
        }
    }

    @Test fun theStoredValueIsReadBackInTheAppsOwnPhaseModel() {
        // Whatever a stored value looks like, the readout is what `WidgetSettings.load` would hand
        // the planner (it coerces anything that is not 1 to 3), and what the card's radio group
        // yields.
        assertEquals(1, ChargerPhases.normalized(1))
        assertEquals(3, ChargerPhases.normalized(3))
        assertEquals(3, ChargerPhases.normalized(0))
        assertEquals(3, ChargerPhases.normalized(2))

        assertEquals(3, ChargerPhases.display(2, null).phases)
        assertEquals(1, ChargerPhases.display(1, null).phases)
    }
}
