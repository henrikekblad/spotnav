package se.sensnology.spotnav.ui.site

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodPickerLayoutTest {
    // 3x dp units in px: arrows 2*44, segment 190, minimum label 110.
    @Test fun aWideRowKeepsTheSegmentBesideTheDate() {
        assertFalse(PeriodPickerLayout.stacked(available = 440, arrows = 88, segment = 190, minLabel = 110))
        assertFalse(PeriodPickerLayout.stacked(available = 388, arrows = 88, segment = 190, minLabel = 110))
    }

    @Test fun aNarrowRowPutsTheSegmentOnARowOfItsOwn() {
        assertTrue(PeriodPickerLayout.stacked(available = 387, arrows = 88, segment = 190, minLabel = 110))
        assertTrue(PeriodPickerLayout.stacked(available = 300, arrows = 88, segment = 190, minLabel = 110))
    }
}
