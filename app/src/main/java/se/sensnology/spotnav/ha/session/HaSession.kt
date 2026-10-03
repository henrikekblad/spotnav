package se.sensnology.spotnav.ha.session

import se.sensnology.spotnav.ha.authority.DashboardAdmission
import se.sensnology.spotnav.ha.client.ChargerPriorityUpdate
import se.sensnology.spotnav.ha.client.FetchedDashboard
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.HomeAssistantCommand
import se.sensnology.spotnav.ha.client.HomeAssistantSettings
import se.sensnology.spotnav.ha.client.SiteUpdate
import se.sensnology.spotnav.ha.client.VehicleUpdate
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.SettingsUpdate
import se.sensnology.spotnav.vehicles.ChargeLimit
import se.sensnology.spotnav.vehicles.VehicleRefresh
import se.sensnology.spotnav.ha.client.SessionsOutcome
import se.sensnology.spotnav.ha.sessions.SessionsCsv
import se.sensnology.spotnav.ha.sessions.SessionsMonth
import java.time.YearMonth
import java.util.concurrent.Executor

/** What a session needs of the network, so its rules can be run against a fake. */
internal interface HaTransport {
    /** The charger's dashboard, with the answer's text; throws when there is no usable answer. */
    fun dashboard(): FetchedDashboard

    /** One command (`start`, `stop`, a pause or resume); throws when it did not go through. */
    fun send(command: HomeAssistantCommand)

    fun refreshVehicle(vehicleId: String): VehicleRefresh.Answer

    fun setChargeLimit(vehicleId: String, percent: Int): ChargeLimit.Answer

    fun updateVehicle(vehicleId: String, changes: List<VehicleUpdate.FieldChange>): VehicleUpdate.Outcome

    fun updateSettings(expectedRevision: Int, replacement: HaPlanningSettings): SettingsUpdate.Outcome

    fun updateSite(request: SiteUpdate.Request): SiteUpdate.Outcome

    fun updateChargerPriority(expected: String, priority: String): ChargerPriorityUpdate.Outcome =
        ChargerPriorityUpdate.Outcome.Failed(null)

    /** One month of charge history (`null`: the current month); never throws. */
    fun sessionsMonth(month: YearMonth?): SessionsOutcome<SessionsMonth> = SessionsOutcome.Failed

    /** One month of charge history as a CSV text; never throws. */
    fun sessionsCsv(month: YearMonth): SessionsOutcome<SessionsCsv> = SessionsOutcome.Failed
}

/** [HaTransport] over the webhook of one charger, captured once so no answer can land on another. */
internal class HomeAssistantTransport(private val connection: HomeAssistantSettings) : HaTransport {
    override fun dashboard(): FetchedDashboard = HomeAssistantClient.dashboardRead(connection)

    override fun send(command: HomeAssistantCommand) = HomeAssistantClient.send(connection, command)

    override fun refreshVehicle(vehicleId: String) = HomeAssistantClient.refreshVehicle(connection, vehicleId)

    override fun setChargeLimit(vehicleId: String, percent: Int) =
        HomeAssistantClient.setChargeLimit(connection, vehicleId, percent)

    override fun updateVehicle(vehicleId: String, changes: List<VehicleUpdate.FieldChange>) =
        HomeAssistantClient.updateVehicle(connection, vehicleId, changes)

    override fun updateSettings(expectedRevision: Int, replacement: HaPlanningSettings) =
        HomeAssistantClient.updateSettings(connection, expectedRevision, replacement)

    override fun updateSite(request: SiteUpdate.Request) = HomeAssistantClient.updateSiteSettings(connection, request)

    override fun updateChargerPriority(expected: String, priority: String) =
        HomeAssistantClient.updateChargerPriority(connection, expected, priority)

    override fun sessionsMonth(month: YearMonth?) = HomeAssistantClient.sessionsMonth(connection, month)

    override fun sessionsCsv(month: YearMonth) = HomeAssistantClient.sessionsCsv(connection, month)
}

/**
 * Where a dashboard answer is kept once it has been read: the profile it updates and the status the
 * home-screen widget draws its bottom line from.
 */
internal fun interface DashboardRecorder {
    fun record(result: Result<FetchedDashboard>): Int?
}

/**
 * Everything one screen says to one charger's Home Assistant: the dashboard read, the commands, the
 * re-reads and the writes -- each one a bounded request on the background executor, answered on the
 * main thread and only while the screen that asked is still the one showing.
 */
