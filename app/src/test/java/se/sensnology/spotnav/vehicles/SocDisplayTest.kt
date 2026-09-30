package se.sensnology.spotnav.vehicles

import org.junit.Assert.assertEquals
import org.junit.Test

/** How a state of charge is shown: whole percent, rounded to nearest. */
class SocDisplayTest {
    @Test
    fun anExactValueIsShownUnchanged() {
        assertEquals(82, SocDisplay.wholePercent(82.0))
        assertEquals(0, SocDisplay.wholePercent(0.0))
        assertEquals(100, SocDisplay.wholePercent(100.0))
    }

    @Test
    fun aValueBelowTheHalfRoundsDown() {
        assertEquals(82, SocDisplay.wholePercent(82.4))
        assertEquals(7, SocDisplay.wholePercent(7.49))
    }

    @Test
    fun aValueAboveTheHalfRoundsUp() {
        assertEquals(83, SocDisplay.wholePercent(82.6))
        assertEquals(8, SocDisplay.wholePercent(7.51))
        // The case a truncating conversion gets wrong: 82.6 must not read 82.
        assertEquals(83, SocDisplay.wholePercent(82.9))
    }

    @Test
    fun exactlyAHalfRoundsUpRatherThanTruncating() {
        assertEquals(83, SocDisplay.wholePercent(82.5))
        assertEquals(8, SocDisplay.wholePercent(7.5))
    }

    @Test
    fun aNonNegativeRealisticReadingIsNeverNegative() {
        // Realistic values, and the defensive floor for the ones a car cannot report: the card's
        // row is only visible when a state of charge exists, so this is about never showing a
        // nonsense percent, not about a path the app expects to take.
        assertEquals(1, SocDisplay.wholePercent(0.6))
        assertEquals(0, SocDisplay.wholePercent(-0.4))
        assertEquals(0, SocDisplay.wholePercent(Double.NaN))
    }
}
