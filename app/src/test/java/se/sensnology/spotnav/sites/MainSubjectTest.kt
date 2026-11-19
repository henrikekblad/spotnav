package se.sensnology.spotnav.sites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerProfile

class MainSubjectTest {
    private val garage = ChargerProfile("p1", "", "https://ha", "own", remoteChargerId = "c1", siteId = "s1", siteWebhookId = "h1")
    private val home = SiteProfile("l1", "https://ha", "h1", "s1", "Home")
    private val cabin = SiteProfile("l2", "https://ha", "h2", "s2", "Cabin")

    @Test fun aChargerShownAlwaysWins() {
        assertNull(MainSubject.site(garage, listOf(garage), listOf(home, cabin), activeSiteId = "l2"))
    }

    @Test fun withNoChargerTheChosenHomeElseTheFirstWithoutACharger() {
        assertEquals(cabin, MainSubject.site(null, listOf(garage), listOf(home, cabin), activeSiteId = null))
        assertEquals(home, MainSubject.site(null, listOf(garage), listOf(home, cabin), activeSiteId = "l1"))
        // A phone with chargers in every home, and no home chosen: the charger screen.
        assertNull(MainSubject.site(null, listOf(garage), listOf(home), activeSiteId = null))
        // Nothing paired at all.
        assertNull(MainSubject.site(null, emptyList(), emptyList(), activeSiteId = null))
        assertEquals("site:l2", MainSubject.pagesKey(cabin))
    }
}
