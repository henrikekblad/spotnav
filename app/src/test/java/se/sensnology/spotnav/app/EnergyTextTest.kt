package se.sensnology.spotnav.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class EnergyTextTest {
    @Test fun aWholeAmountStillHasOneDecimal() {
        assertEquals("20.0 kWh", EnergyText.kwh(20.0, Locale.ROOT))
        assertEquals("30.0 kWh", EnergyText.kwh(30.0, Locale.US))
    }

    @Test fun theDigitsAreWrittenInTheScreensNumberLocale() {
        assertEquals("30,0 kWh", EnergyText.kwh(30.0, Locale.forLanguageTag("sv")))
        assertEquals("12,3 kWh", EnergyText.kwh(12.34, Locale.forLanguageTag("nb")))
    }
}
