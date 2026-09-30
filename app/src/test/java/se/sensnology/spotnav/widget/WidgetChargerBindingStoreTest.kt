package se.sensnology.spotnav.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.FakeKeyValueStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier

class WidgetChargerBindingStoreTest {
    private fun store(backing: FakeKeyValueStore = FakeKeyValueStore()): WidgetChargerBindingStore =
        WidgetChargerBindingStore(backing)

    private fun rawKey(appWidgetId: Int) = "$appWidgetId.chargerBinding"

    @Test fun twoWidgetIdsCanBindToTwoDifferentProfiles() {
        val store = store()

        val bindingOne = store.binding(1) { "profile-a" }
        val bindingTwo = store.binding(2) { "profile-b" }

        assertEquals("profile-a", bindingOne.chargerProfileId)
        assertEquals("profile-b", bindingTwo.chargerProfileId)
    }

    @Test fun savingWidgetANeverChangesWidgetB() {
        val store = store()
        store.setBinding(1, "profile-a")
        store.setBinding(2, "profile-b")

        store.setBinding(1, "profile-a-renewed")

        assertEquals("profile-a-renewed", store.binding(1) { null }.chargerProfileId)
        assertEquals("profile-b", store.binding(2) { null }.chargerProfileId)
    }

    @Test fun changingTheGlobalActiveProfileDoesNotChangeAnExistingBinding() {
        val store = store()
        val first = store.binding(1) { "profile-a" } // migrates/defaults to "profile-a"
        assertEquals("profile-a", first.chargerProfileId)

        val second = store.binding(1) { "profile-b" }

        assertEquals("profile-a", second.chargerProfileId)
    }

    @Test fun aNewWidgetDefaultsToTheCurrentActiveProfile() {
        val store = store()

        val binding = store.binding(42) { "active-profile" }

        assertTrue(binding.initialized)
        assertEquals("active-profile", binding.chargerProfileId)
    }

    @Test fun aLegacyWidgetReceivesTheLazyMigrationExactlyOnce() {
        val store = store()
        var lookups = 0
        fun activeProfileId(): String? {
            lookups++
            return "profile-a"
        }

        val first = store.binding(1, ::activeProfileId)
        val second = store.binding(1, ::activeProfileId)

        assertEquals(1, lookups) // the active-profile lookup ran only for the first, migrating call
        assertEquals(first, second)
        assertEquals("profile-a", second.chargerProfileId)
    }

    @Test fun aWidgetRemainsUnboundIfMigrationRunsWithNoActiveProfile() {
        val store = store()

        val binding = store.binding(1) { null }

        assertTrue(binding.initialized)
        assertNull(binding.chargerProfileId)
    }

    @Test fun explicitNoChargerDoesNotLaterFallBackToAnActiveProfile() {
        val store = store()
        store.setBinding(1, null) // user explicitly chose "No charger"

        val binding = store.binding(1) { "profile-a" } // some profile is now active

        assertTrue(binding.initialized)
        assertNull(binding.chargerProfileId) // never silently re-bound
    }

    @Test fun migratingToNoActiveProfileAlsoNeverLaterFallsBackToOneThatAppearsLater() {
        val store = store()
        store.binding(1) { null } // migrates: no active profile existed yet

        val binding = store.binding(1) { "profile-a" } // a profile becomes active later

        assertNull(binding.chargerProfileId)
    }

    @Test fun removingAWidgetsBindingDeletesOnlyThatWidgets() {
        val store = store()
        store.setBinding(1, "profile-a")
        store.setBinding(2, "profile-b")

        store.removeBinding(1)

        assertEquals("profile-b", store.binding(2) { "should-not-be-used" }.chargerProfileId)
    }

    @Test fun removingAWidgetsBindingClearsBothInitializedAndProfileId() {
        val store = store()
        store.setBinding(1, "profile-a")

        store.removeBinding(1)
        var lookedUp = false
        val binding = store.binding(1) {
            lookedUp = true
            "profile-a-again"
        }

        assertTrue(lookedUp)
        assertEquals("profile-a-again", binding.chargerProfileId)
    }


