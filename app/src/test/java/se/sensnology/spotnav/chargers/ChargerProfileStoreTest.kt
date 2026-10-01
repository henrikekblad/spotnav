package se.sensnology.spotnav.chargers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.testing.FakeKeyValueStore

class ChargerProfileStoreTest {
    // android.util.Log isn't available (or mocked) under a plain JVM unit test; every store in this
    // file is built through here so none of them depend on it, matching ChargerProfileStore's
    // injectable logWarning.
    private fun noOpLogger(): (String) -> Unit = {}

    private fun store(backing: FakeKeyValueStore = FakeKeyValueStore()): ChargerProfileStore =
        ChargerProfileStore(backing, noOpLogger())

    /** A dashboard as Home Assistant's fixture states it, with only the facts a test names changed. */
    private fun dashboard(
        chargerId: String = "ha-entry-123",
        chargerName: String? = "Garage charger",
        detectedPhases: Int? = null,
        phaseSource: String = "unknown",
        phaseConfidence: String = "none"
    ): Dashboard = DashboardFixtures.dashboard {
        getJSONObject("charger").put("charger_id", chargerId).put("charger_name", chargerName ?: org.json.JSONObject.NULL)
        put("detected_phases", detectedPhases ?: org.json.JSONObject.NULL)
        put("phase_detection", org.json.JSONObject().put("source", phaseSource).put("confidence", phaseConfidence))
    }

    @Test fun emptyInstallHasNoProfilesAndNoActiveId() {
        val store = store()
        assertTrue(store.listProfiles().isEmpty())
        assertNull(store.getActiveProfileId())
        assertNull(store.getActiveProfile())
    }

    @Test fun twoProfilesCanBeSavedAndReadWithoutAffectingEachOther() {
        val store = store()
        val a = ChargerProfile("a", "Charger A", "https://a.example.com", "hook-a")
        val b = ChargerProfile("b", "Charger B", "https://b.example.com", "hook-b")
        store.upsertProfile(a)
        store.upsertProfile(b)

        assertEquals(a, store.getProfile("a"))
        assertEquals(b, store.getProfile("b"))
        assertEquals(2, store.listProfiles().size)

        store.upsertProfile(a.copy(displayName = "Renamed A"))

        assertEquals("Renamed A", store.getProfile("a")?.displayName)
        assertEquals(b, store.getProfile("b"))
    }

    @Test fun activeProfileCanBeChangedAndReadBackCorrectly() {
        val store = store()
        store.upsertProfile(ChargerProfile("a", "Charger A", "https://a.example.com", "hook-a"))
        store.upsertProfile(ChargerProfile("b", "Charger B", "https://b.example.com", "hook-b"))

        store.setActiveProfileId("a")
        assertEquals("a", store.getActiveProfileId())
        assertEquals("Charger A", store.getActiveProfile()?.displayName)

        store.setActiveProfileId("b")
        assertEquals("b", store.getActiveProfileId())
        assertEquals("Charger B", store.getActiveProfile()?.displayName)
    }

    @Test fun removingTheActiveProfileClearsTheActiveIdRatherThanReassigningIt() {
        val store = store()
        store.upsertProfile(ChargerProfile("a", "Charger A", "https://a.example.com", "hook-a"))
        store.upsertProfile(ChargerProfile("b", "Charger B", "https://b.example.com", "hook-b"))
        store.setActiveProfileId("a")

        store.removeProfile("a")

        // Documented deterministic result: removing the active profile always clears the active
        // pointer to null, even though "b" still exists.
        assertNull(store.getActiveProfileId())
        assertNull(store.getActiveProfile())
        assertEquals(listOf("b"), store.listProfiles().map { it.localId })
    }

    @Test fun removingAnInactiveProfileLeavesTheActiveIdUnchanged() {
        val store = store()
        store.upsertProfile(ChargerProfile("a", "Charger A", "https://a.example.com", "hook-a"))
        store.upsertProfile(ChargerProfile("b", "Charger B", "https://b.example.com", "hook-b"))
        store.setActiveProfileId("a")

        store.removeProfile("b")

        assertEquals("a", store.getActiveProfileId())
    }

