package se.sensnology.spotnav.ui.settings

import android.app.AlertDialog
import android.graphics.Bitmap
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
import se.sensnology.spotnav.ha.client.CameraCommands
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
import se.sensnology.spotnav.ha.dashboard.ReferencePicture
import se.sensnology.spotnav.ha.dashboard.VehicleIdentificationSources
import se.sensnology.spotnav.ha.settings.HaCameraChoice
import se.sensnology.spotnav.ha.settings.HaIdentificationSettings
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.IdentifyMode
import se.sensnology.spotnav.ui.common.ValueCue
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.card
import se.sensnology.spotnav.ui.common.chooseMany
import se.sensnology.spotnav.ui.common.chooseOne
import se.sensnology.spotnav.ui.common.NumberSpec
import se.sensnology.spotnav.ui.common.editNumber
import se.sensnology.spotnav.ui.common.settingRow
import se.sensnology.spotnav.ui.common.textTabs
import se.sensnology.spotnav.ui.common.textTabsLayoutParams
import se.sensnology.spotnav.ui.common.valueLabel
import se.sensnology.spotnav.ui.common.valueColour
import se.sensnology.spotnav.ui.common.weight
import se.sensnology.spotnav.vehicles.CameraSetup
import se.sensnology.spotnav.vehicles.ChargeLimit
import se.sensnology.spotnav.vehicles.PairedVehicles
import se.sensnology.spotnav.vehicles.SocDisplay
import se.sensnology.spotnav.vehicles.TargetSlider
import se.sensnology.spotnav.vehicles.VehicleIdentification
import java.time.ZoneId
import java.util.Locale

/**
 * The cards of a paired charger's settings, in the order the Home Assistant card has them after the
 * price card: one card per vehicle, the charger, the site and solar.
 *
 * Every value is a row; one the webhook lets the app change (a car's figures, the charger's priority
 * and identification, the site's solar settings) opens its own editor and writes that one value.
 */
