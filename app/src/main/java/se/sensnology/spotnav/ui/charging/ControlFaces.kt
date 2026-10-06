package se.sensnology.spotnav.ui.charging

import se.sensnology.spotnav.R
import se.sensnology.spotnav.chargers.ChargerAction
import se.sensnology.spotnav.chargers.ChargerActions
import se.sensnology.spotnav.ha.dashboard.AutoControl
import se.sensnology.spotnav.ha.dashboard.PlannerControl

/** What is under way while Home Assistant awaits the charger's report of a Start or Stop. */
internal enum class PendingAction { STARTING, STOPPING }

/**
 * One control cell as drawn: the caption, icon and action, and the description read out as
 * "[axis]: [state]. [outcome]", followed by [help] when given. A disabled cell is shown but not tapped.
 */
internal data class CellFace(
    val caption: Int,
    val icon: Int,
    val action: Int,
    val axis: Int,
    val state: Int,
    val outcome: Int,
    val help: Int?,
    val enabled: Boolean
)

/** The charger card's two cells, worked out from the decision without a view, for the tests. */
internal object ControlFaces {
    /**
     * What is under way when either axis says `action_pending`: the command this app just sent,
     * or, without one, what the charger's state calls for (not charging, so starting). `null` when
     * nothing is pending, including any reason this build does not know.
     */
    fun pending(control: AutoControl?, sent: ChargerAction?, charging: Boolean?): PendingAction? {
        if (control?.actionPending != true) return null
        return when (sent ?: ChargerActions.primaryAction(charging)) {
            ChargerAction.START -> PendingAction.STARTING
            ChargerAction.STOP -> PendingAction.STOPPING
        }
    }

    /** The action cell: the offered action, else what is under way, else hidden (`null`). */
    fun action(offered: ChargerAction?, pending: PendingAction?): CellFace? = when (offered) {
        // The caption states the state the offered action implies: a Stop on offer means a charge
        // is running.
        ChargerAction.START -> CellFace(
            R.string.bar_caption_charge_now, R.drawable.ic_play, R.string.bar_start,
            R.string.bar_axis_charging, R.string.bar_state_not_charging, R.string.home_assistant_start,
            // A person's Start pauses Auto until the car is full or unplugged (Home Assistant 1.11).
            help = R.string.home_assistant_start_help, enabled = true
        )
        ChargerAction.STOP -> CellFace(
            R.string.bar_caption_charging, R.drawable.ic_stop, R.string.bar_stop,
            R.string.bar_axis_charging, R.string.bar_state_charging, R.string.home_assistant_stop,
            // A person's Stop pauses Auto until the car is unplugged.
            help = R.string.home_assistant_stop_help, enabled = true
        )
        null -> when (pending) {
            null -> null
            PendingAction.STARTING -> CellFace(
                R.string.bar_caption_charge_now, R.drawable.ic_play, R.string.bar_starting,
                R.string.bar_axis_charging, R.string.bar_state_not_charging, R.string.bar_waiting_for_charger,
                help = null, enabled = false
            )
            PendingAction.STOPPING -> CellFace(
                R.string.bar_caption_charging, R.drawable.ic_stop, R.string.bar_stopping,
                R.string.bar_axis_charging, R.string.bar_state_charging, R.string.bar_waiting_for_charger,
                help = null, enabled = false
            )
        }
    }

    /**
     * The automatic control: the offered one, else, while an action is pending, the one last shown
     * ([shown]) kept with its caption but disabled, else hidden (`null`).
     */
    fun planner(offered: PlannerControl?, pending: Boolean, shown: PlannerControl?): CellFace? {
        if (offered != null) return plannerFace(offered, enabled = true)
        if (!pending || shown == null) return null
        return plannerFace(shown, enabled = false)
    }

    private fun plannerFace(control: PlannerControl, enabled: Boolean): CellFace = when (control) {
        // A Pause on offer means the schedule is running, a Resume that it is paused.
        PlannerControl.PAUSE -> CellFace(
            R.string.bar_caption_schedule_active, R.drawable.ic_pause, R.string.bar_pause,
            R.string.bar_axis_schedule, R.string.bar_state_schedule_active,
            if (enabled) R.string.home_assistant_pause_automatic else R.string.bar_waiting_for_charger,
            help = null, enabled = enabled
        )
        PlannerControl.RESUME -> CellFace(
            R.string.bar_caption_schedule_paused, R.drawable.ic_play, R.string.bar_resume,
            R.string.bar_axis_schedule, R.string.bar_state_schedule_paused,
            if (enabled) R.string.home_assistant_resume_automatic else R.string.bar_waiting_for_charger,
            help = null, enabled = enabled
        )
    }
}
