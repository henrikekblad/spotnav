package se.sensnology.spotnav.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The car's two percent sliders, as the card has them: the target (0..100, whole percent, opening at the
 * planned default when none is stored, marked as such) and the minimum (Off, then 10..80 in fives, never past
 * the target). Nothing is written for a slider that was only drawn; the minimum's shaded part on the plan's
 * target slider runs from 0 to it, its word under the middle.
 */
class PercentSlidersTest {
    @Test fun theMinimumsStopsAreOffThenTenToEightyInFives() {
        assertEquals(listOf(null, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80), FloorSlider.STOPS)
        assertEquals(15, FloorSlider.LAST)
        assertEquals(0, FloorSlider.index(null))
        assertEquals(5, FloorSlider.index(30))
        assertEquals(15, FloorSlider.index(80))
        assertNull(FloorSlider.at(0))
        assertEquals(30, FloorSlider.at(5))
        assertEquals(80, FloorSlider.at(99))
    }

    @Test fun theMinimumStopsAtTheHighestLevelNotAboveTheTarget() {
        assertEquals(15, FloorSlider.capIndex(80.0))
        assertEquals(15, FloorSlider.capIndex(100.0))
        assertEquals(FloorSlider.index(50), FloorSlider.capIndex(50.0))
        assertEquals(FloorSlider.index(50), FloorSlider.capIndex(52.5))
        assertEquals(1, FloorSlider.capIndex(10.0))
        assertEquals(0, FloorSlider.capIndex(9.0))
        assertEquals(FloorSlider.index(50), FloorSlider.clamp(15, 50.0))
        assertEquals(3, FloorSlider.clamp(3, 50.0))
        assertEquals(0, FloorSlider.clamp(4, 5.0))
    }

    @Test fun aTargetNoneStoredOpensAtTheDefaultNoHigherThanTheCarsLimit() {
        assertEquals(80, TargetSlider.default(null))
        assertEquals(80, TargetSlider.default(90.0))
        assertEquals(70, TargetSlider.default(70.6))
        assertEquals(70, TargetSlider.opening(null, 70.0))
        assertEquals(81, TargetSlider.opening(80.6, 90.0))
        assertTrue(TargetSlider.showsDefault(null, moved = false))
        assertFalse(TargetSlider.showsDefault(null, moved = true))
        assertFalse(TargetSlider.showsDefault(80.0, moved = false))
    }

    @Test fun theTargetIsNeverWrittenForOnlyHavingBeenDrawn() {
        assertNull(TargetSlider.toWrite(null, moved = false, value = 80))
        assertNull(TargetSlider.toWrite(80.0, moved = false, value = 80))
        assertNull(TargetSlider.toWrite(80.0, moved = true, value = 80))
        assertEquals(85.0, TargetSlider.toWrite(80.0, moved = true, value = 85)!!, 0.0)
        assertEquals(80.0, TargetSlider.toWrite(null, moved = true, value = 80)!!, 0.0)
    }

    @Test fun theMinimumIsNeverWrittenForOnlyHavingBeenDrawn() {
        assertNull(FloorSlider.toWrite(30, moved = false, index = 0))
        assertNull(FloorSlider.toWrite(30, moved = true, index = FloorSlider.index(30)))
        assertNull(FloorSlider.toWrite(null, moved = true, index = 0))
        assertEquals(FloorSlider.Write(null), FloorSlider.toWrite(30, moved = true, index = 0))
        assertEquals(FloorSlider.Write(50), FloorSlider.toWrite(30, moved = true, index = FloorSlider.index(50)))
    }

    @Test fun theMinimumsSegmentRunsFromZeroToItWithItsWordUnderTheMiddle() {
        assertEquals(FloorSlider.Segment(30, 0.3f, 0.15f), FloorSlider.segment(30, 80.0))
        // Never past the target being chosen.
        assertEquals(FloorSlider.Segment(40, 0.4f, 0.2f), FloorSlider.segment(50, 40.0))
        assertEquals(FloorSlider.Segment(30, 0.3f, 0.15f), FloorSlider.segment(30, null))
        assertNull(FloorSlider.segment(null, 80.0))
        assertNull(FloorSlider.segment(30, 0.0))
    }

    @Test fun theMinimumsWordsAreFilledInFromTheirTemplates() {
        assertEquals("min 30 %", FloorSlider.markText("min %1\$d %%", 30))
        assertEquals("laddmål 50 %", FloorSlider.markText("laddmål %1\$d %%", 50))
    }

    @Test fun theLimitsStopsAreTheRangeAndStepTheCarsLimitTakes() {
        val kia = LimitSlider.stops(ChargeLimitRange(50.0, 100.0, 10.0))
        assertEquals(5, LimitSlider.last(kia))
        assertEquals(50, LimitSlider.at(kia, 0))
        assertEquals(80, LimitSlider.at(kia, 3))
        assertEquals(100, LimitSlider.at(kia, 5))
        assertEquals(100, LimitSlider.at(kia, 9))
        assertEquals(3, LimitSlider.index(kia, 80))
        // A limit between stops, or outside the range, opens at the nearest stop inside it.
        assertEquals(3, LimitSlider.index(kia, 84))
        assertEquals(4, LimitSlider.index(kia, 86))
        assertEquals(0, LimitSlider.index(kia, 30))
        // A range whose top is not a whole step from its bottom ends at the last whole step.
        val uneven = LimitSlider.stops(ChargeLimitRange(50.0, 95.0, 10.0))
        assertEquals(4, LimitSlider.last(uneven))
        assertEquals(90, LimitSlider.at(uneven, LimitSlider.index(uneven, 95)))
    }

    @Test fun theLimitFallsBackToOneToAHundredInWholePercentOnlyWhenUnknown() {
        val fallback = LimitSlider.stops(null)
        assertEquals(ChargeLimitRange(1.0, 100.0, 1.0), fallback)
        assertEquals(99, LimitSlider.last(fallback))
        assertEquals(72, LimitSlider.at(fallback, LimitSlider.index(fallback, 72)))
    }

    @Test fun theLimitIsNeverWrittenForASliderOnlyDrawnOrMovedBack() {
        assertNull(LimitSlider.toWrite(current = 90, moved = false, value = 90))
        assertNull(LimitSlider.toWrite(current = 90, moved = false, value = 80))
        assertNull(LimitSlider.toWrite(current = 90, moved = true, value = 90))
        assertEquals(80, LimitSlider.toWrite(current = 90, moved = true, value = 80))
    }
}