    @Test fun oneCorruptProfileEntryDoesNotDestroyTheOthers() {
        val backing = FakeKeyValueStore()
        val store = store(backing)
        store.upsertProfile(ChargerProfile("a", "Charger A", "https://a.example.com", "hook-a"))
        store.upsertProfile(ChargerProfile("b", "Charger B", "https://b.example.com", "hook-b"))

        // Hand-corrupt the raw JSON in place: replace the first profile entry with something that
        // isn't a JSON object at all.
        val raw = org.json.JSONObject(backing.rawOrNull("state")!!)
        val profiles = raw.getJSONArray("profiles")
        profiles.put(0, "not-an-object")
        backing.setRaw("state", raw.toString())

        val recovered = store.listProfiles()

        assertEquals(listOf("b"), recovered.map { it.localId })
    }

    @Test fun entirelyUnreadableStoreIsTreatedAsEmptyRatherThanCrashing() {
        val backing = FakeKeyValueStore().apply { setRaw("state", "{not valid json at all") }
        val store = store(backing)

        assertTrue(store.listProfiles().isEmpty())
        assertNull(store.getActiveProfileId())
    }

    @Test fun baseUrlIsNormalizedOnDirectUpsertOnlyWhenTheCallerNormalizesIt() {
        // upsertProfile stores exactly what it is given (HomeAssistantSettings.save is what
        // normalizes before constructing a ChargerProfile); this documents that contract rather
        // than silently re-normalizing here.
        val store = store()
        store.upsertProfile(ChargerProfile("a", "Charger A", "https://a.example.com", "hook-a"))
        assertEquals("https://a.example.com", store.getProfile("a")?.baseUrl)
    }

    @Test fun updatingFromTheDashboardKeepsLocalIdWebhookAndLocalDisplayName() {
        val store = store()
        store.upsertProfile(
            ChargerProfile(
                localId = "a",
                displayName = "My kitchen charger",
                baseUrl = "https://a.example.com",
                webhookId = "hook-a",
                selectedVehicleId = "vehicle-one",
                targetSocPercent = 70
            )
        )

        val updated = store.updateFromDashboard(
            "a",
            dashboard(
                chargerId = "ha-entry-123",
                chargerName = "Garage charger",
                detectedPhases = 3,
                phaseSource = "voltage_attributes",
                phaseConfidence = "high"
            )
        )

        assertNotNull(updated)
        assertEquals("a", updated!!.localId)
        assertEquals("hook-a", updated.webhookId)
        assertEquals("https://a.example.com", updated.baseUrl)
        assertEquals("My kitchen charger", updated.displayName) // never overwritten
        // The two vehicle choices are the user's own, exactly like the display name: a dashboard
        // reports vehicles, it never chooses one.
        assertEquals("vehicle-one", updated.selectedVehicleId)
        assertEquals(70, updated.targetSocPercent)
        assertEquals("ha-entry-123", updated.remoteChargerId)
        assertEquals("Garage charger", updated.remoteChargerName)
        assertEquals(3, updated.detectedPhases)
        assertEquals("voltage_attributes", updated.phaseDetectionSource)
        assertEquals("high", updated.phaseDetectionConfidence)
        assertEquals(store.getProfile("a"), updated)
    }

    @Test fun updatingFromTheDashboardNeverErasesPreviouslyKnownMetadataWithANullField() {
        val store = store()
        store.upsertProfile(
            ChargerProfile(
                localId = "a",
                displayName = "Charger A",
                baseUrl = "https://a.example.com",
                webhookId = "hook-a",
                remoteChargerName = "Already known name",
                detectedPhases = 3,
                phaseDetectionSource = "voltage_attributes",
                phaseDetectionConfidence = "high"
            )
        )

        // A dashboard with no name and nothing detected yet.
        val updated = store.updateFromDashboard("a", dashboard(chargerName = null, detectedPhases = null))

        assertEquals("Already known name", updated?.remoteChargerName)
        assertEquals(3, updated?.detectedPhases)
        assertEquals("unknown", updated?.phaseDetectionSource)
    }

