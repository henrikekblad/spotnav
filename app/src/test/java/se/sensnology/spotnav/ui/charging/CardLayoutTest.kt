package se.sensnology.spotnav.ui.charging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one decision behind the two object cards' row: is this screen wide enough for it. */
class CardLayoutTest {
    @Test fun aPhoneStacksTheCards() {
        // The test device: ~411 dp.
        assertFalse(CardLayout.sideBySide(411))
        assertFalse(CardLayout.sideBySide(360))
        assertFalse(CardLayout.sideBySide(480))
    }

    @Test fun aTabletPutsThemInOneRow() {
        assertTrue(CardLayout.sideBySide(600))
        assertTrue(CardLayout.sideBySide(800))
        assertTrue(CardLayout.sideBySide(1280))
    }

    @Test fun theThresholdItselfIsTheFirstWidthThatFits() {
        assertFalse(CardLayout.sideBySide(CardLayout.SIDE_BY_SIDE_MIN_WIDTH_DP - 1))
        assertTrue(CardLayout.sideBySide(CardLayout.SIDE_BY_SIDE_MIN_WIDTH_DP))
    }

    @Test fun adegenerateWidthStacks() {
        assertFalse(CardLayout.sideBySide(0))
        assertFalse(CardLayout.sideBySide(-1))
    }
}
