package se.sensnology.spotnav.ui.settings

import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.ha.client.ChargerPriorityUpdate
import se.sensnology.spotnav.ha.client.SiteFacts
import se.sensnology.spotnav.ha.client.SiteUpdate
import se.sensnology.spotnav.ha.client.VehicleField
import se.sensnology.spotnav.ha.client.VehicleFieldIssue
import se.sensnology.spotnav.ha.client.VehicleUpdate
import se.sensnology.spotnav.ha.dashboard.ChargerPriority
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.DashboardSite
import se.sensnology.spotnav.ha.dashboard.DashboardSummary
import se.sensnology.spotnav.ha.dashboard.DashboardVehicle
import se.sensnology.spotnav.ha.dashboard.IdentificationSource
import se.sensnology.spotnav.ha.dashboard.VehicleIdentificationSources
import se.sensnology.spotnav.ha.settings.HaIdentificationSettings
import se.sensnology.spotnav.ha.settings.IdentifyMode
import se.sensnology.spotnav.ui.common.ValueCue
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.card
import se.sensnology.spotnav.ui.common.chooseMany
import se.sensnology.spotnav.ui.common.chooseOne
import se.sensnology.spotnav.ui.common.settingRow
import se.sensnology.spotnav.ui.common.valueLabel
import se.sensnology.spotnav.ui.common.valueColour
import se.sensnology.spotnav.ui.common.weight
import se.sensnology.spotnav.vehicles.ChargeLimit
import se.sensnology.spotnav.vehicles.PairedVehicles
import se.sensnology.spotnav.vehicles.SocDisplay
import se.sensnology.spotnav.vehicles.VehicleIdentification
import java.util.Locale

/**
 * The cards of a paired charger's settings, in the order the Home Assistant card has them after the
 * price card: one card per vehicle, the charger, the site and solar.
 *
 * The overview saves nothing by itself. The two areas the webhook lets the app change (a vehicle's
 * capacity and consumption, the site's solar settings) each have a **Change** button that opens a
 * dialog with Save and Cancel; the charger and the site's entities are read-only here and changed
 * in Home Assistant.
 */
internal class PairedSettingsCards(scope: ViewScope, parent: LinearLayout) : ViewScope(scope) {
    private val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var dashboard: Dashboard? = null
    private var unreachable = false

    // What a write answered with, shown until the next dashboard replaces it.
    private val adoptedVehicles = mutableMapOf<String, DashboardVehicle>()
    private var adoptedSite: DashboardSite? = null
    private val vehicleNotices = mutableMapOf<String, String>()
    private var solarNotice: String? = null
    private var adoptedPriority: ChargerPriority? = null
    private var priorityNotice: String? = null

    private var writeVehicle: (String, List<VehicleUpdate.FieldChange>, (VehicleUpdate.Outcome) -> Unit) -> Unit =
        { _, _, done -> done(VehicleUpdate.Outcome.Failed(null)) }
    private var writeSite: (SiteUpdate.Request, (SiteUpdate.Outcome) -> Unit) -> Unit =
        { _, done -> done(SiteUpdate.Outcome.Failed(null)) }

    private var writePriority: (String, String, (ChargerPriorityUpdate.Outcome) -> Unit) -> Unit =
        { _, _, done -> done(ChargerPriorityUpdate.Outcome.Failed(null)) }

    // A car's charge limit, written to the car through the integration (`set_charge_limit`).
    private var writeLimit: (String, Int, (ChargeLimit.Answer) -> Unit) -> Unit = { _, _, done -> done(ChargeLimit.Answer.Failed) }

    /** Where a car's charge limit is written (the screen owns the connection). */
    fun attachChargeLimit(write: (String, Int, (ChargeLimit.Answer) -> Unit) -> Unit) {
        writeLimit = write
    }

    // Which cars can charge here and how the plugged-in one is found: the settings record's own fields,
    // written through the ordinary settings write (the screen owns it), and whether it can be written.
    private var writeIdentification: (IdentifyMode, List<String>?, (String?) -> Unit) -> Unit =
        { _, _, done -> done(null) }
    private var identificationWritable = false
    private var identificationWriting = false
    private var adoptedIdentification: HaIdentificationSettings? = null
    private var identificationNotice: String? = null

    init {
        parent.addView(container)
    }

    /** Where the two writes are carried out (the screen owns the connection). */
    fun attachWrites(
        vehicle: (String, List<VehicleUpdate.FieldChange>, (VehicleUpdate.Outcome) -> Unit) -> Unit,
        site: (SiteUpdate.Request, (SiteUpdate.Outcome) -> Unit) -> Unit,
        priority: (String, String, (ChargerPriorityUpdate.Outcome) -> Unit) -> Unit =
            { _, _, done -> done(ChargerPriorityUpdate.Outcome.Failed(null)) }
    ) {
        writeVehicle = vehicle
        writeSite = site
        writePriority = priority
    }

