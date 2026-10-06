package se.sensnology.spotnav.ui.charging

import android.view.View
import android.widget.Toast
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.chargers.ChargerAction
import se.sensnology.spotnav.ha.authority.DashboardAdmission
import se.sensnology.spotnav.ha.client.CommandRefusal
import se.sensnology.spotnav.ha.client.HomeAssistantCommand
import se.sensnology.spotnav.ha.client.IdentifyVehicle
import se.sensnology.spotnav.ha.dashboard.AutoControl
import se.sensnology.spotnav.ha.dashboard.ConnectionState
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.HaStatusText
import se.sensnology.spotnav.ha.dashboard.PlannerControl
import se.sensnology.spotnav.ha.dashboard.StatusFormat
import se.sensnology.spotnav.ha.dashboard.StatusTone
import se.sensnology.spotnav.ha.session.HaSession
import se.sensnology.spotnav.ha.settings.HaSettingsStrategy
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.vehicles.ChargeLimit
import se.sensnology.spotnav.vehicles.VehicleCardState
import se.sensnology.spotnav.vehicles.VehicleIdentification
import se.sensnology.spotnav.vehicles.VehicleRefresh
import se.sensnology.spotnav.widget.WidgetSettings

/**
 * Everything the charger card and the vehicle card draw from Home Assistant's dashboard: the one
 * read, the commands the buttons send, the re-reads, and the state of the control buttons.
 */
