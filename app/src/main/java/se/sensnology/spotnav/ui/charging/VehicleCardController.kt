package se.sensnology.spotnav.ui.charging

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.ha.client.VehicleUpdate
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ui.common.PopoverSpec
import se.sensnology.spotnav.ui.common.Spin
import se.sensnology.spotnav.ui.common.Tick
import se.sensnology.spotnav.ui.common.TickAxis
import se.sensnology.spotnav.ui.common.ValueCue
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.card
import se.sensnology.spotnav.ui.common.headerSpinnerAdapter
import se.sensnology.spotnav.ui.common.number
import se.sensnology.spotnav.ui.common.numberField
import se.sensnology.spotnav.ui.common.popoverControl
import se.sensnology.spotnav.ui.common.reReadIcon
import se.sensnology.spotnav.ui.common.showValuePopover
import se.sensnology.spotnav.ui.common.slider
import se.sensnology.spotnav.ui.common.valueLabel
import se.sensnology.spotnav.ui.common.valueRow
import se.sensnology.spotnav.ui.common.weight
import se.sensnology.spotnav.vehicles.PairedVehicles
import se.sensnology.spotnav.vehicles.SocDisplay
import se.sensnology.spotnav.vehicles.VehicleCapacityStore
import se.sensnology.spotnav.vehicles.VehicleCardState
import se.sensnology.spotnav.vehicles.VehicleEnergy
import se.sensnology.spotnav.vehicles.VehicleFacts
import se.sensnology.spotnav.vehicles.VehicleStatus
import se.sensnology.spotnav.widget.WidgetSettings
import java.util.Locale

/**
 * The vehicle card: the car, its state of charge, its capacity and consumption -- the second object
 * card.
 */
internal class VehicleCardController(scope: ViewScope) : ViewScope(scope) {
    /**
     * The vehicle card — the second object card, and the one that exists in every state: the user
     * owns a car whether or not Home Assistant can see it.
     */
    fun add(
        parent: LinearLayout,
        settings: WidgetSettings,
        profile: ChargerProfile?,
        shareRow: Boolean = false
    ): VehicleCard {
        val card = card(parent, t(R.string.vehicle_title), R.drawable.ic_ev, shareRow)
        // The title is the car's own name, and doubles as the selector only when there is more than
        // one car to choose between.
        val vehicleSpinner = Spinner(context).apply {
            visibility = View.GONE
            adapter = headerSpinnerAdapter(listOf(t(R.string.vehicle_none)))
        }
        card.heading.addView(vehicleSpinner, weight())

        // The header's other occupant, and the card's only control that talks to Home Assistant:
        val refreshIcon = reReadIcon()
        val refreshSpin = Spin(refreshIcon)
        card.header.addView(refreshIcon)

        // Capacity: an input in a popover, a value on the card. Which one the card shows depends on
        // whether a capacity is known -- never on whether the row exists.
        val capacityControl = popoverControl()
        val capacityField = numberField(0.0, t(R.string.vehicle_card_capacity_label))
        capacityControl.addView(capacityField)
        val capacitySave = Button(context).apply {
            text = t(R.string.vehicle_capacity_save)
            isAllCaps = false
        }
        capacityControl.addView(capacitySave, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
        ).apply { topMargin = dp(6) })

        // Consumption per mil: a car's own property, so it belongs on this card, even though how it
        // is stored is per widget (see the report).
        val consumptionControl = popoverControl()
        val consumptionValue = valueLabel()
        val baseConsumption = addConsumptionControls(consumptionControl, consumptionValue, settings)
        // A paired charger's consumption is the *vehicle's* (dashboard `vehicles`), not the
        // slider's:
        var paintPairedConsumption: () -> Unit = {}
        val consumption = ConsumptionControls(baseConsumption.consumption, baseConsumption.valueLabel) {
            baseConsumption.refreshValueLabel()
            paintPairedConsumption()
        }

