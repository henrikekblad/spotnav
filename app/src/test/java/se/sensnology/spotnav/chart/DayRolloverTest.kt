package se.sensnology.spotnav.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DayRolloverTest {
    private class FakeTimer : DayRollover.Timer {
        var delay: Long? = null
        var task: (() -> Unit)? = null
        var sets = 0
        override fun after(delayMs: Long, task: () -> Unit) { delay = delayMs; this.task = task; sets++ }
        override fun cancel() { delay = null; task = null }
        fun fire() { val t = task; delay = null; task = null; t?.invoke() }
    }

    private val zone = ZoneId.of("Europe/Stockholm")
    private var now = Instant.parse("2026-09-22T21:59:00Z") // 23:59 +02:00
    private val timer = FakeTimer()
    private var served = 0
    private val rollover = DayRollover(timer, { now }, { zone }, { served++ })

    @Test fun armingWaitsForTheNextLocalMidnight() {
        rollover.arm()
        assertEquals(60_000L, timer.delay)
    }

    @Test fun atMidnightItServesOnceAndWaitsForTheNextOne() {
        rollover.arm()
        now = Instant.parse("2026-09-22T22:00:01Z")
        timer.fire()
        assertEquals(1, served)
        assertEquals(24 * 3_600_000L - 1_000L, timer.delay)
    }

    @Test fun cancellingLeavesNoAppointmentAndServesNothing() {
        rollover.arm()
        val late = timer.task
        rollover.cancel()
        assertNull(timer.delay)
        now = Instant.parse("2026-09-22T22:05:00Z")
        late?.invoke()
        assertEquals(0, served)
    }

    @Test fun armingAgainReplacesTheEarlierAppointment() {
        rollover.arm()
        rollover.arm()
        assertEquals(60_000L, timer.delay)
        assertEquals(2, timer.sets)
    }

    @Test fun anEarlyFiringWaitsAgainForTheSameBoundaryWithoutServing() {
        rollover.arm()
        now = Instant.parse("2026-09-22T21:59:30Z")
        timer.fire()
        assertEquals(0, served)
        assertEquals(30_000L, timer.delay)
    }

    @Test fun aWaitIsNeverShorterThanTheFloor() {
        now = Instant.parse("2026-09-22T21:59:59.900Z")
        rollover.arm()
        assertEquals(DayRollover.MIN_WAIT_MS, timer.delay)
    }
}
