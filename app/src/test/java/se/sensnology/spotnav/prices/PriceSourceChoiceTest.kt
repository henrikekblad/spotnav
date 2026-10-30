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
    fun aPairedChargerIsHomeAssistantsAndNeverFallsBackToEntsoeOrTheRelay() {
        assertEquals(ChosenSource.HomeAssistant("charger-a"), choose(paired, configured = true, ha = true, key = KeyState.OK))
        assertEquals(ChosenSource.HomeAssistant("charger-a"), choose(paired, configured = true, ha = true, legacy = true))
        // An older Home Assistant: updated, not bridged by the relay, even for a phone that used the relay.
        for (legacy in listOf(false, true)) for (key in KeyState.entries) {
            assertEquals(ChosenSource.None(MissingSource.HA_TOO_OLD), choose(paired, configured = true, ha = false, key = key, legacy = legacy))
            assertEquals(ChosenSource.None(MissingSource.HA_NOT_READ), choose(paired, configured = true, ha = null, key = key, legacy = legacy))
        }
        // Both are a paired charger's: its prices are its Home Assistant's alone, held or asked for.
        assertEquals(true, MissingSource.HA_TOO_OLD.paired)
        assertEquals(true, MissingSource.HA_NOT_READ.paired)
        for (standalone in listOf(MissingSource.NO_KEY, MissingSource.KEY_REJECTED, MissingSource.NO_ROUTE)) {
            assertEquals(false, standalone.paired)
        }
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