    /**
     * Where the identification settings are written (the screen owns the settings write; `done` gets the
     * refusal in words, or `null`), and whether the record can be written right now.
     */
    fun attachIdentification(write: (IdentifyMode, List<String>?, (String?) -> Unit) -> Unit) {
        writeIdentification = write
    }

    fun setIdentificationWritable(writable: Boolean) {
        if (writable == identificationWritable) return
        identificationWritable = writable
        repaint()
    }

    /** Paint [fresh] (the rows a write adopted give way to it); a failed read (`null`) leaves what is shown and says so only when nothing is. */
    fun show(fresh: Dashboard?) {
        if (fresh != null && fresh !== dashboard) {
            adoptedVehicles.clear()
            adoptedSite = null
            adoptedPriority = null
            adoptedIdentification = null
        }
        if (fresh != null) dashboard = fresh
        unreachable = fresh == null && dashboard == null
        repaint()
    }

    private fun muted(text: String, top: Int = 0, bottom: Int = 0) = TextView(context).apply {
        this.text = text; textSize = 13f; setTextColor(muted); setPadding(0, dp(top), 0, dp(bottom))
    }

    /** A value row that cannot be changed here: right-aligned in the normal text colour. */
    private fun readRow(parent: LinearLayout, label: String, value: String, help: String? = null) =
        settingRow(parent, label, value, help)

    /** A value row that opens [onTap] (its dialog), or a read-only one while [onTap] is `null`. */
    private fun valueRow(parent: LinearLayout, label: String, value: String, help: String? = null, onTap: (() -> Unit)?) =
        settingRow(parent, label, value, help, onTap)

    private fun repaint() {
        container.removeAllViews()
        val dash = dashboard
        if (dash == null) {
            if (unreachable) container.addView(muted(t(R.string.instance_unreachable), bottom = 14))
            return
        }
        addVehicleCards(dash)
        addChargerCard(dash)
        val site = adoptedSite ?: dash.site
        addSiteCard(site)
        if (site != null) addSolarCard(site)
    }

    // --- Vehicles -------------------------------------------------------------------------------

    private fun addVehicleCards(dash: Dashboard) {
        val vehicles = PairedOverview.vehicles(dash, adoptedVehicles)
        if (vehicles.isEmpty()) {
            val card = card(container, t(R.string.vehicle_title), R.drawable.ic_ev)
            card.body.addView(muted(t(R.string.settings_vehicle_none), top = 8))
            return
        }
        for (vehicle in vehicles) {
            val card = card(container, vehicle.name, R.drawable.ic_ev)
            if (vehicle.planned) card.body.addView(muted(t(R.string.settings_vehicle_planned_here), top = 8))
            readRow(card.body, t(R.string.vehicle_charge_level_label), chargeLevelText(vehicle))
            // The car's own figures open its dialog; a battery size the car reports itself is read-only there too.
            val edit = { openVehicleDialog(vehicle.id) }
            // A battery size the car reports itself cannot be typed: read-only, saying so under it.
            if (vehicle.capacityReported && vehicle.capacityKwh != null) {
                readRow(card.body, t(R.string.vehicle_card_capacity_label), t(R.string.vehicle_card_capacity_value, vehicle.capacityKwh),
                    help = t(R.string.vehicle_capacity_reported))
            } else {
                valueRow(card.body, t(R.string.vehicle_card_capacity_label), capacityText(vehicle), onTap = edit)
            }
            valueRow(card.body, t(R.string.consumption), vehicle.consumptionKwhPer10km
                ?.let { t(R.string.consumption_value, it) } ?: t(R.string.paired_value_unset), onTap = edit)
            vehicle.onboardPhases?.let { phases ->
                valueRow(card.body, t(R.string.vehicle_onboard_label), phasesText(phases), onTap = edit)
            }
            // The car's own charge limit, when it reports one: written to the car where Home Assistant can.
            vehicle.chargeLimit?.let { limit ->
                valueRow(card.body, t(R.string.vehicle_card_limit_label), t(R.string.vehicle_card_limit_value, limit),
                    onTap = if (vehicle.limitWritable) ({ openLimitDialog(vehicle.id, vehicle.name, limit) }) else null)
            }
            // The car's own target, the same at every charger (changed in the car's dialog).
            if (vehicle.targetStated) {
                valueRow(card.body, t(R.string.vehicle_target), vehicle.targetPercent
                    ?.let { t(R.string.vehicle_card_soc_value, SocDisplay.wholePercent(it)) } ?: t(R.string.paired_value_unset),
                    help = t(R.string.vehicle_target_follows), onTap = edit)
            }
            vehicle.sources?.let { addSources(card.body, it) }
            vehicleNotices[vehicle.id]?.let { card.body.addView(muted(it, top = 4)) }
        }
    }

