package se.sensnology.spotnav.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The words under a slider's marks ("nu", "gräns", "fullt"): each centred under its mark and kept inside
 * the row, and a word that would touch another goes one level down, so two never overlap.
 */
class SliderMarksTest {
    private val gap = 8f

    @Test fun marksFarApartShareTheFirstLevelUnderTheirTicks() {
        val placed = SliderMarks.place(
            listOf(SliderMarks.Mark("now", 100f, 40f), SliderMarks.Mark("limit", 300f, 50f)), 400f, gap
        )
        assertEquals(listOf(SliderMarks.Placed("now", 80f, 0), SliderMarks.Placed("limit", 275f, 0)), placed)
    }

    @Test fun marksCloseTogetherGoOnDifferentLevels() {
        val placed = SliderMarks.place(
            listOf(SliderMarks.Mark("limit", 310f, 50f), SliderMarks.Mark("now", 300f, 40f)), 400f, gap
        )
        // In track order: "now" first on the first level, "limit" on the second.
        assertEquals(listOf(SliderMarks.Placed("now", 280f, 0), SliderMarks.Placed("limit", 285f, 1)), placed)
    }

    @Test fun aWordNearAnEndStaysInsideTheRow() {
        val placed = SliderMarks.place(listOf(SliderMarks.Mark("limit", 395f, 50f), SliderMarks.Mark("now", 2f, 40f)), 400f, gap)
        assertEquals(listOf(SliderMarks.Placed("now", 0f, 0), SliderMarks.Placed("limit", 350f, 0)), placed)
    }

    @Test fun justTouchingWithinTheGapIsTooClose() {
        val placed = SliderMarks.place(listOf(SliderMarks.Mark("a", 100f, 40f), SliderMarks.Mark("b", 145f, 40f)), 400f, gap)
        assertEquals(1, placed[1].level)
    }
}
