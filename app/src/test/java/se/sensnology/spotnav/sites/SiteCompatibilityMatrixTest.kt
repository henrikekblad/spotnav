package se.sensnology.spotnav.sites

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.chargers.toHomeAssistantSettings
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.HomeAssistantSettings
import se.sensnology.spotnav.ha.client.SiteDoor
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.ha.pairing.ChargerPairingApproval
import se.sensnology.spotnav.ha.pairing.HomeAssistantPairingClient
import se.sensnology.spotnav.ha.pairing.PairingStop
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.ui.site.PageKind
import se.sensnology.spotnav.ui.site.PageOffer
import se.sensnology.spotnav.widget.WidgetChargerBinding
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/**
 * The compatibility matrix of site pairing (`plans/site_pairing.md`), each case against `tools/fake_ha.py`, started
 * here on a free port:
 *
 * 1. Home Assistant 2.x + app 2.4: nothing changed, nothing to test.
 * 2. Home Assistant 2.5 + this app: `get_site_access` is an unknown action, so the charger keeps its own webhook and
 *    its page alone; a pairing hands over no homes.
 * 3. Home Assistant 3.0 + app 2.4: not testable here (that app ignores `sites` and `site_id`; the charger webhooks stay).
 * 4. Home Assistant 3.0 + this app: the silent move to the home's webhook with the charger inside, a charger added
 *    later appearing without pairing again, and a home without a charger that pairs and shows house and heat only.
 */
class SiteCompatibilityMatrixTest {
    private var process: Process? = null
    private lateinit var base: String
    private val chargers = ChargerProfileStore(FakeKeyValueStore()) {}
    private val sites = SiteProfileStore(FakeKeyValueStore()) {}
    private var ids = 0
    private fun upkeep() = SiteUpkeep(chargers, sites, newLocalId = { "new-${++ids}" })