internal class PairedSettingsCards(
    scope: ViewScope,
    parent: LinearLayout,
    /** The charger these settings are for, by the name the app shows it under (its heading says so). */
    private val chargerName: String? = null,
    /** With several paired chargers, the charger card's tabs: which one these settings are for. */
    private val chargerTabs: ChargerTabs? = null
) : ViewScope(scope) {
    /** The paired chargers by name, the one shown chosen, and what choosing another does. */
    class ChargerTabs(val names: List<String>, val selected: Int, val onSelect: (Int) -> Unit)

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

    // The charger's camera: its webhook actions (the screen owns the connection; a picture comes back
    // decoded at most the given size), what a frame or a picture write answered, and the reference
    // thumbnails fetched so far (a few, by car, kind and when the picture was taken).
    private var cameraCall: (CameraCommands.Request, Int?, (CameraCommands.Outcome, Bitmap?) -> Unit) -> Unit =
        { _, _, done -> done(CameraCommands.Outcome.Failed(null), null) }
    private var adoptedCamera: HaCameraChoice? = null

    /** The car tab chosen in the car card (several cars only). */
    private var selectedCar: String? = null
    private val adoptedReferences = mutableMapOf<String, List<ReferencePicture>>()
    private val thumbnails = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > MAX_THUMBNAILS
    }
    private val thumbnailWaiters = mutableMapOf<String, MutableList<(Bitmap?) -> Unit>>()

    init {
        parent.addView(container)
    }

    /** Where the camera's actions are carried out (the screen owns the connection and decodes pictures off the main thread). */
    fun attachCamera(call: (CameraCommands.Request, Int?, (CameraCommands.Outcome, Bitmap?) -> Unit) -> Unit) {
        cameraCall = call
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
            adoptedCamera = null
            adoptedReferences.clear()
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
        // One car is "Bil · EV6"; several are one "Bil" card with a tab per car, the chosen car's rows below.
        val selected = PairedOverview.selectedTab(vehicles, selectedCar)
        selectedCar = selected
        if (selected == null) {
            val vehicle = vehicles.first()
            val card = card(container, SettingsHeading.named(t(R.string.vehicle_title), vehicle.name), R.drawable.ic_ev)
            addVehicleRows(card.body, dash, vehicle)
            return
        }
        val card = card(container, t(R.string.vehicle_title), R.drawable.ic_ev)
        val tabs = textTabs(vehicles.map { it.name ?: t(R.string.vehicle_title) }, vehicles.indexOfFirst { it.id == selected }, card.body) { index ->
            val chosen = vehicles[index].id
            if (selectedCar != chosen) {
                selectedCar = chosen
                repaint()
            }
        }
        card.header.addView(tabs.row, textTabsLayoutParams())
        addVehicleRows(card.body, dash, vehicles.first { it.id == selected })
    }

    /** One car's rows: its level, its own figures, its target, its sources and its reference pictures. */
    private fun addVehicleRows(body: LinearLayout, dash: Dashboard, vehicle: PairedOverview.VehicleCard) {
        readRow(body, t(R.string.vehicle_charge_level_label), chargeLevelText(vehicle))
        // Each of the car's own figures opens its own editor and writes that one figure.
        if (vehicle.capacityReported && vehicle.capacityKwh != null) {
            // A battery size the car reports itself cannot be typed: read-only, saying so under it.
            readRow(body, t(R.string.vehicle_card_capacity_label), t(R.string.vehicle_card_capacity_value, vehicle.capacityKwh),
                help = t(R.string.vehicle_capacity_reported))
        } else {
            valueRow(body, t(R.string.vehicle_card_capacity_label), capacityText(vehicle)) {
                editNumber(t(R.string.vehicle_card_capacity_label), spec(VehicleField.CAPACITY, 1), "kWh", vehicle.capacityKwh,
                    t(R.string.vehicle_error_capacity), help = t(R.string.vehicle_card_capacity_note)) { value, done ->
                    writeOne(vehicle.id, VehicleField.CAPACITY, value, done)
                }
            }
        }
        valueRow(body, t(R.string.consumption), vehicle.consumptionKwhPer10km
            ?.let { t(R.string.consumption_value, it) } ?: t(R.string.paired_value_unset)) {
            editNumber(t(R.string.consumption), spec(VehicleField.CONSUMPTION, 1), "kWh/10 km", vehicle.consumptionKwhPer10km,
                t(R.string.vehicle_error_consumption), help = t(R.string.vehicle_card_consumption_note)) { value, done ->
                writeOne(vehicle.id, VehicleField.CONSUMPTION, value, done)
            }
        }
        vehicle.onboardPhases?.let { phases ->
            valueRow(body, t(R.string.vehicle_onboard_label), phasesText(phases)) {
                chooseOne(t(R.string.vehicle_onboard_label), listOf(phasesText(1), phasesText(3)), if (phases == 1) 0 else 1,
                    intro = t(R.string.vehicle_onboard_note)) { index, done ->
                    writeOne(vehicle.id, VehicleField.ONBOARD_PHASES, if (index == 0) 1.0 else 3.0, done)
                }
            }
        }
        // The car's own charge limit, when it reports one: written to the car where Home Assistant can.
        vehicle.chargeLimit?.let { limit ->
            valueRow(body, t(R.string.vehicle_card_limit_label), t(R.string.vehicle_card_limit_value, limit),
                onTap = if (vehicle.limitWritable) ({
                    editNumber(t(R.string.vehicle_card_limit_label), NumberSpec(1.0, 100.0, 0), "%", limit.toDouble(),
                        t(R.string.paired_error_number), help = t(R.string.vehicle_limit_hint)) { value, done ->
                        writeChargeLimit(vehicle.id, value!!.toInt(), limit, done)
                    }
                }) else null)
        }
        // The car's own target, the same at every charger, set with a slider in its editor. One none stored opens
        // there at the target it is planned with, and the minimum stops at that same target.
        val plannedTarget = vehicle.targetPercent ?: TargetSlider.default(vehicle.chargeLimit?.toDouble()).toDouble()
        if (vehicle.targetStated) {
            valueRow(body, t(R.string.vehicle_target), vehicle.targetPercent
                ?.let { t(R.string.vehicle_card_soc_value, SocDisplay.wholePercent(it)) } ?: t(R.string.paired_value_unset)) {
                // That the target follows the car to every charger is said in its editor.
                editTargetSlider(t(R.string.vehicle_target), vehicle.targetPercent, vehicle.chargeLimit?.toDouble(),
                    t(R.string.vehicle_target_follows)) { value, done ->
                    writeOne(vehicle.id, VehicleField.TARGET, value, done)
                }
            }
        }
        // The car's minimum charge level: below it Home Assistant charges at once, whatever the strategy.
        if (vehicle.minStated) {
            val level = vehicle.minPercent?.let { t(R.string.vehicle_card_soc_value, it) } ?: t(R.string.vehicle_minimum_off)
            val shown = if (vehicle.minPercent != null && vehicle.minNeedsLevel) t(R.string.vehicle_minimum_needs_level, level) else level
            valueRow(body, t(R.string.vehicle_minimum), shown) {
                editFloorSlider(t(R.string.vehicle_minimum), vehicle.minPercent, plannedTarget,
                    t(R.string.vehicle_minimum_help)) { chosen, done ->
                    writeOne(vehicle.id, VehicleField.MINIMUM, chosen?.toDouble(), done)
                }
            }
        }
        vehicle.sources?.let { addSources(body, it) }
        addReference(body, dash, vehicle)
        vehicleNotices[vehicle.id]?.let { body.addView(muted(it, top = 4)) }
    }

    /** A vehicle field's range as the write checks it, with the editor's decimals. */
    private fun spec(field: VehicleField, decimals: Int) = NumberSpec(field.min, field.max, decimals, minDecimals = decimals)

    /**
     * One of a car's figures written alone (`update_vehicle` with that field, compared against the
     * value the row showed): `done(null)` when it took or the row changed elsewhere first (said on the
     * card), else the reason in the editor.
     */
    private fun writeOne(vehicleId: String, field: VehicleField, value: Double?, done: (String?) -> Unit) {
        val row = PairedVehicles.row(dashboard ?: return done(t(R.string.paired_error_generic)), adoptedVehicles, vehicleId)
            ?: return done(t(R.string.vehicle_error_unknown))
        if (value == VehicleUpdate.shown(row, field)) return done(null)
        writeVehicle(vehicleId, listOf(VehicleUpdate.single(row, field, value))) { outcome ->
            val feedback = PairedVehicles.feedback(outcome)
            feedback.adopted?.let { adoptedVehicles[it.id] = it }
            if (feedback.close) {
                feedback.notice?.let { vehicleNotices[vehicleId] = vehicleNoticeText(it) } ?: vehicleNotices.remove(vehicleId)
                done(null)
                repaint()
            } else {
                done(feedback.issues[field]?.let { issueText(field, it) }
                    ?: feedback.notice?.let { vehicleNoticeText(it) } ?: t(R.string.paired_error_generic))
            }
        }
    }

    /** The car's charge limit, written to the car through the integration (`set_charge_limit`). */
    private fun writeChargeLimit(vehicleId: String, percent: Int, shown: Int, done: (String?) -> Unit) {
        if (percent == shown) return done(null)
        writeLimit(vehicleId, percent) { answer ->
            val message = ChargeLimit.message(answer)
            if (message == null) {
                vehicleNotices.remove(vehicleId)
                done(null)
                repaint()
            } else {
                done(when (message.kind) {
                    ChargeLimit.Kind.TOO_SOON -> message.retryAfterS?.let { t(R.string.vehicle_limit_too_soon, it) }
                        ?: t(R.string.vehicle_card_reread_too_soon_shortly)
                    ChargeLimit.Kind.FAILED -> t(R.string.vehicle_limit_failed)
                })
            }
        }
    }

    /** A car's identification sources: read-only. */
    private fun addSources(body: LinearLayout, sources: VehicleIdentificationSources) {
        body.addView(divider())
        sources.plug?.let { readRow(body, t(R.string.identify_source_plug), sourceText(it)) }
        sources.location?.let { readRow(body, t(R.string.identify_source_location), sourceText(it)) }
    }

    private fun sourceText(source: IdentificationSource): String = when (val text = VehicleIdentification.sourceText(source)) {
        // The entity is chosen in Home Assistant; here it is only there or not.
        is VehicleIdentification.SourceText.Named -> t(R.string.setup_present)
        VehicleIdentification.SourceText.None -> t(R.string.identify_source_none)
        VehicleIdentification.SourceText.Choose -> t(R.string.identify_source_choose)
        VehicleIdentification.SourceText.NotFound -> t(R.string.setup_missing)
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
                VehicleField.MINIMUM -> R.string.vehicle_error_minimum
            }
            VehicleFieldIssue.NOT_A_NUMBER -> R.string.paired_error_number
            VehicleFieldIssue.UNKNOWN -> R.string.paired_error_field
        }
    )

    /** A current as the card writes it: whole amps without decimals, else one, in the screen's number locale. */
    private fun ampsText(amps: Double): String =
        if (amps % 1.0 == 0.0) amps.toLong().toString()
        else String.format(AppLanguageSettings.numberLocale(context), "%.1f", amps)

    // --- Charger --------------------------------------------------------------------------------

    private fun addChargerCard(dash: Dashboard) {
        val charger = PairedOverview.charger(dash)
        // Which charger, always: with several paired, the heading is what says so.
        // Several paired chargers: "Laddare" with a tab per charger, the shown one chosen; one: its name.
        val tabs = chargerTabs
        val card = if (tabs == null) {
            card(container, SettingsHeading.named(t(R.string.section_charger), chargerName ?: dash.chargerName), R.drawable.ic_card_charger)
        } else {
            card(container, t(R.string.section_charger), R.drawable.ic_card_charger).also { card ->
                card.header.addView(textTabs(tabs.names, tabs.selected, card.body) { index ->
                    if (index != tabs.selected) tabs.onSelect(index)
                }.row, textTabsLayoutParams())
            }
        }
        if (charger.showsStartStop) {
            readRow(card.body, t(R.string.charger_start_stop_label),
                t(if (charger.summary?.startStopName != null) R.string.setup_active else R.string.setup_missing))
        }
        readRow(card.body, t(R.string.charger_current_label), when (charger.summaryPath) {
            DashboardSummary.CurrentPath.CHANGE_CONFIGURATION -> t(R.string.charger_current_ocpp)
            DashboardSummary.CurrentPath.NUMBER -> t(R.string.charger_current_number_unnamed)
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
                is PairedOverview.EnergyMeter.Named -> t(R.string.charger_energy_chosen)
                PairedOverview.EnergyMeter.Automatic -> t(R.string.charger_energy_automatic)
                PairedOverview.EnergyMeter.NotSpecified -> t(R.string.value_none)
            })
        }
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
        val record = currentRecord(dash)
        val section = VehicleIdentification.section(dash, record) ?: return
        val writable = identificationWritable && !identificationWriting
        val allIds = section.cars.map { it.vehicleId }
        val stored = record?.identification

        fun write(mode: IdentifyMode, ids: List<String>?, done: (String?) -> Unit) {
            if (stored != null && stored.mode == mode && stored.vehicleIds == ids) { done(null); return }
            identificationWriting = true
            identificationNotice = null
            writeIdentification(mode, ids) { refusal ->
                identificationWriting = false
                if (refusal == null) adoptedIdentification = HaIdentificationSettings(mode, ids)
                done(refusal)
                repaint()
            }
        }

        // The cars at this charger: "All cars", or the ticked ones by name; a dialog ticks them.
        val names = VehicleIdentification.tickedNames(section)
        valueRow(body, t(R.string.identify_vehicles_title),
            names?.joinToString(", ") ?: t(R.string.identify_vehicles_all),
            onTap = if (writable) ({
                chooseMany(t(R.string.identify_vehicles_title), section.cars.map { it.name }, section.cars.map { it.ticked },
                    intro = t(R.string.identify_vehicles_help), atLeastOne = t(R.string.identify_no_vehicle)) { ticks, done ->
                    write(section.mode, VehicleIdentification.vehicleIdsFor(allIds, allIds.filterIndexed { index, _ -> ticks[index] }.toSet()), done)
                }
            }) else null)
        // How the plugged-in car is found: one of three, each saying what it does.
        valueRow(body, t(R.string.identify_mode_title), modeName(section.mode),
            onTap = if (writable) ({
                chooseOne(t(R.string.identify_mode_title), IdentifyMode.entries.map { modeName(it) },
                    IdentifyMode.entries.indexOf(section.mode), helps = IdentifyMode.entries.map { modeHelp(it) },
                    intro = t(R.string.identify_mode_help)) { index, done ->
                    write(IdentifyMode.entries[index], stored?.vehicleIds, done)
                }
            }) else null)
        addCamera(body, dash, record)
        if (!identificationWritable) body.addView(muted(t(R.string.settings_paired_read_only), top = 4))
        identificationNotice?.let { body.addView(muted(it, top = 4)) }
    }

    /** The record as shown: the dashboard's, with what a write here answered until the next dashboard. */
    private fun currentRecord(dash: Dashboard): HaPlanningSettings? = dash.settings?.let { record ->
        val identified = adoptedIdentification?.let { record.copy(identification = it) } ?: record
        adoptedCamera?.let { identified.copy(camera = it) } ?: identified
    }

    // --- The camera -------------------------------------------------------------------------------

    /**
     * The charger's camera under its identification, where Home Assistant offers one: the camera as
     * chosen in Home Assistant (read-only here; its AI task is the Home Assistant card's alone), and with
     * a camera chosen how its picture is cropped, which opens the frame editor and is saved through
     * `save_camera_frame`. The privacy line goes under the last row.
     */
    private fun addCamera(body: LinearLayout, dash: Dashboard, record: HaPlanningSettings?) {
        val section = CameraSetup.section(dash, record) ?: return
        val chosen = section.chosen
        readRow(body, t(R.string.camera_label), section.cameraName ?: t(R.string.camera_none),
            help = if (chosen == null) t(R.string.camera_help) else null)
        if (chosen == null) return
        valueRow(body, t(R.string.camera_frame_label),
            t(if (section.frameDrawn) R.string.camera_frame_cropped else R.string.camera_frame_whole), help = t(R.string.camera_help)) {
            openFrameEditor(chosen.frame, load = { done -> cameraCall(CameraCommands.Snapshot, PictureDecoding.SNAPSHOT_EDGE, done) }) { frame, done ->
                cameraCall(CameraCommands.SaveFrame(frame), null) { outcome, _ ->
                    if (outcome is CameraCommands.Outcome.Framed) {
                        adoptedCamera = HaCameraChoice(outcome.camera)
                        done(null)
                        repaint()
                    } else {
                        done(cameraFailureText((outcome as? CameraCommands.Outcome.Failed)?.code))
                    }
                }
            }
        }
    }

    /**
     * A car's reference pictures, with a camera chosen and the car one of this charger's: the row (which
     * pictures it has) opening the car's reference editor, and their thumbnails under it.
     */
    private fun addReference(body: LinearLayout, dash: Dashboard, vehicle: PairedOverview.VehicleCard) {
        val listed = CameraSetup.references(dash, currentRecord(dash), vehicle.id) ?: return
        val pictures = adoptedReferences[vehicle.id] ?: listed
        if (vehicle.sources == null) body.addView(divider())
        val carName = vehicle.name ?: t(R.string.vehicle_title)
        valueRow(body, t(R.string.reference_label),
            if (pictures.isEmpty()) t(R.string.reference_none) else pictures.joinToString(", ") { pictureKindText(it.kind) }) {
            openReferenceEditor(
                carName, pictures,
                thumbnail = { picture, done -> thumbnail(vehicle.id, picture, done) },
                takenAt = { CameraSetup.takenAt(it, ZoneId.systemDefault(), AppLanguageSettings.locale(context)) }
            ) { kind, delete, done ->
                val request = if (delete) CameraCommands.DeleteReference(vehicle.id, kind) else CameraCommands.TakeReference(vehicle.id, kind)
                cameraCall(request, null) { outcome, _ ->
                    if (outcome is CameraCommands.Outcome.References) {
                        // The editor stays open and shows the answer; the page behind it shows it too.
                        adoptedReferences[outcome.vehicleId] = outcome.pictures
                        done(ReferenceAnswer.Pictures(outcome.pictures))
                        repaint()
                    } else {
                        done(ReferenceAnswer.Refused(cameraFailureText((outcome as? CameraCommands.Outcome.Failed)?.code)))
                    }
                }
            }
        }
        if (pictures.isEmpty()) return
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(4), 0, 0)
        }
        for (picture in pictures) {
            val image = thumbnailView(picture.kind, 64)
            row.addView(image)
            thumbnail(vehicle.id, picture) { bitmap ->
                if (bitmap != null) {
                    image.setImageBitmap(bitmap)
                    image.visibility = View.VISIBLE
                }
            }
        }
        body.addView(row)
    }

    /** A reference picture's thumbnail: fetched once, kept among the last few, and handed to everyone waiting for it. */
    private fun thumbnail(vehicleId: String, picture: ReferencePicture, done: (Bitmap?) -> Unit) {
        val key = "$vehicleId/${picture.kind.wire}/${picture.takenAt}"
        thumbnails[key]?.let { done(it); return }
        val waiting = thumbnailWaiters.getOrPut(key) { mutableListOf() }
        waiting += done
        if (waiting.size > 1) return
        cameraCall(CameraCommands.Reference(vehicleId, picture.kind), PictureDecoding.THUMBNAIL_EDGE) { _, bitmap ->
            bitmap?.let { thumbnails[key] = it }
            thumbnailWaiters.remove(key).orEmpty().forEach { it(bitmap) }
        }
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
        // "Site · its name"; "Site" alone when it has none.
        val card = card(container, SettingsHeading.named(t(R.string.site_default_name), summary.name), R.drawable.ic_site)
        if (PairedOverview.saysChargers(summary.chargers)) {
            card.body.addView(muted(tq(R.plurals.site_applies, summary.chargers, summary.chargers), top = 8, bottom = 4))
        }
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
            readRow(card.body, t(R.string.site_battery_label), t(if (setup.batteryName != null) R.string.setup_present else R.string.value_none))
        }
        readRow(card.body, t(R.string.site_active_title), t(if (summary.activeControlOn) R.string.site_on else R.string.site_off))
        val reason = when (summary.reason) {
            SiteFacts.Reason.NONE -> null
            SiteFacts.Reason.DUPLICATE_MEMBERSHIP -> t(R.string.site_active_reason_duplicate)
            SiteFacts.Reason.NO_COMMANDABLE_CHARGER -> t(R.string.site_active_reason_no_charger)
            SiteFacts.Reason.MEASUREMENT -> t(R.string.site_active_reason_measurement)
            SiteFacts.Reason.UNKNOWN -> t(R.string.site_active_reason_unknown)
        }
        // State and reason only: the row speaks for itself about where it is changed.
        card.body.addView(muted(listOfNotNull(reason, t(R.string.site_active_note)).joinToString(" "), top = 6))
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
        valueRow(body, t(R.string.priority_label), priorityName(priority.value),
            onTap = if (priority.writable) ({
                chooseOne(t(R.string.priority_label), priority.choices.map { priorityName(it) },
                    priority.choices.indexOf(priority.value), intro = t(R.string.priority_help)) { index, done ->
                    val current = adoptedPriority ?: dashboard?.chargerPriority ?: return@chooseOne done(null)
                    writePriority(current.value, current.choices[index]) { outcome ->
                        val feedback = PairedVehicles.feedback(outcome)
                        feedback.adopted?.let { adoptedPriority = it }
                        val message = feedback.notice?.let { priorityNoticeText(it) }
                        if (feedback.notice == null || feedback.reload) {
                            priorityNotice = message
                            done(null)
                            repaint()
                        } else {
                            done(message)
                        }
                    }
                }
            }) else null)
        if (!priority.writable) body.addView(muted(t(R.string.site_read_only), top = 4))
        priorityNotice?.let { body.addView(muted(it, top = 4)) }
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
        if (PairedOverview.saysChargers(chargers)) card.body.addView(muted(tq(R.plurals.site_applies, chargers, chargers), top = 8, bottom = 4))
        valueRow(card.body, t(R.string.site_solar_priority), priorityText(summary.priority), onTap = if (summary.editable) ({
            chooseOne(t(R.string.site_solar_priority), SiteFacts.PRIORITIES.map { priorityText(it) },
                SiteFacts.PRIORITIES.indexOf(site.solarPriority)) { index, done ->
                writeSolar(SiteUpdate.priorityRequest(site.solarPriority, SiteFacts.PRIORITIES[index]), done)
            }
        }) else null)
        val forecastOffered = summary.editable && site.solarForecastChoices.isNotEmpty()
        valueRow(card.body, t(R.string.site_forecast_title),
            if (summary.forecastTitles.isEmpty()) t(R.string.value_none) else summary.forecastTitles.joinToString(", "),
            onTap = if (forecastOffered) ({
                val selected = PairedOverview.solarSelection(site)
                chooseMany(t(R.string.site_forecast_title), site.solarForecastChoices.map { it.title },
                    site.solarForecastChoices.map { it.id in selected }) { ticks, done ->
                    val chosen = site.solarForecastChoices.filterIndexed { index, _ -> ticks[index] }.map { it.id } +
                        site.solarForecastSelected.filter { id -> site.solarForecastChoices.none { it.id == id } }
                    writeSolar(SiteUpdate.forecastRequest(site.solarForecastSelected, chosen), done)
                }
            }) else null)
        if (!summary.editable) card.body.addView(muted(t(R.string.site_read_only), top = 6))
        solarNotice?.let { card.body.addView(muted(it, top = 4)) }
    }

    /** One solar value written alone (`update_site_settings` with that field, against the value shown). */
    private fun writeSolar(request: SiteUpdate.Request, done: (String?) -> Unit) {
        writeSite(request) { outcome ->
            val feedback = PairedVehicles.feedback(outcome)
            feedback.adopted?.let { adoptedSite = it }
            val message = feedback.notice?.let { siteNoticeText(it) }
            if (feedback.notice == null || feedback.reload) {
                solarNotice = message
                done(null)
                repaint()
            } else {
                done(message)
            }
        }
    }

    private companion object {
        const val ERROR_COLOUR = 0xFFD65C5C.toInt()

        /** The divider between a card's sections: the muted colour, faint. */
        const val DIVIDER_ALPHA = 0x33000000

        /** Longer read-only values go under their label rather than beside it. */
        const val STACK_AFTER_CHARS = 18

        /** How many reference thumbnails are kept (each at most 240 pixels a side). */
        const val MAX_THUMBNAILS = 12
    }
}
