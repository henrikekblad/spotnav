package se.sensnology.spotnav.ha.authority

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.planning.ChargingPeriod
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.SettingsFixtures
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.OffsetDateTime

/** The screen's authority state: */
class VisibleAuthorityTest {
    private val catalogue = listOf(RelayFixtures.se4)
    private val presentation = HaPresentation.QUARTER_HOUR

    private val written = SettingsFixtures.parsed(revision = 4, areaId = "SE4", amps = 16, requestedKwh = 20.5)
    private val autoRecord = SettingsFixtures.parsed(
        revision = 6, areaId = "SE4", amps = 16, requestedKwh = 20.5
    )
    private val revisionZero = SettingsFixtures.parsed(revision = 0, areaId = null, phases = null, amps = null)
    private val incompleteRecord = SettingsFixtures.parsed(revision = 3, areaId = null, amps = 16)

    private val localSettings = WidgetSettings(area = "SE4", chargingAmps = 10, chargingKwh = 12.0)
    private val localInputs = LocalPlanningInputs.of(localSettings)

    private fun adapted(record: HaPlanningSettings): HaPlanningInputs =
        HaPlanningAdapter.of(record, catalogue, presentation)

    private fun resolve(
        outcome: SettingsAuthorityCoordinator.Outcome,
        local: PlanningInputs? = localInputs,
        dashboard: Dashboard? = null
    ): VisibleAuthority {
        val resolution = VisibleAuthorityResolver.resolve(outcome, local, dashboard)
        assertTrue("expected a state, got " + resolution, resolution is AuthorityResolution.State)
        return (resolution as AuthorityResolution.State).authority
    }

    // One answer, one state -- never flattened.
    @Test fun everyCoordinatorAnswerBecomesItsOwnVisibleState() {
        assertEquals(
            VisibleAuthority.LocalOwner(localInputs),
            resolve(SettingsAuthorityCoordinator.Outcome.ProfileMissing)
        )
        assertEquals(
            VisibleAuthority.ReadOnlyOffline(written),
            resolve(SettingsAuthorityCoordinator.Outcome.Unreachable(written))
        )
        assertEquals(
            VisibleAuthority.AutoRemote(
                written,
                remotePlan = null,
                revision = 4,
                availability = AuthorityAvailability.CONFIRMED
            ),
            resolve(SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative(written, adapted(written)))
        )
    }

    // Home Assistant's own defaulted record at revision 0 is adopted like any other.
    @Test fun aRecordAtRevisionZeroIsAdoptedAsIsAndEditable() {
        val state = resolve(SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative(revisionZero, adapted(revisionZero)))

        val auto = state as VisibleAuthority.AutoRemote
        assertEquals(revisionZero, auto.settings)
        assertEquals(0, auto.revision)
        assertTrue(state.writable)
        assertTrue(state.pairedControlsEnabled)
        assertEquals(PlanSource.RemoteOwned(null, 0), AuthorityPlan.source(state))
    }

    @Test fun aSupersededAnswerIsNotAStateAtAll() {
        assertEquals(
            AuthorityResolution.Stale,
            VisibleAuthorityResolver.resolve(
                SettingsAuthorityCoordinator.Outcome.Superseded,
                localInputs,
                null
            )
        )
    }

    @Test fun autoNeverOffersTheLocalPlannerAnythingToCalculate() {
        val dashboard = DashboardFixtures.withSchedule(
            scheduleActive = true,
            amps = 16,
            periods = listOf("2026-09-24T01:00:00+02:00" to "2026-09-24T03:00:00+02:00"),
            charging = true
        )
        val state = resolve(
            SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative(autoRecord, adapted(autoRecord)),
            dashboard = dashboard
        )
        val auto = state as VisibleAuthority.AutoRemote

        assertEquals(autoRecord, auto.settings)
        assertEquals(6, auto.revision)
        assertEquals(AuthorityAvailability.CONFIRMED, auto.availability)
        // No inputs at all.
        val source = AuthorityPlan.source(state)
        assertTrue("Auto must be remote-owned", source is PlanSource.RemoteOwned)
        val remote = source as PlanSource.RemoteOwned
        assertEquals(6, remote.revision)
        assertEquals(
            listOf(ChargingPeriod(OffsetDateTime.parse("2026-09-24T01:00:00+02:00"), OffsetDateTime.parse("2026-09-24T03:00:00+02:00"))),
            remote.remotePlan!!.periods
        )
        assertEquals(16, remote.remotePlan.amps)
        assertTrue(remote.remotePlan.installed)
        assertTrue(remote.remotePlan.chargingEnabled)
    }

    @Test fun autoWithoutADashboardYetIsWaitingRatherThanEmpty() {
        val state = resolve(SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative(autoRecord, adapted(autoRecord)))

        assertTrue(state is VisibleAuthority.AutoRemote)
        val source = AuthorityPlan.source(state)
        assertEquals(PlanSource.RemoteOwned(null, 6), source)
    }

