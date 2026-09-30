package se.sensnology.spotnav.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rail a `SeekBar`'s thumb centre travels over, and where its tick labels go. */
class SliderTicksTest {
    /** The period slider's own shape: 240 px wide, 16 px padding, a 48 px thumb. */
    private val half =
        SliderTicks.rail(viewWidth = 240, paddingLeft = 16, paddingRight = 16, thumbWidth = 48, thumbOffset = 24)!!

    @Test
    fun theFractionIsTheSeekBarsOwnScaleAtMinMiddleAndMax() {
        // 1..8, the period slider: the minimum is 0.0, not 1/8.
        assertEquals(0f, SliderTicks.fraction(1, 1, 8), 0f)
        assertEquals(1f, SliderTicks.fraction(8, 1, 8), 0f)
        assertEquals(3f / 7f, SliderTicks.fraction(4, 1, 8), 1e-6f)
        assertEquals(0f, SliderTicks.fraction(0, 0, 10), 0f)
        assertEquals(0.5f, SliderTicks.fraction(5, 0, 10), 0f)
        assertEquals(1f, SliderTicks.fraction(10, 0, 10), 0f)
        // The amps slider's own range.
        assertEquals(0f, SliderTicks.fraction(6, 6, 16), 0f)
        assertEquals(0.4f, SliderTicks.fraction(10, 6, 16), 1e-6f)
        assertEquals(1f, SliderTicks.fraction(16, 6, 16), 0f)
    }

    @Test
    fun aDegenerateOrOutOfRangeSliderFailsSafely() {
        // No division by zero, and never a position off the rail.
        assertEquals(0f, SliderTicks.fraction(3, 3, 3), 0f)
        assertEquals(0f, SliderTicks.fraction(3, 5, 3), 0f)
        assertEquals(0f, SliderTicks.fraction(-4, 1, 8), 0f)
        assertEquals(1f, SliderTicks.fraction(99, 1, 8), 0f)
    }

    @Test
    fun theDefaultHalfThumbOffsetPutsTheCentresOnThePaddingEdges() {
        assertEquals(16f, half.startCenter, 1e-4f)
        assertEquals(224f, half.endCenter, 1e-4f)
        assertEquals(208f, half.travel, 1e-4f)
        assertEquals(16f, SliderTicks.centerX(1, 1, 8, half, mirrored = false), 1e-4f)
        assertEquals(224f, SliderTicks.centerX(8, 1, 8, half, mirrored = false), 1e-4f)
        assertEquals(16f + 3f / 7f * 208f, SliderTicks.centerX(4, 1, 8, half, mirrored = false), 1e-4f)
    }

    @Test
    fun aNonDefaultThumbOffsetFollowsTheFrameworksOwnFormula() {
        // A thumb not inset by half its width:
        val odd = SliderTicks.rail(viewWidth = 240, paddingLeft = 16, paddingRight = 16, thumbWidth = 48, thumbOffset = 10)!!

        assertEquals(30f, odd.startCenter, 1e-4f)
        assertEquals(210f, odd.endCenter, 1e-4f)
        assertEquals(180f, odd.travel, 1e-4f)
        assertEquals(30f, SliderTicks.centerX(1, 1, 8, odd, mirrored = false), 1e-4f)
        assertEquals(210f, SliderTicks.centerX(8, 1, 8, odd, mirrored = false), 1e-4f)

        // Asymmetric padding too: start = 4 - 10 + 24, travel = 240-4-30-48+20.
        val lopsided =
            SliderTicks.rail(viewWidth = 240, paddingLeft = 4, paddingRight = 30, thumbWidth = 48, thumbOffset = 10)!!
        assertEquals(18f, lopsided.startCenter, 1e-4f)
        assertEquals(18f + 178f, lopsided.endCenter, 1e-4f)
    }

    @Test
    fun aSliderWithNoThumbStillHasARailAcrossItsContentBox() {
        // Nothing to inset for: the formula says the travel is the content box.
        val bare = SliderTicks.rail(viewWidth = 240, paddingLeft = 16, paddingRight = 16, thumbWidth = 0, thumbOffset = 0)!!

        assertEquals(16f, bare.startCenter, 1e-4f)
        assertEquals(224f, bare.endCenter, 1e-4f)
    }

    @Test
    fun invalidOrNonPositiveTravelHasNoRailAtAll() {
        assertNull("no measured width yet", SliderTicks.rail(0, 16, 16, 48, 24))
        // A thumb wider than the box it has to travel in:
        assertNull(SliderTicks.rail(40, 16, 16, 48, 10))
        // Exactly zero travel is no rail either: there is one position, not several.
        assertNull(SliderTicks.rail(80, 16, 16, 48, 0))
        assertTrue(SliderTicks.rail(81, 16, 16, 48, 0) != null)
    }

    @Test
    fun aMirroredSliderReversesTheEndsAndKeepsTheMidpoint() {
        // The same rail, read right to left:
        assertEquals(224f, SliderTicks.centerX(1, 1, 8, half, mirrored = true), 1e-4f)
        assertEquals(16f, SliderTicks.centerX(8, 1, 8, half, mirrored = true), 1e-4f)

        // 1..9's middle value is the geometric middle, so it does not move at all.
        val middleLtr = SliderTicks.centerX(5, 1, 9, half, mirrored = false)
        assertEquals(120f, middleLtr, 1e-4f)
        assertEquals(middleLtr, SliderTicks.centerX(5, 1, 9, half, mirrored = true), 1e-4f)

        for (value in 1..9) {
            val ltr = SliderTicks.centerX(value, 1, 9, half, mirrored = false)
            val rtl = SliderTicks.centerX(value, 1, 9, half, mirrored = true)
            assertEquals("value $value", half.startCenter + half.endCenter - ltr, rtl, 1e-4f)
        }
    }

    @Test
    fun labelsOfDifferentWidthsKeepTheirTicksAndAreOnlyClampedAtTheEdges() {
        val rowWidth = 240f
        val tickMid = SliderTicks.centerX(4, 1, 8, half, mirrored = false)
        val tickMax = SliderTicks.centerX(8, 1, 8, half, mirrored = false)

        // Narrow, medium and wide labels on the middle tick all land on the same centre:
        for (labelWidth in listOf(7f, 8f, 40f)) {
            val labelLeft = SliderTicks.labelLeft(tickMid, labelWidth, 0f, rowWidth)
            assertEquals("label width $labelWidth", tickMid, labelLeft + labelWidth / 2f, 1e-4f)
        }

        // The ticks themselves are the same numbers whatever the labels do.
        assertEquals(16f, SliderTicks.centerX(1, 1, 8, half, mirrored = false), 1e-4f)
        assertEquals(224f, tickMax, 1e-4f)

        // A 60 px label on the last tick would end 14 px past the row, so it is moved back by
        // exactly those 14 px and no further.
        val overflowing = SliderTicks.labelLeft(tickMax, 60f, 0f, rowWidth)
        assertEquals(rowWidth - 60f, overflowing, 1e-4f)
        assertEquals(14f, tickMax + 30f - rowWidth, 1e-4f)
        assertEquals(tickMax - 30f - 14f, overflowing, 1e-4f)
    }
}
