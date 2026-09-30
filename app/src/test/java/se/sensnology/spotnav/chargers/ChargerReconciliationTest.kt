package se.sensnology.spotnav.chargers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.StatusCharger
import se.sensnology.spotnav.ha.pairing.HomeAssistantInstanceStore
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.widget.WidgetChargerResolver
import se.sensnology.spotnav.widget.WidgetSettings

/** What an instance's charger list means for the profiles on the device. */
class ChargerReconciliationTest {
    private val instance = "http://192.168.1.50:8123"

    private fun profile(
        localId: String = "local-a",
        displayName: String = "My charger",
        baseUrl: String = instance,
        webhookId: String = "wh-a",
        remoteChargerId: String? = "entry_a",
        remoteChargerName: String? = "Garage",
        selectedVehicleId: String? = "car-1",
        targetSocPercent: Int? = 80
    ) = ChargerProfile(
        localId = localId,
        displayName = displayName,
        baseUrl = baseUrl,
        webhookId = webhookId,
        remoteChargerId = remoteChargerId,
        remoteChargerName = remoteChargerName,
        selectedVehicleId = selectedVehicleId,
        targetSocPercent = targetSocPercent
    )

    private fun reconcile(
        profiles: List<ChargerProfile>,
        chargers: List<StatusCharger>
    ) = ChargerReconciliation.reconcile(profiles, instance, chargers)

    private fun stateOf(report: ChargerReconciliation.Report, localId: String) =
        report.verdicts.firstOrNull { it.profile.localId == localId }?.state

    // in sync, renamed, gone

    @Test fun aChargerHomeAssistantNamesTheSameWayIsInSync() {
        val report = reconcile(listOf(profile()), listOf(StatusCharger("entry_a", "Garage")))

        assertEquals(ChargerReconciliation.State.IN_SYNC, stateOf(report, "local-a"))
        assertTrue(report.updates.isEmpty())
        assertTrue(report.unprovisioned.isEmpty())
    }

    @Test fun aRenameUpdatesOnlyTheNameHomeAssistantOwns() {
        val stored = profile()

        val report = reconcile(listOf(stored), listOf(StatusCharger("entry_a", "Renamed in HA")))

        assertEquals(ChargerReconciliation.State.RENAMED, stateOf(report, "local-a"))
        assertEquals(1, report.updates.size)
        assertEquals(stored.copy(remoteChargerName = "Renamed in HA"), report.updates.single())
    }

    // The second row of the three: a name the user typed is theirs, and a rename in Home Assistant
    // is not a reason to replace it.
    @Test fun aRenameLeavesTheUsersOwnNameAndEveryOtherChoiceAlone() {
        val report = reconcile(
            listOf(profile(displayName = "Hemma", selectedVehicleId = "car-9", targetSocPercent = 70)),
            listOf(StatusCharger("entry_a", "Renamed in HA"))
        )

        val updated = report.updates.single()
        assertEquals("Hemma", updated.displayName)
        assertEquals("car-9", updated.selectedVehicleId)
        assertEquals(70, updated.targetSocPercent)
        assertEquals("local-a", updated.localId)
        assertEquals("wh-a", updated.webhookId)
    }

    @Test fun aProfileThatNeverHadANameLearnsItInTheSameField() {
        val report = reconcile(
            listOf(profile(remoteChargerName = null)),
            listOf(StatusCharger("entry_a", "Garage"))
        )

        assertEquals(ChargerReconciliation.State.RENAMED, stateOf(report, "local-a"))
        assertEquals("Garage", report.updates.single().remoteChargerName)
    }

    // What pairing writes -- no display name at all -- so the label reads remoteChargerName.
    @Test fun anEmptyDisplayNameSimplyFollowsTheReportedName() {
        val report = reconcile(
            listOf(profile(displayName = "", remoteChargerName = "Old name")),
            listOf(StatusCharger("entry_a", "New name"))
        )

        assertEquals(ChargerReconciliation.State.RENAMED, stateOf(report, "local-a"))
        val updated = report.updates.single()
        assertEquals("", updated.displayName)
        assertEquals("New name", updated.remoteChargerName)
    }