    @Test fun undecidedIsRepresentedByAnAbsentKeyNotATwoKeyPair() {
        val backing = FakeKeyValueStore()
        val store = store(backing)

        // Never read or written at all: nothing is stored yet.
        assertNull(backing.rawOrNull(rawKey(1)))

        store.binding(1) { null }
        assertEquals("none", backing.rawOrNull(rawKey(1)))
    }

    @Test fun explicitlyNoChargerIsOneKeyValuedNone() {
        val backing = FakeKeyValueStore()
        val store = store(backing)

        store.setBinding(1, null)

        assertEquals("none", backing.rawOrNull(rawKey(1)))
    }

    @Test fun aBoundProfileIsOneKeyEncodingTheLocalId() {
        val backing = FakeKeyValueStore()
        val store = store(backing)

        store.setBinding(1, "11111111-1111-1111-1111-111111111111")

        assertEquals("profile:11111111-1111-1111-1111-111111111111", backing.rawOrNull(rawKey(1)))
    }

    @Test fun aMalformedStoredValueDecodesToDecidedNoChargerNotToAGuessedProfile() {
        val backing = FakeKeyValueStore()
        val store = store(backing)

        val malformedValues = listOf(
            "profile:", // prefix with nothing after it
            "profile",  // missing the colon entirely
            "PROFILE:some-id", // wrong case
            "garbage",
            ""
        )

        malformedValues.forEach { malformed ->
            backing.setRaw(rawKey(1), malformed)
            val binding = store.binding(1) { "should-never-be-used" }

            assertTrue("expected initialized=true for raw value '$malformed'", binding.initialized)
            assertNull("expected no profile id for raw value '$malformed'", binding.chargerProfileId)
        }
    }

    @Test fun aValidEncodedProfileIsDecodedExactly() {
        val backing = FakeKeyValueStore()
        val store = store(backing)
        backing.setRaw(rawKey(1), "profile:abc-123")

        val binding = store.binding(1) { "should-never-be-used" }

        assertTrue(binding.initialized)
        assertEquals("abc-123", binding.chargerProfileId)
    }

    @Test fun removalDeletesTheSingleKeyEntirely() {
        val backing = FakeKeyValueStore()
        val store = store(backing)
        store.setBinding(1, "profile-a")
        assertEquals("profile:profile-a", backing.rawOrNull(rawKey(1)))

        store.removeBinding(1)

        assertNull(backing.rawOrNull(rawKey(1)))
    }

    @Test fun noSecretEverAppearsInTheEncodedValue() {
        val backing = FakeKeyValueStore()
        val store = store(backing)

        store.setBinding(1, "11111111-1111-1111-1111-111111111111")

        val raw = backing.rawOrNull(rawKey(1)).orEmpty()
        assertFalse(raw.contains("https://"))
        assertFalse(raw.contains("webhook"))
    }


    @Test fun twoSimultaneousLazyInitializationsAgreeOnOneAuthoritativeDecision() {
        val backing = FakeKeyValueStore()
        val storeA = WidgetChargerBindingStore(backing)
        val storeB = WidgetChargerBindingStore(backing)
        val barrier = CyclicBarrier(2)
        var resultA: WidgetChargerBinding? = null
        var resultB: WidgetChargerBinding? = null

        val threadA = Thread {
            resultA = storeA.binding(1) {
                barrier.await()
                "profile-alpha"
            }
        }
        val threadB = Thread {
            resultB = storeB.binding(1) {
                barrier.await()
                "profile-beta"
            }
        }
        threadA.start()
        threadB.start()
        threadA.join()
        threadB.join()

        // Only one candidate lambda's result became authoritative...
        assertTrue(resultA?.chargerProfileId in setOf("profile-alpha", "profile-beta"))
        // ...and both callers, and everything read afterwards, agree on which one.
        assertEquals(resultA, resultB)
        assertEquals(resultA, storeA.binding(1) { "should-not-be-used" })
    }

    @Test fun setBindingRacingWithLazyInitializationIsNeverOverwrittenByAStaleLazyCandidate() {
        val backing = FakeKeyValueStore()
        val storeA = WidgetChargerBindingStore(backing)
        val storeB = WidgetChargerBindingStore(backing)
        val lazyIsEvaluatingCandidate = CountDownLatch(1)
        val explicitSetHasCompleted = CountDownLatch(1)
        var lazyResult: WidgetChargerBinding? = null

        val lazyThread = Thread {
            lazyResult = storeA.binding(1) {
                lazyIsEvaluatingCandidate.countDown()
                explicitSetHasCompleted.await()
                "stale-lazy-candidate"
            }
        }
        lazyThread.start()
        lazyIsEvaluatingCandidate.await()

        storeB.setBinding(1, "explicit-winner")
        explicitSetHasCompleted.countDown()
        lazyThread.join()

        assertEquals("explicit-winner", lazyResult?.chargerProfileId)
        assertEquals("explicit-winner", storeB.binding(1) { "should-not-be-used" }.chargerProfileId)
    }

