package se.sensnology.spotnav.prices

import org.junit.Assert.assertEquals
import org.junit.Test
import se.sensnology.spotnav.BuildConfig

/** Run by testReleaseUnitTest: the release build's compiled-in relay is the official one. */
class RelayAddressReleaseTest {
    @Test
    fun `a release build carries only the official relay`() {
        assertEquals(RelayAddress.OFFICIAL, BuildConfig.RELAY_BASE_URL)
        assertEquals(RelayAddress.OFFICIAL, RelayAddress.base)
    }
}