    /** The car's charge limit: a whole percent, written to the car through the integration. */
    private fun openLimitDialog(vehicleId: String, name: String, shown: Int) {
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val field = numberInput(shown.toDouble(), "%")
        field.input.inputType = InputType.TYPE_CLASS_NUMBER
        field.input.setText(String.format(Locale.ROOT, "%d", shown))
        body.addView(field.view)
        body.addView(muted(t(R.string.vehicle_limit_hint), top = 2))
        openSaveDialog(t(R.string.settings_vehicle_dialog_title, name), body) { dialog, save ->
            val percent = field.input.text.toString().trim().toIntOrNull()
            if (percent == null || percent !in 1..100) {
                field.error.say(t(R.string.paired_error_number))
                return@openSaveDialog
            }
            if (percent == shown) {
                dialog.dismiss()
                return@openSaveDialog
            }
            field.error.say(null)
            save.isEnabled = false
            writeLimit(vehicleId, percent) { answer ->
                val message = ChargeLimit.message(answer)
                if (message == null) {
                    vehicleNotices.remove(vehicleId)
                    dialog.dismiss()
                    repaint()
                } else {
                    save.isEnabled = true
                    field.error.say(
                        when (message.kind) {
                            ChargeLimit.Kind.TOO_SOON -> message.retryAfterS?.let { t(R.string.vehicle_limit_too_soon, it) }
                                ?: t(R.string.vehicle_card_reread_too_soon_shortly)
                            ChargeLimit.Kind.FAILED -> t(R.string.vehicle_limit_failed)
                        }
                    )
                }
            }
        }
    }

    /** A car's identification sources: read-only here, chosen in Home Assistant. */
    private fun addSources(body: LinearLayout, sources: VehicleIdentificationSources) {
        body.addView(divider())
        body.addView(subheading(t(R.string.identify_sources_title)))
        sources.plug?.let { readRow(body, t(R.string.identify_source_plug), sourceText(it)) }
        sources.location?.let { readRow(body, t(R.string.identify_source_location), sourceText(it)) }
        body.addView(muted(t(R.string.identify_sources_help), top = 2))
    }

    private fun sourceText(source: IdentificationSource): String = when (val text = VehicleIdentification.sourceText(source)) {
        is VehicleIdentification.SourceText.Named -> text.name
        VehicleIdentification.SourceText.None -> t(R.string.identify_source_none)
        VehicleIdentification.SourceText.Choose -> t(R.string.identify_source_choose)
        VehicleIdentification.SourceText.NotFound -> t(R.string.identify_source_not_found)
    }

