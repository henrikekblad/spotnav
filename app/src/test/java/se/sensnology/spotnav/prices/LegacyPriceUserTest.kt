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
        catalogue.putString(AreaCatalogue.KEY_REFRESHED_AT, "5")
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
        catalogue.putString(AreaCatalogue.KEY_REFRESHED_AT, "5")
        assertTrue(ensure())
    }

    @Test
    fun anIndexAloneIsNotADay() {
        cache.putString(PriceRepository.INDEX_KEY, "{}")
        assertFalse(ensure())
    }

    @Test
    fun theExplanationIsShownOnce() {
        assertFalse(LegacyPriceUser.whyShown(flags))
        LegacyPriceUser.markWhyShown(flags)
        assertTrue(LegacyPriceUser.whyShown(flags))
    }
}