    @Test fun aRenameInTheDashboardReachesTheStoredProfileAndABlankNameDoesNot() {
        val store = store()
        store.upsertProfile(ChargerProfile("a", "Charger", "https://a.example.com", "hook-a", remoteChargerName = "halo_charger Connector 1"))

        store.applyDashboardResult("a", Result.success(dashboard(chargerName = "HALO Charger")))
        assertEquals("HALO Charger", store.getProfile("a")?.remoteChargerName)

        store.applyDashboardResult("a", Result.success(dashboard(chargerName = "  ")))
        assertEquals("HALO Charger", store.getProfile("a")?.remoteChargerName)
    }

    @Test fun updatingFromTheDashboardForAnUnknownProfileReturnsNull() {
        assertNull(store().updateFromDashboard("does-not-exist", dashboard()))
    }

    // --- applyDashboardResult: explicit profile id, never the active one at completion time ---

    @Test fun aDashboardForProfileAUpdatesOnlyAEvenWhenActiveProfileChangesToBFirst() {
        val store = store()
        store.upsertProfile(ChargerProfile("a", "Charger A", "https://a.example.com", "hook-a"))
        store.upsertProfile(ChargerProfile("b", "Charger B", "https://b.example.com", "hook-b"))
        store.setActiveProfileId("a")
        // The request for "a" is already in flight; before its response is handled, the user (or
        // another part of the app) switches the active profile to "b".
        store.setActiveProfileId("b")

        store.applyDashboardResult("a", Result.success(dashboard(chargerId = "remote-a", detectedPhases = 1)))

        assertEquals("remote-a", store.getProfile("a")?.remoteChargerId)
        assertEquals(1, store.getProfile("a")?.detectedPhases)
        // "b" — the profile that was active when the response arrived — must be completely
        // untouched.
        assertNull(store.getProfile("b")?.remoteChargerId)
        assertNull(store.getProfile("b")?.detectedPhases)
        assertEquals("b", store.getActiveProfileId())
    }

    @Test fun aFailedDashboardResultNeverChangesStoredMetadata() {
        val store = store()
        store.upsertProfile(
            ChargerProfile(
                localId = "a",
                displayName = "Charger A",
                baseUrl = "https://a.example.com",
                webhookId = "hook-a",
                remoteChargerId = "already-known-id"
            )
        )

        store.applyDashboardResult("a", Result.failure(IllegalStateException("HTTP 500")))

        assertEquals("already-known-id", store.getProfile("a")?.remoteChargerId)
    }

    // --- Concurrency ---

    @Test fun concurrentUpsertsToDifferentProfilesNeverLoseEitherOnesLatestUpdate() {
        val store = store()
        store.upsertProfile(ChargerProfile("a", "A0", "https://a.example.com", "hook-a"))
        store.upsertProfile(ChargerProfile("b", "B0", "https://b.example.com", "hook-b"))

        val iterations = 200
        val threadA = Thread {
            for (i in 1..iterations) {
                store.upsertProfile(ChargerProfile("a", "A$i", "https://a.example.com", "hook-a"))
            }
        }
        val threadB = Thread {
            for (i in 1..iterations) {
                store.upsertProfile(ChargerProfile("b", "B$i", "https://b.example.com", "hook-b"))
            }
        }
        threadA.start()
        threadB.start()
        threadA.join()
        threadB.join()

        // Deterministic once both threads have joined: without the shared lock around each
        // read-modify-write, this reliably loses updates (either the other profile disappears
        // entirely, or its display name isn't the last one written) under real thread interleaving.
        assertEquals(2, store.listProfiles().size)
        assertEquals("A$iterations", store.getProfile("a")?.displayName)
        assertEquals("B$iterations", store.getProfile("b")?.displayName)
    }

