package se.sensnology.spotnav.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * The plan bands' paint: the Home Assistant card's `CROSS_PLAN_BAND_STYLE` values (frontend/test/
 * visual-fixtures.ts), with its CSS pixels read as dp, and the "/" hatch geometry.
 */
class ChartBandStyleTest {
    @Test fun theHatchValuesAreTheCardsCrossClientValues() {
        assertEquals(ChartBandStyle.Hatch(spacingDp = 4f, widthDp = 1.25f, opacity = 0.5f), ChartBandStyle.hatch(ChartBandKind.INSTALLED))
        assertEquals(ChartBandStyle.Hatch(spacingDp = 6f, widthDp = 1f, opacity = 0.3f), ChartBandStyle.hatch(ChartBandKind.PROPOSAL))
        assertEquals(45, ChartBandStyle.HATCH_ANGLE_DEGREES)
        assertEquals(128, ChartBandStyle.INSTALLED_HATCH.alpha)
        assertEquals(77, ChartBandStyle.PROPOSAL_HATCH.alpha)
    }

    @Test fun theInstalledHatchIsDenserAndStrongerThanTheProposals() {
        val installed = ChartBandStyle.INSTALLED_HATCH
        val proposal = ChartBandStyle.PROPOSAL_HATCH
        assertTrue(installed.spacingDp < proposal.spacingDp)
        assertTrue(installed.widthDp > proposal.widthDp)
        assertTrue(installed.opacity > proposal.opacity)
    }

    @Test fun todaysBandsKeepTheChartsOneSolidOpacity() {
        assertEquals(38, ChartBandStyle.SOLID_ALPHA)
    }

    @Test fun theLinesRunFromLowerLeftToUpperRightAtFortyFiveDegrees() {
        val lines = ChartBandStyle.lines(left = 100f, top = 10f, right = 200f, bottom = 60f, spacingPx = 4f)
        assertTrue(lines.isNotEmpty())
        lines.forEach { line ->
            assertEquals(60f, line.y0, 0f)
            assertEquals(10f, line.y1, 0f)
            assertEquals(line.x1 - line.x0, line.y0 - line.y1, 1e-3f)
        }
        // Spacing is perpendicular to the lines: sqrt(2) times it along x.
        lines.zipWithNext().forEach { (a, b) -> assertEquals(4f * sqrt(2f), b.x0 - a.x0, 1e-3f) }
    }

    @Test fun theLinesCoverTheWholeRectangleAndNoMore() {
        val lines = ChartBandStyle.lines(left = 100f, top = 10f, right = 200f, bottom = 60f, spacingPx = 4f)
        // The first line reaches the left edge, the last starts at or before the right edge.
        assertTrue(lines.first().x1 >= 100f && lines.first().x1 - 4f * sqrt(2f) < 100f)
        assertTrue(lines.last().x0 <= 200f && lines.last().x0 + 4f * sqrt(2f) > 200f)
    }

    @Test fun neighbouringBandsShareOnePattern() {
        val a = ChartBandStyle.lines(0f, 0f, 50f, 20f, 6f, originX = 0f)
        val b = ChartBandStyle.lines(50f, 0f, 90f, 20f, 6f, originX = 0f)
        val step = 6f * sqrt(2f)
        (a + b).forEach { line ->
            val k = line.x0 / step
            assertEquals(Math.round(k).toFloat(), k, 1e-3f)
        }
    }

    @Test fun anEmptyRectangleHasNoLines() {
        assertTrue(ChartBandStyle.lines(10f, 0f, 10f, 20f, 4f).isEmpty())
        assertTrue(ChartBandStyle.lines(0f, 0f, 10f, 20f, 0f).isEmpty())
    }
}
