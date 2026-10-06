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
import se.sensnology.spotnav.ui.common.ControlCell
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
    /**
     * The Start or Stop this app last sent, while it may still be awaiting the charger's report:
     * it names what is under way (see [ControlFaces.pending]).
     */
    private val sentAction: () -> ChargerAction?,
    /** Told of each command a cell sends, before it goes: the Start or Stop, or `null` for the planner's. */
    private val onSent: (ChargerAction?) -> Unit,
    private val send: (HomeAssistantCommand) -> Unit
) : ViewScope(scope) {
    private val actionCell = controlCell()
    private val plannerCell = controlCell()
    private val controlBar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    /** The automatic control last offered, so a pending action keeps its caption on the cell. */
    private var shownPlanner: PlannerControl? = null

    init {
        controlBar.addView(actionCell.view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        controlBar.addView(plannerCell.view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        // One action button, not a stack:
        actionCell.view.setOnClickListener {
            when (immediateAction()) {
                ChargerAction.START -> {
                    val value = currentSettings()
                    onSent(ChargerAction.START)
                    send(HomeAssistantCommand("start", amps = value.chargingAmps, phases = value.chargingPhases))
                }
                ChargerAction.STOP -> {
                    onSent(ChargerAction.STOP)
                    send(HomeAssistantCommand("stop"))
                }
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
                        1 -> { onSent(null); send(PlannerCommands.pause(choices[0])) }
                        else -> AlertDialog.Builder(context)
                            .setTitle(t(R.string.pause_dialog_title))
                            .setItems(choices.map { pauseChoiceText(it) }.toTypedArray()) { _, which ->
                                onSent(null)
                                send(PlannerCommands.pause(choices[which]))
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
                PlannerControl.RESUME -> {
                    onSent(null)
                    send(PlannerCommands.resume())
                }
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

    /**
     * What Home Assistant *holds* is the status line's to say, not this stack's. While a Start or
     * Stop awaits the charger's report (`action_pending`), the cells stay, disabled, saying what is
     * under way rather than vanishing until the next read.
     */
    fun refresh() {
        // The one action this charger's state calls for, computed once and shared:
        val action = immediateAction()
        onPrimaryAction(action)
        val held = dashboard()
        val pending = ControlFaces.pending(held?.control, sentAction(), held?.chargingEnabled)
        show(actionCell, ControlFaces.action(action, pending))
        // The automatic control:
        val planner = automaticControl()
        // Kept only across a pending action: a control withdrawn for any other reason is forgotten.
        if (planner != null) shownPlanner = planner else if (pending == null) shownPlanner = null
        show(plannerCell, ControlFaces.planner(planner, pending != null, shownPlanner))
        controlBar.visibility =
            if (actionCell.view.visibility == View.VISIBLE || plannerCell.view.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    private fun show(cell: ControlCell, face: CellFace?) {
        if (face == null) {
            cell.view.visibility = View.GONE
            return
        }
        cell.show(
            caption = t(face.caption), icon = face.icon, action = t(face.action),
            description = "${t(face.axis)}: ${t(face.state)}. ${t(face.outcome)}",
            help = face.help?.let { t(it) },
            enabled = face.enabled
        )
    }

    /** A pause choice in a person's words (the choice vocabulary is closed, see [AutoControl.PAUSE_CHOICES]). */
    private fun pauseChoiceText(choice: String): String = when (choice) {
        AutoControl.PAUSE_NEXT_PERIOD -> t(R.string.pause_choice_next_period)
        AutoControl.PAUSE_UNTIL_TOMORROW -> t(R.string.pause_choice_until_tomorrow)
        else -> t(R.string.pause_choice_until_resumed)
    }
}