        // A paired charger's dashboard (null for an unpaired one, whose selection stays the local
        // one) and the vehicle the card shows, by the dashboard's own id.
        var pairedDash: Dashboard? = null
        var pairedShownId: String? = null
        var onPairedPick: (String) -> Unit = {}
        // The vehicle the reader looked at without a target to write (no `soc` block): view state
        // only.
        var viewPick: String? = null

        val socValue = valueLabel()
        val limitValue = valueLabel()
        val capacityValue = valueLabel()
        val capacityPopoverValue = valueLabel()
        var capacityDialog: AlertDialog? = null
        var consumptionDialog: AlertDialog? = null
        var limitDialog: AlertDialog? = null

        // Charge limit: the card's second control that talks to Home Assistant, and the only one
        // that *writes* to the car (see [ChargeLimit]).
        val limitControl = popoverControl()
        val limitPopoverValue = valueLabel()
        // Whether a write is on its way.
        var limitInFlight = false
        // The screen owns the connection, so it is asked to do the writing -- twice-borrowed, like
        // `requestRefresh` below.
        var requestLimit: (String, Int) -> Unit = { _, _ -> }
        val limitSlider = slider(null, 1, 100, 1, limitPopoverValue, limitControl) { value ->
            limitPopoverValue.text = t(R.string.vehicle_card_limit_value, value)
        }

        // The state of charge is a reading, and only a reading:
        val socRow = valueRow(card.body, t(R.string.vehicle_card_soc_label), socValue)
        // The limit row's popover is attached further down, beside the other listeners declared
        // after what they read:
        val limitRow = valueRow(card.body, t(R.string.vehicle_card_limit_label), limitValue)
        val capacityRow = valueRow(card.body, t(R.string.vehicle_card_capacity_label), capacityValue) {
            // A paired charger's capacity is changed in Settings, in the vehicle's own dialog.
            if (pairedDash != null) return@valueRow
            capacityDialog = showValuePopover(capacityDialog, PopoverSpec(
                eyebrow = t(R.string.popover_eyebrow_vehicle),
                title = t(R.string.vehicle_card_capacity_label),
                value = capacityPopoverValue,
                control = capacityControl,
                note = t(R.string.vehicle_card_capacity_note)
            ))
        }
        // A battery size the car reports itself is read-only, and says so under the row.
        val capacityNote = TextView(context).apply {
            textSize = 13f; setTextColor(muted); setPadding(0, 0, 0, dp(4)); gravity = Gravity.END
            visibility = View.GONE
        }
        card.body.addView(capacityNote)
        // Always shown for an unpaired charger: consumption is the car's own property, in every
        // state.
        val consumptionRow = valueRow(card.body, t(R.string.consumption), consumptionValue) {
            if (pairedDash != null) return@valueRow
            consumptionDialog = showValuePopover(consumptionDialog, PopoverSpec(
                eyebrow = t(R.string.popover_eyebrow_vehicle),
                title = t(R.string.consumption),
                value = consumption.valueLabel,
                control = consumptionControl,
                note = t(R.string.vehicle_card_consumption_note),
                tickAxis = CONSUMPTION_AXIS
            ))
        }

        // The car's onboard charger (a paired vehicle only): read-only here, like the capacity and the
        // consumption, and changed in Settings, in the vehicle's own dialog.
        val onboardValue = valueLabel()
        val onboardRow = valueRow(card.body, t(R.string.vehicle_onboard_label), onboardValue)
        onboardRow.view.visibility = View.GONE

        val capacityStore = VehicleCapacityStore.forContext(applicationContext)
        val profileStore = ChargerProfileStore.forContext(applicationContext)
        val profileId = profile?.localId
        // What the card is showing: the vehicles the instance last reported, and the capabilities
        // that arrived with them.
        var shown = VehicleCardState.NOTHING_FETCHED
        var selectedVehicleId: String? = profile?.selectedVehicleId
        // Who the card is about, and what capacity is remembered for it:
        var factsListener: ((VehicleStatus?, Double?) -> Unit)? = null
        // The spinner's own listener fires while its adapter and selection are being rebuilt;
        // without this guard that re-entrancy would persist whatever row happened to be selected
        // mid-rebuild (the "No vehicle" row), silently clearing the user's own choice.
        var repopulatingVehicles = false

