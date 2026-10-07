package se.sensnology.spotnav.ui.charging

import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.res.ColorStateList
import android.view.View
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.chargers.ChargerCardSelector
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.chargers.ChargerPhases
import se.sensnology.spotnav.ha.dashboard.DashboardChargingPhases
import se.sensnology.spotnav.ha.sessions.SessionsSummary
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.FullCarRule
import se.sensnology.spotnav.ha.dashboard.ChargeProgressContract
import se.sensnology.spotnav.planning.ChargingPlanner
import se.sensnology.spotnav.ui.common.PopoverSpec
import se.sensnology.spotnav.ui.common.Spin
import se.sensnology.spotnav.ui.common.Tick
import se.sensnology.spotnav.ui.common.TickAxis
import se.sensnology.spotnav.ui.common.ValueCue
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.card
import se.sensnology.spotnav.ui.common.chargerName
import se.sensnology.spotnav.ui.common.headerSpinnerAdapter
import se.sensnology.spotnav.ui.common.popoverControl
import se.sensnology.spotnav.ui.common.reReadIcon
import se.sensnology.spotnav.ui.common.showValuePopover
import se.sensnology.spotnav.ui.common.slider
import se.sensnology.spotnav.ui.common.valueLabel
import se.sensnology.spotnav.ui.common.valueRow
import se.sensnology.spotnav.ui.common.weight
import se.sensnology.spotnav.ha.pairing.HomeAssistantInstanceStore
import se.sensnology.spotnav.widget.PriceWidgetProvider
import se.sensnology.spotnav.widget.WidgetChargerBindingStore
import se.sensnology.spotnav.widget.WidgetChargerSelectionController
import se.sensnology.spotnav.widget.WidgetSettings
import se.sensnology.spotnav.widget.WidgetDashboardStore
import se.sensnology.spotnav.vehicles.PairedCarLine

/**
 * The charger card: amps, phases, the charger selector and the status line -- the first of the two
 * object cards.
 */
