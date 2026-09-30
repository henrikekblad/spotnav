package se.sensnology.spotnav.chart

/**
 * The one pending boundary redraw: arming replaces any earlier callback, so there is never more than one.
 *
 * [cancel] pauses (a view detach) and leaves the instance reusable; [dispose] is terminal. The view
 * supplies only `postDelayed`/`removeCallbacks`, so the rules are testable without a view.
 */
internal class ChartBoundaryRefresh(
    private val post: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit
) {
    private val fire = Runnable {
        // A fired callback is no longer pending; clear it so [arm] does not cancel it.
        scheduled = null
        armed = false
        onFire()
    }
    private var armed = false
    private var disposed = false
    private var onFire: () -> Unit = {}
    private var scheduled: Runnable? = null

    val pending: Boolean get() = armed

    /** Whether this refresh has been disposed: nothing may be posted after it has. */
    val isDisposed: Boolean get() = disposed

    /**
     * Arm (or re-arm) the one redraw, [delayMillis] from now, doing [action] when it fires.
     * A `null` delay means there is no boundary to wait for and cancels the appointment.
     */
    fun arm(delayMillis: Long?, action: () -> Unit) {
        onFire = action
        cancel()
        if (disposed || delayMillis == null) return
        armed = true
        scheduled = fire
        post(fire, delayMillis)
    }

    /** Stop waiting without ending this refresh's life; [arm] works again afterwards. */
    fun cancel() {
        val pending = scheduled
        scheduled = null
        armed = false
        if (pending != null) cancel(pending)
    }

    /** Terminal stop: a disposed refresh posts nothing more, so no late callback can redraw. */
    fun dispose() {
        disposed = true
        cancel()
    }
}