internal class ChargerDashboardController(
    scope: ViewScope,
    private val session: HaSession,
    private val chargerCard: ChargerCard,
    private val vehicleCard: VehicleCard,
    currentSettings: () -> WidgetSettings,
    private val listener: Listener
) : ViewScope(scope) {
    /** What the screen wants to hear from each dashboard, and what it is asked for while one is applied. */
    interface Listener {
        /**
         * Reserve the identity of the dashboard request that is about to go out (see
         * [DashboardAdmission]). Asked rather than owned here because the screen owns the
         * controller:
         */
        fun admitDashboard(): DashboardAdmission?

        /**
         * One dashboard **result** (success or failure), with the request identity it was admitted
         * under.
         */
        fun onResult(admission: DashboardAdmission?, result: Result<Dashboard>)

        /** Whether the strategy presentation still offers an automatic control, and which one. */
        fun automaticControl(): PlannerControl?

        /** The one action this charger's state calls for, so the strategy row states the same fact the button does. */
        fun onPrimaryAction(action: ChargerAction?)

        /**
         * The control decision each accepted dashboard states, so the screen can hold it as a fact
         * and the strategy row repaints from it (see [AutoControl]). Reported *before* the card's
         * own controls refresh, so the row and the button can never show two different decisions.
         */
        fun onAcceptedControl(control: AutoControl?)

        /** The strategies each accepted dashboard states this charger may be set to right now. */
        fun onStrategyOptions(options: Set<HaSettingsStrategy>, needingTotalGridPower: Set<HaSettingsStrategy>)

        /**
         * A car chosen with no car plugged in: nothing to identify, so it becomes the car the charger
         * plans for, through the ordinary settings write.
         */
        fun onVehiclePicked(vehicleId: String)

        /** Home Assistant's dashboard each time one was read (`null` when the read failed). */
        fun onDashboard(dashboard: Dashboard?)

        /**
         * The pace the dashboard is to be read at changed: every few seconds ([pendingRefresh]) after
         * a command or while one is pending, or every half minute while the charge bar shows
         * ([chargeBarShown]).
         */
        fun onPendingRefreshChanged()
    }

    // Home Assistant's dashboard answer (webhook `dashboard`): the one read. Everything below --
    // the status line, the controls, the vehicles, the phases -- is drawn from it.
    private var dashboard: Dashboard? = null

    // The connection line lives on the charger card, as the card's own fact about Home Assistant.
    private val status = chargerCard.status

    // A command the cells sent, or a decision that says one is under way, until the axes offer an
    // action again: it names what the cells show meanwhile.
    private val pendingWatch = ActionPendingWatch { java.time.Instant.now() }

    private val cells = ControlCells(
        scope = this,
        dashboard = { dashboard },
        automaticControl = { listener.automaticControl() },
        onPrimaryAction = { action -> listener.onPrimaryAction(action) },
        currentSettings = currentSettings,
        sentAction = { pendingWatch.sentAction },
        onSent = { action ->
            pendingWatch.onSent(action)
            listener.onPendingRefreshChanged()
        },
        send = { command -> send(command) }
    )

    init {
        // The charger's own re-read: the same fetch, asked for by hand.
        chargerCard.reRead.attachRequest {
            chargerCard.reRead.setInFlight(true)
            fetchDashboard()
        }
        // The card draws the control; the screen owns the connection, so it is the screen that gets
        // asked to do the asking.
        vehicleCard.refreshControl.attachRequest { vehicleId -> reReadVehicle(vehicleId) }
        vehicleCard.limitControl.attachRequest { vehicleId, percent -> setChargeLimit(vehicleId, percent) }
        cells.attachTo(chargerCard.body)
        cells.refresh()
        // A person's answer to which car is plugged in, from the banner or Byt bil.
        chargerCard.identification.attachChoose { vehicleId -> identifyVehicle(vehicleId) }
    }

    /**
     * Send the car a person chose (`identify_vehicle`: the answer, or a correction after a decision),
     * then show the dashboard it left. With no car plugged in there is nothing to identify, and the
     * choice is the planned car instead (the screen's settings write, as the picker made it).
     */
    private fun identifyVehicle(vehicleId: String) {
        chargerCard.identification.setBusy(true)
        session.identifyVehicle(vehicleId) { outcome, read ->
            chargerCard.identification.setBusy(false)
            when (outcome) {
                is IdentifyVehicle.Outcome.Identified -> read?.let { applyDashboard(it.result, it.detectedPhases) }
                IdentifyVehicle.Outcome.NotPluggedIn -> listener.onVehiclePicked(vehicleId)
                IdentifyVehicle.Outcome.NotACandidate ->
                    Toast.makeText(context, t(R.string.identify_not_here), Toast.LENGTH_LONG).show()
                else -> Toast.makeText(context, t(R.string.identify_answer_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Whether a command the cells sent, or a decision that says one is under way, still asks for
     * the dashboard every few seconds (at most [ActionPendingWatch.WINDOW_MS] after it began).
     */
    fun pendingRefresh(): Boolean = pendingWatch.fast()

    /** Whether the charge bar shows, so the dashboard is read at the running charge's pace. */
    fun chargeBarShown(): Boolean = barShown

    private var barShown = false

    /** Put the controls in step with the dashboard now held. */
    fun refreshControls() = cells.refresh()

    /** Write the charger card's own line, or hide it when there is nothing to say. */
    private fun showStatusLine(text: CharSequence, colour: Int = muted) {
        status.text = text
        status.setTextColor(colour)
        status.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    /** The headline in the tone's colour, then each note on a line of its own in the muted one. */
    private fun statusText(parts: HaStatusText.Parts, colour: Int): CharSequence {
        val text = android.text.SpannableStringBuilder()
        parts.headline?.let { text.append(it) }
        for (note in parts.notes) {
            if (text.isNotEmpty()) text.append('\n')
            val start = text.length
            text.append(note)
            text.setSpan(
                android.text.style.ForegroundColorSpan(muted), start, text.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return text
    }

    /**
     * The one path a dashboard outcome takes to the screen: the cards, the status line and the
     * controls. The ordinary fetch, a command's confirmation read and the vehicle card's own re-
     * read all end here, so each shows its new values exactly the way a poll does.
     */
    private fun applyDashboard(result: Result<Dashboard>, detectedPhases: Int?) {
        dashboard = result.getOrNull()
        val wasFast = pendingWatch.fast()
        pendingWatch.onControl(dashboard?.control)
        // A failed read says nothing of whether the sent Start or Stop has taken effect.
        pendingWatch.onCharging(dashboard?.live?.charging)
        listener.onDashboard(dashboard)
        if (pendingWatch.fast() != wasFast) listener.onPendingRefreshChanged()
        // The control decision travels with the same dashboard this whole pass is built from, and
        // it is reported *before* the card's own controls refresh below -- so the row and the
        // button can never show two different decisions.
        listener.onAcceptedControl(dashboard?.control)
        // The same dashboard's own offered strategies, or cheapest-only for a failed fetch --
        // nothing here may guess otherwise.
        listener.onStrategyOptions(
            dashboard?.strategyOptions ?: setOf(HaSettingsStrategy.CHEAPEST),
            dashboard?.strategiesNeedingTotalGridPower.orEmpty()
        )
        // A fresh (or failed) fetch is the only source of the card's snapshot, and it is handed
        // over whole:
        vehicleCard.setStatus(
            dashboard?.let { VehicleCardState.Shot(it.readVehicles, it.capabilities) }
        )
        chargerCard.setDetectedPhases(detectedPhases)
        chargerCard.refreshName()
        // The vehicle-side advisory, from the same accepted dashboard: the integration's own
        // observation of whether the car is taking the charge (see ChargeProgressContract).
        chargerCard.showAdvisory(
            dashboard,
            t(R.string.charge_progress_vehicle_not_requesting_current)
        )
        // The charge's progress under the status, from the same dashboard (see ChargeBarRule).
        val now = java.time.Instant.now()
        val bar = ChargeBarRule.of(dashboard, now)
        chargerCard.showChargeBar(bar, now)
        // A running charge is read every half minute, the rest every minute: a change of pace is news.
        if ((bar != null) != barShown) {
            barShown = bar != null
            listener.onPendingRefreshChanged()
        }
        // Which car is plugged in, from the same dashboard: the question, or the car and how it was decided.
        chargerCard.identification.show(dashboard)
        // The bar and the status already say it charges: the connection line stands down meanwhile.
        chargerCard.showVehicleLine(vehicleLineText(dashboard).takeIf { ChargeBarLayout.connectionLineShown(bar) }, if (dashboard?.connection == ConnectionState.ERROR) ERROR_COLOUR else muted)
        // Home Assistant's own status block words the line (see HaStatusText):
        val held = dashboard
        if (held != null) {
            // `blocking` is the existing error styling; `notice` and `normal` are neutral.
            val colour = if (held.status.tone == StatusTone.BLOCKING) ERROR_COLOUR else muted
            val parts = HaStatusText.parts(
                held.status,
                StatusFormat.of(AppLanguageSettings.language(context), held.market, AppLanguageSettings.numberLocale(context)),
                java.time.Instant.now()
            )
            showStatusLine(text = statusText(parts, colour), colour = colour)
        } else if (!ReadFailureLine.shown(result.exceptionOrNull())) {
            // No contact at all: the screen's banner already says so, in words; a second line with the
            // socket's own message would say the same thing twice.
            showStatusLine(text = "", colour = muted)
        } else {
            showStatusLine(
                text = t(R.string.home_assistant_error, result.exceptionOrNull()?.message ?: ""),
                colour = ERROR_COLOUR
            )
        }
        cells.refresh()
    }

    /** The charger's connection state in words ("Ansluten"); `null` when the dashboard states none. */
    private fun vehicleLineText(held: Dashboard?): String? = held?.connection?.let { state ->
        val connection = t(connectionString(state))
        // While the question is open the line says the charger waits for the answer.
        if (VehicleIdentification.waitingForAnswer(held)) "$connection · ${t(R.string.identify_waiting)}" else connection
    }

    private fun connectionString(state: ConnectionState): Int = when (state) {
        ConnectionState.DISCONNECTED -> R.string.connection_disconnected
        ConnectionState.CONNECTED -> R.string.connection_connected
        ConnectionState.CHARGING -> R.string.connection_charging
        ConnectionState.PAUSED -> R.string.connection_paused
        ConnectionState.FINISHED -> R.string.connection_finished
        ConnectionState.ERROR -> R.string.connection_error
    }

    /** Read the dashboard, store it, and show it. */
    fun fetchDashboard() {
        session.read(admit = { listener.admitDashboard() }, done = ::showRead)
    }

    /** One ordinary read's answer, wherever it came from: the poll, or a follow-up to a command. */
    private fun showRead(read: HaSession.Read) {
        applyDashboard(read.result, read.detectedPhases)
        // One dashboard **result** feeds the card's own facts above *and* the authority resolution
        // for this widget:
        listener.onResult(read.admission, read.result)
        // Whatever put the charger card's icon in motion, this fetch is what it was waiting for --
        // so it stops here, on the same line the values land on.
        chargerCard.reRead.setInFlight(false)
    }

    /**
     * Send one command, show the confirmation read that follows it, and say what came of the send.
     */
    private fun send(command: HomeAssistantCommand) {
        showStatusLine(t(R.string.home_assistant_sending))
        session.send(
            command,
            admit = { listener.admitDashboard() },
            onConfirmed = { sent ->
                // The confirmation read carries the same decision the command changed, so the row
                // and the control button are repainted from it; the line then says what came of the
                // send.
                applyDashboard(sent.result, sent.detectedPhases)
                // The same read is the authority's newest record: a settings write right after a
                // Start or Stop is built on the revision the command left.
                if (sent.result.isSuccess) listener.onResult(sent.admission, sent.result)
                val failure = sent.result.exceptionOrNull()
                showStatusLine(
                    text = when {
                        failure == null -> t(R.string.home_assistant_sent)
                        // A Start with no car: Home Assistant's own refusal, said plainly.
                        CommandRefusal.code(failure) == CommandRefusal.VEHICLE_NOT_CONNECTED ->
                            t(R.string.home_assistant_not_connected)
                        else -> t(R.string.home_assistant_error, failure.message ?: "")
                    },
                    colour = if (sent.result.isSuccess) accent else ERROR_COLOUR
                )
            },
            onRefreshed = ::showRead
        )
    }

    /**
     * The vehicle card's re-read: ask the integration to re-read the selected vehicle's own
     * entities, then take the ordinary dashboard path.
     */
    private fun reReadVehicle(vehicleId: String) {
        vehicleCard.refreshControl.setInFlight(true)
        session.reReadVehicle(vehicleId) { reRead ->
            vehicleCard.refreshControl.setInFlight(false)
            VehicleRefresh.message(reRead.answer)?.let { message ->
                val text = when (message.kind) {
                    VehicleRefresh.Kind.TOO_SOON -> message.retryAfterS
                        ?.let { seconds -> t(R.string.vehicle_card_reread_too_soon, seconds) }
                        ?: t(R.string.vehicle_card_reread_too_soon_shortly)
                    VehicleRefresh.Kind.FAILED -> t(R.string.vehicle_card_reread_failed)
                }
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
            reRead.read?.let { applyDashboard(it.result, it.detectedPhases) }
        }
    }

    /**
     * The vehicle card's charge limit: write the percent the user chose, and say what came of it.
     */
    private fun setChargeLimit(vehicleId: String, percent: Int) {
        vehicleCard.limitControl.setInFlight(true)
        session.setChargeLimit(vehicleId, percent) { answer ->
            vehicleCard.limitControl.setInFlight(false)
            ChargeLimit.message(answer)?.let { message ->
                val text = when (message.kind) {
                    ChargeLimit.Kind.TOO_SOON -> message.retryAfterS
                        ?.let { seconds -> t(R.string.vehicle_limit_too_soon, seconds) }
                        ?: t(R.string.vehicle_card_reread_too_soon_shortly)
                    ChargeLimit.Kind.FAILED -> t(R.string.vehicle_limit_failed)
                }
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        /** The app's error red: a blocked status and a failed send. */
        const val ERROR_COLOUR = 0xFFD65C5C.toInt()
    }
}
