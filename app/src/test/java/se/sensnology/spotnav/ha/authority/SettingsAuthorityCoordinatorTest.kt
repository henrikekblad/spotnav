package se.sensnology.spotnav.ha.authority

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.settings.ConfirmedSettingsStore
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.HaSettingsCodec
import se.sensnology.spotnav.prices.PriceMarket
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.SettingsFixtures
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The profile-scoped authority coordinator: */
class SettingsAuthorityCoordinatorTest {
    private val profileId = "local-a"
    private val otherId = "local-b"
    private val presentation = HaPresentation.QUARTER_HOUR

    private val profile = ChargerProfile(
        localId = profileId,
        displayName = "A",
        baseUrl = "https://spotnav.example.invalid:8123",
        webhookId = "webhook-secret-a",
        selectedVehicleId = "vehicle-1",
        targetSocPercent = 80
    )
    private val other = ChargerProfile(
        localId = otherId,
        displayName = "B",
        baseUrl = "https://other.example.invalid",
        webhookId = "webhook-secret-b"
    )

    private var raw = BlockingRecordingKeyValueStore()
    private var cache = ConfirmedSettingsStore(raw) { }
    private val fetches = mutableListOf<ChargerProfile>()

    private var profileSnapshot: () -> List<ChargerProfile> = { listOf(profile, other) }
    private var catalogueSnapshot: () -> List<PriceMarket> = { listOf(RelayFixtures.se4) }
    private var dashboards: (ChargerProfile) -> Dashboard = { dashboard(writtenRecord) }
    private var reachable: Boolean = true

    private fun coordinator(): SettingsAuthorityCoordinator = SettingsAuthorityCoordinator(
        profiles = { profileSnapshot() },
        catalogue = { catalogueSnapshot() },
        fetchDashboard = { subject ->
            fetches.add(subject)
            if (reachable) Result.success(dashboards(subject)) else Result.failure(IllegalStateException("no route"))
        },
        cache = cache
    )

    /**
     * Rebuilds the store and the cache so the [occurrence]-th mutation of [key] parks until the
     * test releases it.
     */
    private fun blockingCache(key: String, occurrence: Int = 1): BlockingRecordingKeyValueStore {
        val store = BlockingRecordingKeyValueStore(blockedKey = key, blockOnOccurrence = occurrence)
        raw = store
        cache = ConfirmedSettingsStore(store) { }
        return store
    }

    private fun dashboard(record: HaPlanningSettings): Dashboard =
        DashboardFixtures.dashboard { put("settings", HaSettingsCodec.encode(record)) }

    private fun dashboardWithoutSettings(): Dashboard =
        DashboardFixtures.dashboard { put("settings", JSONObject.NULL) }

    private val writtenRecord = SettingsFixtures.parsed(revision = 4, areaId = "SE4", amps = 16)
    private val defaultedRecord = SettingsFixtures.parsed(revision = 0, areaId = null, phases = null, amps = null)

    private fun authoritative(record: HaPlanningSettings) = SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative(
        record,
        HaPlanningAdapter.of(record, listOf(RelayFixtures.se4), presentation)
    )

    @Test fun aWrittenRecordIsAuthoritative() {
        dashboards = { dashboard(writtenRecord) }

        val outcome = coordinator().reconcile(profileId, presentation, 1)

        val remote = outcome as SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative
        assertEquals(writtenRecord, remote.settings)
        assertTrue(remote.adaptation is HaPlanningInputs.Auto)
        assertEquals(listOf(profile), fetches)
        assertEquals(4, cache.confirmed(profileId)!!.revision)
    }

    // Home Assistant creates a defaulted record for a charger nobody has configured.
    @Test fun aDefaultedRecordAtRevisionZeroIsAdoptedNotSeeded() {
        dashboards = { dashboard(defaultedRecord) }

        val outcome = coordinator().reconcile(profileId, presentation, 1)

        assertEquals(authoritative(defaultedRecord), outcome)
        assertEquals(0, cache.confirmed(profileId)!!.revision)
    }

    @Test fun anUnreachableServerAndADashboardWithoutARecordAreBothUncheckable() {
        val coordinator = coordinator()

        reachable = false
        assertEquals(SettingsAuthorityCoordinator.Outcome.Unreachable(null), coordinator.reconcile(profileId, presentation, 1))

        // Reachable, but Home Assistant states no record: the revision cannot be checked either.
        reachable = true
        dashboards = { dashboardWithoutSettings() }
        assertEquals(
            SettingsAuthorityCoordinator.Outcome.Unreachable(null, answeredWithoutRecord = true),
            coordinator.reconcile(profileId, presentation, 2)
        )

        assertNull(cache.confirmed(profileId))
        // Two operations, two reads: one per reconcile, never more.
        assertEquals(2, fetches.size)
    }