        // A paired charger's vehicle is matched by the dashboard's own id in the status list (the
        // same Home Assistant device id); an unpaired charger keeps the local selection and its
        // fallbacks.
        fun effectiveVehicle(): VehicleStatus? =
            if (pairedDash != null) PairedVehicles.statusVehicle(shown.vehicles, pairedShownId)
            else VehicleFacts.effectiveVehicle(shown.vehicles, selectedVehicleId)

        /**
         * A fresh list, a new selection and a capacity the user just entered all come through here,
         * so target-SoC mode can appear and disappear with them.
         */
        fun reportFacts() {
            val vehicle = effectiveVehicle()
            val dash = pairedDash
            // A paired charger's capacity is Home Assistant's (the dashboard row first, see
            // PairedVehicles.capacityKwh); the local store only stands in when Home Assistant
            // states none.
            val capacity = if (dash != null) PairedVehicles.capacityKwh(
                dash, PairedVehicles.row(dash, emptyMap(), pairedShownId), vehicle,
                pairedShownId?.let { capacityStore.get(it) }, pairedShownId
            ) else vehicle?.let { capacityStore.get(it.id) }
            factsListener?.invoke(vehicle, capacity)
        }

        // What the re-read control shows: anything in flight, and the handler the screen attaches
        // (it owns the connection).
        var refreshInFlight = false
        var requestRefresh: (String) -> Unit = {}

        /**
         * Draw both of this card's Home Assistant controls from [VehicleCardState.controls] -- the
         * pure decision -- so they are always read from the same live snapshot and can never
         * disagree about which fetch they came from.
         */
        fun paintControls() {
            val controls = VehicleCardState.controls(
                shot = shown,
                vehicle = effectiveVehicle(),
                refreshInFlight = refreshInFlight,
                limitInFlight = limitInFlight
            )
            refreshIcon.visibility = if (controls.refresh.visible) View.VISIBLE else View.GONE
            refreshIcon.isEnabled = controls.refresh.enabled
            refreshIcon.imageTintList = ColorStateList.valueOf(if (controls.refresh.enabled) accent else muted)
            refreshSpin.set(refreshInFlight)
            // The limit row's cue is the whole of it:
            limitRow.show(ValueCue.of(editable = controls.limit.visible, available = controls.limit.enabled))
        }
        refreshIcon.setOnClickListener {
            // `isEnabled` already is the pure decision above: a press cannot arrive with no vehicle
            // selected, or while one is in flight.
            effectiveVehicle()?.let { vehicle -> requestRefresh(vehicle.id) }
        }

