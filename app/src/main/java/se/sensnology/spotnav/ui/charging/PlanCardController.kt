package se.sensnology.spotnav.ui.charging

import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.chart.PlanChartView
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.planning.ChargeNeed
import se.sensnology.spotnav.planning.PlanDriver
import se.sensnology.spotnav.planning.PlanMode
import se.sensnology.spotnav.planning.PlanReadout
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.ui.Screen
import se.sensnology.spotnav.ui.ScreenShell
import se.sensnology.spotnav.ui.common.DRIVER_SLOT_BOTTOM_DP
import se.sensnology.spotnav.ui.common.DRIVER_SLOT_TOP_DP
import se.sensnology.spotnav.ui.common.HEADER_CONTROL_GAP_DP
import se.sensnology.spotnav.ui.common.PopoverSpec
import se.sensnology.spotnav.ui.common.Tick
import se.sensnology.spotnav.ui.common.TickAxis
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.card
import se.sensnology.spotnav.ui.common.chartDescription
import se.sensnology.spotnav.ui.common.checkbox
import se.sensnology.spotnav.ui.common.compactIconAction
import se.sensnology.spotnav.ui.common.expandActionTarget
import se.sensnology.spotnav.ui.common.onLaidOut
import se.sensnology.spotnav.ui.common.popoverControl
import se.sensnology.spotnav.ui.common.readoutLines
import se.sensnology.spotnav.ui.common.resultRow
import se.sensnology.spotnav.ui.common.resultValue
import se.sensnology.spotnav.ui.common.showValuePopover
import se.sensnology.spotnav.ui.common.slider
import se.sensnology.spotnav.ui.common.valueLabel
import se.sensnology.spotnav.ui.common.valueRow
import se.sensnology.spotnav.vehicles.PairedTarget
import se.sensnology.spotnav.vehicles.VehicleEnergy
import se.sensnology.spotnav.vehicles.VehicleStatus
import se.sensnology.spotnav.widget.WidgetSettings

internal class PlanCardController(scope: ViewScope, private val shell: ScreenShell) : ViewScope(scope) {
    fun add(
        parent: LinearLayout,
        settings: WidgetSettings,
        profile: ChargerProfile?,
        persistTargetSoc: (Int) -> Unit
    ): PlanCard {
        val card = card(parent, t(R.string.card_planning_title), R.drawable.ic_card_plan)
        // The header of this card ends in a *control* rather than in title text, so it needs the
        // room a control needs below it:
        card.header.setPadding(0, 0, 0, dp(HEADER_CONTROL_GAP_DP))

        // The strategy uses the same compact label-and-blue-value pattern as the card's other
        // selectors. The value opens the existing chooser; ownership is not a second visual field.
        var openStrategyChooser: () -> Unit = {}
        val strategyValue = valueLabel()
        val strategyValueRow = valueRow(
            card.body,
            t(R.string.strategy_field_label),
            strategyValue
        ) { openStrategyChooser() }
        val strategy = StrategyRow(
            row = strategyValueRow.view,
            value = strategyValue,
            attachOpen = { handler -> openStrategyChooser = handler }
        )

        // The driver choice, in the header: two small labels, and only present when both modes can
        // actually work. Nothing is ever shown disabled.
        val kwhOption = TextView(context).apply {
            text = t(R.string.driver_kwh)
            textSize = 13f
            isClickable = true
            isFocusable = true
            gravity = Gravity.CENTER
            // 36 dp tall and 44 wide:
            minimumHeight = dp(36)
            minimumWidth = dp(44)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        val targetOption = TextView(context).apply {
            // The short name, and only here: "Målladdningsnivå" is a label, not a switch. The
            // slider it drives keeps the long wording, which fits.
            text = t(R.string.driver_target_soc)
            textSize = 13f
            isClickable = true
            isFocusable = true
            gravity = Gravity.CENTER
            minimumHeight = dp(36)
            minimumWidth = dp(44)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        // Two segments in one rounded, stroked container, with the active half filled:
        val driverChoice = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(0x00000000)
                setStroke(dp(1), muted)
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(2), dp(2), dp(2), dp(2))
            addView(kwhOption)
            addView(targetOption)
        }
        // The slack belongs to the header's own heading row (which is weighted), not to a spacer of
        // its own and not to the stroked control:
        card.header.addView(driverChoice)

        // The driver slot: one control at a time, in the same place -- the energy control in kWh
        // mode, the target slider in target-SoC mode.
        val driverSlot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(0, 0, 0, dp(DRIVER_SLOT_BOTTOM_DP))
        }
        val energyDriver = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        val energy = EnergyController(this).add(energyDriver, settings)

