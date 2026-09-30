package se.sensnology.spotnav.testmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Entering and leaving test mode. */
class TestModePlanTest {
    private val servers = listOf(
        MockCatalogue.Server("http://192.168.1.50:8099", listOf(
            MockCatalogue.Scenario("full", "Everything works", "the ordinary screen"),
            MockCatalogue.Scenario("nostop", "No stop", "the card without a stop button")
        )),
        MockCatalogue.Server("http://192.168.1.51:8099", listOf(
            MockCatalogue.Scenario("full", "Second machine", "two chargers side by side")
        ))
    )

    private var nextId = 0
    private val newId: () -> String = { "test-${nextId++}" }

    // the re-entry guard

    @Test fun enteringActivatesEveryScenarioEveryServerOffered() {
        nextId = 0
        val outcome = TestModePlan.enter(snapshotPresent = false, servers = servers, newLocalId = newId)

        val activated = (outcome as TestModePlan.Enter.Activate)
        assertEquals(3, activated.profiles.size)
        assertEquals("http://192.168.1.50:8099", activated.profiles[0].baseUrl)
        assertEquals("full", activated.profiles[0].webhookId)
        assertEquals("Everything works", activated.profiles[0].displayName)
        // And the widget that asked is bound to it: activating profiles without rebinding leaves
        // the widget pointing at a profile that was just deleted, which reads as "no Home Assistant
        // configured".
        assertEquals(activated.profiles[0].localId, activated.boundProfileId)
    }

    @Test fun enteringWhileAlreadyInTestModeSnapshotsNothing() {
        // The guard. No profiles come back at all, so a caller has nothing it could snapshot in
        // place of the real ones.
        val outcome = TestModePlan.enter(snapshotPresent = true, servers = servers, newLocalId = newId)

        assertEquals(TestModePlan.Enter.AlreadyActive, outcome)
    }

    @Test fun theGuardHoldsWhateverTheServersOffer() {
        for (offered in listOf(emptyList<MockCatalogue.Server>(), servers)) {
            assertEquals(
                TestModePlan.Enter.AlreadyActive,
                TestModePlan.enter(snapshotPresent = true, servers = offered, newLocalId = newId)
            )
        }
    }

    @Test fun leavingNeedsSomethingToRestore() {
        assertTrue(TestModePlan.canLeave(snapshotPresent = true))
        assertFalse(TestModePlan.canLeave(snapshotPresent = false))
    }

    @Test fun enteringNeedsNothingStored() {
        assertTrue(TestModePlan.canEnter(snapshotPresent = false))
        assertFalse(TestModePlan.canEnter(snapshotPresent = true))
    }

    // what test mode activates

    @Test fun oneProfilePerScenarioOnEveryServer() {
        val profiles = TestModePlan.profiles(servers, newId)

        assertEquals(3, profiles.size)
        assertEquals(listOf("full", "nostop", "full"), profiles.map { it.webhookId })
        assertEquals(
            listOf("http://192.168.1.50:8099", "http://192.168.1.50:8099", "http://192.168.1.51:8099"),
            profiles.map { it.baseUrl }
        )
        // Fresh, distinct local ids: these are profiles of their own, not stand-ins carrying a real
        // profile's identity.
        assertEquals(3, profiles.map { it.localId }.toSet().size)
        assertTrue(profiles.all { it.configured })
    }

    @Test fun twoServersOfferingTheSameWebhookIdStayApart() {
        // The same scenario name on two machines is two chargers, which is itself a layout worth
        // looking at, and the charger card lists both.
        val profiles = TestModePlan.profiles(servers.subList(0, 1) + servers.subList(1, 2), newId)

        assertEquals(3, profiles.size)
        assertEquals(
            setOf("http://192.168.1.50:8099", "http://192.168.1.51:8099"),
            profiles.map { it.baseUrl }.toSet()
        )
    }

    @Test fun aProfileCarriesNothingButTheScenario() {
        val profile = TestModePlan.profiles(servers, newId).first()

        // Nothing detected, nothing chosen: the screens that show those states are the ones test
        // mode exists to reach.
        assertEquals(null, profile.selectedVehicleId)
        assertEquals(null, profile.targetSocPercent)
        assertEquals(null, profile.remoteChargerId)
        assertEquals(null, profile.detectedPhases)
    }

    @Test fun enteringBindsTheWidgetToTheFirstActivatedProfile() {
        val outcome = TestModePlan.enter(snapshotPresent = false, servers = servers, newLocalId = newId)

        val activated = (outcome as TestModePlan.Enter.Activate)
        assertEquals(activated.profiles[0].localId, activated.boundProfileId)
    }

    @Test fun theFirstActivatedProfileIsTheActiveOne() {
        val profiles = TestModePlan.profiles(servers, newId)

        assertEquals(profiles[0].localId, TestModePlan.activeProfileId(profiles))
    }
}
