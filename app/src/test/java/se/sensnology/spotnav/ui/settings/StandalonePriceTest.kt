package se.sensnology.spotnav.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.prices.MissingSource
import se.sensnology.spotnav.prices.entsoe.KeyState
import se.sensnology.spotnav.testing.BundledAreas

/** What an unpaired phone says about its prices' source (design 5.1-5.3, O-A5). */
class StandalonePriceTest {
    private val se4 = BundledAreas.area("SE4")

    /** A Great Britain region as the relay lists it: no ENTSO-E route. */
    private val gb = se4.copy(id = "GB-C", entsoe = null)

    @Test
    fun theKeyRowSaysAddedMissingOrNotAccepted() {
        assertEquals(StandalonePrice.KeyValue.MISSING, StandalonePrice.keyValue(KeyState.NONE))
        assertEquals(StandalonePrice.KeyValue.ADDED, StandalonePrice.keyValue(KeyState.OK))
        assertEquals("a key waiting for its first answer is used", StandalonePrice.KeyValue.ADDED, StandalonePrice.keyValue(KeyState.UNCHECKED))
        assertEquals(StandalonePrice.KeyValue.NOT_ACCEPTED, StandalonePrice.keyValue(KeyState.REJECTED))
    }

    @Test
    fun anAreaNoKeyCanPriceHasNoKeyRowAndNeedsHomeAssistant() {
        assertTrue(StandalonePrice.keyRowShown(se4))
        assertFalse(StandalonePrice.keyRowShown(gb))
        assertFalse("an area the list no longer has", StandalonePrice.keyRowShown(null))
        for (state in KeyState.entries) {
            assertEquals(StandalonePrice.Line.NEEDS_HOME_ASSISTANT, StandalonePrice.line(gb, state))
        }
    }

    @Test
    fun theSourceLineNamesTheKeyOnlyOnceItIsUsable() {
        assertEquals(StandalonePrice.Line.OWN_KEY, StandalonePrice.line(se4, KeyState.OK))
        assertEquals(StandalonePrice.Line.OWN_KEY, StandalonePrice.line(se4, KeyState.UNCHECKED))
        assertNull(StandalonePrice.line(se4, KeyState.NONE))
        assertNull(StandalonePrice.line(se4, KeyState.REJECTED))
    }

    @Test
    fun theChargingScreenSaysOnlyWhatThePersonCanAct() {
        assertEquals(StandalonePrice.Notice.ADD_KEY, StandalonePrice.notice(MissingSource.NO_KEY))
        assertEquals(StandalonePrice.Notice.REPLACE_KEY, StandalonePrice.notice(MissingSource.KEY_REJECTED))
        assertEquals(StandalonePrice.Notice.NEEDS_HOME_ASSISTANT, StandalonePrice.notice(MissingSource.NO_ROUTE))
        assertNull("fixed in Home Assistant, said by the paired card", StandalonePrice.notice(MissingSource.HA_TOO_OLD))
        assertNull(StandalonePrice.notice(null))
    }

    @Test
    fun theExplanationComesOnceAndOnlyForSomeoneWhoHadServerPrices() {
        assertTrue(StandalonePrice.explainFirst(legacyUser = true, alreadyShown = false))
        assertFalse(StandalonePrice.explainFirst(legacyUser = true, alreadyShown = true))
        assertFalse("a new user sees only the how-to", StandalonePrice.explainFirst(legacyUser = false, alreadyShown = false))
    }
}