        var driver: PlanDriver = settings.driver
        var vehicle: VehicleStatus? = null
        var rememberedCapacityKwh: Double? = null

        // Departure:
        var departureHour = settings.departureHour
        var departureMinute = settings.departureMinute
        val departureControl = popoverControl()
        val useDeparture = checkbox(t(R.string.departure_check), settings.useDepartureTime)
        departureControl.addView(useDeparture)
        val departurePicker = Button(context).apply {
            text = t(R.string.departure, "%02d:%02d".format(departureHour, departureMinute))
            isAllCaps = false
            isEnabled = useDeparture.isChecked
        }
        departureControl.addView(departurePicker, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
        ).apply { topMargin = dp(6) })
        val departureValue = valueLabel()
        val departurePopoverValue = valueLabel()
        var departureDialog: AlertDialog? = null
        valueRow(card.body, t(R.string.plan_card_departure_label), departureValue) {
            departureDialog = showValuePopover(departureDialog, PopoverSpec(
                eyebrow = t(R.string.popover_eyebrow_plan),
                title = t(R.string.plan_card_departure_label),
                value = departurePopoverValue,
                control = departureControl,
                note = t(R.string.plan_card_departure_note)
            ))
        }
        val refreshDepartureLabel = {
            val reading = PlanReadout.departureReading(useDeparture.isChecked, departureHour, departureMinute)
            val text = reading.time() ?: t(R.string.plan_card_departure_none)
            departureValue.text = text
            departurePopoverValue.text = text
        }
        refreshDepartureLabel()

        // Max charging periods:
        val periodsControl = popoverControl()
        val periodsValue = valueLabel()
        // No heading: the popover renders the name and this value itself.
        val periods = slider(null, 1, 8, settings.maxChargingPeriods, periodsValue, periodsControl) { value ->
            // Two arguments, not one:
            periodsValue.text = tq(R.plurals.charging_period_count, value, value)
        }
        val periodsReading = valueLabel()
        var periodsDialog: AlertDialog? = null
        valueRow(card.body, t(R.string.max_charging_periods), periodsReading) {
            periodsDialog = showValuePopover(periodsDialog, PopoverSpec(
                eyebrow = t(R.string.popover_eyebrow_plan),
                title = t(R.string.max_charging_periods),
                value = periodsValue,
                control = periodsControl,
                note = t(R.string.plan_card_periods_note),
                tickAxis = PERIOD_AXIS
            ))
        }
        val refreshPeriodsLabel = {
            // The same plural, and the same two arguments:
            val count = periods.progress + 1
            val text = tq(R.plurals.charging_period_count, count, count)
            periodsReading.text = text
            periodsValue.text = text
        }
        refreshPeriodsLabel()


        // The driver control sits directly above what it drives:
        val targetSoc = TargetSocController(this).add(driverSlot, profile, persistTargetSoc)
        driverSlot.addView(energyDriver, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        card.body.addView(driverSlot, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(DRIVER_SLOT_TOP_DP) })

        // The other card: what came out.
        val result = addPlanResultCard(parent)
        val status = result.status

        /** target-SoC mode is driving it, and the target asks for no energy at all. */
        val nothingToCharge = { driverInForce: PlanDriver, targetPercent: Int? ->
            ChargeNeed.nothingToCharge(
                driverInForce,
                vehicle,
                targetPercent,
                rememberedCapacityKwh
            )
        }

        /**
         * Put the driver slot, the choice in the header and the readout set in step with the mode
         * actually in force.
         */
        /** The driver actually in force, as [refreshDriver] decides it. */
        // A paired charger's dashboard (null for an unpaired one):
        var paired: Dashboard? = null
        var pairedPicked: String? = null
        val availableDrivers = {
            val held = paired
            if (held == null) PlanMode.availableDrivers(vehicle, rememberedCapacityKwh)
            // The card's own rule: a charge-level source resolves, or the record already stands on
            // the target (then it is shown as it is, and can still be undone).
            else if (PairedTarget.available(held) || driver == PlanDriver.TARGET_SOC) setOf(PlanDriver.KWH, PlanDriver.TARGET_SOC)
            else setOf(PlanDriver.KWH)
        }
        var effectiveDriver = if (driver in availableDrivers()) driver else PlanDriver.KWH
        val refreshDriver = {
            val available = availableDrivers()
            val effective = if (driver in available) driver else PlanDriver.KWH
            effectiveDriver = effective
            driverChoice.visibility = if (PlanDriver.TARGET_SOC in available) View.VISIBLE else View.GONE
            styleSegment(kwhOption, selected = effective == PlanDriver.KWH)
            styleSegment(targetOption, selected = effective == PlanDriver.TARGET_SOC)
            energyDriver.visibility = if (effective == PlanDriver.KWH) View.VISIBLE else View.GONE
            targetSoc.container.visibility = if (effective == PlanDriver.TARGET_SOC) View.VISIBLE else View.GONE
            result.energyRow.visibility = if (effective == PlanDriver.TARGET_SOC) View.VISIBLE else View.GONE
            if (effective == PlanDriver.TARGET_SOC) {
                val held = paired
                if (held == null) {
                    PlanMode.derivedEnergyKwh(vehicle, targetSoc.progress(), rememberedCapacityKwh)?.let { kwh ->
                        energy.energy.progress = VehicleEnergy.energySliderProgress(kwh.toDouble())
                    }
                } else {
                    // Kept at Home Assistant's own need for the target, so switching back to kWh
                    // continues from it rather than jumping.
                    held.soc?.let { soc ->
                        PairedTarget.needKwh(
                            PairedTarget.facts(soc, held.vehicles, pairedPicked), targetSoc.progress().toDouble()
                        )
                    }?.let { kwh -> energy.energy.progress = VehicleEnergy.energySliderProgress(kwh) }
                }
                energy.refreshValueLabel()
            }
        }
        refreshDriver()

        return PlanCard(
            strategy = strategy,
            targetSoc = targetSoc,
            energy = energy,
            status = status,
            kwhOption = kwhOption,
            targetOption = targetOption,
            driver = { driver },
            effectiveDriver = { effectiveDriver },
            setDriver = { chosen ->
                driver = chosen
                refreshDriver()
            },
            applyVehicleFacts = { newVehicle, newCapacity ->
                vehicle = newVehicle
                rememberedCapacityKwh = newCapacity
                // A paired charger's slider is the whole 0-100 scale from the dashboard, never the
                // local band of a status vehicle (whose ids and readings are not the record's own).
                if (paired?.soc == null) targetSoc.applyVehicle(newVehicle)
                refreshDriver()
            },
            applyPaired = { dashboard, picked ->
                paired = dashboard
                pairedPicked = picked
                val soc = dashboard?.soc
                targetSoc.applyPaired(soc, dashboard?.vehicles.orEmpty(), picked)
                if (dashboard != null && soc == null) targetSoc.applyVehicle(vehicle)
                if (dashboard == null) targetSoc.applyVehicle(vehicle)
                refreshDriver()
            },
            refreshDriver = { refreshDriver() },
            nothingToCharge = { driverInForce, targetPercent -> nothingToCharge(driverInForce, targetPercent) },
            periods = periods,
            refreshPeriodsLabel = refreshPeriodsLabel,
            useDeparture = useDeparture,
            departurePicker = departurePicker,
            departureHour = { departureHour },
            departureMinute = { departureMinute },
            setDeparture = { hour, minute ->
                departureHour = hour; departureMinute = minute
                departurePicker.text = t(R.string.departure, "%02d:%02d".format(hour, minute))
                refreshDepartureLabel()
            },
            refreshDepartureLabel = refreshDepartureLabel,
            result = result
        )
    }

    /** what came out, after the card that holds what you set. */
    private fun addPlanResultCard(parent: LinearLayout): ResultBox {
        // The marker says what this card *is*:
        val card = card(parent, t(R.string.card_charging_plan_title), R.drawable.ic_plan_clock)
        // The header is an ordinary card header: a title, and a 24 dp glyph at its end in a box the
        // size of that glyph. Nothing here re-centres the header or pads it:
        val tableAction = compactIconAction(R.drawable.ic_price_table, t(R.string.table)) { shell.navigate(Screen.PRICE_TABLE) }
        card.header.addView(tableAction)
        // The finger still gets its 48 dp: the body grows the *hit* rectangle around that box,
        // after layout, as far as the room above the graph allows (see [expandActionTarget]).
        val chart = PlanChartView(context).apply {
            contentDescription = chartDescription(emptyList())
        }
        card.body.addView(chart, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10); bottomMargin = dp(4) })
        // The table action's real touch area, once the layout knows where the graph begins:
        onLaidOut(card.body) { expandActionTarget(card.body, tableAction, chart) }
        // The readout: the tapped interval in words, under the graph it belongs to.
        val callout = TextView(context).apply {
            textSize = 13f
            setTextColor(dark)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply { setColor(cardBackground); cornerRadius = dp(8).toFloat() }
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener { chart.clearSelection() }
        }
        card.body.addView(callout, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) })
        // Handed over as the screen's one visible chart and readout: a tap that begins anywhere
        // else puts the readout away (see `ScreenShell.afterDispatch`).
        shell.planChart = chart
        shell.planCallout = callout
        chart.onSelectionChanged = { selection, market, prices ->
            // One line per value, so a day that repeats the wall clock shows *both* of its values
            // rather than one of them; the offset appears only where it is what tells two otherwise
            // identical lines apart (see readoutLines).
            val unit = PriceMarkets.find(market.areaId)?.appliedPriceUnit.orEmpty()
            val lines = selection?.let { readoutLines(it, unit) } ?: emptyList()
            callout.text = lines.joinToString("\n")
            callout.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
            // The chart carries the same words, so a screen reader can obtain the selection without
            // a colour or a point on a canvas.
            chart.contentDescription = chartDescription(lines)
        }
        // The answer, in a frame of its own: the computed windows, or the reason there are none.
        val title = TextView(context).apply {
            textSize = 17f
            setTextColor(dark)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(0x00000000)
                setStroke(dp(1), muted)
                cornerRadius = dp(8).toFloat()
            }
        }
        val cost = resultValue()
        val costRow = resultRow(card.body, t(R.string.cost), cost)
        val energyResult = resultValue()
        val energyRow = resultRow(card.body, t(R.string.charging), energyResult)
        val distance = resultValue()
        val distanceRow = resultRow(card.body, t(R.string.range), distance)
        // The figures first, then the schedule they describe:
        card.body.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        // No power row: it was amps × phases, and both of those are facts on the charger card one
        // card up. Empty in the ordinary case, and then absent rather than blank:
        val note = TextView(context).apply {
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(7), 0, 0)
            // Watched rather than set alongside every write: `render()` writes this note from four
            // branches, and a view that hides itself cannot have one of them forget to.
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(edited: android.text.Editable?) {
                    visibility = if (edited.isNullOrBlank()) View.GONE else View.VISIBLE
                }
            })
            visibility = View.GONE
        }
        card.body.addView(note)
        // The one status line the screen has, at the foot of the readouts it is about:
        val status = TextView(context).apply {
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(8), 0, 0)
        }
        card.body.addView(status)
        // What the *authority* has to say about the values above it -- who owns them, whether they
        // are stale, what is missing, what changed elsewhere. Its own view rather than the note:
        val authorityNote = TextView(context).apply {
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(7), 0, 0)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(edited: android.text.Editable?) {
                    visibility = if (edited.isNullOrBlank()) View.GONE else View.VISIBLE
                }
            })
            visibility = View.GONE
        }
        card.body.addView(authorityNote)
        return ResultBox(
            title = title, cost = cost, energyResult = energyResult, distance = distance,
            note = note, authorityNote = authorityNote, costRow = costRow, energyRow = energyRow,
            distanceRow = distanceRow, status = status,
            chart = chart
        )
    }

    private companion object {
        // The period slider runs 1..8 charging windows.
        val PERIOD_AXIS = TickAxis(1, 8, listOf(Tick(1, "1"), Tick(4, "4"), Tick(8, "8")))
    }
}

