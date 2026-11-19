package se.sensnology.spotnav.sites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.chargers.PairAgain
import se.sensnology.spotnav.chargers.toHomeAssistantSettings
import se.sensnology.spotnav.chargers.ownWebhook
import se.sensnology.spotnav.ha.client.HomeAssistantSettings
import se.sensnology.spotnav.ha.client.WebhookGoneException
import se.sensnology.spotnav.ha.pairing.SiteCharger
import se.sensnology.spotnav.testing.FakeKeyValueStore

class SiteProfileStoreTest {
    private fun store(backing: FakeKeyValueStore = FakeKeyValueStore()) = SiteProfileStore(backing) {}

    private val home = SiteProfile("l1", "https://ha", "site-secret", "site_a", "Home", listOf(SiteCharger("c1", "Garage")))

    @Test fun aHomeIsKeptWithItsChargersAndReadBackByAFreshStore() {
        val backing = FakeKeyValueStore()
        store(backing).upsert(home)
        store(backing).setActiveSiteId("l1")
        val again = store(backing)
        assertEquals(listOf(home), again.list())
        assertEquals(home, again.find("https://ha", "site_a"))
        assertNull(again.find("https://other", "site_a"))
        assertEquals("l1", again.activeSiteId())
        again.remove("l1")
        assertTrue(again.list().isEmpty())
        assertNull(again.activeSiteId())
    }

    @Test fun anUnknownHomeCannotBeShownAndAnUnreadableEntryIsSkipped() {
        val store = store()
        assertTrue(runCatching { store.setActiveSiteId("nope") }.isFailure)
        val backing = FakeKeyValueStore()
        backing.putString("state", """{"sites":[{"local_id":"x"},{"local_id":"l2","site_id":"s2","base_url":"https://ha","webhook_id":"w"}]}""")
        assertEquals(listOf("l2"), store(backing).list().map { it.localId })
        backing.putString("state", "not json")
        assertTrue(store(backing).list().isEmpty())
    }

    @Test fun aGoneWebhookMarksTheHomeAndAnAnswerClearsIt() {
        val store = store()
        store.upsert(home)
        store.recordWebhookAnswer("l1", WebhookGoneException(), now = 1_000)
        assertTrue(store.get("l1")!!.needsPairing)
        // A marked home is asked only at the slow pace.
        assertFalse(store.claimRead("l1", now = 2_000))
        assertTrue(store.claimRead("l1", now = 1_000 + PairAgain.SLOW_CHECK_MS))
        // Offline says nothing; an answer clears it.
        store.recordWebhookAnswer("l1", java.io.IOException(), now = 3_000)
        assertTrue(store.get("l1")!!.needsPairing)
        store.recordWebhookAnswer("l1", null, now = 4_000)
        assertFalse(store.get("l1")!!.needsPairing)
    }

    @Test fun neverPrintsTheWebhook() {
        assertFalse(home.toString().contains("site-secret"))
        assertEquals(HomeAssistantSettings("https://ha", "site-secret"), home.toHomeAssistantSettings())
    }

    @Test fun aChargerInsideAHomeIsReachedThroughTheHomesWebhookAndKeptSo() {
        val inside = ChargerProfile(
            localId = "p1", displayName = "", baseUrl = "https://ha", webhookId = "own", remoteChargerId = "c1",
            siteId = "site_a", siteWebhookId = "site-secret"
        )
        assertEquals(HomeAssistantSettings("https://ha", "site-secret", "c1"), inside.toHomeAssistantSettings())
        assertEquals(HomeAssistantSettings("https://ha", "own"), inside.ownWebhook())
        assertFalse(inside.toString().contains("site-secret"))
        // A charger the home listed later has no webhook of its own, and is still configured.
        val later = inside.copy(localId = "p2", webhookId = "", remoteChargerId = "c2")
        assertTrue(later.configured)
        assertFalse(later.copy(siteWebhookId = null).configured)
        // A charger profile from before 3.0: its own webhook, as always.
        val old = ChargerProfile("p3", "", "https://ha", "own", remoteChargerId = "c3")
        assertEquals(HomeAssistantSettings("https://ha", "own"), old.toHomeAssistantSettings())
        val backing = FakeKeyValueStore()
        ChargerProfileStore(backing) {}.apply { upsertProfile(inside); upsertProfile(old) }
        assertEquals(listOf(inside, old), ChargerProfileStore(backing) {}.listProfiles())
    }
}
