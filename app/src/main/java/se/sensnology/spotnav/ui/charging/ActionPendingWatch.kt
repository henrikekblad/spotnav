package se.sensnology.spotnav.ui.charging

import se.sensnology.spotnav.chargers.ChargerAction
import se.sensnology.spotnav.ha.dashboard.AutoControl
import java.time.Instant

/**
 * Whether a Start, Stop, pause or resume is under way, so the charging screen reads the dashboard
 * every few seconds ([LiveRefresh.FAST_PERIOD_MS]) rather than every minute. A window opens when a
 * cell sends a command, or when the dashboard first says `action_pending`; it closes when the axes
 * offer an action again (or the decision names nothing pending), and in any case [WINDOW_MS] after
 * it opened. A Start or Stop this app sent is also followed to its outcome: the reads stay fast until
 * the dashboard's `live.charging` says the charge runs (Start) or has ended (Stop), at most
 * [OUTCOME_WINDOW_MS] -- Home Assistant acknowledges at once, while the charge itself may begin a
 * minute later. The clock is handed in, for the tests.
 */
internal class ActionPendingWatch(private val now: () -> Instant) {
    /** The Start or Stop this app sent in the open window; `null` otherwise. */
    var sentAction: ChargerAction? = null
        private set

    private var since: Instant? = null

    /** The Start or Stop whose outcome the reads still wait for, and since when. */
    private var awaited: ChargerAction? = null
    private var awaitedSince: Instant? = null

    /** A cell sent a command: [action] is the Start or Stop, `null` for a pause or resume. */
    fun onSent(action: ChargerAction?) {
        sentAction = action
        since = now()
        awaited = action
        awaitedSince = if (action == null) null else since
    }

    /** One read's `live.charging`; `null` (a failed read) says nothing of the outcome. */
    fun onCharging(charging: Boolean?) {
        val reached = when (awaited) {
            ChargerAction.START -> charging == true
            ChargerAction.STOP -> charging == false
            else -> false
        }
        if (reached) {
            awaited = null
            awaitedSince = null
        }
    }

    /** One read's decision; `null` (a failed read) leaves the window as it is. */
    fun onControl(control: AutoControl?) {
        if (control == null) return
        if (control.actionPending && control.chargerCommand() == null && control.plannerControl() == null) {
            // Already open (or spent) stays as it is: still pending does not start a new window.
            if (since == null) since = now()
        } else {
            since = null
            sentAction = null
        }
    }

    /** Whether the open window still runs. */
    fun fast(): Boolean {
        val at = now()
        val pending = since?.let { at.isBefore(it.plusMillis(WINDOW_MS)) } == true
        val outcome = awaited != null && awaitedSince?.let { at.isBefore(it.plusMillis(OUTCOME_WINDOW_MS)) } == true
        return pending || outcome
    }

    companion object {
        /** The longest the fast reads run after a command, or after the first pending answer. */
        const val WINDOW_MS = 45_000L

        /** The longest the fast reads wait for a sent Start or Stop to show in `live.charging`. */
        const val OUTCOME_WINDOW_MS = 120_000L
    }
}