/**
 * the readout views that function writes, the energy row itself — a consequence in target-SoC mode,
 * and deliberately absent in kWh mode, where the energy control already shows that number — and the
 * price-status line beneath the figures.
 */
internal class ResultBox(
    val title: TextView,
    val cost: TextView,
    val energyResult: TextView,
    val distance: TextView,
    val note: TextView,
    /** Who owns these values, and anything the authority has to say about them. */
    val authorityNote: TextView,
    val costRow: View,
    val energyRow: View,
    val distanceRow: View,
    val status: TextView,
    /**
     * The widget's own graph, at the top of this card. It is handed out because it is not a readout
     * `render()` writes text into:
     */
    val chart: PlanChartView
) {
    fun showFigures(visible: Boolean) {
        costRow.visibility = if (visible) View.VISIBLE else View.GONE
        energyRow.visibility = if (visible) View.VISIBLE else View.GONE
        distanceRow.visibility = if (visible) View.VISIBLE else View.GONE
    }
}

/**
 * [setDeparture] is the only way to move it, so every label showing it agrees — and, living in the
 * second of its two cards, the readout block `render()` writes and [status], the one status line
 * the screen has.
 */
/** The compact strategy selector: one label, one clickable value, one chooser. */
internal class StrategyRow(
    val row: LinearLayout,
    val value: TextView,
    val attachOpen: ((() -> Unit)) -> Unit
)

internal class PlanCard(
    /** The compact strategy selector and the one chooser behind it. */
    val strategy: StrategyRow,
    val targetSoc: TargetSocControls,
    val energy: EnergyControls,
    val status: TextView,
    val kwhOption: TextView,
    val targetOption: TextView,
    val driver: () -> PlanDriver,
    val effectiveDriver: () -> PlanDriver,
    val setDriver: (PlanDriver) -> Unit,
    val applyVehicleFacts: (VehicleStatus?, Double?) -> Unit,
    /**
     * A paired charger's dashboard, and the vehicle picked in the vehicle card (or `null`): what
     * the target editor reads instead of the local vehicle facts. `null` returns it to those.
     */
    val applyPaired: (Dashboard?, String?) -> Unit,
    val refreshDriver: () -> Unit,
    val nothingToCharge: (PlanDriver, Int?) -> Boolean,
    val periods: SeekBar,
    val refreshPeriodsLabel: () -> Unit,
    val useDeparture: CheckBox,
    val departurePicker: Button,
    val departureHour: () -> Int,
    val departureMinute: () -> Int,
    val setDeparture: (Int, Int) -> Unit,
    val refreshDepartureLabel: () -> Unit,
    val result: ResultBox
)
