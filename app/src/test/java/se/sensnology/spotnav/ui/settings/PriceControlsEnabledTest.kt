package se.sensnology.spotnav.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.authority.VisibleAuthority

class PriceControlsEnabledTest {
    @Test
    fun anUnpairedPhoneMayAlwaysEditItsPriceSettings() {
        // No profile means no authority at all: the phone's own settings stay editable.
        assertTrue(priceControlsEnabled(paired = false, authority = null))
    }

    @Test
    fun aPairedPhoneWaitsForItsAuthority() {
        assertFalse(priceControlsEnabled(paired = true, authority = null))
        assertFalse(priceControlsEnabled(paired = true, authority = VisibleAuthority.ReadOnlyOffline(null)))
        assertTrue(priceControlsEnabled(paired = true, authority = VisibleAuthority.LocalOwner(null)))
    }
}