    @Test fun anUnreachableServerMayShowACachedRecordButNeverEdits() {
        // First a reachable answer confirms a record, then the server goes away.
        dashboards = { dashboard(writtenRecord) }
        val coordinator = coordinator()
        coordinator.reconcile(profileId, presentation, 1)

        reachable = false
        val outcome = coordinator.reconcile(profileId, presentation, 2)

        assertEquals(SettingsAuthorityCoordinator.Outcome.Unreachable(writtenRecord), outcome)
        assertEquals(4, (outcome as SettingsAuthorityCoordinator.Outcome.Unreachable).lastConfirmed!!.revision)
    }

    @Test fun anAnswerForOneProfileNeverLandsOnAnother() {
        val recordForA = SettingsFixtures.parsed(revision = 7, areaId = "SE4", amps = 16)
        val recordForB = SettingsFixtures.parsed(revision = 3, areaId = "SE4", amps = 10)
        dashboards = { subject -> dashboard(if (subject.localId == profileId) recordForA else recordForB) }
        val coordinator = coordinator()

        val forA = coordinator.reconcile(profileId, presentation, 1)
        val forB = coordinator.reconcile(otherId, presentation, 1)

        assertEquals(7, (forA as SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative).settings.revision)
        assertEquals(3, (forB as SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative).settings.revision)
        assertEquals(7, cache.confirmed(profileId)!!.revision)
        assertEquals(3, cache.confirmed(otherId)!!.revision)
        assertEquals(forA, coordinator.published(profileId))
        assertEquals(forB, coordinator.published(otherId))
    }

    @Test fun aNewerGenerationKeepsItsResultWhenAnOlderAnswerArrivesLate() {
        val newerRecord = SettingsFixtures.parsed(revision = 2, areaId = "SE4", amps = 6)
        val olderRecord = SettingsFixtures.parsed(revision = 9, areaId = "SE4", amps = 32)
        lateinit var coordinator: SettingsAuthorityCoordinator
        var depth = 0
        var newer: SettingsAuthorityCoordinator.Outcome? = null
        dashboards = { _ ->
            val mine = depth + 1
            depth = mine
            if (mine == 1) newer = coordinator.reconcile(profileId, presentation, 2)
            dashboard(if (mine == 1) olderRecord else newerRecord)
        }
        coordinator = coordinator()
        reachable = true

        val older = coordinator.reconcile(profileId, presentation, 1)

        assertEquals("a late older answer is not the visible result", SettingsAuthorityCoordinator.Outcome.Superseded, older)
        assertEquals(authoritative(newerRecord), coordinator.published(profileId))
        // Not just the final publish.
        assertEquals("only the accepted operation's own observation was folded", 1, raw.writes.size)
        assertEquals(2, cache.confirmed(profileId)!!.revision)
        assertEquals(2, fetches.size)
    }

    @Test fun anAdmissionWaitsForACacheObservationAlreadyInProgress() {
        val key = "confirmed.$profileId"
        val store = blockingCache(key, occurrence = 1)
        val laterRecord = SettingsFixtures.parsed(revision = 7, areaId = "SE4", amps = 6)
        val fetchCalls = AtomicInteger(0)
        val writesSeenByTheNewerOperation = CopyOnWriteArrayList<List<String>>()
        dashboards = { _ ->
            val mine = fetchCalls.incrementAndGet()
            if (mine > 1) writesSeenByTheNewerOperation.add(store.writes.toList())
            dashboard(if (mine == 1) writtenRecord else laterRecord)
        }
        val coordinator = coordinator()
        var olderResult: SettingsAuthorityCoordinator.Outcome? = null
        var newerResult: SettingsAuthorityCoordinator.Outcome? = null

        val olderOperation = Thread { olderResult = coordinator.reconcile(profileId, presentation, 1) }
        olderOperation.start()
        assertTrue("the older observation should be parked in its cache write", store.entered.await(5, TimeUnit.SECONDS))

        val newerFinished = CountDownLatch(1)
        val newerOperation = Thread {
            newerResult = coordinator.reconcile(profileId, presentation, 2)
            newerFinished.countDown()
        }
        newerOperation.start()

        assertFalse(
            "a newer admission must wait for an in-progress cache mutation",
            newerFinished.await(200, TimeUnit.MILLISECONDS)
        )
        assertEquals("the newer operation must not have read a dashboard yet", 1, fetchCalls.get())
        assertTrue("a parked mutation is not a completed one", store.writes.isEmpty())

        store.mayProceed.countDown()
        olderOperation.join(5000)
        assertTrue("the newer operation should finish once released", newerFinished.await(5, TimeUnit.SECONDS))
        newerOperation.join(5000)

        // The order is defined.
        assertEquals(listOf(listOf(key)), writesSeenByTheNewerOperation)
        assertEquals(listOf(key, key), store.writes.toList())
        assertEquals(7, cache.confirmed(profileId)!!.revision)
        assertEquals(authoritative(laterRecord), newerResult)
        assertEquals(newerResult, coordinator.published(profileId))
        assertTrue(
            "the older operation finishes or is superseded, and is never both",
            olderResult == SettingsAuthorityCoordinator.Outcome.Superseded ||
                olderResult is SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative
        )
        assertEquals(2, fetchCalls.get())
    }

