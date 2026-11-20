package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.chart.DayRollover

/**
 * The reads of a site page while it is in view and the app is in the foreground: the live flows every [liveMs] (10 s,
 * the Now tab), the page's larger document when it opens and then every [slowMs] (60 s). Nothing is read out of view
 * or in the background: [start] when the page comes into view, [stop] when it leaves or the app does. Coming back
 * within [slowMs] reads the live flows at once and the larger document only when its minute is up.
 *
 * The timer and the clock are handed in, as for `LiveRefresh`.
 */
internal class SitePolling(
    private val timer: DayRollover.Timer,
    private val now: () -> Long,
    /** Whether the live flows are wanted (the Now tab is the one shown), read afresh for each tick. */
    private val live: () -> Boolean,
    private val readLive: () -> Unit,
    private val readSlow: () -> Unit,
    private val liveMs: Long = LIVE_MS,
    private val slowMs: Long = SLOW_MS
) {
    private var running = false
    private var lastSlow: Long? = null

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        tick()
    }

    fun stop() {
        running = false
        timer.cancel()
    }

    /** Read the larger document at the next tick whatever its age (the page's subject changed). */
    fun forget() {
        lastSlow = null
    }

    private fun tick() {
        if (!running) return
        val at = now()
        val last = lastSlow
        if (last == null || at - last >= slowMs) {
            lastSlow = at
            readSlow()
        }
        if (live()) readLive()
        timer.cancel()
        timer.after(if (live()) liveMs else slowMs) { tick() }
    }

    companion object {
        const val LIVE_MS = 10_000L
        const val SLOW_MS = 60_000L
    }
}
