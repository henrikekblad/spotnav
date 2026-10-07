package se.sensnology.spotnav.ha.authority

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.SettingsUpdate
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.SettingsFixtures
import se.sensnology.spotnav.widget.WidgetSettings

/** What a price load and an authority answer belong to. */
class AuthorityRefreshTest {
    private val catalogue = listOf(RelayFixtures.se4)
    private val written = SettingsFixtures.parsed(revision = 4, areaId = "SE4")
    private val autoRecord = SettingsFixtures.parsed(revision = 6, areaId = "SE4")
    private val localInputs = LocalPlanningInputs.of(WidgetSettings(area = "NO1"))

    private fun adapted(record: HaPlanningSettings): HaPlanningInputs =
        HaPlanningAdapter.of(record, catalogue)

    private fun state(outcome: SettingsAuthorityCoordinator.Outcome): VisibleAuthority =
        (VisibleAuthorityResolver.resolve(outcome, localInputs, null) as AuthorityResolution.State).authority

    @Test fun aPairedScreenPricesTheRecordsOwnMarketAndTheLocalOneElsewhere() {
        // The widget's local copy says NO1; the record Home Assistant owns says SE4.
        assertEquals(
            "SE4",
            AuthorityRefresh.priceArea(
                "NO1",
                state(SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative(written, adapted(written)))
            )
        )
        assertEquals(
            "SE4",
            AuthorityRefresh.priceArea(
                "NO1",
                state(SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative(autoRecord, adapted(autoRecord)))
            )
        )
        // Offline: the last confirmed record still names the market to show, stale.
        assertEquals(
            "SE4",
            AuthorityRefresh.priceArea("NO1", state(SettingsAuthorityCoordinator.Outcome.Unreachable(written)))
        )
        // No record: the widget's own area, exactly as before pairing.
        assertEquals(
            "NO1",
            AuthorityRefresh.priceArea("NO1", state(SettingsAuthorityCoordinator.Outcome.ProfileMissing))
        )
    }

    @Test fun aDefaultedRecordWithNoAreaYetFallsBackToTheLocalOne() {
        val noArea = SettingsFixtures.parsed(revision = 0, areaId = null)

        assertEquals(
            "NO1",
            AuthorityRefresh.priceArea(
                "NO1",
                state(SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative(noArea, adapted(noArea)))
            )
        )
    }

    // A late answer for another subject never renders.
    @Test fun anAnswerIsAcceptedOnlyWhileItIsExactlyTheCurrentSubject() {
        val current = PriceRequestKey(profileId = "local-a", areaId = "SE4", generation = 3)

        assertTrue(AuthorityRefresh.accepts(current, current))
        assertFalse("another area", AuthorityRefresh.accepts(current.copy(areaId = "SE3"), current))
        assertFalse("an older generation", AuthorityRefresh.accepts(current.copy(generation = 2), current))
        assertFalse("another profile", AuthorityRefresh.accepts(current.copy(profileId = "local-b"), current))
        assertFalse("no answer at all", AuthorityRefresh.accepts(null, current))
        // And a screen with no subject yet accepts nothing.
        assertFalse(AuthorityRefresh.accepts(null, null))
    }

    // Econd half: one new load for a new area, none for the same one.
    @Test fun onlyAChangedKnownAreaStartsANewLoad() {
        assertTrue(AuthorityRefresh.areaChangeRequiresLoad("SE4", "SE3"))
        assertFalse("the same area is not a change", AuthorityRefresh.areaChangeRequiresLoad("SE4", "SE4"))
        assertFalse("nothing to load from", AuthorityRefresh.areaChangeRequiresLoad(null, "SE4"))
        assertFalse("nothing to load into", AuthorityRefresh.areaChangeRequiresLoad("SE4", null))
    }

    @Test fun onlyAReconciledSettingsUpdateReloadsTheStatusThatCarriesItsPlan() {
        val updated = SettingsUpdate.Outcome.Updated(SettingsFixtures.parsed(revision = 8))
        assertTrue(AuthorityRefresh.settingsChangeNeedsDashboardReload(updated))
        assertFalse(
            AuthorityRefresh.settingsChangeNeedsDashboardReload(
                SettingsUpdate.Outcome.CommittedButReconcileFailed(SettingsFixtures.parsed(revision = 8))
            )
        )
        assertFalse(
            AuthorityRefresh.settingsChangeNeedsDashboardReload(
                SettingsUpdate.Outcome.Conflict(SettingsFixtures.parsed(revision = 7))
            )
        )
        assertFalse(AuthorityRefresh.settingsChangeNeedsDashboardReload(SettingsUpdate.Outcome.Unavailable))
    }

    @Test fun thePlanSubjectCarriesTheProfileTheAreaAndTheGeneration() {
        val one = PriceRequestKey("local-a", "SE4", 1)
        assertEquals(one, PriceRequestKey("local-a", "SE4", 1))
        assertNull(null as PriceRequestKey?)
    }
}