    @Test fun concurrentDashboardUpdatesToTheSameProfileNeverLoseAFieldToAnInterleavedWrite() {
        val store = store()
        store.upsertProfile(ChargerProfile("a", "Charger A", "https://a.example.com", "hook-a"))

        val iterations = 200
        // Two different kinds of concurrent writers to the SAME profile: one repeatedly applying a
        // dashboard result (merges fields), the other repeatedly renaming it.
        val statusThread = Thread {
            for (i in 1..iterations) {
                store.applyDashboardResult("a", Result.success(dashboard(detectedPhases = (i % 3) + 1)))
            }
        }
        val renameThread = Thread {
            for (i in 1..iterations) {
                store.updateProfile("a") { it.copy(displayName = "Renamed $i") }
            }
        }
        statusThread.start()
        renameThread.start()
        statusThread.join()
        renameThread.join()

        val finalProfile = store.getProfile("a")
        assertNotNull(finalProfile)
        assertEquals("hook-a", finalProfile!!.webhookId)
        assertTrue(finalProfile.displayName.startsWith("Renamed "))
        assertNotNull(finalProfile.detectedPhases)
    }

    @Test fun getProfileThenUpsertProfileAcrossTwoSeparateCallsCanLoseAConcurrentField() {
        // It is intentionally not asserting a specific outcome — the point is that this pattern is
        // unsafe, not that it fails in one particular way every run.
        val store = store()
        store.upsertProfile(ChargerProfile("a", "Charger A", "https://a.example.com", "hook-a"))

        val iterations = 200
        val statusThread = Thread {
            for (i in 1..iterations) {
                store.applyDashboardResult("a", Result.success(dashboard(detectedPhases = (i % 3) + 1)))
            }
        }
        val unsafeRenameThread = Thread {
            for (i in 1..iterations) {
                val current = store.getProfile("a")
                if (current != null) {
                    store.upsertProfile(current.copy(displayName = "Renamed $i"))
                }
            }
        }
        statusThread.start()
        unsafeRenameThread.start()
        statusThread.join()
        unsafeRenameThread.join()

        // Only asserting the store itself never corrupts (still exactly one profile, with its
        // identity intact) — not that no field was lost, since losing one is exactly the
        // documented, expected risk of this pattern.
        assertEquals(1, store.listProfiles().size)
        assertEquals("hook-a", store.getProfile("a")?.webhookId)
    }

    /**
     * The user's vehicle choice and target are persisted per profile like every other profile
     * field: a fresh store over the same storage (an app restart) reads them back, so neither is
     * lost to a reload -- or to a later dashboard read, which reports vehicles but never chooses
     * one.
     */
    @Test fun selectedVehicleAndTargetSocSurviveAStoreReload() {
        val backing = FakeKeyValueStore()
        store(backing).upsertProfile(
            ChargerProfile(
                localId = "a",
                displayName = "Charger A",
                baseUrl = "https://a.example.com",
                webhookId = "hook-a",
                selectedVehicleId = "vehicle-one",
                targetSocPercent = 65
            )
        )

        val reloaded = store(backing).getProfile("a")

        assertEquals("vehicle-one", reloaded?.selectedVehicleId)
        assertEquals(65, reloaded?.targetSocPercent)
    }

    /** Both fields default to "nothing chosen yet", which is a normal state. */
    @Test fun aProfileWithoutAVehicleChoiceRoundTripsBothFieldsAsNull() {
        val backing = FakeKeyValueStore()
        store(backing).upsertProfile(
            ChargerProfile("a", "Charger A", "https://a.example.com", "hook-a")
        )

        val reloaded = store(backing).getProfile("a")

        assertNull(reloaded?.selectedVehicleId)
        assertNull(reloaded?.targetSocPercent)
    }
}
