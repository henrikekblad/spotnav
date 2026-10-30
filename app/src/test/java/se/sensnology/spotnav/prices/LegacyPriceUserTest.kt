package se.sensnology.spotnav.prices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.FakeKeyValueStore

class LegacyPriceUserTest {
    private val flags = FakeKeyValueStore()
    private val cache = FakeKeyValueStore()
    private val catalogue = FakeKeyValueStore()

    private fun ensure(now: Long = 1_000L) = LegacyPriceUser.ensure(flags, cache, catalogue, now)

    @Test
    fun aFreshInstallIsNotALegacyUserForever() {
        assertFalse(ensure())
        // Whatever this version writes later does not change it.
        cache.putString("v1:SE4:2026-10-30", "{}")
        catalogue.putString(RelayLeftovers.KEY_REFRESHED_AT, "5")
        assertFalse(ensure(2_000L))
        assertEquals("1000", flags.rawOrNull(LegacyPriceUser.KEY_MIGRATED_AT))
    }

    @Test
    fun aHeldRelayDayMakesALegacyUser() {
        cache.putString("v1:GB-C:2026-09-01", "{}")
        assertTrue(ensure())
        assertEquals("true", flags.rawOrNull(LegacyPriceUser.KEY_LEGACY))
    }

    @Test
    fun aCatalogueRefreshMakesALegacyUser() {
        catalogue.putString(RelayLeftovers.KEY_REFRESHED_AT, "5")
        assertTrue(ensure())
    }

    @Test
    fun anIndexAloneIsNotADay() {
        cache.putString(RelayLeftovers.INDEX_KEY, "{}")
        assertFalse(ensure())
    }

    @Test
    fun theRelaysListsAndIndexesAreForgottenAfterTheFlagIsKept() {
        cache.putString("v1:SE4:2026-10-30", "{}")
        cache.putString(RelayLeftovers.INDEX_KEY, "{}")
        cache.putString(RelayLeftovers.INDEX_V2_KEY, "{}")
        catalogue.putString(RelayLeftovers.KEY_REFRESHED_AT, "5")
        catalogue.putString(RelayLeftovers.KEY_LAST_GOOD, "{}")
        catalogue.putString(RelayLeftovers.KEY_LAST_GOOD_V2, "{}")
        assertTrue(ensure())

        RelayLeftovers.forget(catalogue, cache)

        assertEquals(setOf("v1:SE4:2026-10-30"), cache.keys().toSet())
        assertTrue(catalogue.keys().isEmpty())
        assertTrue("the flag outlives what it was read from", ensure(2_000L))
    }

    @Test
    fun theExplanationIsShownOnce() {
        assertFalse(LegacyPriceUser.whyShown(flags))
        LegacyPriceUser.markWhyShown(flags)
        assertTrue(LegacyPriceUser.whyShown(flags))
    }
}