    @Test fun anEqualOrOlderGenerationDoesNoWorkAtAll() {
        var profileCalls = 0
        var catalogueCalls = 0
        profileSnapshot = { profileCalls += 1; listOf(profile, other) }
        catalogueSnapshot = { catalogueCalls += 1; listOf(RelayFixtures.se4) }
        dashboards = { dashboard(writtenRecord) }
        val coordinator = coordinator()
        coordinator.reconcile(profileId, presentation, 5)
        fetches.clear()
        val snapshotsAfterTheFirst = listOf(profileCalls, catalogueCalls)

        assertEquals(SettingsAuthorityCoordinator.Outcome.Superseded, coordinator.reconcile(profileId, presentation, 5))
        assertEquals(SettingsAuthorityCoordinator.Outcome.Superseded, coordinator.reconcile(profileId, presentation, 4))

        assertTrue("a superseded operation must not even read the charger", fetches.isEmpty())
        // Admission happens before the first snapshot provider is called, so a superseded operation
        // does not even look up the profile it would have used.
        assertEquals(
            "no snapshot provider may be called for a superseded generation",
            snapshotsAfterTheFirst,
            listOf(profileCalls, catalogueCalls)
        )
    }

    @Test fun anAdmittedNewerOperationStopsAnOlderOneBeforeItCanReadOrWrite() {
        // Generation 1 is admitted and paused inside its own dashboard read.
        val gen1InFetch = CountDownLatch(1)
        val gen2InFetch = CountDownLatch(1)
        val gen1MayAnswer = CountDownLatch(1)
        val gen2MayAnswer = CountDownLatch(1)
        val fetchCalls = AtomicInteger(0)
        dashboards = { _ ->
            when (fetchCalls.incrementAndGet()) {
                1 -> {
                    gen1InFetch.countDown()
                    gen1MayAnswer.await(5, TimeUnit.SECONDS)
                    dashboard(defaultedRecord)
                }
                else -> {
                    gen2InFetch.countDown()
                    gen2MayAnswer.await(5, TimeUnit.SECONDS)
                    dashboard(writtenRecord)
                }
            }
        }
        val coordinator = coordinator()
        var olderResult: SettingsAuthorityCoordinator.Outcome? = null
        var newerResult: SettingsAuthorityCoordinator.Outcome? = null

        val olderOperation = Thread { olderResult = coordinator.reconcile(profileId, presentation, 1) }
        olderOperation.start()
        assertTrue("generation 1 should reach its dashboard read", gen1InFetch.await(5, TimeUnit.SECONDS))

        val newerOperation = Thread { newerResult = coordinator.reconcile(profileId, presentation, 2) }
        newerOperation.start()
        assertTrue("generation 2 should reach its dashboard read", gen2InFetch.await(5, TimeUnit.SECONDS))
        assertNull("generation 2 has published nothing yet", coordinator.published(profileId))

        gen1MayAnswer.countDown()
        olderOperation.join(5000)

        assertEquals(SettingsAuthorityCoordinator.Outcome.Superseded, olderResult)
        assertTrue("a superseded operation must not touch the cache", raw.writes.isEmpty())
        assertNull("nothing older may be published", coordinator.published(profileId))
        assertNull(cache.confirmed(profileId))

        // And the newer operation still completes normally afterwards.
        gen2MayAnswer.countDown()
        newerOperation.join(5000)
        assertEquals(authoritative(writtenRecord), newerResult)
        assertEquals(4, cache.confirmed(profileId)!!.revision)
    }

