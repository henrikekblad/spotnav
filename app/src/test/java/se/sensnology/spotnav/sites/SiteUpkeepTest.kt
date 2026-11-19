package se.sensnology.spotnav.sites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.chargers.toHomeAssistantSettings
import se.sensnology.spotnav.ha.client.HomeAssistantSettings
import se.sensnology.spotnav.ha.client.HomeSite
import se.sensnology.spotnav.ha.client.SiteCapabilities
import se.sensnology.spotnav.ha.client.SiteDoor
import se.sensnology.spotnav.ha.client.WebhookGoneException
import se.sensnology.spotnav.ha.pairing.PairedSite
import se.sensnology.spotnav.ha.pairing.SiteCharger
import se.sensnology.spotnav.testing.FakeKeyValueStore

/** The silent move to the home's webhook, and a home's chargers followed without pairing again. */
class SiteUpkeepTest {
    private val base = "https://ha"
    private val chargers = ChargerProfileStore(FakeKeyValueStore()) {}
    private val sites = SiteProfileStore(FakeKeyValueStore()) {}
    private var ids = 0
    private val asked = mutableListOf<HomeAssistantSettings>()

    private fun garage() = ChargerProfile("p1", "", base, "own-1", remoteChargerId = "c1", remoteChargerName = "Garage", selectedVehicleId = "ev6", targetSocPercent = 80)

    private fun upkeep(access: (HomeAssistantSettings) -> SiteDoor.Access, getSite: (HomeAssistantSettings) -> SiteDoor.GetSite = { SiteDoor.GetSite.Later }) =
        SiteUpkeep(chargers, sites, { asked += it; access(it) }, getSite, newLocalId = { "new-${++ids}" })

    private val home = PairedSite("s1", "Home", "site-hook", listOf(SiteCharger("c1", "Garage")))

    private fun read(vararg listed: SiteCharger) =
        SiteDoor.GetSite.Read(HomeSite("s1", "Home", true, SiteCapabilities(getHouse = true), listed.toList()))

    @Test fun aChargerWhoseHomeAnswersMovesIntoItKeepingWhatItRemembers() {
        chargers.upsertProfile(garage())
        chargers.setActiveProfileId("p1")
        val report = upkeep({ SiteDoor.Access.Granted(home) }).run(now = 1)
        // Asked on the charger's own webhook, naming no charger.
        assertEquals(listOf(HomeAssistantSettings(base, "own-1")), asked)
        val site = sites.list().single()
        assertEquals(listOf("s1", "Home", "site-hook"), listOf(site.siteId, site.name, site.webhookId))
        val moved = chargers.getProfile("p1")!!
        assertEquals(HomeAssistantSettings(base, "site-hook", "c1"), moved.toHomeAssistantSettings())
        assertEquals("ev6", moved.selectedVehicleId)
        assertEquals(80, moved.targetSocPercent)
        assertEquals("p1", chargers.getActiveProfileId())
        assertEquals(listOf("p1"), report.moved)
        // Moved once: the next start asks it no more.
        asked.clear()
        upkeep({ error("not asked") }).run(now = 2)
        assertTrue(asked.isEmpty())
    }

    @Test fun homeAssistant2xNoHomeARateLimitAndAHomeNotLoadedKeepTheChargerAsItIs() {
        for (answer in listOf(SiteDoor.Access.Unsupported, SiteDoor.Access.NoSite, SiteDoor.Access.Later(60), SiteDoor.Access.Later())) {
            chargers.upsertProfile(garage())
            val report = upkeep({ answer }).run(now = 1)
            assertEquals(answer.toString(), garage(), chargers.getProfile("p1"))
            assertTrue(sites.list().isEmpty())
            assertEquals(SiteUpkeep.Report(), report)
        }
    }

    @Test fun anEmptyAnswerMarksTheChargerToBePairedAgain() {
        chargers.upsertProfile(garage())
        upkeep({ SiteDoor.Access.Gone }).run(now = 1)
        assertTrue(chargers.getProfile("p1")!!.needsPairing)
        assertTrue(sites.list().isEmpty())
    }

    @Test fun twoChargersOfOneHomeMoveOnOneAnswer() {
        chargers.upsertProfile(garage())
        chargers.upsertProfile(ChargerProfile("p2", "", base, "own-2", remoteChargerId = "c2"))
        val both = home.copy(chargers = listOf(SiteCharger("c1", "Garage"), SiteCharger("c2", "Carport")))
        upkeep({ SiteDoor.Access.Granted(both) }).run(now = 1)
        assertEquals(1, asked.size)
        assertTrue(chargers.listProfiles().all { it.siteWebhookId == "site-hook" })
        assertEquals(1, sites.list().size)
    }

    @Test fun aChargerAddedToTheHomeLaterAppearsWithoutPairingAgain() {
        chargers.upsertProfile(garage())
        upkeep({ SiteDoor.Access.Granted(home) }, { read(SiteCharger("c1", "Garage")) }).run(now = 1)
        assertEquals(1, chargers.listProfiles().size)
        // Home Assistant got a second charger in the home: the next start finds it.
        val report = upkeep({ error("not asked") }, { read(SiteCharger("c1", "Garage"), SiteCharger("c2", "Carport")) }).run(now = 2)
        val added = chargers.listProfiles().single { it.remoteChargerId == "c2" }
        assertEquals(listOf(added.localId), report.added)
        assertEquals("Carport", added.remoteChargerName)
        assertEquals("", added.webhookId)
        assertEquals(HomeAssistantSettings(base, "site-hook", "c2"), added.toHomeAssistantSettings())
        assertEquals(listOf("c1", "c2"), sites.list().single().chargers.map { it.id })
    }

    @Test fun aHomeWithoutAChargerIsReadAndAGoneHomeIsMarked() {
        sites.upsert(SiteProfile("l1", base, "cabin-hook", "s9", "Cabin"))
        val answers = mutableListOf<SiteDoor.GetSite>(SiteDoor.GetSite.Later, SiteDoor.GetSite.Gone)
        val upkeep = upkeep({ error("no chargers") }, { answers.removeAt(0) })
        // Not loaded: nothing marked.
        upkeep.run(now = 1)
        assertFalse(sites.get("l1")!!.needsPairing)
        upkeep.run(now = 2)
        assertTrue(sites.get("l1")!!.needsPairing)
        assertTrue(chargers.listProfiles().isEmpty())
    }

    @Test fun aChargerThatLeftTheHomeGoesBackToItsOwnWebhook() {
        val inside = garage().copy(siteId = "s1", siteWebhookId = "site-hook")
        val sync = SiteSync.apply(base, "s1", "Home", "site-hook", emptyList(), emptyList(), listOf(inside), { "l" }, { "n" })
        assertNull(sync.chargers.single().siteWebhookId)
        assertEquals(listOf("p1"), sync.moved)
        // One already inside with the same door is not written again.
        val same = SiteSync.apply(base, "s1", "Home", "site-hook", listOf(SiteCharger("c1", "Garage")), emptyList(), listOf(inside), { "l" }, { "n" })
        assertTrue(same.chargers.isEmpty())
        // A charger of another instance is never matched.
        val other = SiteSync.apply("https://other", "s1", "Home", "h", listOf(SiteCharger("c1", "Garage")), emptyList(), listOf(garage()), { "l" }, { "n" })
        assertEquals(listOf("n"), other.added)
    }
}