    @Before fun startFakeHomeAssistant() {
        val script = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "tools/fake_ha.py") }.firstOrNull { it.isFile }
        assumeTrue("tools/fake_ha.py not found", script != null)
        assumeTrue("python3 is needed", runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val port = ServerSocket(0).use { it.localPort }
        base = "http://127.0.0.1:$port"
        process = ProcessBuilder("python3", "-I", script!!.path, "--quiet", "--port", "$port").redirectErrorStream(true).start()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && runCatching { get("/spotnav-mock/scenarios") }.isFailure) Thread.sleep(100)
        get("/spotnav-mock/scenarios")
        HomeAssistantClient.retryWaitMs = 0
    }

    @After fun stop() {
        process?.destroy()
        HomeAssistantClient.retryWaitMs = se.sensnology.spotnav.ha.client.WebhookGone.RETRY_WAIT_MS
    }

    private fun get(path: String): String {
        val connection = URL("$base$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 1_000
        connection.instanceFollowRedirects = false
        return try { connection.inputStream.bufferedReader().readText() } finally { connection.disconnect() }
    }

    private fun charger(key: String) = ChargerProfile("p-$key", "", base, key, remoteChargerId = key, remoteChargerName = key)

    private fun pageKinds(settings: HomeAssistantSettings): List<PageKind> {
        val capabilities = HomeAssistantClient.dashboard(settings).capabilities
        return PageOffer.kinds(house = capabilities.getHouse, heat = capabilities.getHeat)
    }

    /** One pairing against the fake: a request, an approval on its page, and the poll that answers it. */
    private fun pair(): PairingStop.Approved {
        val requestId = HomeAssistantPairingClient.request(base, "Test phone", "123456")!!
        runCatching { get("/pairing/approve/$requestId") }
        return HomeAssistantPairingClient.awaitApproval(base, requestId) as PairingStop.Approved
    }

    private fun applyPairing(approved: PairingStop.Approved) {
        val applied = ChargerPairingApproval.apply(
            approved, chargers.listProfiles(), approved.baseUrl, WidgetChargerBinding(false, null),
            newLocalId = { "c-${++ids}" }, existingSites = sites.list(), newSiteLocalId = { "s-${++ids}" }
        )
        ChargerPairingApproval.commit(applied, object : ChargerPairingApproval.Device {
            override fun forgetCached(localId: String) = Unit
            override fun upsert(profile: ChargerProfile) = chargers.upsertProfile(profile)
            override fun activeProfileId(): String? = chargers.getActiveProfileId()
            override fun activate(localId: String) = chargers.setActiveProfileId(localId)
            override fun bind(localId: String) = Unit
            override fun upsertSite(site: SiteProfile) = sites.upsert(site)
            override fun activeSiteId(): String? = sites.activeSiteId()
            override fun activateSite(localId: String) = sites.setActiveSiteId(localId)
            override fun repaint() = Unit
        })
    }

    // (2) Home Assistant 2.5 + this app

    @Test fun homeAssistant25KeepsTheChargerOnItsOwnWebhookWithItsPageAlone() {
        chargers.upsertProfile(charger("ha25"))
        upkeep().run()
        val kept = chargers.getProfile("p-ha25")!!
        assertEquals(charger("ha25"), kept)
        assertTrue(sites.list().isEmpty())
        assertEquals(HomeAssistantSettings(base, "ha25"), kept.toHomeAssistantSettings())
        assertEquals(listOf(PageKind.CHARGER), pageKinds(kept.toHomeAssistantSettings()))
    }

    @Test fun homeAssistant25PairingHandsOverNoHomes() {
        get("/spotnav-mock/sites?pairing=off")
        val approved = pair()
        assertTrue(approved.sites.isEmpty())
        assertTrue(approved.chargers.none { it.siteId != null })
        applyPairing(approved)
        assertTrue(sites.list().isEmpty())
        assertTrue(chargers.listProfiles().none { it.viaSite })
    }

    // (4) Home Assistant 3.0 + this app

    @Test fun aChargerPairedBefore30MovesSilentlyIntoItsHomeAndALaterChargerAppears() {
        chargers.upsertProfile(charger("sitecharger"))
        val first = upkeep().run()
        assertEquals(listOf("p-sitecharger"), first.moved)
        val home = sites.list().single()
        assertEquals(listOf("siteonly_charger", "Villan"), listOf(home.siteId, home.name))
        val moved = chargers.getProfile("p-sitecharger")!!
        assertEquals(HomeAssistantSettings(base, "site-siteonly_charger", "sitecharger"), moved.toHomeAssistantSettings())
        // The charger's page with the home's pages, all through the home's webhook.
        assertEquals(listOf(PageKind.CHARGER, PageKind.HOUSE, PageKind.HEAT), pageKinds(moved.toHomeAssistantSettings()))
        // A charger added to the home in Home Assistant: found at the next start, no pairing.
        get("/spotnav-mock/sites?add=1")
        val second = upkeep().run()
        val carport = chargers.listProfiles().single { it.remoteChargerId == "sitecharger2" }
        assertEquals(listOf(carport.localId), second.added)
        assertEquals(HomeAssistantSettings(base, "site-siteonly_charger", "sitecharger2"), carport.toHomeAssistantSettings())
        assertEquals("Carport", HomeAssistantClient.dashboard(carport.toHomeAssistantSettings()).chargerName)
        assertEquals(listOf("sitecharger", "sitecharger2"), sites.list().single().chargers.map { it.id })
    }

    @Test fun aHomeNotLoadedOrARateLimitIsAskedAgainLaterNeverNoHome() {
        chargers.upsertProfile(charger("sitecharger"))
        get("/spotnav-mock/sites?access=unloaded")
        upkeep().run()
        assertFalse(chargers.getProfile("p-sitecharger")!!.viaSite)
        get("/spotnav-mock/sites?access=limited")
        upkeep().run()
        assertFalse(chargers.getProfile("p-sitecharger")!!.viaSite)
        assertFalse(chargers.getProfile("p-sitecharger")!!.needsPairing)
        // Loaded again: the next start moves it.
        get("/spotnav-mock/sites?access=ok")
        upkeep().run()
        assertTrue(chargers.getProfile("p-sitecharger")!!.viaSite)
        // The whole home not loaded: its webhook answers 503, which never marks it for pairing again.
        get("/spotnav-mock/sites?unloaded=1")
        assertEquals(SiteDoor.GetSite.Later, upkeep().refresh(sites.list().single().localId))
        assertFalse(sites.list().single().needsPairing)
    }

    @Test fun aHomeWithoutAChargerPairsAndShowsHouseAndHeatOnly() {
        val approved = pair()
        assertEquals(listOf("siteonly", "siteonly_charger"), approved.sites.map { it.id })
        applyPairing(approved)
        // Its charger reached through the home's webhook, the others through their own.
        assertTrue(chargers.listProfiles().single { it.remoteChargerId == "sitecharger" }.viaSite)
        assertFalse(chargers.listProfiles().single { it.remoteChargerId == "full" }.viaSite)
        // With no charger shown, the main screen is the charger-less home.
        val cabin = MainSubject.site(null, chargers.listProfiles(), sites.list(), sites.activeSiteId())
        assertNotNull(cabin)
        assertEquals("Stugan", cabin!!.name)
        val read = HomeAssistantClient.getSite(cabin.toHomeAssistantSettings()) as SiteDoor.GetSite.Read
        assertTrue(read.site.chargers.isEmpty())
        val heat = HomeAssistantClient.heat(cabin.toHomeAssistantSettings())
        assertTrue(heat is SiteRead.Read && heat.value.hasSomething)
        assertEquals(
            listOf(PageKind.HOUSE, PageKind.HEAT),
            PageOffer.kinds(house = read.site.capabilities.getHouse, heat = true, charger = false)
        )
        assertTrue(HomeAssistantClient.houseNow(cabin.toHomeAssistantSettings()) is SiteRead.Read)
        // The price is read-only from it.
        assertEquals("SE4", HomeAssistantClient.homePrice(cabin.toHomeAssistantSettings())?.areaId)
    }

    @Test fun aHomeWhoseWebhookIsGoneIsMarkedToBePairedAgain() {
        applyPairing(pair())
        get("/spotnav-mock/rotate-webhooks")
        val cabin = sites.list().first { it.siteId == "siteonly" }
        assertEquals(SiteDoor.GetSite.Gone, upkeep().refresh(cabin.localId))
        assertTrue(sites.get(cabin.localId)!!.needsPairing)
        assertNull(MainSubject.site(chargers.getActiveProfile(), chargers.listProfiles(), sites.list(), null))
    }

}
