package se.sensnology.spotnav.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one boundary appointment: replaced rather than multiplied, cancelled on disposal. */
class ChartBoundaryRefreshTest {
    private class Recorder {
        val posted = mutableListOf<Pair<Runnable, Long>>()
        val cancelled = mutableListOf<Runnable>()

        /** The callbacks the platform is actually holding: one, or none, ever. */
        private val live = linkedSetOf<Runnable>()
        val refresh = ChartBoundaryRefresh(
            post = { runnable, delay -> posted += runnable to delay; live += runnable },
            cancel = { runnable -> if (live.remove(runnable)) cancelled += runnable }
        )

        val waiting: Int get() = live.size

        /** Run the newest waiting callback, as the platform would when its instant arrives. */
        fun fireLast() {
            val fired = live.last()
            live.remove(fired)
            fired.run()
        }
    }

    @Test
    fun armingReplacesTheAppointmentRatherThanAddingOne() {
        val recorder = Recorder()
        var fired = 0

        recorder.refresh.arm(1_000L) { fired++ }
        assertTrue(recorder.refresh.pending)
        assertEquals(1, recorder.waiting)

        recorder.refresh.arm(2_000L) { fired++ }
        assertEquals("still exactly one appointment", 1, recorder.waiting)
        assertEquals("the superseded one was cancelled", 1, recorder.cancelled.size)
        assertEquals("and the newest delay is the one posted", 2_000L, recorder.posted.last().second)

        recorder.fireLast()
        assertEquals("only the surviving appointment fires", 1, fired)
    }

    @Test
    fun aNullBoundaryCancelsInsteadOfWaitingForAnIntervalThatHasGone() {
        val recorder = Recorder()
        recorder.refresh.arm(1_000L) { }
        assertEquals(1, recorder.waiting)

        recorder.refresh.arm(null) { }

        assertFalse(recorder.refresh.pending)
        assertEquals("nothing is waiting any more", 0, recorder.waiting)
        assertEquals(1, recorder.cancelled.size)
    }

    @Test
    fun disposalCancelsTheAppointmentAndPostsNothingAfterwards() {
        val recorder = Recorder()
        var fired = 0
        recorder.refresh.arm(1_000L) { fired++ }

        recorder.refresh.dispose()

        assertTrue(recorder.refresh.isDisposed)
        assertFalse(recorder.refresh.pending)
        assertEquals("exactly the one appointment, cancelled", 0, recorder.waiting)

        recorder.refresh.arm(5_000L) { fired++ }
        assertEquals("a disposed refresh posts nothing, however often it is armed", 0, recorder.waiting)
        assertEquals("and nothing was posted at all after disposal", 1, recorder.posted.size)
        assertEquals(0, fired)
    }

    @Test
    fun aDetachPausesAndAReattachmentArmsAgain() {
        val recorder = Recorder()
        var fired = 0

        recorder.refresh.arm(1_000L) { fired++ }
        assertEquals(1, recorder.waiting)

        recorder.refresh.cancel()
        assertFalse(recorder.refresh.pending)
        assertFalse("a detach is not the end of this refresh", recorder.refresh.isDisposed)
        assertEquals(0, recorder.waiting)

        // Reattach: the same instance arms again, and exactly one callback waits.
        recorder.refresh.arm(900L) { fired++ }
        assertTrue(recorder.refresh.pending)
        assertEquals("exactly one callback after reattachment", 1, recorder.waiting)

        recorder.fireLast()
        assertEquals(1, fired)
        assertEquals(0, recorder.waiting)
    }

    @Test
    fun aFiredAppointmentSchedulesItsSuccessorThroughTheSameOneSlot() {
        val recorder = Recorder()
        var fired = 0

        // The shape the view arms: redraw, then re-arm for the next interval boundary.
        val action: () -> Unit = {
            fired++
            recorder.refresh.arm(900L) { fired++ }
        }
        recorder.refresh.arm(1_000L, action)
        recorder.fireLast()

        assertEquals("the appointment fired once and its successor is waiting", 1, fired)
        assertTrue("the successor is waiting", recorder.refresh.pending)
        assertEquals("the fired slot was replaced, not doubled", 1, recorder.waiting)
        assertEquals(900L, recorder.posted.last().second)

        recorder.fireLast()
        assertEquals("and the successor fires too", 2, fired)
    }
}
