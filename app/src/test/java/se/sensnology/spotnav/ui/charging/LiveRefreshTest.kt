package se.sensnology.spotnav.ui.charging

import org.junit.Assert.assertEquals
import org.junit.Test
import se.sensnology.spotnav.chart.DayRollover
import java.time.Instant

class LiveRefreshTest {
    private class FakeTimer : DayRollover.Timer {
        var delay: Long? = null
        var task: (() -> Unit)? = null
        override fun after(delayMs: Long, task: () -> Unit) { delay = delayMs; this.task = task }
        override fun cancel() { delay = null; task = null }
        fun fire() { val t = task; task = null; t?.invoke() }
    }

    private val t0 = Instant.parse("2026-10-05T09:00:00Z")

    @Test fun readsEveryMinuteWhileArmed() {
        val timer = FakeTimer()
        var reads = 0
        val live = LiveRefresh(timer, { t0 }, { null }) { reads++ }
        live.arm()
        assertEquals(60_000L, timer.delay)
        timer.fire(); timer.fire()
        assertEquals(2, reads)
        assertEquals(60_000L, timer.delay)
    }

    @Test fun readsJustAfterANamedEndThatComesSooner() {
        val live = LiveRefresh(FakeTimer(), { t0 }, { null }) {}
        assertEquals(22_000L, live.delayMs(t0, t0.plusSeconds(20)))
        // An end further away than the period, or already passed, leaves the period:
        assertEquals(60_000L, live.delayMs(t0, t0.plusSeconds(600)))
        assertEquals(60_000L, live.delayMs(t0, t0.minusSeconds(30)))
    }

    @Test fun aSoonerEndNamedMidWaitShortensTheWait() {
        val timer = FakeTimer()
        var end: Instant? = null
        val live = LiveRefresh(timer, { t0 }, { end }) {}
        live.arm()
        assertEquals(60_000L, timer.delay)
        end = t0.plusSeconds(10)
        live.reconsider()
        assertEquals(12_000L, timer.delay)
        live.cancel()
        live.reconsider()
        assertEquals(null, timer.delay)
    }

    @Test fun aCancelledRefreshReadsNothingMore() {
        val timer = FakeTimer()
        var reads = 0
        val live = LiveRefresh(timer, { t0 }, { null }) { reads++ }
        live.arm()
        val pending = timer.task
        live.cancel()
        pending?.invoke()
        assertEquals(0, reads)
        assertEquals(null, timer.delay)
    }

    @Test fun readsEveryFiveSecondsWhileAnActionIsPending() {
        val timer = FakeTimer()
        var fast = false
        var reads = 0
        val live = LiveRefresh(timer, { t0 }, { null }, fast = { fast }) { reads++ }
        live.arm()
        assertEquals(60_000L, timer.delay)
        fast = true
        live.reconsider()
        assertEquals(5_000L, timer.delay)
        timer.fire()
        assertEquals(1, reads)
        assertEquals(5_000L, timer.delay)
        // Nothing pending any more: back to the minute.
        fast = false
        timer.fire()
        assertEquals(2, reads)
        assertEquals(60_000L, timer.delay)
        // A sooner named end still wins over the fast pace only when it is sooner.
        assertEquals(5_000L, live.delayMs(t0, t0.plusSeconds(20), fast = true))
        assertEquals(3_000L, live.delayMs(t0, t0.plusSeconds(1), fast = true))
    }

    @Test fun neverReadsFastOutOfView() {
        val timer = FakeTimer()
        var reads = 0
        val live = LiveRefresh(timer, { t0 }, { null }, fast = { true }) { reads++ }
        // Not armed: a pending action asks for nothing.
        live.reconsider()
        assertEquals(null, timer.delay)
        live.arm()
        assertEquals(5_000L, timer.delay)
        val pending = timer.task
        live.cancel()
        live.reconsider()
        pending?.invoke()
        assertEquals(0, reads)
        assertEquals(null, timer.delay)
    }

    @Test fun readsEveryHalfMinuteWhileAChargeRuns() {
        val timer = FakeTimer()
        var charging = false
        val live = LiveRefresh(timer, { t0 }, { null }, charging = { charging }) {}
        live.arm()
        assertEquals(60_000L, timer.delay)
        charging = true
        live.reconsider()
        assertEquals(30_000L, timer.delay)
        // A pending action is quicker still, and a sooner named end wins over both.
        assertEquals(5_000L, live.delayMs(t0, null, fast = true, charging = true))
        assertEquals(12_000L, live.delayMs(t0, t0.plusSeconds(10), charging = true))
        charging = false
        timer.fire()
        assertEquals(60_000L, timer.delay)
    }

    @Test fun aWakeReadsAtOnceAndStartsTheWaitAfresh() {
        val timer = FakeTimer()
        var reads = 0
        var clock = t0
        val live = LiveRefresh(timer, { clock }, { null }) { reads++ }
        live.arm()
        clock = clock.plusSeconds(40)
        live.readNow()
        assertEquals(1, reads)
        assertEquals(60_000L, timer.delay)
    }

    @Test fun aWakeOutOfViewReadsNothing() {
        val timer = FakeTimer()
        var reads = 0
        val live = LiveRefresh(timer, { t0 }, { null }) { reads++ }
        live.readNow()
        live.arm(); live.cancel()
        live.readNow()
        assertEquals(0, reads)
        assertEquals(null, timer.delay)
    }
}