    @Test fun aChargerHomeAssistantNoLongerReportsIsStaleAndUntouched() {
        val report = reconcile(listOf(profile()), listOf(StatusCharger("entry_other", "Other")))

        assertEquals(ChargerReconciliation.State.GONE, stateOf(report, "local-a"))
        assertEquals(listOf("local-a"), report.stale.map { it.localId })
        // Never deleted, never redirected, never rewritten.
        assertTrue(report.updates.isEmpty())
    }

    @Test fun anEmptyChargerListMakesEveryMatchedProfileStale() {
        val report = reconcile(listOf(profile()), emptyList())

        assertEquals(ChargerReconciliation.State.GONE, stateOf(report, "local-a"))
    }

    // what is never guessed

    @Test fun aProfileWithNoChargerIdIsUnknownRatherThanStale() {
        // Paired before any dashboard named its charger: guessing that it is gone would mark a
        // working charger stale on the strength of nothing.
        val report = reconcile(listOf(profile(remoteChargerId = null)), emptyList())

        assertEquals(ChargerReconciliation.State.UNKNOWN, stateOf(report, "local-a"))
        assertTrue(report.stale.isEmpty())
        assertTrue(report.updates.isEmpty())
    }

    @Test fun anotherInstancesProfileIsNotPartOfThisReport() {
        val report = reconcile(
            listOf(profile(localId = "local-b", baseUrl = "http://10.0.0.9:8123")),
            listOf(StatusCharger("entry_a", "Garage"))
        )

        assertNull(stateOf(report, "local-b"))
        assertTrue(report.updates.isEmpty())
        // It is not a profile this instance could have provisioned, either.
        assertEquals(listOf("entry_a"), report.unprovisioned.map { it.id })
    }

    // chargers the app cannot control yet

    @Test fun aChargerTheAppHoldsNoWebhookForIsOfferedRatherThanHidden() {
        val report = reconcile(
            listOf(profile()),
            listOf(StatusCharger("entry_a", "Garage"), StatusCharger("entry_b", "Carport"))
        )

        assertEquals(listOf("entry_b"), report.unprovisioned.map { it.id })
        assertEquals("Carport", report.unprovisioned.single().name)
    }

    @Test fun everyReportedChargerIsInOneOfTheTwoLists() {
        val report = reconcile(
            listOf(profile()),
            listOf(StatusCharger("entry_a", "Garage"), StatusCharger("entry_b", "Carport"))
        )

        assertEquals(ChargerReconciliation.State.IN_SYNC, stateOf(report, "local-a"))
        assertEquals(setOf("entry_a", "entry_b"), setOf("entry_a") + report.unprovisioned.map { it.id })
    }

    // nothing happens to an installation that already works

    @Test fun anExistingInstallationNeedsNoMigration() {
        // The whole point of changing provisioning rather than the schema: an app that already has
        // a working charger keeps it, with no instance stored, no re-pairing, and its widget's
        // binding still resolving.
        val profiles = ChargerProfileStore(FakeKeyValueStore()) { }
        val existing = profile(remoteChargerId = "entry_a", remoteChargerName = "Garage")
        profiles.upsertProfile(existing)
        profiles.setActiveProfileId(existing.localId)

        // No instance is stored yet, so the settings screen has nothing to reconcile against and
        // offers the login button...
        assertNull(HomeAssistantInstanceStore(FakeKeyValueStore()).baseUrl())
        // ...the charger a widget is bound to still resolves exactly...
        assertEquals(existing, WidgetChargerResolver.resolve(WidgetSettings(chargerProfileId = existing.localId), profiles))
        // ...and nothing was rewritten or removed on the way.
        assertEquals(listOf(existing), profiles.listProfiles())
        assertEquals(existing.localId, profiles.getActiveProfileId())
    }
}
