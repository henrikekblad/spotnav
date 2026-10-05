package se.sensnology.spotnav.ui.charging

import se.sensnology.spotnav.chart.DayRollover
import java.time.Instant

/**
 * Re-reads the paired dashboard while the charging screen is in view: every [PERIOD_MS], and sooner
 * when Home Assistant has said when a state of its own ends (its start-up, `starting_up.until`), so
 * a line such as "Starting up…" does not outlast it. Armed by [arm] while the screen is resumed,
 * ended by [cancel] when it stops; the clock and the timer are handed in, as for [DayRollover].
 */
internal class LiveRefresh(
    private val timer: DayRollover.Timer,
    private val now: () -> Instant,
    /** The end Home Assistant named for a passing state, if any, read afresh for each wait. */
    private val nextEnd: () -> Instant?,
    private val onRefresh: () -> Unit
) {
    private var armed = false

    fun arm() {
        armed = true
        schedule()
    }

    /** A new answer may name a sooner end: the wait is worked out again, if armed. */
    fun reconsider() {
        if (armed) schedule()
    }

    fun cancel() {
        armed = false
        timer.cancel()
    }

    /** How long to wait from [at] for the next read. Pure, for the tests. */
    internal fun delayMs(at: Instant, end: Instant?): Long {
        val untilEnd = end?.let { it.toEpochMilli() - at.toEpochMilli() + END_MARGIN_MS }
            ?.takeIf { it > 0 }
        return minOf(PERIOD_MS, untilEnd ?: PERIOD_MS).coerceAtLeast(DayRollover.MIN_WAIT_MS)
    }

    private fun schedule() {
        timer.cancel()
        timer.after(delayMs(now(), nextEnd())) {
            if (!armed) return@after
            onRefresh()
            if (armed) schedule()
        }
    }

    companion object {
        const val PERIOD_MS = 60_000L
        /** Read a moment after the named end, so the answer is already past it. */
        const val END_MARGIN_MS = 2_000L
    }
}