        val render = {
            val dash = pairedDash
            val pairedChoices = dash?.let { PairedVehicles.choices(it) }.orEmpty()
            val pairedRow = dash?.let { PairedVehicles.row(it, emptyMap(), pairedShownId) }
            val pairedName = dash?.let { pairedRow?.name ?: PairedVehicles.name(it, pairedShownId) }
            val content = VehicleFacts.content(shown.vehicles, selectedVehicleId)
            val selectable = if (dash != null) pairedChoices.isNotEmpty() else content.selectable
            // The vehicle the status list knows by this id (paired) or the local selection
            // (unpaired).
            val vehicle = if (dash != null) effectiveVehicle() else content.vehicle

            // The title is the car's name, or the selector when there is more than one car to
            // choose between.
            card.title.text = if (dash != null) pairedName ?: t(R.string.vehicle_title)
                else content.vehicle?.name ?: t(R.string.vehicle_title)
            card.title.visibility = if (selectable) View.GONE else View.VISIBLE
            vehicleSpinner.visibility = if (selectable) View.VISIBLE else View.GONE
            repopulatingVehicles = true
            if (dash != null) {
                vehicleSpinner.adapter = headerSpinnerAdapter(pairedChoices.map { it.name })
                vehicleSpinner.setSelection(pairedChoices.indexOfFirst { it.id == pairedShownId }.coerceAtLeast(0), false)
            } else {
                vehicleSpinner.adapter = headerSpinnerAdapter(listOf(t(R.string.vehicle_none)) + shown.vehicles.map { it.name })
                vehicleSpinner.setSelection(shown.vehicles.indexOfFirst { it.id == selectedVehicleId } + 1, false)
            }
            // `AdapterView` dispatches its selection callback on the next layout pass, not inline,
            // so clearing the flag here would clear it *before* the callback it exists to suppress
            // ever arrives -- which is how a stored vehicle was silently unselected on every
            // launch:
            vehicleSpinner.post { repopulatingVehicles = false }

            // State rows are present exactly when there is a selected vehicle that reports them;
            // otherwise they are gone, saying nothing.
            val socNow = if (dash != null) PairedVehicles.socPercent(dash, vehicle, pairedShownId)
                else vehicle?.socPercent
            socRow.view.visibility = if (socNow != null) View.VISIBLE else View.GONE
            socNow?.let { socValue.text = t(R.string.vehicle_card_soc_value, SocDisplay.wholePercent(it)) }
            val limit = vehicle?.let { VehicleFacts.chargeLimit(it) }
            limitRow.view.visibility = if (limit != null) View.VISIBLE else View.GONE
            limit?.let { limitValue.text = t(R.string.vehicle_card_limit_value, it) }

            if (dash != null) {
                // Capacity and consumption are the vehicle's own, from the dashboard row: a
                // capacity the car reports is read-only, "reported by the vehicle".
                val capacityKwh = PairedVehicles.capacityKwh(
                    dash, pairedRow, vehicle, pairedShownId?.let { capacityStore.get(it) }, pairedShownId
                )
                capacityValue.text = if (capacityKwh != null) t(R.string.vehicle_card_capacity_value, capacityKwh)
                    else t(R.string.vehicle_card_capacity_unknown)
                capacityPopoverValue.text = capacityValue.text
                capacityRow.view.visibility = if (pairedRow != null) View.VISIBLE else View.GONE
                val capacityEditable = pairedRow != null && VehicleUpdate.capacityEditable(pairedRow)
                capacityRow.show(ValueCue.READ_ONLY)
                capacityNote.text = t(R.string.vehicle_capacity_reported)
                capacityNote.visibility = if (pairedRow != null && !capacityEditable) View.VISIBLE else View.GONE
                consumptionRow.view.visibility = if (pairedRow != null) View.VISIBLE else View.GONE
                // Consumption is changed in Settings, in the vehicle's own dialog.
                consumptionRow.show(ValueCue.READ_ONLY)
                val onboard = pairedRow?.onboardPhases
                onboardRow.view.visibility = if (onboard != null) View.VISIBLE else View.GONE
                onboardRow.show(ValueCue.READ_ONLY)
                if (onboard != null) onboardValue.text = t(if (onboard == 1) R.string.phase_one else R.string.phase_three)
            } else {
                onboardRow.view.visibility = View.GONE
                capacityNote.visibility = View.GONE
                consumptionRow.view.visibility = View.VISIBLE
                consumptionRow.show(ValueCue.EDITABLE)

                // A capacity is a property of the car, detected or entered once.
                val capacity = content.vehicle?.let { VehicleEnergy.effectiveCapacityKwh(it, capacityStore.get(it.id)) }
                capacityValue.text = when {
                    capacity != null -> t(R.string.vehicle_card_capacity_value, capacity)
                    content.capacitySettable -> t(R.string.vehicle_card_capacity_unknown)
                    // No car selected:
                    else -> ""
                }
                capacityPopoverValue.text = capacityValue.text
                // A capacity belongs to a car:
                capacityRow.show(ValueCue.of(editable = content.capacitySettable))
                capacityRow.view.visibility = if (content.capacitySettable) View.VISIBLE else View.GONE
            }
            consumption.refreshValueLabel()
            reportFacts()
            // Both Home Assistant controls answer the same two questions the rows do -- which car,
            // and whether this charger's integration can be asked -- so they are repainted wherever
            // those are.
            paintControls()
        }
        paintPairedConsumption = {
            val dash = pairedDash
            if (dash != null) {
                val figure = PairedVehicles.row(dash, emptyMap(), pairedShownId)?.consumptionKwhPer10km
                consumptionValue.text = if (figure != null) t(R.string.consumption_value, figure)
                    else t(R.string.vehicle_card_consumption_unknown)
            }
        }

        vehicleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Two guards, for two different ways this fires without a user:
                val dash = pairedDash
                if (dash != null) {
                    // A paired charger's choice is Home Assistant's own field
                    // (`target.vehicle_id`):
                    if (repopulatingVehicles) return
                    val picked = PairedVehicles.choices(dash).getOrNull(position)?.id ?: return
                    if (picked == pairedShownId) return
                    pairedShownId = picked
                    if (dash.soc == null) viewPick = picked
                    render()
                    if (dash.soc != null) onPairedPick(picked)
                    return
                }
                if (repopulatingVehicles || shown.vehicles.isEmpty()) return
                val newId = shown.vehicles.getOrNull(position - 1)?.id
                if (newId == selectedVehicleId) return
                selectedVehicleId = newId
                // The user's own choice, stored on this charger profile -- never derived from a
                // status response.
                profileId?.let { localId ->
                    profileStore.updateProfile(localId) { it.copy(selectedVehicleId = newId) }
                }
                render()
            }
        }
        capacitySave.setOnClickListener {
            val vehicle = effectiveVehicle() ?: return@setOnClickListener
            val kwh = number(capacityField)
            if (kwh > 0.0) {
                capacityStore.set(vehicle.id, kwh)
                render()
            }
        }

        /**
         * The limit row's tap: The row is tappable only while there is a live limit (see
         * `paintControls`), and the reading moves between two openings.
         */
        limitRow.tap {
            val vehicle = effectiveVehicle() ?: return@tap
            val current = VehicleFacts.chargeLimit(vehicle) ?: return@tap
            limitPopoverValue.text = t(R.string.vehicle_card_limit_value, current)
            // A `SeekBar`'s progress is 0-based from its minimum, so the slider and `progress + 1`
            // are the same value said two ways.
            limitSlider.progress = current - 1
            limitDialog = showValuePopover(
                limitDialog,
                PopoverSpec(
                    eyebrow = t(R.string.popover_eyebrow_vehicle),
                    title = t(R.string.vehicle_card_limit_label),
                    value = limitPopoverValue,
                    control = limitControl,
                    note = t(R.string.vehicle_limit_hint)
                ),
                confirmLabel = t(R.string.vehicle_limit_set)
            ) {
                // The vehicle is read at the press rather than captured when the dialog was built
                // -- it is re-shown rather than rebuilt, and the car can change between two
                // openings.
                effectiveVehicle()?.let { chosen ->
                    requestLimit(chosen.id, limitSlider.progress + 1)
                }
            }
        }

        render()
        return VehicleCard(
            body = card.body,
            consumption = consumption,
            setStatus = { fetched ->
                shown = VehicleCardState.next(shown, fetched)
                render()
            },
            applyPaired = { dashboard, recordVehicleId ->
                pairedDash = dashboard
                pairedShownId = dashboard?.let { PairedVehicles.shownId(it, viewPick ?: recordVehicleId) }
                render()
            },
            attachPairedPick = { handler -> onPairedPick = handler },
            attachFactsListener = { listener ->
                factsListener = listener
                // Seed immediately:
                reportFacts()
            },
            refreshControl = VehicleRefreshControl(
                // The screen owns the connection, so the handler arrives from there -- twice-
                // borrowed, like `factsListener` above.
                attachRequest = { handler -> requestRefresh = handler },
                setInFlight = { inFlight ->
                    refreshInFlight = inFlight
                    paintControls()
                }
            ),
            limitControl = VehicleLimitControl(
                attachRequest = { handler -> requestLimit = handler },
                setInFlight = { inFlight ->
                    limitInFlight = inFlight
                    paintControls()
                }
            )
        )
    }

    /** How much the car uses per mil: Heading for the settings gear. */
    private fun addConsumptionControls(parent: LinearLayout, cardValue: TextView, settings: WidgetSettings): ConsumptionControls {
        val consumptionValue = valueLabel()
        // No heading: the popover renders the name and this value itself.
        val consumption = slider(null, 10, 40, (settings.consumptionKwhPerMil * 10).toInt(), consumptionValue, parent) { value ->
            consumptionValue.text = t(R.string.consumption_value, value / 10.0)
        }
        return ConsumptionControls(
            consumption = consumption,
            valueLabel = consumptionValue,
            refreshValueLabel = {
                // The piece owns the number, so it owns every label showing it: the popover's large
                // value, and the card's row.
                val text = t(R.string.consumption_value, (consumption.progress + 10) / 10.0)
                consumptionValue.text = text
                cardValue.text = text
            }
        )
    }

    private companion object {
        val CONSUMPTION_AXIS = TickAxis(10, 40, listOf(
            Tick(10, "1.0"), Tick(20, "2.0"), Tick(30, "3.0"), Tick(40, "4.0")
        ))
    }
}