    @Test fun aNewerGenerationForAnotherProfileNeverSupersedesThisOne() {
        val otherRecord = SettingsFixtures.parsed(revision = 3, areaId = "SE4", amps = 6)
        val inFetch = CountDownLatch(1)
        val mayAnswer = CountDownLatch(1)
        dashboards = { subject ->
            if (subject.localId == profileId) {
                inFetch.countDown()
                mayAnswer.await(5, TimeUnit.SECONDS)
            }
            dashboard(if (subject.localId == profileId) writtenRecord else otherRecord)
        }
        val coordinator = coordinator()
        var forA: SettingsAuthorityCoordinator.Outcome? = null

        val operationForA = Thread { forA = coordinator.reconcile(profileId, presentation, 1) }
        operationForA.start()
        assertTrue(inFetch.await(5, TimeUnit.SECONDS))

        // A much newer generation for B, admitted and published while A waits.
        val forB = coordinator.reconcile(otherId, presentation, 9)
        assertEquals(3, (forB as SettingsAuthorityCoordinator.Outcome.RemoteAuthoritative).settings.revision)

        mayAnswer.countDown()
        operationForA.join(5000)

        assertEquals(authoritative(writtenRecord), forA)
        assertEquals(4, cache.confirmed(profileId)!!.revision)
        assertEquals(3, cache.confirmed(otherId)!!.revision)
    }

    @Test fun theCapturedProfileIsUsedAndNeverReRead() {
        var profileCalls = 0
        profileSnapshot = { profileCalls += 1; if (profileCalls == 1) listOf(profile) else listOf(other) }
        dashboards = { dashboard(writtenRecord) }

        coordinator().reconcile(profileId, presentation, 1)

        assertEquals("the captured profile is what was asked, once", 1, profileCalls)
        assertEquals(listOf(profileId), fetches.map { it.localId })
        assertEquals(4, cache.confirmed(profileId)!!.revision)
        assertNull(cache.confirmed(otherId))
    }

    @Test fun anUnknownProfileIsNamedAndDoesNothing() {
        assertEquals(
            SettingsAuthorityCoordinator.Outcome.ProfileMissing,
            coordinator().reconcile("local-missing", presentation, 1)
        )
        assertTrue(fetches.isEmpty())
    }

    @Test fun noOutcomeAndNoCachedByteCarriesConnectionMaterial() {
        dashboards = { dashboard(SettingsFixtures.parsed(revision = 1, areaId = "SE4", target = SettingsFixtures.target(vehicleId = "vehicle-1"))) }

        val outcome = coordinator().reconcile(profileId, presentation, 1)
        val text = outcome.toString()
        val stored = raw.rawOrNull("confirmed.$profileId")!!

        assertFalse("the outcome carries the profile's webhook id", text.contains("webhook-secret-a"))
        assertFalse("the outcome carries the profile's address", text.contains("spotnav.example.invalid"))
        assertFalse("the cache carries the profile's webhook id", stored.contains("webhook-secret-a"))
        assertFalse("the cache carries the profile's address", stored.contains("spotnav.example.invalid"))
        // What *is* part of the canonical record travels: the target's vehicle.
        assertTrue("the canonical target must survive the round trip", stored.contains("vehicle-1"))
    }
}

/**
 * A [KeyValueStore] that remembers every **completed** mutation, and can park one of them on a
 * latch.
 */
private class BlockingRecordingKeyValueStore(
    private val blockedKey: String? = null,
    private val blockOnOccurrence: Int = 1
) : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    private val matches = AtomicInteger(0)

    /** Every key written or removed, in order, once that mutation has completed. */
    val writes = CopyOnWriteArrayList<String>()

    /** Counts down when the blocked mutation has been entered. */
    val entered = CountDownLatch(1)

    /** The test counts this down to let the blocked mutation finish. */
    val mayProceed = CountDownLatch(1)

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        parkIfBlocked(key)
        writes.add(key)
        values[key] = value
    }

    override fun remove(key: String) {
        parkIfBlocked(key)
        writes.add(key)
        values.remove(key)
    }

    private fun parkIfBlocked(key: String) {
        if (key != blockedKey) return
        if (matches.incrementAndGet() != blockOnOccurrence) return
        entered.countDown()
        assertTrue("the parked cache mutation was never released", mayProceed.await(5, TimeUnit.SECONDS))
    }

    /** Lets a test read back a raw stored document. */
    fun rawOrNull(key: String): String? = values[key]
}
