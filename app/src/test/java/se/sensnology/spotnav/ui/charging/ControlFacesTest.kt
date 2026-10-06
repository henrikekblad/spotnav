package se.sensnology.spotnav.ui.charging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.R
import se.sensnology.spotnav.chargers.ChargerAction
import se.sensnology.spotnav.ha.dashboard.AutoControl
import se.sensnology.spotnav.ha.dashboard.PlannerControl

/** The charger card's two cells while a Start or Stop awaits the charger's report. */
class ControlFacesTest {
    private val pending = AutoControl.of(
        AutoControl.ACTION_NONE, AutoControl.REASON_ACTION_PENDING,
        AutoControl.ACTION_NONE, AutoControl.REASON_ACTION_PENDING, emptyList()
    )
    private val noSettings = AutoControl.of(
        AutoControl.ACTION_NONE, "no_settings", AutoControl.ACTION_NONE, "no_settings", emptyList()
    )

    @Test fun theCommandThisAppSentNamesWhatIsUnderWay() {
        assertEquals(PendingAction.STARTING, ControlFaces.pending(pending, ChargerAction.START, charging = true))
        assertEquals(PendingAction.STOPPING, ControlFaces.pending(pending, ChargerAction.STOP, charging = false))
    }

    @Test fun withoutOneTheChargersStateNamesIt() {
        assertEquals(PendingAction.STARTING, ControlFaces.pending(pending, null, charging = false))
        assertEquals(PendingAction.STARTING, ControlFaces.pending(pending, null, charging = null))
        assertEquals(PendingAction.STOPPING, ControlFaces.pending(pending, null, charging = true))
    }

    @Test fun nothingIsPendingWithoutTheReason() {
        assertNull(ControlFaces.pending(noSettings, ChargerAction.START, charging = false))
        assertNull(ControlFaces.pending(null, ChargerAction.START, charging = false))
        // An unknown reason behaves as today: nothing to show.
        val odd = AutoControl.of(AutoControl.ACTION_NONE, "because", AutoControl.ACTION_NONE, "because", emptyList())
        assertNull(ControlFaces.pending(odd, ChargerAction.START, charging = false))
        assertNull(ControlFaces.action(null, ControlFaces.pending(odd, null, false)))
    }

    @Test fun aPendingStartIsShownDisabledAsStarting() {
        val face = ControlFaces.action(null, PendingAction.STARTING)!!
        assertFalse(face.enabled)
        assertEquals(R.string.bar_starting, face.action)
        assertEquals(R.string.bar_caption_charge_now, face.caption)
        assertEquals(R.drawable.ic_play, face.icon)
        // Read out as waiting for the charger, with no help about what a tap would do.
        assertEquals(R.string.bar_waiting_for_charger, face.outcome)
        assertNull(face.help)
    }

    @Test fun aPendingStopIsShownDisabledAsStopping() {
        val face = ControlFaces.action(null, PendingAction.STOPPING)!!
        assertFalse(face.enabled)
        assertEquals(R.string.bar_stopping, face.action)
        assertEquals(R.string.bar_caption_charging, face.caption)
        assertEquals(R.drawable.ic_stop, face.icon)
        assertEquals(R.string.bar_waiting_for_charger, face.outcome)
    }

    @Test fun anOfferedActionIsTheOrdinaryEnabledCell() {
        val start = ControlFaces.action(ChargerAction.START, null)!!
        assertTrue(start.enabled)
        assertEquals(R.string.bar_start, start.action)
        assertEquals(R.string.home_assistant_start_help, start.help)
        val stop = ControlFaces.action(ChargerAction.STOP, PendingAction.STARTING)!!
        assertTrue("an action on offer wins over the other axis's pending", stop.enabled)
        assertEquals(R.string.bar_stop, stop.action)
        assertNull("nothing offered and nothing pending is hidden", ControlFaces.action(null, null))
    }

    @Test fun thePlannerCellKeepsItsCaptionDisabledWhilePending() {
        val active = ControlFaces.planner(null, pending = true, shown = PlannerControl.PAUSE)!!
        assertFalse(active.enabled)
        assertEquals(R.string.bar_caption_schedule_active, active.caption)
        assertEquals(R.string.bar_waiting_for_charger, active.outcome)
        val paused = ControlFaces.planner(null, pending = true, shown = PlannerControl.RESUME)!!
        assertFalse(paused.enabled)
        assertEquals(R.string.bar_caption_schedule_paused, paused.caption)
        // Never shown before: there is no caption to keep.
        assertNull(ControlFaces.planner(null, pending = true, shown = null))
        // Not pending: hidden as today, and an offered control is the ordinary cell.
        assertNull(ControlFaces.planner(null, pending = false, shown = PlannerControl.PAUSE))
        val offered = ControlFaces.planner(PlannerControl.RESUME, pending = true, shown = PlannerControl.PAUSE)!!
        assertTrue(offered.enabled)
        assertEquals(R.string.bar_resume, offered.action)
    }
}