    @Test fun aRecordIsRemoteOwnedAndNeverCalculatedLocally() {
        val dashboard = DashboardFixtures.withSchedule(scheduleActive = true, amps = 16)
        val state = resolve(
            SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative(written, adapted(written)),
            dashboard = dashboard
        )
        val remote = state as VisibleAuthority.AutoRemote

        assertEquals(4, remote.revision)
        assertEquals(AuthorityAvailability.CONFIRMED, remote.availability)
        assertEquals(PlanSource.RemoteOwned(remote.remotePlan, 4), AuthorityPlan.source(state))
        assertEquals(16, remote.remotePlan!!.amps)
        assertTrue(remote.remotePlan.installed)
    }

    @Test fun aSparseRecordStillBelongsToHomeAssistantAndCannotProduceLocalInputs() {
        val state = resolve(
            SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative(incompleteRecord, adapted(incompleteRecord))
        )
        val remote = state as VisibleAuthority.AutoRemote
        assertEquals(incompleteRecord, remote.settings)
        assertEquals(PlanSource.RemoteOwned(null, incompleteRecord.revision), AuthorityPlan.source(state))
        assertTrue(remote.writable)
    }

    @Test fun offlineShowsTheLastConfirmedRecordAsStaleAndForbidsEditingIt() {
        val state = resolve(SettingsAuthorityCoordinator.Outcome.Unreachable(written))
        val offline = state as VisibleAuthority.ReadOnlyOffline

        assertEquals(written, offline.lastConfirmed)
        assertFalse("offline never edits", offline.writable)
        assertTrue(offline.haOwnsPlanning)
        // Nothing is calculated for an offline paired charger, with or without the cached record.
        assertEquals(PlanSource.None, AuthorityPlan.source(state))
    }

    @Test fun offlineWithNothingEverConfirmedIsStillItsOwnState() {
        val state = resolve(SettingsAuthorityCoordinator.Outcome.Unreachable(null))

        assertEquals(VisibleAuthority.ReadOnlyOffline(null), state)
        assertFalse(state.writable)
    }

    @Test fun aDashboardThatStatesNoRecordIsReadOnlyAndSaysSoApartFromNoConnection() {
        val state = resolve(SettingsAuthorityCoordinator.Outcome.Unreachable(null, answeredWithoutRecord = true))

        assertEquals(VisibleAuthority.ReadOnlyOffline(null, answeredWithoutRecord = true), state)
        assertFalse(state.writable)
        assertTrue((resolve(SettingsAuthorityCoordinator.Outcome.Unreachable(null)) as VisibleAuthority.ReadOnlyOffline).answeredWithoutRecord.not())
    }

    @Test fun writabilityIsExactlyTheStatesThatMayWriteThroughTheContract() {
        val writable: List<VisibleAuthority> = listOf(
            resolve(SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative(written, adapted(written))),
            resolve(SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative(autoRecord, adapted(autoRecord))),
            resolve(SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative(revisionZero, adapted(revisionZero))),
            resolve(SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative(incompleteRecord, adapted(incompleteRecord)))
        )
        val readOnly: List<VisibleAuthority> = listOf(
            resolve(SettingsAuthorityCoordinator.Outcome.Unreachable(written)),
            resolve(SettingsAuthorityCoordinator.Outcome.Unreachable(null)),
            resolve(SettingsAuthorityCoordinator.Outcome.ProfileMissing)
        )

        writable.forEach { assertTrue("$it should be writable", it.writable) }
        readOnly.forEach { assertFalse("$it should not be writable", it.writable) }
    }

    @Test fun everyStateButTheLocalOwnerKnowsHomeAssistantOwnsTheValues() {
        listOf(
            resolve(SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative(written, adapted(written))),
            resolve(SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative(autoRecord, adapted(autoRecord))),
            resolve(SettingsAuthorityCoordinator.Outcome.Unreachable(written)),
            resolve(SettingsAuthorityCoordinator.Outcome.Unreachable(null))
        ).forEach { assertTrue("$it should be HA-owned", it.haOwnsPlanning) }

        assertFalse(resolve(SettingsAuthorityCoordinator.Outcome.ProfileMissing).haOwnsPlanning)
    }

    @Test fun aStateThatOwnsPlanningNeverFallsBackToTheWidgetsOwnRecord() {
        val auto = resolve(SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative(autoRecord, adapted(autoRecord)))
        assertEquals(PlanSource.RemoteOwned(null, 6), AuthorityPlan.screenSource(auto, localInputs))

        listOf(
            resolve(SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative(written, adapted(written))),
            resolve(SettingsAuthorityCoordinator.Outcome.Unreachable(written)),
            resolve(SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative(incompleteRecord, adapted(incompleteRecord)))
        ).forEach { state ->
            assertEquals(
                "$state must ignore the widget's own record",
                AuthorityPlan.source(state),
                AuthorityPlan.screenSource(state, localInputs)
            )
        }

        // The widget's own record is used exactly when nothing Home Assistant owns applies.
        assertEquals(PlanSource.AndroidCalculates(localInputs, null), AuthorityPlan.screenSource(null, localInputs))
        assertEquals(
            PlanSource.AndroidCalculates(localInputs, null),
            AuthorityPlan.screenSource(resolve(SettingsAuthorityCoordinator.Outcome.ProfileMissing), localInputs)
        )
        // And with no local record either, there is nothing to draw.
        assertEquals(PlanSource.None, AuthorityPlan.screenSource(null, null))
    }
}
