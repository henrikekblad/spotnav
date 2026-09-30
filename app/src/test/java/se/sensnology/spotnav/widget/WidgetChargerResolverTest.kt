package se.sensnology.spotnav.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.testing.FakeKeyValueStore

class WidgetChargerResolverTest {
    private fun store(): ChargerProfileStore = ChargerProfileStore(FakeKeyValueStore()) {}

    @Test fun resolvesToTheExactBoundProfile() {
        val store = store()
        val a = ChargerProfile("a", "Charger A", "https://a.example.com", "hook-a")
        val b = ChargerProfile("b", "Charger B", "https://b.example.com", "hook-b")
        store.upsertProfile(a)
        store.upsertProfile(b)

        assertEquals(a, WidgetChargerResolver.resolve(WidgetSettings(chargerProfileId = "a"), store))
        assertEquals(b, WidgetChargerResolver.resolve(WidgetSettings(chargerProfileId = "b"), store))
    }

    @Test fun resolvesToNullWhenUnbound() {
        val store = store()
        store.upsertProfile(ChargerProfile("a", "Charger A", "https://a.example.com", "hook-a"))

        assertNull(WidgetChargerResolver.resolve(WidgetSettings(chargerProfileId = null), store))
    }

    @Test fun resolvesToNullForADeletedProfileRatherThanAnyOtherCharger() {
        val store = store()
        val a = ChargerProfile("a", "Charger A", "https://a.example.com", "hook-a")
        val b = ChargerProfile("b", "Charger B", "https://b.example.com", "hook-b")
        store.upsertProfile(a)
        store.upsertProfile(b)
        store.removeProfile("a")

        val resolved = WidgetChargerResolver.resolve(WidgetSettings(chargerProfileId = "a"), store)

        assertNull(resolved)
    }

    @Test fun aNewlyCreatedProfileNeverClaimsAStaleBinding() {
        val store = store()
        store.upsertProfile(ChargerProfile("a", "Charger A", "https://a.example.com", "hook-a"))
        store.removeProfile("a")
        store.upsertProfile(ChargerProfile("a-2", "New charger", "https://new.example.com", "hook-new"))

        val resolved = WidgetChargerResolver.resolve(WidgetSettings(chargerProfileId = "a"), store)

        assertNull(resolved)
    }

    @Test fun resolverNeverConsultsTheActiveProfile() {
        val store = store()
        val active = ChargerProfile("active", "Active charger", "https://active.example.com", "hook-active")
        store.upsertProfile(active)
        store.setActiveProfileId("active")
        store.upsertProfile(ChargerProfile("other", "Other charger", "https://other.example.com", "hook-other"))

        val resolved = WidgetChargerResolver.resolve(WidgetSettings(chargerProfileId = null), store)

        assertNull(resolved)
    }
}
