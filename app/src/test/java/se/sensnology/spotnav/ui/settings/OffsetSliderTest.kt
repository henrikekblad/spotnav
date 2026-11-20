package se.sensnology.spotnav.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import se.sensnology.spotnav.ha.heat.HeatWrite

class OffsetSliderTest {
    @Test fun theSliderRunsFromMinusThreeToPlusThreeInHalfDegreesWithZeroInTheMiddle() {
        assertEquals(12, OffsetSlider.steps())
        assertEquals(-3.0, OffsetSlider.value(0), 0.0)
        assertEquals(0.0, OffsetSlider.value(6), 0.0)
        assertEquals(3.0, OffsetSlider.value(12), 0.0)
        assertEquals(-2.5, OffsetSlider.value(1), 0.0)
        // Always a half step the API takes, inside its range.
        for (p in 0..OffsetSlider.steps()) {
            val v = OffsetSlider.value(p)
            assertEquals(true, HeatWrite.isHalfStep(v) && v >= HeatWrite.OFFSET_MIN && v <= HeatWrite.OFFSET_MAX)
        }
    }

    @Test fun aValueFindsItsPositionAndAStrayOneTheNearestHalfDegree() {
        assertEquals(6, OffsetSlider.progress(0.0))
        assertEquals(8, OffsetSlider.progress(1.0))
        assertEquals(5, OffsetSlider.progress(-0.5))
        assertEquals(7, OffsetSlider.progress(0.4))
        assertEquals(0, OffsetSlider.progress(-9.0))
        assertEquals(12, OffsetSlider.progress(9.0))
    }
}