/** What [VehicleCardController.add] hands back: */
internal class VehicleCard(
    val body: LinearLayout,
    val consumption: ConsumptionControls,
    /** The result of one status fetch, or `null` for one that failed: see [VehicleCardState.next]. */
    val setStatus: (VehicleCardState.Shot?) -> Unit,
    val attachFactsListener: (((VehicleStatus?, Double?) -> Unit) -> Unit),
    /**
     * A paired charger's dashboard and the vehicle its record names (or `null`, `null` for an
     * unpaired one): the title shows the vehicle by the dashboard's own id, the rows are the
     * dashboard's, and the picker offers its vehicles.
     */
    val applyPaired: (Dashboard?, String?) -> Unit,
    /** Set by the screen: a vehicle was picked in the paired picker, and its id is to be written. */
    val attachPairedPick: (((String) -> Unit) -> Unit),
    val refreshControl: VehicleRefreshControl,
    /** The card's other Home Assistant control: the charge-limit write. */
    val limitControl: VehicleLimitControl
)

/**
 * What the vehicle card's re-read control needs from the screen, which is the only thing that owns
 * a connection: something to call when it is pressed, and a way to be told a request is running.
 */
internal class VehicleRefreshControl(
    /** Set by the screen: re-read this vehicle, through its session. */
    val attachRequest: (((String) -> Unit) -> Unit),
    /** Tell the card a request is in flight, or no longer is -- the spin. */
    val setInFlight: (Boolean) -> Unit
)

/**
 * What the vehicle card's charge-limit control needs from the screen, in the same two halves
 * [VehicleRefreshControl] has:
 */
internal class VehicleLimitControl(
    /** Set by the screen: write this percent to this vehicle, through its session. */
    val attachRequest: (((String, Int) -> Unit) -> Unit),
    /** Tell the card a write is in flight, or no longer is. */
    val setInFlight: (Boolean) -> Unit
)

/** What [addConsumptionControls] hands back. */
internal class ConsumptionControls(
    val consumption: SeekBar,
    val valueLabel: TextView,
    val refreshValueLabel: () -> Unit
)
