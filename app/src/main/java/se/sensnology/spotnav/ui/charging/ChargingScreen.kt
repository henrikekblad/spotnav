package se.sensnology.spotnav.ui.charging

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.chargers.ChargerAction
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.chart.DashboardChart
import se.sensnology.spotnav.chart.PairedChart
import se.sensnology.spotnav.ha.authority.AuthorityController
import se.sensnology.spotnav.ha.authority.AuthorityResolution
import se.sensnology.spotnav.ha.authority.DashboardAdmission
import se.sensnology.spotnav.ha.authority.HaPresentation
import se.sensnology.spotnav.ha.authority.SettingsAuthorityCoordinator
import se.sensnology.spotnav.ha.authority.VisibleAuthority
import se.sensnology.spotnav.ha.dashboard.AutoControl
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.session.HaSession
import se.sensnology.spotnav.ha.settings.ConfirmedSettingsStore
import se.sensnology.spotnav.ha.settings.DepartureDays
import se.sensnology.spotnav.ha.settings.HaSettingsDriver
import se.sensnology.spotnav.ha.settings.HaSettingsEdit
import se.sensnology.spotnav.ha.settings.HaSettingsStrategy
import se.sensnology.spotnav.ha.settings.HaTargetIntent
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.planning.PlanDriver
import se.sensnology.spotnav.prices.AreaCatalogue
import se.sensnology.spotnav.prices.PriceDays
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.PriceRepository
import se.sensnology.spotnav.testmode.TestMode
import se.sensnology.spotnav.ui.Screen
import se.sensnology.spotnav.ui.ScreenPart
import se.sensnology.spotnav.ui.ScreenShell
import se.sensnology.spotnav.vehicles.VehicleEnergy
import se.sensnology.spotnav.ui.common.addMainHeader
import se.sensnology.spotnav.ui.haSession
import se.sensnology.spotnav.ui.settings.AreaMoney
import se.sensnology.spotnav.ui.settings.AreaMoneyFacts
import se.sensnology.spotnav.vehicles.PairedTarget
import se.sensnology.spotnav.widget.PriceWidgetProvider
import se.sensnology.spotnav.widget.WidgetChargerResolver
import se.sensnology.spotnav.widget.WidgetChartBoundary
import se.sensnology.spotnav.widget.WidgetPlanPublication
import se.sensnology.spotnav.widget.WidgetPlanSnapshotStore
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The charging screen: */
internal class ChargingScreen(
    shell: ScreenShell,
    private val rebuild: () -> Unit
) : ScreenPart(shell) {
    // Generation
    internal var generation = 0
    internal var settings: WidgetSettings = WidgetSettings.load(context, widgetId)
    internal var resolvedChargerProfile: ChargerProfile? = null
    internal lateinit var offlineNotice: TextView

    // Cards
    internal lateinit var chargerCard: ChargerCard
    internal lateinit var connection: ConnectionControls
    internal lateinit var vehicleCard: VehicleCard
    internal lateinit var planCard: PlanCard
    internal lateinit var targetSoc: TargetSocControls
    internal lateinit var consumption: ConsumptionControls
    internal lateinit var energy: EnergyControls
    internal lateinit var status: TextView
    internal lateinit var profileStore: ChargerProfileStore
    internal var profileId: String? = null

    // ---- Authority: The screen keeps only what a screen must: views, listeners and localized
    // text.
    internal var authorityNote: String? = null
    internal var authorityProfile: ChargerProfile? = null
    internal lateinit var authority: AuthorityController
    internal lateinit var widgetPlans: WidgetPlanPublication

    /** The one conversation with this charger's Home Assistant, or `null` for a widget nobody paired. */
    internal var session: HaSession? = null

    /** The one action the charger controls have computed, and the row it is painted into. */
    internal var currentPrimaryAction: ChargerAction? = null

    /**
     * The control decision the last accepted status stated, or `null` when none has been read (see
     * [AutoControl]).
     */
    internal var currentControl: AutoControl? = null

    /** the picker's selectability comes from this and nothing else. */
    internal var currentStrategyOptions: Set<HaSettingsStrategy> = setOf(HaSettingsStrategy.CHEAPEST)
    internal var currentNeedingTotalGridPower: Set<HaSettingsStrategy> = emptySet()

    /**
     * The strategy presentation this pass rendered. Initialised in [show] before anything can be
     * rendered, so it is **never** an absent value that a paired-only control could read as
     * permission:
     */
    internal lateinit var strategyUi: ChargingStrategyUi

    /** The one chooser state this screen owns. Screen-local on purpose (see StrategyChooserState). */
    internal val strategyChooser = StrategyChooserState()

    /**
     * Whether the strategy row exists and may be repainted. The dashboard controller reports its
     * first facts while it is being built, before the row is wired; those are recorded and painted
     * by the first full pass instead.
     */
    internal var strategyWired = false

    internal var planningControlsEnabled = false

    // True only while a record is being pushed into the controls: a programmatic set fires the same
    // listeners a tap does, and those must not write.
    internal var authorityApplying = false

    // Set once the controls exist: called once the whole screen is wired (see show).
    internal var startDashboardFetch: () -> Unit = {}
    /** Whether the paired dashboard is to be read every few seconds (see ChargerDashboardController.pendingRefresh). */
    internal var pendingRefresh: () -> Boolean = { false }

    /** Whether the charge bar shows, so the dashboard is read every half minute (see LiveRefresh). */
    internal var chargeBarShown: () -> Boolean = { false }
    private var refreshControls: () -> Unit = {}

    // ---- What Home Assistant's dashboard decides for a paired charger  The last dashboard answer,
    // and the vehicle picked in the card but not yet written.
    internal var pairedDashboard: Dashboard? = null
    internal var pendingVehiclePick: String? = null

    // The paired chart is built from the dashboard once per answer and interval, not once per pass:
    internal var pairedChartMemo: PairedChartMemo? = null

    fun show() {
        beginScreen()
        generation = viewGeneration
        // The page's own header, first, and *inside* the scroll body: it is what a tap on a widget
        // lands on, and it has to scroll away with the rest.
        addMainHeader(content) { showScreen(Screen.SETTINGS) }
        // Debug builds only, and only while test mode is on: the screen must never look like the
        // real thing while it is showing mocked chargers.
        TestMode.addBanner(context, content)
        // The paired-offline presentation, first under the header and above every Home Assistant-
        // owned control:
        offlineNotice = pairedOfflineNotice()
        content.addView(offlineNotice, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
        // This widget's own bound charger — never HomeAssistantSettings.load(), which would resolve
        // whichever profile is globally active instead.
        resolvedChargerProfile = WidgetChargerResolver.resolve(settings, ChargerProfileStore.forContext(applicationContext))
        // The two object cards: side by side on a screen wide enough for it (CardLayout owns that
        // decision), stacked otherwise -- which is what a phone this size gets.
        val shareRow = CardLayout.sideBySide(resources.configuration.screenWidthDp)
        val objectCards = if (shareRow) LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL } else content
        if (shareRow) content.addView(objectCards)
        chargerCard = ChargerCardController(this, widgetId).add(
            objectCards, settings, resolvedChargerProfile, shareRow, onOpenHistory = { shell.navigate(Screen.HISTORY) }
        ) { rebuild() }
        connection = chargerCard.connection
        // The vehicle card is the second of the two, and the one that exists in every state.
        vehicleCard = VehicleCardController(this).add(objectCards, settings, resolvedChargerProfile, shareRow)
        profileStore = ChargerProfileStore.forContext(applicationContext)
        profileId = resolvedChargerProfile?.localId
        strategyUi = strategyFace(null)
        authorityProfile = resolvedChargerProfile?.takeIf { it.configured }
        session = authorityProfile?.let { shell.haSession(it) }
        val authorityCache = ConfirmedSettingsStore.forContext(applicationContext)
        val authorityCoordinator = authorityProfile?.let { subject ->
            SettingsAuthorityCoordinator(
                profiles = { profileStore.listProfiles() },
                catalogue = { PriceMarkets.all },
                // The screen's own poll already fetched this:
                fetchDashboard = { captured ->
                    val controller = authorityController
                    if (captured.localId == subject.localId && controller != null) {
                        controller.capturedResult ?: Result.failure(IllegalStateException("no dashboard yet"))
                    } else {
                        Result.failure(IllegalStateException("another charger"))
                    }
                },
                cache = authorityCache
            )
        }
        authority = AuthorityController(
            profileId = authorityProfile?.localId,
            screenGeneration = generation,
            coordinator = authorityCoordinator,
            cache = authorityCache,
            catalogue = { PriceMarkets.all },
            presentation = { HaPresentation(settings.intervalMinutes) }
        ).also { controller ->
            controller.captureLocal(settings.area, LocalPlanningInputs.ofOrNull(settings))
        }
        authorityController = authority
        widgetPlans = WidgetPlanPublication(WidgetPlanSnapshotStore.forContext(applicationContext)) {
            WidgetChartBoundary.requestRedraw(activity)
        }
        planCard = PlanCardController(this, shell).add(content, settings, resolvedChargerProfile) { value ->
            profileId?.let { localId ->
                profileStore.updateProfile(localId) { it.copy(targetSocPercent = value) }
            }
        }
        targetSoc = planCard.targetSoc
        // The consumption slider is on the vehicle card, so this reads it through that handle.
        consumption = vehicleCard.consumption
        energy = planCard.energy
        status = planCard.status

        wireHomeAssistant()
        wireControls()

        // The strategy row's first face, and for a widget nobody paired the *only* one:
        strategyWired = true
        applyStrategy(strategyFace(authority.authority))
        // The strategy row's one handler, attached here where the authority, the note and `render`
        // all exist.
        planCard.strategy.attachOpen { openStrategyChooser() }

        render()
        // The first price load runs against whatever the authority already says; until the status
        // answer arrives that is the widget's own area, so an unpaired or not-yet-answered screen
        // behaves exactly as it always did.
        loadPrices()
        shell.reloadPrices = { loadPrices() }
        // Back in the foreground: what is held is drawn again for today's date at once, then a fresh
        // dashboard is asked for now rather than on whatever next triggers one (the prices follow
        // through the price refresh's own resume).
        shell.onForeground = {
            render()
            startDashboardFetch()
        }
        // Midnight with the app open: today's date drawn from what is held at once, then fresh answers.
        shell.dayZone = {
            pairedDashboard?.let { DashboardChart.zone(it) }
                ?: shell.authorityController?.priceArea()?.let { PriceMarkets.find(it)?.zoneId }
                ?: java.time.ZoneId.systemDefault()
        }
        shell.onDayBoundary = {
            render()
            startDashboardFetch()
            loadPrices()
        }
        // In view, the paired dashboard is read again every minute, and just after a start-up Home
        // Assistant said would end sooner: a status line does not wait for a tap to move on.
        shell.onLiveRefresh = { startDashboardFetch() }
        shell.liveRefreshEnd = { pairedDashboard?.startingUpUntil }
        // A Start or Stop awaiting the charger's report is read every few seconds instead, so the
        // buttons return as soon as Home Assistant offers them again.
        shell.liveRefreshFast = { pendingRefresh() }
        // A running charge is read every half minute, as the Home Assistant card reads it.
        shell.liveRefreshCharging = { chargeBarShown() }
        startDashboardFetch()
    }

    /**
     * The one place this screen builds a strategy face: the binding it resolved synchronously (a
     * configured charger, or none) and whatever authority answer is in hand.
     */
    internal fun strategyFace(state: VisibleAuthority?): ChargingStrategyUi =
        ChargingStrategyScreenFace.first(
            resolvedChargerProfile, state, currentPrimaryAction, currentControl, currentStrategyOptions,
            currentNeedingTotalGridPower
        )

    internal fun pairedNow(): Dashboard? = pairedDashboard.takeIf { authority.authority?.haOwnsPlanning == true }

    /** Never writes anything. */
    internal fun syncPaired() {
        val held = pairedNow()
        val recordVehicle = authority.authority?.remoteSettings?.target?.vehicleId
        if (held != null) {
            val range = PairedTarget.ampsRange(held)
            connection.setAmps(connection.amps(), range.first, range.last)
            connection.refreshValueLabel()
        }
        val picked = pendingVehiclePick ?: recordVehicle
        // The vehicle card reads Home Assistant's dashboard as soon as one has arrived for a paired
        // charger, even in the moment before the authority has resolved:
        vehicleCard.applyPaired(held ?: pairedDashboard.takeIf { authorityProfile != null && authority.authority == null }, picked)
        planCard.applyPaired(held, picked)
        chargerCard.showPairedPhases(held?.chargingPhases)
        chargerCard.showHistory((held ?: pairedDashboard.takeIf { authorityProfile != null })?.sessionsSummary)
        syncDepartureDays()
    }

    internal fun currentSettings() = settings.copy(
        chargingPhases = connection.selectedPhases(),
        chargingAmps = connection.amps(),
        consumptionKwhPerMil = (consumption.consumption.progress + 10) / 10.0,
        chargingKwh = energyEnergy(),
        driver = planCard.driver(),
        maxChargingPeriods = planCard.periods.progress + 1,
        // The widget display option lives on the settings screen; this reads the stored value,
        // which is what it always meant.
        showChargingPlan = settings.showChargingPlan,
        useDepartureTime = planCard.useDeparture.isChecked,
        departureHour = planCard.departureHour(),
        departureMinute = planCard.departureMinute()
    )

    internal fun saveCharging(value: WidgetSettings) {
        WidgetSettings.save(context, widgetId, value)
        if (widgetId > 0) {
            PriceWidgetProvider.update(context, AppWidgetManager.getInstance(context), widgetId)
        }
    }

    /**
     * The charger card's Home Assistant side, for a widget with a configured charger only: a widget
     * with no binding, or one bound to a since-deleted profile, shows no Home Assistant controls at
     * all rather than falling back to whatever else happens to be active.
     */
    private fun wireHomeAssistant() {
        val held = session ?: return
        val controller = ChargerDashboardController(
            scope = this,
            session = held,
            chargerCard = chargerCard,
            vehicleCard = vehicleCard,
            currentSettings = ::currentSettings,
            listener = object : ChargerDashboardController.Listener {
                // The dashboard request's own identity, reserved where the request goes out and
                // carried with its answer:
                override fun admitDashboard() = authority.admitDashboard()

                override fun onResult(admission: DashboardAdmission?, result: Result<Dashboard>) =
                    onDashboardResult(admission, result)

                // The strategy's own answer about the automatic control, the one action its state
                // calls for handed back, and the control decision each dashboard states handed in:
                override fun automaticControl() = strategyUi.automaticControl

                override fun onPrimaryAction(action: ChargerAction?) {
                    currentPrimaryAction = action
                    applyPrimaryAction(action)
                }

                override fun onAcceptedControl(control: AutoControl?) {
                    currentControl = control
                    applyAcceptedControl(control)
                }

                override fun onStrategyOptions(
                    options: Set<HaSettingsStrategy>,
                    needingTotalGridPower: Set<HaSettingsStrategy>
                ) {
                    currentStrategyOptions = options
                    currentNeedingTotalGridPower = needingTotalGridPower
                    applyAcceptedStrategyOptions()
                }

                override fun onDashboard(dashboard: Dashboard?) {
                    pairedDashboard = dashboard
                    if (dashboard?.startingUpUntil != null) shell.liveRefreshReconsider?.invoke()
                    // A fresh answer is the record's own again: a pick that never became a write is
                    // dropped.
                    pendingVehiclePick = null
                    syncPaired()
                }

                override fun onPendingRefreshChanged() {
                    shell.liveRefreshReconsider?.invoke()
                }

                override fun onVehiclePicked(vehicleId: String) = pickPairedVehicle(vehicleId)
            }
        )
        startDashboardFetch = { controller.fetchDashboard() }
        pendingRefresh = { controller.pendingRefresh() }
        chargeBarShown = { controller.chargeBarShown() }
        refreshControls = { controller.refreshControls() }
    }

    /**
     * The screen's one dashboard **result**, feeding both the facts beside it and the authority --
     * one request, never two: the coordinator's own fetch dependency returns this captured result
     * rather than asking again.
     */
    private fun onDashboardResult(admission: DashboardAdmission?, result: Result<Dashboard>) {
        if (authorityProfile == null) return
        // The widget's own state is captured here, on this thread, before the resolution runs off
        // it.
        authority.captureLocal(settings.area, LocalPlanningInputs.ofOrNull(settings))
        ioExecutor.execute {
            // The admission travels with the result:
            val resolution = authority.onDashboardResult(admission, result)
            runOnUiThread {
                if (isDestroyed || generation != viewGeneration) return@runOnUiThread
                if (resolution is AuthorityResolution.State) applyState(resolution.authority)
            }
        }
    }

    /** One price load, for the area the authority names, guarded by its own key. */
    internal fun loadPrices() {
        // The subject, not just the area:
        authority.captureLocal(settings.area, LocalPlanningInputs.ofOrNull(settings))
        val subject = authority.beginPriceLoad()
        if (subject == null) {
            // Nothing to price, so nothing is loaded: the in-flight mark this attempt was started
            // with is given back here rather than left standing (see PriceScreenRefresh).
            shell.priceRefresh.loadFinished()
            return
        }
        val area = subject.areaId
        shell.priceRefresh.loadStarted()
        ioExecutor.execute {
            // Held prices first when what the screen shows is not today's: the load below may wait on the
            // network for the index, and a stale day must not stay on screen meanwhile.
            val zone = PriceMarkets.find(area)?.zoneId ?: java.time.ZoneId.systemDefault()
            val shown = authority.currentPrices
            if (shown == null || !PriceDays.showsDate(shown, java.time.LocalDate.now(zone))) {
                val held = PriceRepository.heldOnly(applicationContext, area)
                if (held.today.isNotEmpty() || held.tomorrow.isNotEmpty()) {
                    runOnUiThread {
                        if (isDestroyed || generation != viewGeneration) return@runOnUiThread
                        if (authority.currentPrices === shown && authority.onPriceLoaded(subject, held) != null) render()
                    }
                }
            }
            // Off the main thread, as the screen's own work always is:
            AreaCatalogue.refreshIfDue(applicationContext)
            val loaded = PriceRepository.load(applicationContext, area)
            runOnUiThread {
                // Whatever becomes of this answer below, the screen owes no load any longer:
                shell.priceRefresh.loadFinished()
                if (isDestroyed || generation != viewGeneration) return@runOnUiThread
                // A late answer for another subject is dropped, never rendered.
                if (authority.onPriceLoaded(subject, loaded) == null) return@runOnUiThread
                render()
                // The footer's facts come from the same tested formatter the money tests cover, so
                // what is asserted about "area id plus ISO currency, never a borrowed currency" is
                // what this screen actually renders.
                status.text = when (val facts = AreaMoney.of(area, PriceMarkets.find(area))) {
                    is AreaMoneyFacts.Available -> t(R.string.prices_for_currency, facts.areaId, facts.currency)
                    is AreaMoneyFacts.Unavailable -> t(R.string.area_unavailable, facts.areaId)
                }
            }
        }
    }

    /** Re-sync the charger controls with the dashboard now held; the render pass calls this. */
    internal fun refreshChargerControls() = refreshControls()

    /** One departure edit: the whole state (enabled, time, day), as the contract replaces it. */
    internal fun commitDeparture(enabled: Boolean, date: LocalDate?) {
        commitEdit(
            HaSettingsEdit.Departure(
                enabled,
                "%02d:%02d".format(planCard.departureHour(), planCard.departureMinute()),
                date,
                planCard.departureWeekdays()
            )
        )
    }

    /**
     * The departure day's own facts for the paired record now held: the days its area's zone
     * offers, shown only while Home Assistant owns the plan. The local planner keeps the daily
     * departure.
     */
    internal fun syncDepartureDays() {
        val owned = authority.authority?.takeIf { it.haOwnsPlanning }
        val zone = owned?.remoteSettings?.areaId?.let { PriceMarkets.find(it)?.zoneId }
            ?: pairedNow()?.market?.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        planCard.setDepartureDays(DepartureDays.of(zone, Instant.now()), owned != null)
    }

    /** The energy the energy control is showing, in kWh. */
    internal fun energyEnergy(): Double = VehicleEnergy.energyKwhOfProgress(energy.energy.progress)

    /** The target intent a driver edit carries. */
    internal fun targetIntentFor(driver: HaSettingsDriver): HaTargetIntent {
        val recorded = authority.authority?.remoteSettings?.target ?: HaTargetIntent.EMPTY
        // Saving in target mode names the vehicle the target is for: the one picked, else the
        // record's, else the one Home Assistant resolved (the card's own rule).
        val known = if (driver == HaSettingsDriver.TARGET_SOC && pairedNow()?.soc != null) {
            recorded.copy(vehicleId = pendingVehiclePick ?: recorded.vehicleId ?: pairedNow()?.soc?.vehicleId)
        } else recorded
        return known.forDriver(driver, targetSoc.progress().toDouble())
    }

    /**
     * The car the charger plans for, chosen by a person: Home Assistant's own field
     * (`target.vehicle_id`), written through the ordinary settings write like any other edit (never
     * stored on this profile). The vehicle card's picker, and Byt bil with no car plugged in.
     */
    private fun pickPairedVehicle(picked: String) {
        pendingVehiclePick = picked
        val recorded = authority.authority?.remoteSettings
        val driver = recorded?.driver
            ?: if (planCard.effectiveDriver() == PlanDriver.TARGET_SOC) HaSettingsDriver.TARGET_SOC else HaSettingsDriver.MANUAL_KWH
        val target = (recorded?.target ?: HaTargetIntent.EMPTY).copy(vehicleId = picked)
            .forDriver(driver, targetSoc.progress().toDouble())
        syncPaired()
        commitEdit(HaSettingsEdit.Driver(driver, target))
    }

    /** Every control's listener, attached where the authority, the note and `render` all exist. */
    private fun wireControls() {
        // The paired vehicle picker: a choice is Home Assistant's own field, written through the
        // ordinary settings write like any other edit (never stored on this profile).
        vehicleCard.attachPairedPick { picked -> pickPairedVehicle(picked) }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // A person moving the current states it:
                if (fromUser && seekBar === connection.ampsSeek) connection.setAmpsNotSet(false)
                // Only a person's move reaches or leaves "Fill", never a value set from the record.
                if (fromUser && seekBar === energy.energy) energy.userMoved(progress)
                connection.refreshValueLabel()
                consumption.refreshValueLabel()
                energy.refreshValueLabel()
                planCard.refreshPeriodsLabel()
                render()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // The slider that was let go names the edit: one field, one replacement, one
                // request at the commit boundary -- never a write per tick.
                when {
                    seekBar === connection.ampsSeek -> {
                        // Letting the slider go is the person's answer, even at the position it
                        // stood.
                        connection.setAmpsNotSet(false)
                        connection.refreshValueLabel()
                        commitEdit(HaSettingsEdit.Amps(connection.amps()))
                    }
                    seekBar === energy.energy -> commitEdit(HaSettingsEdit.Energy(energyEnergy(), energy.filling()))
                    seekBar === planCard.periods ->
                        commitEdit(HaSettingsEdit.MaxPeriods(planCard.periods.progress + 1))
                    else -> saveCharging(currentSettings())
                }
            }
        }
        connection.ampsSeek.setOnSeekBarChangeListener(listener); consumption.consumption.setOnSeekBarChangeListener(listener)
        energy.energy.setOnSeekBarChangeListener(listener); planCard.periods.setOnSeekBarChangeListener(listener)
        targetSoc.slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // The band, enforced here because this is the listener that survives:
                val allowed = targetSoc.enforceBand(progress)
                if (allowed != progress && seekBar != null) {
                    seekBar.progress = allowed
                    return
                }
                targetSoc.refreshValueLabel()
                // A new target means new derived energy:
                planCard.refreshDriver()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Only a real drag decides the target:
                commitTargetPercent()
            }
        })
        connection.phases.setOnCheckedChangeListener { _, _ ->
            if (authorityApplying) return@setOnCheckedChangeListener
            chargerCard.refreshPhasesRow()
            // Only the local planner has a phase choice. A paired charger's phases come from its
            // wiring and the car's onboard charger, which Home Assistant states: nothing is written.
            if (!strategyUi.phaseRadioEnabled) return@setOnCheckedChangeListener
            connection.refreshValueLabel()
            saveCharging(currentSettings())
            render()
        }
        planCard.departurePicker.setOnClickListener {
            TimePickerDialog(context, { _, hour, minute ->
                planCard.setDeparture(hour, minute)
                commitDeparture(planCard.useDeparture.isChecked, planCard.departureDate())
            }, planCard.departureHour(), planCard.departureMinute(), true).show()
        }
        planCard.dailyRadio.setOnClickListener {
            if (authorityApplying) return@setOnClickListener
            if (planCard.departureDate() == null) {
                planCard.refreshDepartureLabel()
                return@setOnClickListener
            }
            planCard.setDepartureDate(null)
            commitDeparture(planCard.useDeparture.isChecked, null)
        }
        planCard.onDateRadio.setOnClickListener {
            if (authorityApplying) return@setOnClickListener
            val days = planCard.departureDays()
            if (days == null || planCard.departureDate() != null) {
                planCard.refreshDepartureLabel()
                return@setOnClickListener
            }
            // Choosing the date starts at the next occurrence of the time:
            val date = planCard.departureDate()
                ?: days.nextOccurrence(LocalTime.of(planCard.departureHour(), planCard.departureMinute()))
            planCard.setDepartureDate(date)
            commitDeparture(planCard.useDeparture.isChecked, date)
        }
        planCard.dateButton.setOnClickListener {
            val days = planCard.departureDays() ?: return@setOnClickListener
            // A stored date that has gone by opens on today; the picker offers today..today+7.
            val shown = planCard.departureDate()?.takeUnless { days.isPast(it) } ?: days.today
            val zone = ZoneId.systemDefault()
            DatePickerDialog(context, { _, year, month, day ->
                val chosen = LocalDate.of(year, month + 1, day)
                planCard.setDepartureDate(chosen)
                commitDeparture(planCard.useDeparture.isChecked, chosen)
            }, shown.year, shown.monthValue - 1, shown.dayOfMonth).apply {
                datePicker.minDate = days.today.atStartOfDay(zone).toInstant().toEpochMilli()
                datePicker.maxDate = days.max.atStartOfDay(zone).toInstant().toEpochMilli()
            }.show()
        }
        planCard.weekdayBoxes.forEach { box ->
            box.setOnCheckedChangeListener { _, checked ->
                if (authorityApplying) return@setOnCheckedChangeListener
                if (!checked && planCard.departureWeekdays().isEmpty()) {
                    // At least one day: the last one stays, and the card says why.
                    authorityApplying = true
                    try { box.isChecked = true } finally { authorityApplying = false }
                    planCard.weekdayError.visibility = View.VISIBLE
                    return@setOnCheckedChangeListener
                }
                planCard.weekdayError.visibility = View.GONE
                commitDeparture(planCard.useDeparture.isChecked, planCard.departureDate())
            }
        }
        planCard.useDeparture.setOnCheckedChangeListener { _, checked ->
            if (authorityApplying) {
                planCard.departurePicker.isEnabled = checked
                planCard.refreshDepartureLabel()
                return@setOnCheckedChangeListener
            }
            planCard.departurePicker.isEnabled = checked && (authority.authority?.pairedControlsEnabled ?: false)
            planCard.refreshDepartureLabel()
            commitDeparture(checked, planCard.departureDate())
        }
        planCard.kwhOption.setOnClickListener {
            if (authorityApplying) return@setOnClickListener
            planCard.setDriver(PlanDriver.KWH)
            commitEdit(HaSettingsEdit.Driver(HaSettingsDriver.MANUAL_KWH, targetIntentFor(HaSettingsDriver.MANUAL_KWH)))
        }
        planCard.targetOption.setOnClickListener {
            if (authorityApplying) return@setOnClickListener
            planCard.setDriver(PlanDriver.TARGET_SOC)
            commitEdit(
                HaSettingsEdit.Driver(
                    HaSettingsDriver.TARGET_SOC,
                    targetIntentFor(HaSettingsDriver.TARGET_SOC)
                )
            )
        }
        // Attached here, where both cards and `render` exist.
        vehicleCard.attachFactsListener { vehicle, capacityKwh ->
            planCard.applyVehicleFacts(vehicle, capacityKwh)
            render()
        }
    }
}
