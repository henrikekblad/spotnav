package se.sensnology.spotnav.chart

import java.time.Instant
import java.time.ZoneId

/**
 * Calls [onRollover] once at the next local midnight of the zone [zone] names, while armed. It is armed by
 * [arm] (again: the earlier appointment is dropped) and ended by [cancel]; nothing re-arms it on its own
 * except the midnight it has just served, so a screen that is stopped holds no appointment. The clock and
 * the timer are handed in, so the scheduling is decided here and the platform only keeps time.
 *
 * A timer that fires early (the clock was set back) finds the instant not yet reached and waits again for
 * the same boundary, at least [MIN_WAIT_MS] away, so it cannot spin.
 */
internal class DayRollover(
    private val timer: Timer,
    private val now: () -> Instant,
    private val zone: () -> ZoneId,
    private val onRollover: () -> Unit
) {
    /** The platform's one-shot timer: runs [task] once after [delayMs], and can drop what it has set. */
    interface Timer {
        fun after(delayMs: Long, task: () -> Unit)
        fun cancel()
    }

    private var armed = false

    fun arm() {
        armed = true
        schedule(DayBoundary.nextMidnight(now(), zone()))
    }

    fun cancel() {
        armed = false
        timer.cancel()
    }

    private fun schedule(boundary: Instant) {
        timer.cancel()
        val wait = (boundary.toEpochMilli() - now().toEpochMilli()).coerceAtLeast(MIN_WAIT_MS)
        timer.after(wait) { fired(boundary) }
    }

    private fun fired(boundary: Instant) {
        if (!armed) return
        if (now() < boundary) return schedule(boundary)
        // The day has changed: serve it, then wait for the next midnight.
        onRollover()
        if (armed) schedule(DayBoundary.nextMidnight(now(), zone()))
    }

    companion object {
        const val MIN_WAIT_MS = 1_000L
    }
}
