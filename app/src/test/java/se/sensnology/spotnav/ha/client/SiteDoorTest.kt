package se.sensnology.spotnav.ha.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.pairing.PairedSite
import se.sensnology.spotnav.ha.pairing.SiteCharger
import se.sensnology.spotnav.testing.HaFixtures

/** `get_site` and `get_site_access` against the integration's own fixtures. */
class SiteDoorTest {
    private fun fixture(name: String) = HaFixtures.root.resolve("webhook/$name.json").readText()

    @Test fun getSiteReadsTheHomeItsCapabilitiesAndItsChargers() {
        val read = SiteDoor.getSite(200, fixture("get_site"), null) as SiteDoor.GetSite.Read
        assertEquals("<site_id>", read.site.siteId)
        assertEquals("Site", read.site.name)
        assertTrue(read.site.loaded)
        assertEquals(SiteCapabilities(true, true, true, true, true, true, true), read.site.capabilities)
        assertEquals(listOf(SiteCharger("<charger_id>", "<charger_id>")), read.site.chargers)
        val none = SiteDoor.getSite(200, fixture("get_site_no_charger"), null) as SiteDoor.GetSite.Read
        assertEquals("Villa", none.site.name)
        assertEquals(emptyList<SiteCharger>(), none.site.chargers)
    }

    @Test fun aHomeNotLoadedIsLaterAndAnEmptyAnswerIsGone() {
        assertEquals(SiteDoor.GetSite.Later, SiteDoor.getSite(503, fixture("site_webhook_site_unloaded"), null))
        assertEquals(SiteDoor.GetSite.Later, SiteDoor.getSite(null, null, java.io.IOException()))
        assertEquals(SiteDoor.GetSite.Gone, SiteDoor.getSite(null, null, WebhookGoneException()))
        assertEquals(SiteDoor.GetSite.Failed("spotnav_charger_required"), SiteDoor.getSite(400, fixture("site_webhook_charger_required"), null))
    }

    @Test fun getSiteAccessHandsOverTheHome() {
        assertEquals(
            SiteDoor.Access.Granted(PairedSite("<site_id>", "Site", "<site_webhook>", listOf(SiteCharger("<charger_id>", "<charger_id>")))),
            SiteDoor.access(200, fixture("get_site_access_success"), null)
        )
    }

    @Test fun getSiteAccessRefusalsKeepTheChargerOrWaitButNeverSayNoHomeForAnUnloadedOne() {
        assertEquals(SiteDoor.Access.NoSite, SiteDoor.access(400, fixture("get_site_access_no_site"), null))
        assertEquals(SiteDoor.Access.Later(60), SiteDoor.access(429, fixture("get_site_access_rate_limited"), null))
        // The home exists but is not loaded: try later, never "no home".
        assertEquals(SiteDoor.Access.Later(), SiteDoor.access(503, fixture("get_site_access_site_unloaded"), null))
        // Home Assistant 2.x: the action is unknown.
        assertEquals(SiteDoor.Access.Unsupported, SiteDoor.access(400, """{"ok": false, "error": "Unsupported action"}""", null))
        assertEquals(SiteDoor.Access.Later(), SiteDoor.access(null, null, java.io.IOException()))
        assertEquals(SiteDoor.Access.Gone, SiteDoor.access(null, null, WebhookGoneException()))
        // An ok answer without a usable home is no move.
        assertEquals(SiteDoor.Access.Unsupported, SiteDoor.access(200, """{"ok": true, "site": {"id": "s"}}""", null))
    }
}
