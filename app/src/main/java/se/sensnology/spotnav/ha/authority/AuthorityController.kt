package se.sensnology.spotnav.ha.authority

import se.sensnology.spotnav.chart.ConfirmedChart
import se.sensnology.spotnav.chart.ConfirmedChartRules
import se.sensnology.spotnav.chart.LocalChart
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.settings.ConfirmedSettingsStore
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.HaSettingsEdit
import se.sensnology.spotnav.ha.settings.HaSettingsEditResult
import se.sensnology.spotnav.ha.settings.HaSettingsEditor
import se.sensnology.spotnav.ha.settings.PairedSettingsForm
import se.sensnology.spotnav.ha.settings.SettingsFormValues
import se.sensnology.spotnav.ha.settings.SettingsSave
import se.sensnology.spotnav.ha.settings.SettingsUpdate
import se.sensnology.spotnav.planning.ChargingPlan
import se.sensnology.spotnav.planning.PlanHandover
import se.sensnology.spotnav.planning.PlannedCharge
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.prices.PriceMarket
import se.sensnology.spotnav.prices.PriceResult

/** What one commit attempt must do — the three meanings a boolean cannot carry. */
internal sealed interface CommitRoute {
    /** Nothing Home Assistant owns applies: the widget's own record is saved, as before. */
    data object LocalSave : CommitRoute

    /** One compare-and-set through the settings contract, at the displayed revision. */
    data class Send(
        val expectedRevision: Int,
        val replacement: HaPlanningSettings,
        val operation: Long,
        /** The confirmed record the edit was built on (at [expectedRevision]). */
        val base: HaPlanningSettings
    ) : CommitRoute

    /** Home Assistant owns the values and nothing may be written: restore what is shown. */
    data object ReadOnly : CommitRoute

    /** The value a control built is one the contract refuses; nothing is sent. */
    data class Refused(val code: String) : CommitRoute
}

/** The subject one settings write belongs to: which profile, which operation, which revision. */
internal data class WriteSubject(val profileId: String, val operation: Long, val revision: Int)

/** The identity one dashboard **request** was admitted under. */
internal data class DashboardAdmission(
    val profileId: String,
    val screenGeneration: Int,
    val operation: Long
)

/** What one write answer meant for the screen. */
internal sealed interface WriteOutcome {
    /** An older operation's answer: folded into the cache, never adopted into the UI. */
    data object Stale : WriteOutcome

    data class Applied(val authority: VisibleAuthority, val answer: SettingsUpdate.Outcome) : WriteOutcome

    /**
     * The answer changed **no** record, so the screen keeps what it has and says what happened.
     * [authority] is what to render.
     */
    data class Reported(val answer: SettingsUpdate.Outcome, val authority: VisibleAuthority?) : WriteOutcome
}

/**
 * The exact confirmed value one paired control must show, and whether that control can even hold
 * it.
 */
internal data class ControlReading(val exact: String, val representable: Boolean)

/** The ranges the existing paired controls can represent, and the readings they must show. */
internal object PairedControlRanges {
    const val AMPS_MIN = 6
    const val AMPS_MAX = 16
    const val ENERGY_KWH_MIN = 1
    const val ENERGY_KWH_MAX = 100
    const val ENERGY_KWH_STEP = 0.5
    const val PERIODS_MIN = 1
    const val PERIODS_MAX = 8
    const val TARGET_PERCENT_MIN = 0
    const val TARGET_PERCENT_MAX = 100

    /** A whole-ampere current in the slider's range. */
    fun amps(amps: Int): ControlReading =
        ControlReading(amps.toString(), amps in AMPS_MIN..AMPS_MAX)

    /** The requested energy, which the half-kWh slider holds exactly when it is a multiple of 0.5. */
    fun energy(kwh: Double): ControlReading =
        ControlReading(oneDecimal(kwh), kwh / ENERGY_KWH_STEP == Math.floor(kwh / ENERGY_KWH_STEP) && kwh in ENERGY_KWH_MIN.toDouble()..ENERGY_KWH_MAX.toDouble())

    fun periods(periods: Int): ControlReading =
        ControlReading(periods.toString(), periods in PERIODS_MIN..PERIODS_MAX)

    /**
     * The target percentage, which the whole-percent slider cannot hold exactly when it is
     * fractional.
     */
    fun target(percent: Double): ControlReading =
        ControlReading(oneDecimal(percent), percent == Math.floor(percent) && percent in TARGET_PERCENT_MIN.toDouble()..TARGET_PERCENT_MAX.toDouble())

    private fun oneDecimal(value: Double): String = java.util.Locale.ROOT.let { String.format(it, "%.1f", value) }
}

