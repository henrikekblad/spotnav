package se.sensnology.spotnav.ui.charging

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import se.sensnology.spotnav.R
import se.sensnology.spotnav.chargers.ChargerAction
import se.sensnology.spotnav.chargers.ChargerActions
import se.sensnology.spotnav.ha.client.HomeAssistantCommand
import se.sensnology.spotnav.ha.dashboard.AutoControl
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.PlannerCommands
import se.sensnology.spotnav.ha.dashboard.PlannerControl
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.controlCell
import se.sensnology.spotnav.widget.WidgetSettings

/**
 * The charger card's two action cells, side by side like the Home Assistant card's: Each states its
 * axis' state on top and the action below; a cell that is not offered leaves the other the whole
 * width.
 */
internal class ControlCells(
    scope: ViewScope,
    /** The dashboard the cells are drawn from: `null` until one arrives, and again after a failed read. */
    private val dashboard: () -> Dashboard?,
    /**
     * Whether the strategy presentation still offers an automatic control, and which one (see
     * ChargingStrategyUi.automaticControl). Asked rather than assumed so the button and the row
     * cannot disagree, and asked here because this is the only place the button exists.
     */
    private val automaticControl: () -> PlannerControl?,
    /**
     * Handed the one action this charger's state calls for, so the strategy row states the same
     * fact the button does -- one computation, two places that show it.
     */
    private val onPrimaryAction: (ChargerAction?) -> Unit,
    private val currentSettings: () -> WidgetSettings,
    private val send: (HomeAssistantCommand) -> Unit
) : ViewScope(scope) {
    private val actionCell = controlCell()
    private val plannerCell = controlCell()
    private val controlBar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    init {
        controlBar.addView(actionCell.view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        controlBar.addView(plannerCell.view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        // One action button, not a stack:
        actionCell.view.setOnClickListener {
            when (immediateAction()) {
                ChargerAction.START -> {
                    val value = currentSettings()
                    send(HomeAssistantCommand("start", amps = value.chargingAmps, phases = value.chargingPhases))
                }
                ChargerAction.STOP -> send(HomeAssistantCommand("stop"))
                else -> Unit
            }
        }
        // The one automatic control, and it is not a schedule button:
        plannerCell.view.setOnClickListener {
            when (automaticControl()) {
                null -> Unit
                PlannerControl.PAUSE -> {
                    // Exactly the choices Home Assistant offers, in its own order: one is sent at
                    // once, several are asked for.
                    val choices = dashboard()?.control?.pauseChoices.orEmpty()
                    when (choices.size) {
                        0 -> Unit
                        1 -> send(PlannerCommands.pause(choices[0]))
                        else -> AlertDialog.Builder(context)
                            .setTitle(t(R.string.pause_dialog_title))
                            .setItems(choices.map { pauseChoiceText(it) }.toTypedArray()) { _, which ->
                                send(PlannerCommands.pause(choices[which]))
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
                PlannerControl.RESUME -> send(PlannerCommands.resume())
            }
        }
    }

    /** Put the bar on the charger card's body. */
    fun attachTo(body: LinearLayout) {
        body.addView(controlBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
    }

    /**
     * Home Assistant's own answer when the dashboard carried a decision, and the state-only answer
     * otherwise.
     */
    fun immediateAction(): ChargerAction? {
        val held = dashboard()
        val control = held?.control ?: return ChargerActions.primaryAction(held?.chargingEnabled)
        return control.chargerCommand()
    }

    /** What Home Assistant *holds* is the status line's to say, not this stack's. */
    fun refresh() {
        // The one action this charger's state calls for, computed once and shared:
        val action = immediateAction()
        onPrimaryAction(action)
        when (action) {
            null -> actionCell.view.visibility = View.GONE
            // The caption states the state the offered action implies: a Stop on offer means a
            // charge is running.
            ChargerAction.START -> actionCell.show(
                caption = t(R.string.bar_caption_charge_now), icon = R.drawable.ic_play,
                action = t(R.string.bar_start),
                description = "${t(R.string.bar_axis_charging)}: ${t(R.string.bar_state_not_charging)}. ${t(R.string.home_assistant_start)}",
                // A person's Start pauses Auto until the car is full or unplugged (Home Assistant 1.11).
                help = t(R.string.home_assistant_start_help)
            )
            ChargerAction.STOP -> actionCell.show(
                caption = t(R.string.bar_caption_charging), icon = R.drawable.ic_stop,
                action = t(R.string.bar_stop),
                description = "${t(R.string.bar_axis_charging)}: ${t(R.string.bar_state_charging)}. ${t(R.string.home_assistant_stop)}",
                // A person's Stop pauses Auto until the car is unplugged.
                help = t(R.string.home_assistant_stop_help)
            )
        }
        // The automatic control:
        when (automaticControl()) {
            null -> plannerCell.view.visibility = View.GONE
            // A Pause on offer means the schedule is running, a Resume that it is paused.
            PlannerControl.PAUSE -> plannerCell.show(
                caption = t(R.string.bar_caption_schedule_active), icon = R.drawable.ic_pause,
                action = t(R.string.bar_pause),
                description = "${t(R.string.bar_axis_schedule)}: ${t(R.string.bar_state_schedule_active)}. ${t(R.string.home_assistant_pause_automatic)}"
            )
            PlannerControl.RESUME -> plannerCell.show(
                caption = t(R.string.bar_caption_schedule_paused), icon = R.drawable.ic_play,
                action = t(R.string.bar_resume),
                description = "${t(R.string.bar_axis_schedule)}: ${t(R.string.bar_state_schedule_paused)}. ${t(R.string.home_assistant_resume_automatic)}"
            )
        }
        controlBar.visibility =
            if (actionCell.view.visibility == View.VISIBLE || plannerCell.view.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    /** A pause choice in a person's words (the choice vocabulary is closed, see [AutoControl.PAUSE_CHOICES]). */
    private fun pauseChoiceText(choice: String): String = when (choice) {
        AutoControl.PAUSE_NEXT_PERIOD -> t(R.string.pause_choice_next_period)
        AutoControl.PAUSE_UNTIL_TOMORROW -> t(R.string.pause_choice_until_tomorrow)
        else -> t(R.string.pause_choice_until_resumed)
    }
}