    @Test fun removeBindingLeavesTheKeyAbsentAndAllowsALaterInitializationFromAnotherInstance() {
        val backing = FakeKeyValueStore()
        val storeA = WidgetChargerBindingStore(backing)
        val storeB = WidgetChargerBindingStore(backing)
        storeA.setBinding(1, "profile-a")

        storeA.removeBinding(1)

        assertNull(backing.rawOrNull(rawKey(1)))
        val binding = storeB.binding(1) { "profile-fresh" }
        assertTrue(binding.initialized)
        assertEquals("profile-fresh", binding.chargerProfileId)
    }

    @Test fun anAlreadyInitializedBindingNeverEvaluatesTheActiveProfileLambdaFromAnotherInstance() {
        val backing = FakeKeyValueStore()
        val storeA = WidgetChargerBindingStore(backing)
        val storeB = WidgetChargerBindingStore(backing)
        storeA.setBinding(1, "profile-a")

        var lookedUp = false
        val binding = storeB.binding(1) {
            lookedUp = true
            "should-never-be-used"
        }

        assertFalse(lookedUp)
        assertEquals("profile-a", binding.chargerProfileId)
    }

    @Test fun removeBindingRacingWithLazyInitializationIsNeverResurrectedByTheStaleLazyCandidate() {
        val backing = FakeKeyValueStore()
        val storeA = WidgetChargerBindingStore(backing)
        val storeB = WidgetChargerBindingStore(backing)
        val lazyIsEvaluatingCandidate = CountDownLatch(1)
        val removeHasCompleted = CountDownLatch(1)
        var lazyResult: WidgetChargerBinding? = null

        val lazyThread = Thread {
            lazyResult = storeA.binding(1) {
                lazyIsEvaluatingCandidate.countDown()
                removeHasCompleted.await()
                "stale-lazy-candidate"
            }
        }
        lazyThread.start()
        lazyIsEvaluatingCandidate.await()

        storeB.removeBinding(1)
        assertNull(backing.rawOrNull(rawKey(1))) // the removal itself completes with the key absent

        removeHasCompleted.countDown()
        lazyThread.join()

        assertNull(backing.rawOrNull(rawKey(1)))
        assertFalse(lazyResult?.initialized ?: true)
        assertNull(lazyResult?.chargerProfileId)

        // A later, genuinely new read for the same widget id initializes normally.
        val fresh = storeB.binding(1) { "profile-fresh" }
        assertTrue(fresh.initialized)
        assertEquals("profile-fresh", fresh.chargerProfileId)
    }

    @Test fun removingWidgetADoesNotInvalidateAnInFlightInitializationForWidgetB() {
        val backing = FakeKeyValueStore()
        val storeA = WidgetChargerBindingStore(backing)
        val storeB = WidgetChargerBindingStore(backing)
        val widgetBIsEvaluatingCandidate = CountDownLatch(1)
        val widgetARemovalHasCompleted = CountDownLatch(1)
        var widgetBResult: WidgetChargerBinding? = null
        storeA.setBinding(1, "profile-a") // widget A has an existing binding to remove

        val widgetBThread = Thread {
            widgetBResult = storeB.binding(2) {
                widgetBIsEvaluatingCandidate.countDown()
                widgetARemovalHasCompleted.await()
                "profile-b"
            }
        }
        widgetBThread.start()
        widgetBIsEvaluatingCandidate.await()

        storeA.removeBinding(1)
        widgetARemovalHasCompleted.countDown()
        widgetBThread.join()

        assertTrue(widgetBResult?.initialized ?: false)
        assertEquals("profile-b", widgetBResult?.chargerProfileId)
        assertNull(backing.rawOrNull(rawKey(1))) // widget A stays removed, unaffected by widget B's init
    }
}
