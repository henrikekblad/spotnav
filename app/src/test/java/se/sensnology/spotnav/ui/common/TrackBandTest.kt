package se.sensnology.spotnav.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** The minimum's part of the plan's target slider is the fill's own colour, darker. */
class TrackBandTest {
    @Test fun darkerKeepsTheHueAndAlphaAndScalesTowardBlack() {
        assertEquals(0xFF000000.toInt(), darker(0xFF65B9F0.toInt(), 1f))
        assertEquals(0xFF65B9F0.toInt(), darker(0xFF65B9F0.toInt(), 0f))
        assertEquals(0x80326078, darker(0x8064C0F0.toInt(), 0.5f).toLong() and 0xFFFFFFFFL)
    }
}
