package se.sensnology.spotnav.widget

import se.sensnology.spotnav.ha.client.FetchedDashboard

/**
 * What one batch of widget updates may still spend on the network. A widget update runs inside a broadcast
 * with only seconds to spare, shared by the whole batch; once the share is spent the remaining widgets are
 * drawn from what is held.
 */
internal class WidgetNetworkBudget(
    private val startedAt: Long,
    private val now: () -> Long,
    private val budgetMs: Long = BATCH_BUDGET_MS
) {
    /** The two timeouts of one read, in milliseconds. */
    class Timeouts(val connectMs: Int, val readMs: Int)

    fun remainingMs(): Long = (startedAt + budgetMs - now()).coerceAtLeast(0L)

    /** Timeouts for one dashboard read, or `null` when too little is left; together they never exceed what is left. */
    fun dashboardTimeouts(): Timeouts? {
        val left = remainingMs()
        if (left < MIN_READ_MS) return null
        val connect = minOf(CONNECT_MS, left / 3)
        val read = minOf(READ_MS, left - connect)
        return Timeouts(connect.toInt(), read.toInt())
    }

    companion object {
        /** All the network time one batch may spend on charger reads. */
        const val BATCH_BUDGET_MS = 8_000L

        /** One charger read: connect, then read. */
        const val CONNECT_MS = 2_000L
        const val READ_MS = 3_000L

        /** Less than this is left: do not start a read that cannot finish. */
        const val MIN_READ_MS = 1_000L
    }
}

/**
 * The dashboards one batch of widget updates draws from, and the only place that decides whether a
 * charger is asked for a fresh one.
 *
 * - At most one read per charger per batch; the result (or the stored dashboard, if the read failed or
 *   was not due) is shared by every widget bound to it.
 * - Only when due: a dashboard younger than [WidgetStatusLine.REFRESH_AFTER_MS] is used as is, unless forced.
 * - Only when [network] is allowed; a redraw batch reads the store alone.
 * - Within the budget: no read starts once the batch's share is spent.
 *
 * A successful read is stored at once.
 */
internal class WidgetDashboardSource(
    private val store: WidgetDashboardStore,
    /** One read of [profileId]'s charger; `null` when it cannot be read at all. Throws on failure. */
    private val fetch: (profileId: String, timeouts: WidgetNetworkBudget.Timeouts) -> FetchedDashboard?,
    private val budget: WidgetNetworkBudget,
    private val now: () -> Long,
    private val network: Boolean,
    private val force: Boolean,
    private val logFailure: (String) -> Unit = {}
) {
    private val resolved = HashMap<String, StoredDashboard?>()

    fun latest(profileId: String): StoredDashboard? {
        if (resolved.containsKey(profileId)) return resolved[profileId]
        var current = store.dashboardFor(profileId)
        if (network && WidgetStatusLine.refreshDueAt(current?.capturedAt, now(), force)) {
            val timeouts = budget.dashboardTimeouts()
            if (timeouts != null) {
                try {
                    fetch(profileId, timeouts)?.let { fetched ->
                        val capturedAt = now()
                        store.put(profileId, fetched.body, capturedAt)
                        current = StoredDashboard(fetched.dashboard, capturedAt)
                    }
                } catch (failure: Exception) {
                    // Log only the failure kind, never the address or webhook id.
                    logFailure(failure.javaClass.simpleName)
                }
            }
        }
        resolved[profileId] = current
        return current
    }
}
