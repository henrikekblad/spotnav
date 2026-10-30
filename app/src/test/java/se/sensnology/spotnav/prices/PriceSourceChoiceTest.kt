package se.sensnology.spotnav.prices

import org.junit.Assert.assertEquals
import org.junit.Test
import se.sensnology.spotnav.prices.entsoe.KeyState
import se.sensnology.spotnav.testing.BundledAreas

/** The rule of design section 1, as a table. */
class PriceSourceChoiceTest {
    private val se4 = BundledAreas.area("SE4")
    private val noRoute = se4.copy(entsoe = null)
    private val local = PriceSubject(null, "SE4")
    private val paired = PriceSubject("charger-a", "SE4")

    private fun choose(
        subject: PriceSubject = local,
        configured: Boolean = false,
        ha: Boolean? = null,
        key: KeyState = KeyState.NONE,
        area: PriceMarket? = se4,
        legacy: Boolean = false
    ) = PriceSourceChoice.choose(subject, configured, ha, key, area, legacy)

    @Test
    fun aPairedChargerIsHomeAssistantsAndNeverFallsBackToEntsoe() {
        assertEquals(ChosenSource.HomeAssistant("charger-a"), choose(paired, configured = true, ha = true, key = KeyState.OK))
        assertEquals(ChosenSource.Relay, choose(paired, configured = true, ha = false, key = KeyState.OK, legacy = true))
        assertEquals(ChosenSource.None(MissingSource.HA_TOO_OLD), choose(paired, configured = true, ha = null, key = KeyState.OK))
        assertEquals(ChosenSource.None(MissingSource.HA_TOO_OLD), choose(paired, configured = true, ha = false, legacy = false))
    }

    @Test
    fun aProfileThatIsNoLongerConfiguredIsStandalone() {
        assertEquals(ChosenSource.Entsoe, choose(paired, configured = false, key = KeyState.OK))
    }

    @Test
    fun standaloneWithAUsableKeyUsesEntsoe() {
        assertEquals(ChosenSource.Entsoe, choose(key = KeyState.OK))
        assertEquals(ChosenSource.Entsoe, choose(key = KeyState.UNCHECKED))
        assertEquals("a key beats the relay", ChosenSource.Entsoe, choose(key = KeyState.OK, legacy = true))
    }

    @Test
    fun withoutAUsableKeyALegacyUserKeepsTheRelay() {
        assertEquals(ChosenSource.Relay, choose(key = KeyState.NONE, legacy = true))
        assertEquals(ChosenSource.Relay, choose(key = KeyState.REJECTED, legacy = true))
        assertEquals(ChosenSource.Relay, choose(key = KeyState.OK, area = noRoute, legacy = true))
    }

    @Test
    fun otherwiseNothingWithTheReasonTheUiShows() {
        assertEquals(ChosenSource.None(MissingSource.NO_KEY), choose(key = KeyState.NONE))
        assertEquals(ChosenSource.None(MissingSource.KEY_REJECTED), choose(key = KeyState.REJECTED))
        assertEquals(ChosenSource.None(MissingSource.NO_ROUTE), choose(key = KeyState.OK, area = noRoute))
        assertEquals(ChosenSource.None(MissingSource.NO_ROUTE), choose(key = KeyState.OK, area = null))
    }
}
