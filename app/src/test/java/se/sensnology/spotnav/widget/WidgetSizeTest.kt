package se.sensnology.spotnav.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSizeTest {
    @Test fun inPortraitTheWidgetIsAsNarrowAsItsMinimumAndAsTallAsItsMaximum() {
        assertEquals(300 * 3 to 200 * 3, WidgetSize.pixels(300, 150, 380, 200, portrait = true, density = 3f))
    }

    @Test fun inLandscapeTheWidgetIsAsWideAsItsMaximumAndAsLowAsItsMinimum() {
        assertEquals(380 * 2 to 150 * 2, WidgetSize.pixels(300, 150, 380, 200, portrait = false, density = 2f))
    }

    @Test fun aMissingSideFallsBackToTheOtherBoundThenToTheDefault() {
        assertEquals(380 to 200, WidgetSize.pixels(0, 0, 380, 200, portrait = true, density = 1f))
        assertEquals(
            WidgetSize.DEFAULT_WIDTH_DP to WidgetSize.DEFAULT_HEIGHT_DP,
            WidgetSize.pixels(0, 0, 0, 0, portrait = true, density = 1f)
        )
    }
}