internal class HaSession(
    private val transport: HaTransport,
    private val recorder: DashboardRecorder,
    private val background: Executor,
    private val mainThread: (() -> Unit) -> Unit,
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit
) {
    /**
     * One dashboard read: the identity it was admitted under, its outcome, and the phases then
     * known.
     */
    class Read(val admission: DashboardAdmission?, val result: Result<Dashboard>, val detectedPhases: Int?)

    /** A command's outcome: the confirmation read that followed it (or the failure of either step). */
    class Sent(val result: Result<Dashboard>, val detectedPhases: Int?)

    /** The vehicle re-read's answer, and the read that followed it when the integration re-read. */
    class ReRead(val answer: VehicleRefresh.Answer, val read: Sent?)

    /** Read the dashboard, record it, and hand it on. */
    fun read(admit: () -> DashboardAdmission?, done: (Read) -> Unit) {
        background.execute {
            val admission = admit()
            val fetched = runCatching { transport.dashboard() }
            val phases = recorder.record(fetched)
            mainThread { done(Read(admission, fetched.map { it.dashboard }, phases)) }
        }
    }

    /** A dashboard read that is nobody's to record: the settings screen's site section. */
    fun peekDashboard(done: (Dashboard?) -> Unit) {
        background.execute {
            val dashboard = runCatching { transport.dashboard().dashboard }.getOrNull()
            mainThread { done(dashboard) }
        }
    }

    /** Send one command and read the dashboard back, so the screen shows what the command changed. */
    fun send(
        command: HomeAssistantCommand,
        admit: () -> DashboardAdmission?,
        onConfirmed: (Sent) -> Unit,
        onRefreshed: (Read) -> Unit
    ) {
        background.execute {
            val fetched = runCatching {
                transport.send(command)
                transport.dashboard()
            }
            val phases = recorder.record(fetched)
            mainThread {
                onConfirmed(Sent(fetched.map { it.dashboard }, phases))
                if (fetched.isSuccess && command.action in FOLLOWED_BY_A_READ) {
                    schedule(FOLLOW_UP_MS) { read(admit, onRefreshed) }
                }
            }
        }
    }

    /** Ask the integration to re-read one vehicle's own entities. */
    fun reReadVehicle(vehicleId: String, done: (ReRead) -> Unit) {
        background.execute {
            // Never throws: every outcome is one of the three meanings.
            val answer = transport.refreshVehicle(vehicleId)
            val read = if (answer == VehicleRefresh.Answer.Refreshed) {
                val fetched = runCatching { transport.dashboard() }
                Sent(fetched.map { it.dashboard }, recorder.record(fetched))
            } else {
                null
            }
            mainThread { done(ReRead(answer, read)) }
        }
    }

    /** Write one vehicle's charge limit. */
    fun setChargeLimit(vehicleId: String, percent: Int, done: (ChargeLimit.Answer) -> Unit) {
        background.execute {
            val answer = transport.setChargeLimit(vehicleId, percent)
            mainThread { done(answer) }
        }
    }

    /** `update_vehicle`: never throws, the outcome is one of the pure module's meanings. */
    fun updateVehicle(
        vehicleId: String,
        changes: List<VehicleUpdate.FieldChange>,
        done: (VehicleUpdate.Outcome) -> Unit
    ) {
        background.execute {
            val outcome = transport.updateVehicle(vehicleId, changes)
            mainThread { done(outcome) }
        }
    }

    /** One settings replacement against the revision it was built on (never throws). */
    fun updateSettings(
        expectedRevision: Int,
        replacement: HaPlanningSettings,
        done: (SettingsUpdate.Outcome) -> Unit
    ) {
        background.execute {
            val outcome = transport.updateSettings(expectedRevision, replacement)
            mainThread { done(outcome) }
        }
    }

    /** `update_site_settings` (never throws). */
    fun updateSite(request: SiteUpdate.Request, done: (SiteUpdate.Outcome) -> Unit) {
        background.execute {
            val outcome = transport.updateSite(request)
            mainThread { done(outcome) }
        }
    }

    /** `update_charger_priority` (never throws). */
    fun updateChargerPriority(expected: String, priority: String, done: (ChargerPriorityUpdate.Outcome) -> Unit) {
        background.execute {
            val outcome = transport.updateChargerPriority(expected, priority)
            mainThread { done(outcome) }
        }
    }

    /** One month of charge history, read on the background executor and answered on the main thread. */
    fun sessionsMonth(month: YearMonth?, done: (SessionsOutcome<SessionsMonth>) -> Unit) {
        background.execute {
            val outcome = transport.sessionsMonth(month)
            mainThread { done(outcome) }
        }
    }

    /** One month of charge history as a CSV text. */
    fun sessionsCsv(month: YearMonth, done: (SessionsOutcome<SessionsCsv>) -> Unit) {
        background.execute {
            val outcome = transport.sessionsCsv(month)
            mainThread { done(outcome) }
        }
    }

    companion object {
        /**
         * How long after a Start or a Stop the follow-up read waits: the charger acts, then
         * reports.
         */
        const val FOLLOW_UP_MS = 3_000L

        private val FOLLOWED_BY_A_READ = setOf("start", "stop")
    }
}
