package se.sensnology.spotnav.ui.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.FakeKeyValueStore

/** The shared views' rules apart from the views: what a page remembers. */
class SharedViewsTest {
    @Test fun theHiddenPartsAndTheTogglesAreRememberedPerSite() {
        val store = FakeKeyValueStore()
        val preferences = SitePreferences(store)
        // The parts hidden through a chart's legend.
        assertEquals(emptySet<String>(), preferences.hidden("site_a", "house.graph"))
        preferences.setHidden("site_a", "house.graph", setOf("car", "battery"))
        assertEquals(setOf("battery", "car"), preferences.hidden("site_a", "house.graph"))
        assertEquals(emptySet<String>(), preferences.hidden("site_a", "house.earn"))
        preferences.setHidden("site_a", "house.graph", emptySet())
        assertEquals(emptySet<String>(), preferences.hidden("site_a", "house.graph"))
        // A switch keeps its own default until it is set.
        assertTrue(preferences.flag("site_a", "heat.use.money", fallback = true))
        assertFalse(preferences.flag("site_a", "heat.use.money", fallback = false))
        preferences.setFlag("site_a", "heat.use.money", false)
        assertFalse(preferences.flag("site_a", "heat.use.money", fallback = true))
        assertTrue(preferences.flag("site_b", "heat.use.money", fallback = true))
    }

    @Test fun thePageLastInViewAndThePagesOfferedAreRememberedPerCharger() {
        val preferences = SitePreferences(FakeKeyValueStore())
        val known = PageKind.entries.map { it.name }
        // Nothing remembered: the caller opens the charger's page and waits for Home Assistant's word on the others.
        assertNull(preferences.page("garage", known))
        assertEquals(emptyList<String>(), preferences.pages("garage", known))
        preferences.setPage("garage", PageKind.HOUSE.name)
        preferences.setPages("garage", listOf("CHARGER", "HOUSE"))
        assertEquals("HOUSE", preferences.page("garage", known))
        assertEquals(listOf("CHARGER", "HOUSE"), preferences.pages("garage", known))
        // Another charger has its own.
        assertNull(preferences.page("cabin", known))
        // A page this app does not have (a newer app wrote it) is nothing remembered.
        preferences.setPage("garage", "POOL")
        preferences.setPages("garage", listOf("CHARGER", "POOL", "HOUSE"))
        assertNull(preferences.page("garage", known))
        assertEquals(listOf("CHARGER", "HOUSE"), preferences.pages("garage", known))
        // The page remembered is opened only while it is still offered, else the charger's.
        assertEquals(PageKind.HOUSE, PageOffer.opening(PageKind.HOUSE, PageOffer.kinds(house = true, heat = false)))
        assertEquals(PageKind.CHARGER, PageOffer.opening(PageKind.HOUSE, PageOffer.kinds(house = false, heat = false)))
    }

    @Test fun aStoreThatThrowsRemembersNothing() {
        val broken = object : se.sensnology.spotnav.app.KeyValueStore {
            override fun getString(key: String): String? = throw IllegalStateException("blocked")
            override fun putString(key: String, value: String) = throw IllegalStateException("blocked")
            override fun remove(key: String) = throw IllegalStateException("blocked")
        }
        val preferences = SitePreferences(broken)
        assertEquals(emptySet<String>(), preferences.hidden("site_a", "house.graph"))
        assertTrue(preferences.flag("site_a", "x", fallback = true))
        assertNull(preferences.page("garage", listOf("HOUSE")))
        preferences.setPage("garage", "HOUSE")
        assertEquals(emptyList<String>(), preferences.pages("garage", listOf("CHARGER")))
    }
}