internal class ChargerCardController(scope: ViewScope, private val widgetId: Int) : ViewScope(scope) {
    /**
     * The charger card — the first of the object cards, and where both reusable patterns above are
     * established.
     */
    fun add(
        parent: LinearLayout,
        settings: WidgetSettings,
        profile: ChargerProfile?,
        shareRow: Boolean = false,
        onOpenHistory: () -> Unit = {},
        onChargerChosen: () -> Unit
    ): ChargerCard {
        val card = card(parent, t(R.string.widget_charger_title), R.drawable.ic_card_charger, shareRow)
        // The title is this charger's own name, and doubles as the selector -- the same arrangement
        // the vehicle card uses.
        val chargerSpinner = Spinner(context).apply {
            visibility = View.GONE
            adapter = headerSpinnerAdapter(listOf(t(R.string.widget_charger_none)))
        }
        card.heading.addView(chargerSpinner, weight())

        // The charger card's own re-read: not a webhook action and not a capability -- it is the
        // `status` poll this screen already does for this charger, on demand.
        val chargerReReadIcon = reReadIcon()
        val chargerReReadSpin = Spin(chargerReReadIcon)
        card.header.addView(chargerReReadIcon)
        var chargerReReadInFlight = false
        var requestChargerReRead: () -> Unit = {}
        /**
         * Drawn only for a charger that can actually be asked: with no profile, or one with no
         * connection, there is nothing to poll -- the same gate the charger card's other Home
         * Assistant controls use.
         */
        fun paintReRead() {
            chargerReReadIcon.visibility = if (profile?.configured == true) View.VISIBLE else View.GONE
            chargerReReadIcon.isEnabled = !chargerReReadInFlight
            chargerReReadIcon.imageTintList = ColorStateList.valueOf(if (chargerReReadInFlight) muted else accent)
            chargerReReadSpin.set(chargerReReadInFlight)
        }
        chargerReReadIcon.setOnClickListener { requestChargerReRead() }
        paintReRead()

        // This widget's own binding, read through the controller that already owns the distinction
        // between a valid, an explicitly empty and a stale binding; ChargerCardSelector turns that
        // state into rows.
        var chargerSelection = WidgetChargerSelectionController(
            ChargerProfileStore.forContext(applicationContext).listProfiles(),
            settings.chargerProfileId
        )
        var selector = ChargerCardSelector.content(chargerSelection)
        // With no Home Assistant paired, a missing charger went with the instance a person removed:
        // that is "No charger", not a charger to warn about.
        if (selector.stale && HomeAssistantInstanceStore.forContext(applicationContext).baseUrl() == null) {
            WidgetChargerBindingStore.forContext(applicationContext).setBinding(widgetId, null)
            chargerSelection = WidgetChargerSelectionController(
                ChargerProfileStore.forContext(applicationContext).listProfiles(),
                null
            )
            selector = ChargerCardSelector.content(chargerSelection)
            if (widgetId > 0) PriceWidgetProvider.update(applicationContext, AppWidgetManager.getInstance(applicationContext), widgetId)
        }

        // The spinner's own listener fires while its adapter and selection are being rebuilt;
        // without this guard that re-entrancy would persist whatever row happened to be selected
        // mid-rebuild (the stale placeholder, or "No charger"), silently changing the user's
        // binding.
        var repopulatingChargers = true
        fun paintSelector() {
            card.title.text = selector.bound?.let { bound -> chargerName(bound) } ?: t(R.string.widget_charger_title)
            card.title.visibility = if (selector.showsControl) View.GONE else View.VISIBLE
            chargerSpinner.visibility = if (selector.showsControl) View.VISIBLE else View.GONE
            chargerSpinner.adapter = headerSpinnerAdapter(
                selector.entries.map { entry -> chargerEntryLabel(entry) },
                // As on the Settings screen this replaces: a disabled position cannot be tapped in the
                // dropdown, so "the charger is missing" is never something the user can pick.
                isRowEnabled = { position ->
                    selector.entries.getOrNull(position)?.kind != ChargerCardSelector.Kind.PLACEHOLDER
                },
                // Each paired charger's car and its levels, from its last dashboard ("EV6 · 89 % → 93 %").
                subtitle = { position -> chargerCarLine(selector.entries.getOrNull(position)) }
            )
            chargerSpinner.setSelection(selector.selectedIndex, false)
            // That is what this defers, exactly as that card does.
            chargerSpinner.post { repopulatingChargers = false }
        }
        paintSelector()
        chargerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Two guards, for two different ways this fires without a user:
                if (repopulatingChargers || !selector.showsControl) return
                when (val target = selector.targetAt(position)) {
                    // The stale row states what is wrong; it is not a choice, so re-selecting it --
                    // which a rebuild does -- writes nothing.
                    ChargerCardSelector.Target.Ignore -> return
                    ChargerCardSelector.Target.NoCharger -> chargerSelection.selectNoCharger()
                    is ChargerCardSelector.Target.Profile -> chargerSelection.selectProfile(target.localId)
                }
                // The id is read back through the controller, so the rule it already guarantees
                // holds here too:
                val chosen = chargerSelection.chargerProfileIdToSave
                if (chosen == settings.chargerProfileId) return
                // This widget's binding only -- never ChargerProfileStore's active profile, which a
                // widget deliberately does not follow.
                WidgetChargerBindingStore.forContext(applicationContext).setBinding(widgetId, chosen)
                if (chosen != null && settings.chargerProfileId == null && widgetId > 0) {
                    WidgetSettings.planDefaultOnBinding(applicationContext, widgetId)
                }
                // The widget shows the charger it is bound to, so it is redrawn now, not at its next update:
                if (widgetId > 0) PriceWidgetProvider.update(applicationContext, AppWidgetManager.getInstance(applicationContext), widgetId)
                onChargerChosen()
            }
        }
        val configured = profile?.configured == true
        // Only ever the connection line, kept up to date from `ChargerDashboardController`. With no
        // charger configured the card says nothing at all:
        val status = TextView(context).apply {
            text = t(R.string.home_assistant_checking)
            textSize = 13f; setTextColor(muted); setPadding(0, dp(2), 0, dp(6))
            visibility = if (configured) View.VISIBLE else View.GONE
        }
        // A stale binding says so, right above the connection line:
        if (selector.stale) {
            card.body.addView(TextView(context).apply {
                text = t(R.string.widget_charger_missing_warning)
                textSize = 13f; setTextColor(0xFFD65C5C.toInt()); setPadding(0, dp(2), 0, dp(6))
            })
        }
        card.body.addView(status)

        // The charge's progress, right under the status that says it charges: a slim bar and one line
        // ("62 % klart · klart ca 14:35"), there only while a charge is on and the dashboard carries
        // its numbers (see ChargeBarRule).
        val chargeBarView = ChargeBarView(context).apply {
            colours(track = (muted and 0x00FFFFFF) or TRACK_ALPHA, fill = accent)
            setPadding(0, dp(4), 0, dp(2))
        }
        val chargeBarLine = TextView(context).apply {
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(2), 0, dp(6))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val chargeBarBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(chargeBarView)
            addView(chargeBarLine)
        }
        card.body.addView(chargeBarBlock)

        // The vehicle-side advisory: one sentence, and only when the integration observes that a
        // charge it started is not being taken by the car (see ChargeProgressContract).
        val advisory = TextView(context).apply {
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(2), 0, dp(6))
            visibility = View.GONE
        }
        card.body.addView(advisory)

        // The charger's connection state, "Ansluten" (the vehicle card carries the car and its charge).
        val vehicleLine = TextView(context).apply {
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(2), 0, dp(6))
            visibility = View.GONE
        }
        card.body.addView(vehicleLine)

        // Which car is plugged in: the open question's banner, else the car line with Byt bil.
        val identification = IdentificationViews(this, card.body)

        // The controls themselves live in the popovers, built once here rather than per tap, so
        // they stay the single source of truth that `currentSettings()`, the shared seek listener
        // and the phases listener already read.
        val ampsControl = popoverControl()
        val phasesControl = popoverControl()
        val ampsRowValue = valueLabel()
        val phasesRowValue = valueLabel()
        // The phases popover's own large value, written by `renderPhasesRow`.
        val phasesValue = valueLabel()
        val connection = addConnectionControls(ampsControl, phasesControl, ampsRowValue, settings)
        var ampsDialog: AlertDialog? = null
        var phasesDialog: AlertDialog? = null
        val ampsRow = valueRow(card.body, t(R.string.charge_current), ampsRowValue) {
            ampsDialog = showValuePopover(ampsDialog, PopoverSpec(
                eyebrow = t(R.string.popover_eyebrow_charger),
                title = t(R.string.charge_current),
                value = connection.valueLabel,
                control = ampsControl,
                note = t(R.string.charger_card_amps_note),
                tickAxis = ampAxis(connection.minAmps(), connection.maxAmps())
            ))
        }

        // The charger's own last-reported phases: read once here, and replaced by
        // `setDetectedPhases` after every fetch. Never written anywhere.
        var detectedPhases = profile?.detectedPhases

        // The row before the renderer that writes it: the phases row is coloured and re-coloured by
        // `renderPhasesRow` below, and a lambda cannot name a row that does not exist yet.
        val phasesRow = valueRow(card.body, t(R.string.charger_card_phases_label), phasesRowValue) {
            phasesDialog = showValuePopover(phasesDialog, PopoverSpec(
                eyebrow = t(R.string.popover_eyebrow_charger),
                title = t(R.string.charger_card_phases_label),
                value = phasesValue,
                control = phasesControl,
                note = t(R.string.charger_card_phases_note)
            ))
        }

        // A paired charger's phases are a fact the integration states, not a choice made here: they
        // go into the current row's label, in the editor's place, from the dashboard's
        // `charging_phases` block.
        var pairedPhases: DashboardChargingPhases? = null
        var phaseEditorShown = true
        val renderPairedPhases = {
            val block = pairedPhases?.takeUnless { phaseEditorShown }
            ampsRow.label?.text = ChargingPhasesText.currentLabel(
                block?.phases, t(R.string.charge_current)
            ) { count ->
                val label = tq(R.plurals.charge_current_on_phases, count, count)
                // Fewer phases than the charger is wired for: name the car as the reason.
                if (block?.limitedByVehicle == true) t(R.string.charge_current_limited_by_vehicle, label) else label
            }
        }

        // The charge history, for a paired charger whose dashboard carries `sessions_summary`: one
        // row, the month so far, and a tap opens the History view.
        val historyValue = valueLabel()
        val historyRow = valueRow(card.body, t(R.string.history_row_label), historyValue) { onOpenHistory() }
        historyRow.view.visibility = View.GONE

        val renderPhasesRow = {
            val notSet = connection.phasesNotSet()
            val display = ChargerPhases.display(connection.selectedPhases(), detectedPhases)
            val phases = if (notSet) t(R.string.value_not_set) else phasesLabel(display.phases)
            // A detection that agrees is not news. this is the row among the card's shortest
            // values, and a sentence here wraps onto two lines.
            val mismatch = !notSet && display.status == ChargerPhases.Status.CONFLICT
            phasesRowValue.text = if (mismatch) t(R.string.phases_mismatch_marker, phases) else phases
            // That colour is about the number ("look at this"), not about the row being openable.
            phasesRow.show(ValueCue.EDITABLE, attention = mismatch)
            // The popover says the same thing, in the same words: it is the same fact, read in two
            // places.
            phasesValue.text = phasesRowValue.text
            // The detection is stated once, here, by marking the option it points at.
            connection.markDetectedPhases(display.adoptablePhases)
        }
        renderPhasesRow()

        return ChargerCard(
            body = card.body,
            status = status,
            showChargeBar = { bar, now -> paintChargeBar(bar, now, chargeBarBlock, chargeBarView, chargeBarLine) },
            advisory = advisory,
            vehicleLine = vehicleLine,
            identification = identification,
            connection = connection,
            refreshPhasesRow = { renderPhasesRow(); renderPairedPhases() },
            showHistory = { summary ->
                val month = summary?.thisMonth
                historyRow.view.visibility = if (summary != null) View.VISIBLE else View.GONE
                historyValue.text = if (month != null && !month.isEmpty) t(
                    R.string.history_row_month, kwhText(month.energyKwh),
                    ChargingPhasesText.monthAbbreviation(java.time.LocalDate.now(), monthLocale())
                )
                    else t(R.string.history_row_empty)
            },
            showPairedPhases = { block ->
                pairedPhases = block
                renderPairedPhases()
            },
            refreshName = {
                // A rename reported by Home Assistant lands in the store first; the title and the
                // selector's rows follow it, keeping this widget's binding as it was.
                chargerSelection = WidgetChargerSelectionController(
                    ChargerProfileStore.forContext(applicationContext).listProfiles(),
                    settings.chargerProfileId
                )
                val renamed = ChargerCardSelector.content(chargerSelection)
                if (renamed.entries.map { chargerEntryLabel(it) } != selector.entries.map { chargerEntryLabel(it) }) {
                    selector = renamed
                    repopulatingChargers = true
                    paintSelector()
                }
            },
            setDetectedPhases = { detected ->
                detectedPhases = detected
                renderPhasesRow()
            },
            reRead = ChargerReReadControl(
                // The screen owns the connection, so the handler arrives from there, like the
                // vehicle card's.
                attachRequest = { handler -> requestChargerReRead = handler },
                setInFlight = { inFlight ->
                    chargerReReadInFlight = inFlight
                    paintReRead()
                }
            ),
            /**
             * Home Assistant's record owns the charger's topology, so the editor goes: the row, the
             * control behind it, and the readout that names the value. The confirmed count itself
             * is not thrown away:
             */
            setPhaseEditor = { rowVisible, controlEnabled ->
                phasesRow.view.visibility = if (rowVisible) View.VISIBLE else View.GONE
                phasesControl.visibility = if (rowVisible) View.VISIBLE else View.GONE
                connection.phases.isEnabled = controlEnabled
                phaseEditorShown = rowVisible
                renderPairedPhases()
            }
        )
    }

    /** The bar and its line, or neither when there is no bar. */
    private fun paintChargeBar(bar: ChargeBar?, now: java.time.Instant, block: View, view: ChargeBarView, line: TextView) {
        if (bar == null) {
            view.show(null, animate = false)
            block.visibility = View.GONE
            return
        }
        val locale = AppLanguageSettings.locale(context)
        val clock = bar.endsAt?.let { end ->
            ChargeBarText.clock(
                end, now, java.time.ZoneId.systemDefault(),
                android.text.format.DateFormat.is24HourFormat(context), locale
            )
        }
        val kw = bar.powerKw?.let { ChargeBarText.kw(it, AppLanguageSettings.numberLocale(context)) }
        val words = ChargeBarText.Words(
            done = template(R.string.charge_bar_done), ofTarget = template(R.string.charge_bar_of_target),
            ends = template(R.string.charge_bar_ends), charging = template(R.string.charge_bar_charging),
            chargingPower = template(R.string.charge_bar_charging_power), line = template(R.string.charge_bar_line)
        )
        val text = ChargeBarText.line(bar, words, clock, kw, locale)
        line.text = text
        view.contentDescription = t(R.string.charge_bar_description) + ", " + text
        view.show(bar.percent?.let { it / 100f }, ChargeBarMotion.animate(bar, android.animation.ValueAnimator.areAnimatorsEnabled()))
        block.visibility = View.VISIBLE
    }

    /**
     * A paired charger's car and its levels, as its last dashboard said them; `null` for "No charger",
     * an unpaired charger, or one not read yet.
     */
    private fun chargerCarLine(entry: ChargerCardSelector.Entry?): String? {
        val profile = entry?.profile?.takeIf { entry.kind == ChargerCardSelector.Kind.PROFILE && it.configured } ?: return null
        val stored = WidgetDashboardStore.forContext(applicationContext).dashboardFor(profile.localId) ?: return null
        return PairedCarLine.summary(stored.dashboard) { value -> t(R.string.vehicle_card_soc_value, value) }
    }

    /** One row's label in the charger card's selector. */
    private fun chargerEntryLabel(entry: ChargerCardSelector.Entry) = when (entry.kind) {
        ChargerCardSelector.Kind.PLACEHOLDER -> t(R.string.widget_charger_missing_placeholder)
        ChargerCardSelector.Kind.NO_CHARGER -> t(R.string.widget_charger_none)
        // The row is only ever built from a profile, so the fallback is a name for a charger whose
        // own name is blank -- not a missing profile.
        ChargerCardSelector.Kind.PROFILE ->
            entry.profile?.let { chargerName(it) } ?: t(R.string.charger_generic_name)
    }

    /** The app's own phase model as a label: one phase, or three. */
    /** The locale the history row's month is written in; English as the British write it ("Oct"). */
    private fun monthLocale(): java.util.Locale =
        AppLanguageSettings.locale(context).let { if (it.language == "en") java.util.Locale.UK else it }

    private fun phasesLabel(phases: Int) = t(if (phases == 1) R.string.phase_one else R.string.phase_three)

    /** The connection readout: phases, and the charging current. */
    private fun addConnectionControls(
        ampsParent: LinearLayout,
        phasesParent: LinearLayout,
        cardAmpsValue: TextView,
        settings: WidgetSettings
    ): ConnectionControls {
        val phases = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        val onePhaseText = t(R.string.phase_one)
        val threePhaseText = t(R.string.phase_three)
        val onePhaseId = View.generateViewId()
        val threePhaseId = View.generateViewId()
        val onePhase = RadioButton(context).apply { id = onePhaseId; text = onePhaseText; isChecked = settings.chargingPhases == 1 }
        val threePhase = RadioButton(context).apply { id = threePhaseId; text = threePhaseText; isChecked = settings.chargingPhases != 1 }
        phases.addView(onePhase); phases.addView(threePhase); phasesParent.addView(phases)
        val selectedPhases: () -> Int = { if (phases.checkedRadioButtonId == onePhaseId) 1 else 3 }
        val ampsValue = valueLabel()
        // A paired record can state no current (or no phases): The phases need no flag of their
        // own: an unchecked group *is* "not set".
        var ampsNotSet = false
        val notSetText = t(R.string.value_not_set)
        // The slider's floor: 6 A until Home Assistant states the charger's own range
        // (`current_range`, see `setRange`).
        var minAmps = DEFAULT_MIN_AMPS
        // No heading: the popover renders the name and this value itself.
        val amps = slider(null, DEFAULT_MIN_AMPS, DEFAULT_MAX_AMPS, settings.chargingAmps, ampsValue, ampsParent) { value ->
            ampsValue.text = if (ampsNotSet) notSetText else t(R.string.amps_power, value, ChargingPlanner.powerKw(value, selectedPhases()))
        }
        val ampsNow = { amps.progress + minAmps }
        val controls = ConnectionControls(
            phases = phases,
            ampsSeek = amps,
            selectedPhases = selectedPhases,
            setPhases = { value -> phases.check(if (value == 1) onePhaseId else threePhaseId) },
            setPhasesNotSet = { phases.clearCheck() },
            phasesNotSet = { phases.checkedRadioButtonId == View.NO_ID },
            valueLabel = ampsValue,
            markDetectedPhases = { detected ->
                // Naming the option the charger's own wiring points at, and nothing more:
                onePhase.text = if (detected == 1) t(R.string.charger_card_phases_detected, onePhaseText) else onePhaseText
                threePhase.text = if (detected == 3) t(R.string.charger_card_phases_detected, threePhaseText) else threePhaseText
            },
            refreshValueLabel = {
                // The piece owns the number, so it owns every label showing it: the popover's large
                // value, and the card's row.
                val text = if (ampsNotSet) notSetText else t(R.string.amps_power, ampsNow(), ChargingPlanner.powerKw(ampsNow(), selectedPhases()))
                ampsValue.text = text
                cardAmpsValue.text = text
            },
            amps = { ampsNow() },
            setAmpsNotSet = { notSet -> ampsNotSet = notSet },
            ampsNotSet = { ampsNotSet },
            minAmps = { minAmps },
            maxAmps = { minAmps + amps.max },
            setAmps = { value, floor, ceiling ->
                // The range, with the value held:
                val low = minOf(floor, value)
                val high = maxOf(ceiling, value)
                minAmps = low
                amps.max = high - low
                amps.progress = (value - low).coerceIn(0, amps.max)
                // A range refresh is not a person's answer: what was "not set" stays so.
            }
        )
        controls.refreshValueLabel()
        return controls
    }

    private companion object {
        const val DEFAULT_MIN_AMPS = 6
        const val DEFAULT_MAX_AMPS = 16

        /** The bar's track: the muted colour, faint, so the accent share reads in both themes. */
        const val TRACK_ALPHA = 0x40000000

        // The stops labelled under each slider, with the value each one stands at in that slider's
        // own units -- the same units and the same min/max its `slider(...)` call uses, because the
        // value is what has to line up with the thumb (see `TickRow` and SliderTicks).
        val AMP_AXIS = TickAxis(6, 16, listOf(Tick(6, "6"), Tick(10, "10"), Tick(13, "13"), Tick(16, "16")))

        /** The amps slider's labelled stops for a range: the familiar four for 6-16 A, else evenly spread. */
        fun ampAxis(min: Int, max: Int): TickAxis {
            if (min == DEFAULT_MIN_AMPS && max == DEFAULT_MAX_AMPS) return AMP_AXIS
            val stops = (0..3).map { min + Math.round((max - min) * it / 3.0).toInt() }.distinct()
            return TickAxis(min, max, stops.map { Tick(it, it.toString()) })
        }
    }
}

