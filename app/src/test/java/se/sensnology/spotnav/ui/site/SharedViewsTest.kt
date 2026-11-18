package se.sensnology.spotnav.ui.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.FakeKeyValueStore

/** The shared views' rules apart from the views: how a tab row is worded, and what a page remembers. */
class SharedViewsTest {
    @Test fun theTabRowIsWordedByTheRoomItHas() {
        val long = listOf(20f, 90f, 80f, 45f, 110f)
        val short = listOf(20f, 40f, 35f, 45f, 30f)
        // Each tab is its words and its padding.
        assertEquals(TabFit.LONG, TabFitting.choose(room = 420f, long, short, padding = 14f, gap = 0f))
        assertEquals(TabFit.LONG, TabFitting.choose(room = 415f, long, short, padding = 14f, gap = 0f))
        // A pixel of rounding is not a reason for shorter words; two are.
        assertEquals(TabFit.LONG, TabFitting.choose(room = 414f, long, short, padding = 14f, gap = 0f))
        assertEquals(TabFit.SHORT, TabFitting.choose(room = 413f, long, short, padding = 14f, gap = 0f))
        assertEquals(TabFit.SHORT, TabFitting.choose(room = 240f, long, short, padding = 14f, gap = 0f))
        assertEquals(TabFit.ICONS, TabFitting.choose(room = 230f, long, short, padding = 14f, gap = 0f))
        // The gaps between the tabs count.
        assertEquals(TabFit.SHORT, TabFitting.choose(room = 420f, long, short, padding = 14f, gap = 2f))
        // A row not laid out keeps its long names.
        assertEquals(TabFit.LONG, TabFitting.choose(room = 0f, long, short, padding = 14f, gap = 0f))
    }

    @Test fun theTabAndTheTogglesAreRememberedPerSite() {
        val store = FakeKeyValueStore()
        val preferences = SitePreferences(store)
        val offered = listOf("now", "flow", "graph")
        assertNull(preferences.tab("site_a", "house", offered))
        preferences.setTab("site_a", "house", "graph")
        assertEquals("graph", preferences.tab("site_a", "house", offered))
        // Another site, and the same site's other page, have their own.
        assertNull(preferences.tab("site_b", "house", offered))
        assertNull(preferences.tab("site_a", "heat", offered))
        // A tab the page does not have (yet, or any longer) is nothing remembered.
        assertNull(preferences.tab("site_a", "house", listOf("now")))
        store.setRaw("tab.house.site_a", "nonsense")
        assertNull(preferences.tab("site_a", "house", offered))
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

    @Test fun aStoreThatThrowsRemembersNothing() {
        val broken = object : se.sensnology.spotnav.app.KeyValueStore {
            override fun getString(key: String): String? = throw IllegalStateException("blocked")
            override fun putString(key: String, value: String) = throw IllegalStateException("blocked")
            override fun remove(key: String) = throw IllegalStateException("blocked")
        }
        val preferences = SitePreferences(broken)
        assertNull(preferences.tab("site_a", "house", listOf("now")))
        preferences.setTab("site_a", "house", "now")
        assertEquals(emptySet<String>(), preferences.hidden("site_a", "house.graph"))
        assertTrue(preferences.flag("site_a", "x", fallback = true))
    }
}