/**
 * One screen generation's authority: the state it shows, the subject its prices belong to, the
 * operation its writes belong to, and what each commit may do.
 */
internal class AuthorityController(
    /** The profile this screen is about, or `null` when the widget is unpaired. */
    private val profileId: String?,
    private val screenGeneration: Int,
    /** `null` for an unpaired widget: there is no coordinator to ask. */
    private val coordinator: SettingsAuthorityCoordinator?,
    /** Where every answer is folded before it is rendered. */
    private val cache: ConfirmedSettingsStore,
    private val catalogue: () -> List<PriceMarket>
) {
    private val lock = Any()
    private var state: VisibleAuthority? = null
    private var capturedDashboard: Dashboard? = null
    private var prices: PriceResult? = null
    private var pricesSubject: PriceRequestKey? = null
    private var handover: PlannedCharge? = null
    /** The last chart this screen confirmed, in memory only (see [ConfirmedChart]). */
    private var confirmedChart: ConfirmedChart? = null
    private var handoverSubject: PriceRequestKey? = null
    private var handoverRevision: Int? = null
    private var resolves: Long = 0
    private var writes: Long = 0
    /**
     * The dashboard request this screen is currently waiting on, or `null` when none is
     * outstanding.
     */
    private var admittedDashboard: DashboardAdmission? = null

    /** The state the screen is showing, or `null` before the first answer. */
    val authority: VisibleAuthority? get() = synchronized(lock) { state }

    /** The dashboard the current state was resolved from, when the fetch succeeded. */
    val dashboard: Dashboard? get() = synchronized(lock) { capturedDashboard }

    /** The last dashboard result handed in: what the coordinator's fetch dependency returns. */
    var capturedResult: Result<Dashboard>? = null
        private set

    /**
     * Reserve this screen's identity for the dashboard request that is **about to go out**, or
     * `null` when there is no paired charger to ask about.
     */
    fun admitDashboard(): DashboardAdmission? {
        val localId = profileId ?: return null
        return synchronized(lock) {
            val admission = DashboardAdmission(localId, screenGeneration, ++resolves)
            admittedDashboard = admission
            admission
        }
    }

    /**
     * One dashboard **result** into one authority state, published only for the request it was
     * admitted under.
     */
    fun onDashboardResult(admission: DashboardAdmission?, result: Result<Dashboard>): AuthorityResolution {
        val localId = profileId ?: return AuthorityResolution.Stale
        val coordinator = coordinator ?: return AuthorityResolution.Stale
        val accepted = admission ?: return AuthorityResolution.Stale
        if (accepted.profileId != localId || accepted.screenGeneration != screenGeneration) {
            return AuthorityResolution.Stale
        }
        val current = synchronized(lock) {
            // The request this screen is waiting on: the operation it reserved.
            if (admittedDashboard?.operation != accepted.operation) return@synchronized false
            // The captured facts are written here, inside the same critical section that accepted
            // this answer, so the dashboard the coordinator reads and the dashboard the card shows
            // are one value.
            capturedResult = result
            capturedDashboard = result.getOrNull()
            true
        }
        if (!current) return AuthorityResolution.Stale
        val answer = runCatching { coordinator.reconcile(localId, accepted.operation) }.getOrNull()
            ?: return AuthorityResolution.Stale
        return apply(answer)
    }

    /**
     * The state a screen that does **not** poll starts from: the last record the charger confirmed,
     * and nothing else.
     */
    fun seedFromConfirmedRecord(): VisibleAuthority? {
        val localId = profileId ?: return null
        val record = cache.confirmed(localId)
            ?: return show(VisibleAuthority.ReadOnlyOffline(null))
        val adaptation = HaPlanningAdapter.of(record, catalogue())
        return show(VisibleAuthorityResolver.forRecord(record, adaptation, dashboard = null))
    }

    /** Adopt a record the server just returned for a write this screen made. */
    fun onWriteAnswer(subject: WriteSubject, answer: SettingsUpdate.Outcome): WriteOutcome {
        if (profileId == null || subject.profileId != profileId) return WriteOutcome.Stale
        // Folded first, whatever the answer is.
        cache.recordOutcome(subject.profileId, answer)
        val current = synchronized(lock) { writes }
        if (subject.operation != current) return WriteOutcome.Stale
        if (answer == SettingsUpdate.Outcome.Unavailable) {
            // The request never reached the charger, so the current revision cannot be checked at
            // all.
            val next = show(VisibleAuthority.ReadOnlyOffline(cache.confirmed(subject.profileId)))
            return WriteOutcome.Reported(answer, next)
        }
        val record = cache.confirmed(subject.profileId) ?: answer.record
        if (record == null) {
            // Nothing changed anywhere, and every record-less answer (a refusal, an unreadable
            // contract) leaves the screen exactly as it was.
            val next = synchronized(lock) { state }
            return WriteOutcome.Reported(answer, next)
        }
        val adaptation = HaPlanningAdapter.of(record, catalogue())
        val next = VisibleAuthorityResolver.forRecord(record, adaptation, dashboard)
        show(next)
        return WriteOutcome.Applied(next, answer)
    }

    /** What a control's commit may do, and the operation a send belongs to. */
    fun beginWrite(edit: HaSettingsEdit): CommitRoute {
        val current = synchronized(lock) { state }
        return when {
            profileId == null -> CommitRoute.LocalSave
            current == null -> CommitRoute.ReadOnly
            current.haOwnsPlanning -> writableRoute(current, edit)
            else -> CommitRoute.LocalSave
        }
    }

    private fun writableRoute(current: VisibleAuthority, edit: HaSettingsEdit): CommitRoute {
        if (!current.writable) return CommitRoute.ReadOnly
        val record = current.remoteSettings ?: return CommitRoute.ReadOnly
        return when (val built = HaSettingsEditor.replacement(record, edit)) {
            is HaSettingsEditResult.Refused -> CommitRoute.Refused(built.code)
            is HaSettingsEditResult.Ready ->
                CommitRoute.Send(record.revision, built.settings, reserveWrite(), record)
        }
    }

    /** Reserve the next write operation, and make any outstanding dashboard request inert. */
    private fun reserveWrite(): Long = synchronized(lock) {
        admittedDashboard = null
        ++writes
    }

    /**
     * Admit one press of Save on the Settings form: **one** complete replacement document and one
     * reserved operation, or the reason nothing may be written.
     */
    fun admitFormSave(values: SettingsFormValues): SettingsSave {
        val localId = profileId
        val current = synchronized(lock) { state }
        return when {
            // No paired profile at all: the widget's own record is the owner, exactly as it was
            // before pairing existed.
            localId == null || (current != null && !current.haOwnsPlanning) -> SettingsSave.LocalOnly
            current == null || !current.writable -> SettingsSave.ReadOnly
            else -> {
                val record = current.remoteSettings ?: return SettingsSave.ReadOnly
                when (val built = PairedSettingsForm.replacement(record, values, catalogue())) {
                    is HaSettingsEditResult.Refused -> SettingsSave.Refused(built.code)
                    is HaSettingsEditResult.Ready -> SettingsSave.Send(
                        profileId = localId,
                        screenGeneration = screenGeneration,
                        operation = reserveWrite(),
                        expectedRevision = record.revision,
                        replacement = built.settings
                    )
                }
            }
        }
    }

    /**
     * Whether an admitted Save may still be sent, checked immediately before the one request goes
     * out.
     */
    fun stillAdmitted(decision: SettingsSave.Send): Boolean = synchronized(lock) {
        // One coherent snapshot, taken in a single critical section.
        val current = state
        profileId == decision.profileId &&
            screenGeneration == decision.screenGeneration &&
            decision.operation == writes &&
            current?.writable == true &&
            current.remoteSettings?.revision == decision.expectedRevision
    }

    /** The subject of the next price load, or `null` when no area is known yet. */
    fun beginPriceLoad(): PriceRequestKey? {
        val area = priceArea() ?: return null
        val subject = PriceRequestKey(profileId, area, screenGeneration)
        synchronized(lock) {
            if (pricesSubject != subject) {
                prices = null
                handover = null
                handoverSubject = null
                // Another market's subject.
                confirmedChart = null
            }
            pricesSubject = subject
        }
        return subject
    }

    /** Fold one price answer in, or drop it: only the current subject's result renders. */
    fun onPriceLoaded(subject: PriceRequestKey, result: PriceResult?): PriceResult? {
        synchronized(lock) {
            // The accepted rule lives with the rest of the subject rules, so the screen and the
            // price table cannot answer "is this still mine" differently.
            if (!AuthorityRefresh.accepts(subject, pricesSubject)) return null
            prices = result
            return result
        }
    }

    /** The prices that belong to the current subject, or `null` while none do. */
    val currentPrices: PriceResult? get() = synchronized(lock) { prices }

    /** The subject the held prices belong to, or `null` before the first load. */
    val currentSubject: PriceRequestKey? get() = synchronized(lock) { pricesSubject }

    /** The effective area the authority names, or `null` when there is nothing to price. */
    fun priceArea(): String? = AuthorityRefresh.priceArea(localAreaNow, authority)

    /** Remember the plan this pass computed, for the table's handover, under its subject. */
    fun rememberPlan(inputs: PlanningInputs?, prices: PriceResult?, plan: ChargingPlan?, revision: Int?) {
        val subject = synchronized(lock) { pricesSubject }
        if (subject == null || inputs == null || prices == null) {
            clearPlanHandover()
            return
        }
        synchronized(lock) {
            handover = PlannedCharge(plan = plan, inputs = inputs, prices = prices)
            handoverSubject = subject
            handoverRevision = revision
        }
    }

    /** Forget the remembered plan, all three of its facts at once. */
    fun clearPlanHandover() {
        synchronized(lock) {
            handover = null
            handoverSubject = null
            handoverRevision = null
        }
    }

    /** One render pass's memo decision, from the authority's own answer. */
    /** Retain the chart one render pass drew, when that pass is one to retain from. */
    fun noteChart(captured: VisibleAuthority?, prices: PriceResult?, chart: LocalChart?): ConfirmedChart? =
        publishChart(candidateChart(captured, prices, chart), captured, prices)

    /** The candidate chart this pass would retain, built **outside** the lock. */
    internal fun candidateChart(
        captured: VisibleAuthority?,
        prices: PriceResult?,
        chart: LocalChart?
    ): ConfirmedChart? = ConfirmedChartRules.capture(
        state = captured,
        subject = synchronized(lock) { pricesSubject },
        prices = prices,
        market = chart?.market,
        bands = chart?.bands.orEmpty(),
        footer = chart?.footer,
        profileId = profileId,
        screenGeneration = screenGeneration,
        drawnPrices = chart?.drawnPrices,
        figures = chart?.figures
    )

    /**
     * Publish [candidate] if **every** fact it was built from still holds, and answer the retained
     * chart.
     */
    internal fun publishChart(
        candidate: ConfirmedChart?,
        captured: VisibleAuthority?,
        prices: PriceResult?
    ): ConfirmedChart? = synchronized(lock) {
        val liveSubject = pricesSubject
        val stillTrue = candidate != null &&
            state == captured &&
            candidate.prices === this.prices &&
            this.prices === prices &&
            liveSubject != null &&
            candidate.subject == liveSubject &&
            liveSubject.profileId == profileId &&
            liveSubject.generation == screenGeneration
        if (stillTrue) confirmedChart = candidate
        // What this call published, or `null` when it published nothing.
        if (stillTrue) candidate else null
    }

    /** The retained chart to show for [record] on this screen, or `null`. */
    fun confirmedChartFor(record: HaPlanningSettings?): ConfirmedChart? = synchronized(lock) {
        val liveState = state
        val snapshot = confirmedChart ?: return null
        if (liveState !is VisibleAuthority.ReadOnlyOffline) return null
        if (record == null || liveState.lastConfirmed != record) return null
        if (!ConfirmedChartRules.matches(snapshot, profileId, screenGeneration, record, pricesSubject)) return null
        snapshot
    }

    fun planMemoFor(source: PlanSource, inputs: PlanningInputs?, prices: PriceResult?, plan: ChargingPlan?) {
        if (source is PlanSource.AndroidCalculates) {
            rememberPlan(inputs, prices, plan, source.revision)
        } else {
            clearPlanHandover()
        }
    }

    /**
     * the same price identity, the same inputs (and with them the same fiscal semantics) and the
     * same settings revision.
     */
    fun handoverFor(inputs: PlanningInputs, prices: PriceResult, revision: Int?): ChargingPlan? {
        val subject = synchronized(lock) { pricesSubject }
        val memoSubject = synchronized(lock) { handoverSubject }
        val memoRevision = synchronized(lock) { handoverRevision }
        val memo = synchronized(lock) { handover }
        if (subject == null || subject != memoSubject || memoRevision != revision) return null
        return PlanHandover.planFor(memo = memo, inputs = inputs, prices = prices)
    }

    /** Apply one coordinator answer. */
    fun apply(answer: SettingsAuthorityCoordinator.Outcome): AuthorityResolution {
        val resolution = VisibleAuthorityResolver.resolve(answer, localInputsNow, dashboard)
        if (resolution !is AuthorityResolution.State) return resolution
        val next = resolution.authority
        show(next)
        return AuthorityResolution.State(next)
    }

    /** Replace the state on screen: the one place that does it. */
    private fun show(next: VisibleAuthority): VisibleAuthority {
        synchronized(lock) { state = next }
        return next
    }

    /**
     * The widget's own inputs, and its own area, exactly as the screen's thread captured them
     * before handing a dashboard result over.
     */
    private var localInputsNow: PlanningInputs? = null
    private var localAreaNow: String? = null

    /** Note the widget's own state, on the screen's own thread. */
    fun captureLocal(area: String?, inputs: PlanningInputs?) {
        synchronized(lock) {
            localAreaNow = area
            localInputsNow = inputs
        }
    }
}
