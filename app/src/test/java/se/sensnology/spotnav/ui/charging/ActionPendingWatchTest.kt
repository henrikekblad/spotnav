package se.sensnology.spotnav.ui.charging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerAction
import se.sensnology.spotnav.ha.dashboard.AutoControl
import java.time.Instant

/** When the charging screen reads the dashboard every few seconds instead of every minute. */
class ActionPendingWatchTest {
    private var clock = Instant.parse("2026-10-06T09:00:00Z")
    private val watch = ActionPendingWatch { clock }

    private val pending = AutoControl.of(
        AutoControl.ACTION_NONE, AutoControl.REASON_ACTION_PENDING,
        AutoControl.ACTION_NONE, AutoControl.REASON_ACTION_PENDING, emptyList()
    )
    private val offersStop = AutoControl.of(
        AutoControl.ACTION_STOP, null, AutoControl.ACTION_NONE, AutoControl.REASON_ACTION_PENDING, emptyList()
    )
    private val offersStart = AutoControl.of(
        AutoControl.ACTION_START, null, AutoControl.ACTION_PAUSE, null, listOf(AutoControl.PAUSE_UNTIL_RESUMED)
    )

    private fun later(seconds: Long) { clock = clock.plusSeconds(seconds) }

    @Test fun nothingPendingReadsAtTheOrdinaryPace() {
        assertFalse(watch.fast())
        watch.onControl(offersStart)
        assertFalse(watch.fast())
        watch.onControl(null)
        assertFalse(watch.fast())
    }

    @Test fun aSentCommandStartsTheFastReads() {
        watch.onSent(ChargerAction.START)
        assertTrue(watch.fast())
        assertEquals(ChargerAction.START, watch.sentAction)
        // A failed read neither ends nor extends it:
        later(10); watch.onControl(null)
        assertTrue(watch.fast())
    }

    @Test fun theDashboardsOwnPendingStartsThemToo() {
        watch.onControl(pending)
        assertTrue(watch.fast())
        assertNull("nothing was sent from here", watch.sentAction)
    }

    @Test fun anOfferedActionEndsThem() {
        watch.onSent(ChargerAction.START)
        watch.onControl(pending)
        later(5); watch.onControl(pending)
        assertTrue(watch.fast())
        // The charger reported: the immediate axis offers Stop again.
        later(5); watch.onControl(offersStop)
        assertFalse(watch.fast())
        assertNull(watch.sentAction)
    }

    @Test fun theyEndFortyFiveSecondsAfterTheCommand() {
        watch.onSent(ChargerAction.STOP)
        later(44); watch.onControl(pending)
        assertTrue(watch.fast())
        later(1)
        assertFalse(watch.fast())
        // Still pending afterwards does not start a new window:
        later(5); watch.onControl(pending)
        assertFalse(watch.fast())
    }

    @Test fun aNewCommandAfterAnEndedWindowStartsAnother() {
        watch.onSent(ChargerAction.START)
        later(60)
        assertFalse(watch.fast())
        watch.onSent(ChargerAction.STOP)
        assertTrue(watch.fast())
        assertEquals(ChargerAction.STOP, watch.sentAction)
    }

    @Test fun aDecisionWithNothingPendingAndNothingOfferedEndsThem() {
        watch.onControl(pending)
        val noSettings = AutoControl.of(AutoControl.ACTION_NONE, "no_settings", AutoControl.ACTION_NONE, "no_settings", emptyList())
        watch.onControl(noSettings)
        assertFalse(watch.fast())
        // ... so a later pending starts afresh.
        later(120); watch.onControl(pending)
        assertTrue(watch.fast())
    }
}