/** What [ChargerCardController.add] hands back: */
internal class ChargerCard(
    val body: LinearLayout,
    val status: TextView,
    /** Show the charge bar for the dashboard read at `now`, or hide it (`null`). */
    val showChargeBar: (ChargeBar?, java.time.Instant) -> Unit,
    /**
     * The vehicle-side advisory (see [ChargeProgressContract]), and the only line on this card that
     * speaks about the *car*: written by [showAdvisory] from the integration's own observation,
     * hidden whenever there is nothing to say, and never a control.
     */
    val advisory: TextView,
    /** The vehicle's charge followed by the connection state; hidden when there is neither. */
    val vehicleLine: TextView,
    /** "Which car is plugged in?": the banner and the car line with Byt bil (see [IdentificationViews]). */
    val identification: IdentificationViews,
    val connection: ConnectionControls,
    val refreshPhasesRow: () -> Unit,
    /**
     * A paired charger's `charging_phases` block (or `null`): the read-only line "Charges on N
     * phases · nominal ≈ X kW" that stands in for the phase editor while Home Assistant owns it.
     */
    val showPairedPhases: (DashboardChargingPhases?) -> Unit,
    /** The dashboard's `sessions_summary` (or `null`): the History row is there only when it is. */
    val showHistory: (SessionsSummary?) -> Unit,
    /** Repaints the title and the selector's rows when a charger's name has changed in the store. */
    val refreshName: () -> Unit,
    val setDetectedPhases: (Int?) -> Unit,
    val reRead: ChargerReReadControl,
    /**
     * whether the card's row is offered, and whether the control behind it is live (see
     * [ChargingStrategyUi]).
     */
    val setPhaseEditor: (rowVisible: Boolean, controlEnabled: Boolean) -> Unit
) {
    /** Write the vehicle line, or hide it when there is nothing to say. */
    fun showVehicleLine(text: String?, colour: Int) {
        vehicleLine.text = text.orEmpty()
        vehicleLine.setTextColor(colour)
        vehicleLine.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** Write the vehicle-side advisory, or hide the line when there is nothing to say. */
    fun showAdvisory(dashboard: Dashboard?, sentence: String) {
        val show = FullCarRule.advisory(dashboard)
        advisory.text = if (show) sentence else ""
        advisory.visibility = if (show) View.VISIBLE else View.GONE
    }
}

/**
 * What the charger card's re-read icon needs from the screen: something to call when it is pressed,
 * and a way to be told a `status` fetch is running -- which is exactly what the spin means on this
 * card.
 */
internal class ChargerReReadControl(
    /** Set by the screen: poll the dashboard for this charger, through its session. */
    val attachRequest: (((() -> Unit)) -> Unit),
    /** Tell the card a fetch is in flight, or no longer is -- the spin. */
    val setInFlight: (Boolean) -> Unit
)

/**
 * What [addConnectionControls] hands back: the two views whose listeners the caller attaches, the
 * phases the rest of the screen asks for, the way to mark the option a detection points at, and the
 * refresher that keeps every label showing the amps figure in step.
 */
internal class ConnectionControls(
    val phases: RadioGroup,
    /** The slider itself; its progress is 0-based from [minAmps], so read the current through [amps]. */
    val ampsSeek: SeekBar,
    val selectedPhases: () -> Int,
    val setPhases: (Int) -> Unit,
    /** Show no phase count at all: the record states none, so neither option is chosen. */
    val setPhasesNotSet: () -> Unit,
    val phasesNotSet: () -> Boolean,
    val valueLabel: TextView,
    val markDetectedPhases: (Int?) -> Unit,
    val refreshValueLabel: () -> Unit,
    /** The charging current now, in amperes (this phone's own slider position while [ampsNotSet]). */
    val amps: () -> Int,
    /** Mark the current as stated by nobody: the readouts say "not set" until the person sets it. */
    val setAmpsNotSet: (Boolean) -> Unit,
    val ampsNotSet: () -> Boolean,
    /** The slider's floor and ceiling now, in amperes. */
    val minAmps: () -> Int,
    val maxAmps: () -> Int,
    /**
     * Show [value] on a slider spanning [floor]..[ceiling] (widened to hold [value] when it lies
     * outside), without saving anything.
     */
    val setAmps: (value: Int, floor: Int, ceiling: Int) -> Unit
)
