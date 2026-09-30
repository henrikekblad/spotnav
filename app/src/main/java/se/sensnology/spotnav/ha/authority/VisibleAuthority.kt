package se.sensnology.spotnav.ha.authority

import se.sensnology.spotnav.chart.DashboardChart
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.planning.ChargingPeriod
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.planning.PlanningInputs

/** Where one visible widget's planning values come from, as one closed typed state. */

internal enum class AuthorityAvailability {
    /** A fresh answer from the charger: editable, and the revision may be written against. */
    CONFIRMED,

    /** The last server-confirmed record, shown while the current revision cannot be checked. */
    CACHED,
}

internal data class RemotePlan(
    /** Whether Home Assistant says it currently holds a schedule for this charger. */
    val installed: Boolean,
    /** The installed schedule's periods, exactly as reported. Never a local plan. */
    val periods: List<ChargingPeriod>,
    /** The installed schedule's current limit, when it reported one. */
    val amps: Int?,
    val chargingEnabled: Boolean
) {
    /** Whether there is an installed schedule with periods to draw. */
    val hasInstalledPeriods: Boolean get() = installed && periods.isNotEmpty()

    companion object {
        /** The installed-plan facts one dashboard reports. */
        fun of(dashboard: Dashboard): RemotePlan {
            val installed = dashboard.plan.installed
            return RemotePlan(
                installed = dashboard.live.scheduleActive,
                periods = installed?.periods.orEmpty().map { ChargingPeriod(it.start, it.end) },
                amps = installed?.amps,
                chargingEnabled = dashboard.live.charging
            )
        }
    }
}

internal sealed interface VisibleAuthority {
    /** The widget's own stored planning fields are the authority. */
    data class LocalOwner(val inputs: PlanningInputs?) : VisibleAuthority

    /** Home Assistant's record is canonical and Home Assistant calculates the plan. */
    data class AutoRemote(
        val settings: HaPlanningSettings,
        val remotePlan: RemotePlan?,
        val revision: Int,
        val availability: AuthorityAvailability
    ) : VisibleAuthority

    /** Canonical settings with a named incomplete presentation. */
    data class Incomplete(
        val reasons: List<HaPlanningInputs.Reason>,
        val lastConfirmed: HaPlanningSettings?
    ) : VisibleAuthority {
        init {
            require(reasons.isNotEmpty()) { "an incomplete authority names at least one reason" }
        }
    }

    /** The current revision cannot be checked: show [lastConfirmed] as stale, never edit it. */
    data class ReadOnlyOffline(
        val lastConfirmed: HaPlanningSettings?,
        /**
         * Home Assistant did answer, but has no settings for this charger yet -- not a lost
         * connection.
         */
        val answeredWithoutRecord: Boolean = false
    ) : VisibleAuthority

    /** The canonical record this state is about, when it has one. */
    val remoteSettings: HaPlanningSettings?
        get() = when (this) {
            is AutoRemote -> settings
            is Incomplete -> lastConfirmed
            is ReadOnlyOffline -> lastConfirmed
            is LocalOwner -> null
        }

    val haOwnsPlanning: Boolean
        get() = when (this) {
            is AutoRemote, is Incomplete, is ReadOnlyOffline -> true
            is LocalOwner -> false
        }

    val pairedControlsEnabled: Boolean
        get() = when (this) {
            is AutoRemote -> availability == AuthorityAvailability.CONFIRMED
            is Incomplete -> lastConfirmed != null
            is LocalOwner -> true
            is ReadOnlyOffline -> false
        }

    /** Offline never may: there is no revision to check. */
    val writable: Boolean
        get() = when (this) {
            is AutoRemote -> availability == AuthorityAvailability.CONFIRMED
            is Incomplete -> lastConfirmed != null
            else -> false
        }
}

/** One resolution: */
internal sealed interface AuthorityResolution {
    data class State(val authority: VisibleAuthority) : AuthorityResolution

    /**
     * The coordinator's answer was for a generation that has been taken over. The screen keeps
     * whatever it already shows.
     */
    data object Stale : AuthorityResolution
}

/** The coordinator's answer as the screen's own state. */
internal object VisibleAuthorityResolver {
    /** One record and its own adaptation, as a state. */
    fun forRecord(
        settings: HaPlanningSettings,
        adaptation: HaPlanningInputs,
        dashboard: Dashboard?
    ): VisibleAuthority = remote(settings, adaptation, dashboard)

    fun resolve(
        outcome: SettingsAuthorityCoordinator.Outcome,
        localInputs: PlanningInputs?,
        dashboard: Dashboard?
    ): AuthorityResolution = when (outcome) {
        SettingsAuthorityCoordinator.Outcome.Superseded -> AuthorityResolution.Stale
        // No such profile in the captured snapshot: nothing Home Assistant owns here.
        SettingsAuthorityCoordinator.Outcome.ProfileMissing ->
            AuthorityResolution.State(VisibleAuthority.LocalOwner(localInputs))
        is SettingsAuthorityCoordinator.Outcome.Unreachable ->
            AuthorityResolution.State(VisibleAuthority.ReadOnlyOffline(outcome.lastConfirmed, outcome.answeredWithoutRecord))
        is SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative -> AuthorityResolution.State(
            remote(outcome.settings, outcome.adaptation, dashboard)
        )
    }

    private fun remote(
        settings: HaPlanningSettings,
        adaptation: HaPlanningInputs,
        dashboard: Dashboard?
    ): VisibleAuthority = when (adaptation) {
        is HaPlanningInputs.Auto -> VisibleAuthority.AutoRemote(
            settings = settings,
            remotePlan = dashboard?.let(RemotePlan::of),
            revision = settings.revision,
            availability = AuthorityAvailability.CONFIRMED
        )
        is HaPlanningInputs.Incomplete -> VisibleAuthority.Incomplete(
            reasons = adaptation.reasons,
            lastConfirmed = settings
        )
    }
}

internal sealed interface PlanSource {
    /** Android calculates from these inputs. [revision] is the record they came from, if any. */
    data class AndroidCalculates(val inputs: PlanningInputs, val revision: Int?) : PlanSource

    /** Home Assistant owns the plan: show what the dashboard reported, calculate nothing. */
    data class RemoteOwned(val remotePlan: RemotePlan?, val revision: Int) : PlanSource

    /** Nothing may be drawn: no valid inputs and no remote plan. */
    data object None : PlanSource
}

/** The plan-ownership decision for one authority state. */
internal object AuthorityPlan {
    /** What a *screen pass* may draw: */
    fun screenSource(
        authority: VisibleAuthority?,
        localInputs: PlanningInputs?
    ): PlanSource =
        if (authority != null && authority.haOwnsPlanning) {
            source(authority)
        } else {
            localInputs?.let { PlanSource.AndroidCalculates(it, null) } ?: PlanSource.None
        }

    /** What the screen may draw for [authority]. */
    fun source(authority: VisibleAuthority): PlanSource = when (authority) {
        is VisibleAuthority.LocalOwner ->
            authority.inputs?.let { PlanSource.AndroidCalculates(it, null) } ?: PlanSource.None
        // Auto.
        is VisibleAuthority.AutoRemote -> PlanSource.RemoteOwned(authority.remotePlan, authority.revision)
        is VisibleAuthority.Incomplete -> PlanSource.None
        // Offline: the revision cannot be checked, so nothing may be calculated.
        is VisibleAuthority.ReadOnlyOffline -> PlanSource.None
    }
}