    private fun divider() = View(context).apply {
        setBackgroundColor((muted and 0x00FFFFFF) or DIVIDER_ALPHA)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(12); bottomMargin = dp(8)
        }
    }

    private fun subheading(text: String) = TextView(context).apply {
        this.text = text; textSize = 15f; setTextColor(dark); typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun phasesText(phases: Int) = t(if (phases == 1) R.string.phase_one else R.string.phase_three)

    private fun chargeLevelText(vehicle: PairedOverview.VehicleCard): String =
        when (val level = vehicle.chargeLevel) {
            is PairedOverview.ChargeLevel.Reading -> t(R.string.vehicle_card_soc_value, level.percent)
                .let { if (level.estimated) "~$it" else it }
            PairedOverview.ChargeLevel.NoSensor, PairedOverview.ChargeLevel.NoReading -> t(R.string.vehicle_charge_level_none)
        }

    private fun capacityText(vehicle: PairedOverview.VehicleCard): String {
        val kwh = vehicle.capacityKwh ?: return t(R.string.paired_value_unset)
        val text = t(R.string.vehicle_card_capacity_value, kwh)
        return if (vehicle.capacityReported) "$text (${t(R.string.vehicle_capacity_reported)})" else text
    }

    private fun vehicleNoticeText(notice: PairedVehicles.Notice) = t(
        when (notice) {
            PairedVehicles.Notice.CONFLICT -> R.string.paired_error_conflict
            PairedVehicles.Notice.UNKNOWN_VEHICLE -> R.string.vehicle_error_unknown
            PairedVehicles.Notice.NOT_SUPPORTED -> R.string.paired_error_version
            PairedVehicles.Notice.FAILED -> R.string.paired_error_generic
            PairedVehicles.Notice.REFUSED -> R.string.paired_error_invalid
        }
    )

    private fun issueText(field: VehicleField, issue: VehicleFieldIssue) = t(
        when (issue) {
            VehicleFieldIssue.OUT_OF_RANGE -> when (field) {
                VehicleField.CAPACITY -> R.string.vehicle_error_capacity
                VehicleField.CONSUMPTION -> R.string.vehicle_error_consumption
                VehicleField.ONBOARD_PHASES -> R.string.vehicle_error_onboard
                VehicleField.TARGET -> R.string.vehicle_error_target
            }
            VehicleFieldIssue.NOT_A_NUMBER -> R.string.paired_error_number
            VehicleFieldIssue.UNKNOWN -> R.string.paired_error_field
        }
    )

    /** A current as the card writes it: whole amps without decimals, else one, in the screen's number locale. */
    private fun ampsText(amps: Double): String =
        if (amps % 1.0 == 0.0) amps.toLong().toString()
        else String.format(AppLanguageSettings.numberLocale(context), "%.1f", amps)

    private fun errorView() = TextView(context).apply {
        textSize = 13f; setTextColor(ERROR_COLOUR); setPadding(0, dp(4), 0, 0); visibility = View.GONE
    }

    private fun TextView.say(text: String?) {
        this.text = text.orEmpty()
        visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** A decimal field with its unit and the error its save can put under it. */
    private class NumberField(val input: EditText, val error: TextView, val view: View)

    private fun numberInput(initial: Double?, unit: String): NumberField {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            initial?.let { setText(VehicleUpdate.display(it, Locale.ROOT)) }
            hint = t(R.string.paired_value_unset)
            setSelectAllOnFocus(true)
        }
        val error = errorView()
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(input, weight())
                addView(TextView(context).apply {
                    text = unit; textSize = 15f; setTextColor(muted); setPadding(dp(8), 0, 0, 0)
                })
            })
            addView(error)
        }
        return NumberField(input, error, view)
    }

    private fun openVehicleDialog(vehicleId: String) {
        val dash = dashboard ?: return
        val row = PairedVehicles.row(dash, adoptedVehicles, vehicleId) ?: return
        val editable = VehicleUpdate.capacityEditable(row)
        vehicleNotices.remove(vehicleId)
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val capacity = numberInput(row.capacityKwh, "kWh")
        body.addView(TextView(context).apply {
            text = t(R.string.vehicle_card_capacity_label); textSize = 14f; setTextColor(muted)
        })
        if (editable) {
            body.addView(capacity.view)
            body.addView(muted(t(R.string.vehicle_card_capacity_note), top = 2))
        } else {
            // A capacity the car reports itself always wins, so a typed figure could never take effect.
            body.addView(TextView(context).apply {
                text = "${row.capacityKwh?.let { t(R.string.vehicle_card_capacity_value, it) }.orEmpty()} (${t(R.string.vehicle_capacity_reported)})"
                textSize = 16f; setTextColor(dark); setPadding(0, dp(6), 0, dp(6))
            })
        }
        val consumption = numberInput(row.consumptionKwhPer10km, "kWh/10 km")
        body.addView(TextView(context).apply {
            text = t(R.string.consumption); textSize = 14f; setTextColor(muted); setPadding(0, dp(14), 0, 0)
        })
        body.addView(consumption.view)
        body.addView(muted(t(R.string.vehicle_card_consumption_note), top = 2))
        // The car's onboard charger: how many phases it takes (a car that takes one charges on one
        // phase even on a three-phase charger). Offered only when Home Assistant states it.
        var onboardGroup: RadioGroup? = null
        var onboardOne: RadioButton? = null
        row.onboardPhases?.let { phases ->
            body.addView(TextView(context).apply {
                text = t(R.string.vehicle_onboard_label); textSize = 14f; setTextColor(muted); setPadding(0, dp(14), 0, 0)
            })
            val group = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
            val one = RadioButton(context).apply { id = View.generateViewId(); text = phasesText(1); isChecked = phases == 1 }
            val three = RadioButton(context).apply { id = View.generateViewId(); text = phasesText(3); isChecked = phases != 1 }
            group.addView(one); group.addView(three)
            body.addView(group)
            body.addView(muted(t(R.string.vehicle_onboard_note), top = 2))
            onboardGroup = group
            onboardOne = one
        }
        // The car's own target, the same at every charger: offered only when Home Assistant states it.
        val target = if (row.targetStated) numberInput(row.targetPercent, "%").also { field ->
            row.targetPercent?.let { field.input.setText(String.format(Locale.ROOT, "%d", SocDisplay.wholePercent(it))) }
            field.input.inputType = InputType.TYPE_CLASS_NUMBER
            body.addView(TextView(context).apply {
                text = t(R.string.vehicle_target); textSize = 14f; setTextColor(muted); setPadding(0, dp(14), 0, 0)
            })
            body.addView(field.view)
            body.addView(muted(t(R.string.vehicle_target_follows), top = 2))
        } else null
        val general = errorView()
        body.addView(general)

        openSaveDialog(t(R.string.settings_vehicle_dialog_title, row.name), body) { dialog, save ->
            val current = PairedVehicles.row(dashboard ?: return@openSaveDialog, adoptedVehicles, vehicleId)
                ?: return@openSaveDialog
            capacity.error.say(null); consumption.error.say(null); general.say(null); target?.error?.say(null)
            val chosenPhases = onboardGroup?.let { group -> if (group.checkedRadioButtonId == onboardOne?.id) 1 else 3 }
            when (val draft = VehicleUpdate.draft(
                current, capacity.input.text.toString(), consumption.input.text.toString(), chosenPhases,
                target?.input?.text?.toString()
            )) {
                VehicleUpdate.Draft.Unchanged -> dialog.dismiss()
                is VehicleUpdate.Draft.Invalid -> {
                    draft.issues[VehicleField.CAPACITY]?.let { capacity.error.say(issueText(VehicleField.CAPACITY, it)) }
                    draft.issues[VehicleField.CONSUMPTION]?.let { consumption.error.say(issueText(VehicleField.CONSUMPTION, it)) }
                    draft.issues[VehicleField.ONBOARD_PHASES]?.let { general.say(issueText(VehicleField.ONBOARD_PHASES, it)) }
                    draft.issues[VehicleField.TARGET]?.let { (target?.error ?: general).say(issueText(VehicleField.TARGET, it)) }
                }
                is VehicleUpdate.Draft.Write -> {
                    save.isEnabled = false
                    writeVehicle(vehicleId, draft.changes) { outcome ->
                        val feedback = PairedVehicles.feedback(outcome)
                        feedback.adopted?.let { adoptedVehicles[it.id] = it }
                        if (feedback.close) {
                            feedback.notice?.let { vehicleNotices[vehicleId] = vehicleNoticeText(it) }
                                ?: vehicleNotices.remove(vehicleId)
                            dialog.dismiss()
                            repaint()
                        } else {
                            save.isEnabled = true
                            feedback.issues[VehicleField.CAPACITY]?.let { capacity.error.say(issueText(VehicleField.CAPACITY, it)) }
                            feedback.issues[VehicleField.CONSUMPTION]?.let { consumption.error.say(issueText(VehicleField.CONSUMPTION, it)) }
                            feedback.issues[VehicleField.ONBOARD_PHASES]?.let { general.say(issueText(VehicleField.ONBOARD_PHASES, it)) }
                            feedback.issues[VehicleField.TARGET]?.let { (target?.error ?: general).say(issueText(VehicleField.TARGET, it)) }
                            if (feedback.issues.isEmpty()) general.say(feedback.notice?.let { vehicleNoticeText(it) })
                        }
                    }
                }
            }
        }
    }

    // --- Charger --------------------------------------------------------------------------------

    private fun addChargerCard(dash: Dashboard) {
        val charger = PairedOverview.charger(dash)
        val card = card(container, t(R.string.section_charger), R.drawable.ic_card_charger)
        if (charger.showsStartStop) {
            readRow(card.body, t(R.string.charger_start_stop_label),
                charger.summary?.startStopName ?: t(R.string.paired_value_unset))
        }
        readRow(card.body, t(R.string.charger_current_label), when (charger.summaryPath) {
            DashboardSummary.CurrentPath.CHANGE_CONFIGURATION -> t(R.string.charger_current_ocpp)
            DashboardSummary.CurrentPath.NUMBER -> charger.summary?.currentEntityName
                ?.let { t(R.string.charger_current_number, it) } ?: t(R.string.charger_current_number_unnamed)
            DashboardSummary.CurrentPath.EASEE_DYNAMIC_LIMIT -> t(R.string.charger_current_easee)
            DashboardSummary.CurrentPath.NONE -> t(R.string.charger_current_kept)
            null -> when (charger.currentPath) {
                PairedOverview.CurrentPath.SET_BY_SPOTNAV ->
                    t(R.string.charger_current_set, charger.minA, charger.maxA)
                PairedOverview.CurrentPath.KEPT_BY_CHARGER -> t(R.string.charger_current_kept)
            }
        })
        charger.energy?.let { energy ->
            readRow(card.body, t(R.string.charger_energy_label), when (energy) {
                is PairedOverview.EnergyMeter.Named -> energy.name
                PairedOverview.EnergyMeter.Automatic -> t(R.string.charger_energy_automatic)
                PairedOverview.EnergyMeter.NotSpecified -> t(R.string.paired_value_unset)
            })
        }
        card.body.addView(muted(t(R.string.changed_in_home_assistant), top = 6))
        // The priority is this charger's own setting (its place among the site's chargers), as in the card.
        addPriority(card.body)
        addIdentification(card.body, dash)
    }

    // --- Which car is plugged in ------------------------------------------------------------------

    private fun modeName(mode: IdentifyMode) = t(
        when (mode) {
            IdentifyMode.AUTOMATIC -> R.string.identify_mode_automatic
            IdentifyMode.ASK -> R.string.identify_mode_ask
            IdentifyMode.OFF -> R.string.identify_mode_off
        }
    )

    private fun modeHelp(mode: IdentifyMode) = t(
        when (mode) {
            IdentifyMode.AUTOMATIC -> R.string.identify_mode_automatic_help
            IdentifyMode.ASK -> R.string.identify_mode_ask_help
            IdentifyMode.OFF -> R.string.identify_mode_off_help
        }
    )

    /**
     * The cars at this charger and how the plugged-in one is found, at a charger more than one car can
     * charge at (Home Assistant states both or neither). Each change is written at once, through the
     * ordinary settings write; while the record cannot be written they are shown read-only.
     */
    private fun addIdentification(body: LinearLayout, dash: Dashboard) {
        val record = dash.settings?.let { record ->
            adoptedIdentification?.let { record.copy(identification = it) } ?: record
        }
        val section = VehicleIdentification.section(dash, record) ?: return
        val writable = identificationWritable && !identificationWriting
        val allIds = section.cars.map { it.vehicleId }
        val stored = record?.identification

        fun write(mode: IdentifyMode, ids: List<String>?, done: (String?) -> Unit = {}) {
            if (stored != null && stored.mode == mode && stored.vehicleIds == ids) { done(null); return }
            identificationWriting = true
            identificationNotice = null
            repaint()
            writeIdentification(mode, ids) { refusal ->
                identificationWriting = false
                if (refusal == null) adoptedIdentification = HaIdentificationSettings(mode, ids)
                identificationNotice = refusal
                done(refusal)
                repaint()
            }
        }

        body.addView(divider())
        // The cars at this charger: "All cars", or the ticked ones by name; a dialog ticks them.
        val names = VehicleIdentification.tickedNames(section)
        valueRow(body, t(R.string.identify_vehicles_title),
            names?.joinToString(", ") ?: t(R.string.identify_vehicles_all),
            onTap = if (writable) ({
                chooseMany(t(R.string.identify_vehicles_title), section.cars.map { it.name }, section.cars.map { it.ticked },
                    intro = t(R.string.identify_vehicles_help)) { ticks, dialog, say ->
                    val ids = VehicleIdentification.vehicleIdsFor(allIds, allIds.filterIndexed { index, _ -> ticks[index] }.toSet())
                    if (ids != null && ids.isEmpty()) {
                        say(t(R.string.identify_no_vehicle))
                    } else {
                        dialog.dismiss()
                        write(section.mode, ids)
                    }
                }
            }) else null)
        // How the plugged-in car is found: one of three, each saying what it does.
        valueRow(body, t(R.string.identify_mode_title), modeName(section.mode), help = t(R.string.identify_mode_help),
            onTap = if (writable) ({
                chooseOne(t(R.string.identify_mode_title), IdentifyMode.entries.map { modeName(it) },
                    IdentifyMode.entries.indexOf(section.mode), helps = IdentifyMode.entries.map { modeHelp(it) }) { index ->
                    write(IdentifyMode.entries[index], stored?.vehicleIds)
                }
            }) else null)
        if (!identificationWritable) body.addView(muted(t(R.string.settings_paired_read_only), top = 4))
        identificationNotice?.let { body.addView(muted(it, top = 4)) }
    }

    // --- Site -----------------------------------------------------------------------------------

    private fun siteNoticeText(notice: PairedVehicles.SiteNotice) = t(
        when (notice) {
            PairedVehicles.SiteNotice.CONFLICT -> R.string.site_error_conflict
            PairedVehicles.SiteNotice.INVALID -> R.string.paired_error_invalid
            PairedVehicles.SiteNotice.NOT_PERMITTED -> R.string.site_error_not_permitted
            PairedVehicles.SiteNotice.UNAVAILABLE -> R.string.site_error_unavailable
            PairedVehicles.SiteNotice.NOT_SUPPORTED -> R.string.paired_error_version
            PairedVehicles.SiteNotice.FAILED -> R.string.paired_error_generic
        }
    )

    private fun addSiteCard(site: DashboardSite?) {
        if (site == null) {
            val card = card(container, t(R.string.site_default_name), R.drawable.ic_site)
            card.body.addView(muted(t(R.string.site_none), top = 8))
            return
        }
        val summary = PairedOverview.site(site, dashboard?.summary?.site)
        // The site's own name alone; "Site" only when it has none.
        val card = card(container, summary.name ?: t(R.string.site_default_name), R.drawable.ic_site)
        card.body.addView(muted(tq(R.plurals.site_applies, summary.chargers, summary.chargers), top = 8, bottom = 4))
        summary.setup?.let { setup ->
            setup.mainFuseA?.let { readRow(card.body, t(R.string.site_main_fuse_label), t(R.string.site_main_fuse_value, ampsText(it))) }
            setup.measurementMode?.let { mode ->
                readRow(card.body, t(R.string.site_measurement_label), t(
                    when (mode) {
                        DashboardSummary.MeasurementMode.DIRECT -> R.string.site_measurement_direct
                        DashboardSummary.MeasurementMode.DERIVED -> R.string.site_measurement_derived
                    }
                ))
            }
            readRow(card.body, t(R.string.site_battery_label), setup.batteryName ?: t(R.string.value_none))
        }
        readRow(card.body, t(R.string.site_active_title), t(if (summary.activeControlOn) R.string.site_on else R.string.site_off))
        val reason = when (summary.reason) {
            SiteFacts.Reason.NONE -> null
            SiteFacts.Reason.DUPLICATE_MEMBERSHIP -> t(R.string.site_active_reason_duplicate)
            SiteFacts.Reason.NO_COMMANDABLE_CHARGER -> t(R.string.site_active_reason_no_charger)
            SiteFacts.Reason.MEASUREMENT -> t(R.string.site_active_reason_measurement)
            SiteFacts.Reason.UNKNOWN -> t(R.string.site_active_reason_unknown)
        }
        // State and reason only: the switch and the site's entities are changed in Home Assistant.
        card.body.addView(muted(
            listOfNotNull(reason, t(R.string.site_active_in_ha), t(R.string.site_active_note)).joinToString(" "),
            top = 6
        ))
    }

    // --- Charger priority -----------------------------------------------------------------------

    private fun priorityName(value: String) = t(
        when (value) {
            ChargerPriority.FIRST -> R.string.priority_first
            ChargerPriority.LAST -> R.string.priority_last
            else -> R.string.priority_normal
        }
    )

    private fun priorityNoticeText(notice: PairedVehicles.PriorityNotice) = t(
        when (notice) {
            PairedVehicles.PriorityNotice.CONFLICT -> R.string.site_error_conflict
            PairedVehicles.PriorityNotice.INVALID -> R.string.paired_error_invalid
            PairedVehicles.PriorityNotice.NO_SITE -> R.string.site_error_unavailable
            PairedVehicles.PriorityNotice.NOT_SUPPORTED -> R.string.paired_error_version
            PairedVehicles.PriorityNotice.FAILED -> R.string.paired_error_generic
        }
    )

    /** The charger's priority on its site: absent from an older Home Assistant, so then nothing is shown. */
    private fun addPriority(body: LinearLayout) {
        val priority = adoptedPriority ?: dashboard?.chargerPriority ?: return
        valueRow(body, t(R.string.priority_label), priorityName(priority.value), help = t(R.string.priority_help),
            onTap = if (priority.writable) ({ openPriorityDialog() }) else null)
        if (!priority.writable) body.addView(muted(t(R.string.site_read_only), top = 4))
        priorityNotice?.let { body.addView(muted(it, top = 4)) }
    }

    private fun openPriorityDialog() {
        val shown = adoptedPriority ?: dashboard?.chargerPriority ?: return
        if (!shown.writable) return
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(muted(t(R.string.priority_help), bottom = 4))
        val group = RadioGroup(context).apply { orientation = LinearLayout.VERTICAL }
        val buttons = shown.choices.map { value ->
            RadioButton(context).apply {
                id = View.generateViewId()
                text = priorityName(value)
                isChecked = value == shown.value
            }.also { group.addView(it) }
        }
        body.addView(group)
        val error = errorView()
        body.addView(error)

        openSaveDialog(t(R.string.priority_label), body) { dialog, save ->
            val current = adoptedPriority ?: dashboard?.chargerPriority ?: return@openSaveDialog
            val chosen = current.choices.getOrNull(buttons.indexOfFirst { it.isChecked }) ?: current.value
            if (chosen == current.value) {
                dialog.dismiss()
                return@openSaveDialog
            }
            error.say(null)
            save.isEnabled = false
            writePriority(current.value, chosen) { outcome ->
                val feedback = PairedVehicles.feedback(outcome)
                feedback.adopted?.let { adoptedPriority = it }
                val message = feedback.notice?.let { priorityNoticeText(it) }
                if (feedback.notice == null || feedback.reload) {
                    priorityNotice = message
                    dialog.dismiss()
                    repaint()
                } else {
                    save.isEnabled = true
                    error.say(message)
                }
            }
        }
    }

    // --- Solar ----------------------------------------------------------------------------------

    private fun priorityText(value: String?) = when (value) {
        SiteUpdate.CAR_FIRST -> t(R.string.site_car_first)
        SiteUpdate.BATTERY_FIRST -> t(R.string.site_battery_first)
        else -> t(R.string.paired_value_unset)
    }

    private fun addSolarCard(site: DashboardSite) {
        val summary = PairedOverview.solar(site)
        val chargers = PairedOverview.site(site).chargers
        val card = card(container, t(R.string.section_solar), R.drawable.ic_solar)
        card.body.addView(muted(tq(R.plurals.site_applies, chargers, chargers), top = 8, bottom = 4))
        val edit: (() -> Unit)? = if (summary.editable) ({ openSolarDialog() }) else null
        valueRow(card.body, t(R.string.site_solar_priority), priorityText(summary.priority), onTap = edit)
        valueRow(card.body, t(R.string.site_forecast_title),
            if (summary.forecastTitles.isEmpty()) t(R.string.value_none) else summary.forecastTitles.joinToString(", "), onTap = edit)
        if (!summary.editable) card.body.addView(muted(t(R.string.site_read_only), top = 6))
        solarNotice?.let { card.body.addView(muted(it, top = 4)) }
    }

    private fun openSolarDialog() {
        val site = adoptedSite ?: dashboard?.site ?: return
        if (!SiteFacts.solarEditable(site)) return
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val chargers = PairedOverview.site(site).chargers
        body.addView(muted(tq(R.plurals.site_applies, chargers, chargers), bottom = 4))
        body.addView(TextView(context).apply {
            text = t(R.string.site_solar_priority); textSize = 14f; setTextColor(muted)
        })
        val group = RadioGroup(context).apply { orientation = LinearLayout.VERTICAL }
        val buttons = SiteFacts.PRIORITIES.map { value ->
            RadioButton(context).apply {
                id = View.generateViewId()
                text = priorityText(value)
                isChecked = value == site.solarPriority
            }.also { group.addView(it) }
        }
        body.addView(group)
        body.addView(TextView(context).apply {
            text = t(R.string.site_forecast_title); textSize = 14f; setTextColor(muted); setPadding(0, dp(12), 0, dp(4))
        })
        val boxes = site.solarForecastChoices.map { choice ->
            CheckBox(context).apply {
                text = choice.title; textSize = 16f; isChecked = choice.id in PairedOverview.solarSelection(site)
            }.also { body.addView(it) }
        }
        if (boxes.isEmpty()) body.addView(muted(t(R.string.site_forecast_none)))
        val error = errorView()
        body.addView(error)

        openSaveDialog(t(R.string.solar_dialog_title), body) { dialog, save ->
            val current = adoptedSite ?: dashboard?.site ?: return@openSaveDialog
            val priority = SiteFacts.PRIORITIES.getOrNull(buttons.indexOfFirst { it.isChecked })
                ?: current.solarPriority ?: SiteUpdate.CAR_FIRST
            val chosen = current.solarForecastChoices.filterIndexed { index, _ -> boxes.getOrNull(index)?.isChecked == true }
                .map { it.id }.toSet() +
                current.solarForecastSelected.filter { id -> current.solarForecastChoices.none { it.id == id } }
            val request = SiteUpdate.solarRequest(current, priority, chosen)
            if (request == null) {
                dialog.dismiss()
            } else {
                error.say(null)
                save.isEnabled = false
                writeSite(request) { outcome ->
                    val feedback = PairedVehicles.feedback(outcome)
                    feedback.adopted?.let { adoptedSite = it }
                    val message = feedback.notice?.let { siteNoticeText(it) }
                    if (feedback.notice == null || feedback.reload) {
                        solarNotice = message
                        dialog.dismiss()
                        repaint()
                    } else {
                        save.isEnabled = true
                        error.say(message)
                    }
                }
            }
        }
    }

    private companion object {
        const val ERROR_COLOUR = 0xFFD65C5C.toInt()

        /** The divider between a card's sections: the muted colour, faint. */
        const val DIVIDER_ALPHA = 0x33000000

        /** Longer read-only values go under their label rather than beside it. */
        const val STACK_AFTER_CHARS = 18
    }
}

/** The dialog shell every Change button opens: a title, a body, Save that stays open and Cancel. */
internal fun ViewScope.openSaveDialog(title: String, body: View, onSave: (AlertDialog, Button) -> Unit) {
    val padded = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(8), dp(22), 0)
        addView(body)
    }
    val dialog = AlertDialog.Builder(context)
        .setTitle(title)
        .setView(ScrollView(context).apply { addView(padded) })
        .setPositiveButton(t(R.string.paired_save), null)
        .setNegativeButton(t(android.R.string.cancel), null)
        .create()
    dialog.show()
    val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
    save.setOnClickListener { onSave(dialog, save) }
}
