package se.sensnology.spotnav.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two rendering profiles, compared by measured geometry. */
class ChartLayoutTest {
    /** A stand-in for `Paint.measureText`: wide text, so the fit rule is exercised. */
    private val measure = { textSize: Float -> textSize * 6f }

    private fun metrics(
        profile: ChartProfile,
        width: Int = 840,
        height: Int = 440,
        scaledDensity: Float = 2f,
        hasPlan: Boolean = true
    ) = ChartLayout.metrics(profile, width, height, scaledDensity, hasPlan, measure)

    @Test
    fun theWidgetProfileKeepsItsOwnSizesAndItsFooter() {
        val widget = metrics(ChartProfile.WIDGET)

        // The sizes the widget has always drawn with, straight from its density.
        assertEquals(38f, widget.headerTextSize, 1e-4f)
        assertEquals(28f, widget.axisTextSize, 1e-4f)
        assertEquals(56f, widget.footerHeight, 1e-4f)
        assertEquals(ChartLayout.scaleFor(840, 440) * 14f, widget.pad, 1e-4f)
        assertEquals(widget.pad + 38f * 1.65f, widget.top, 1e-4f)
        assertEquals(440f - widget.pad - 28f * 1.35f - 56f, widget.bottom, 1e-4f)
        assertTrue("the footer is the profile's own feature", ChartProfile.WIDGET.footer)

        // No plan to describe: the footer's space is not reserved.
        assertEquals(0f, metrics(ChartProfile.WIDGET, hasPlan = false).footerHeight, 1e-4f)
        assertTrue(metrics(ChartProfile.WIDGET, hasPlan = false).plotHeight > widget.plotHeight)
    }

    @Test
    fun thePlanCardProfileDropsTheFooterAndGivesItsSpaceToThePlot() {
        val widget = metrics(ChartProfile.WIDGET)
        val planCard = metrics(ChartProfile.PLAN_CARD)

        assertFalse("the plan card draws no footer", ChartProfile.PLAN_CARD.footer)
        assertEquals(0f, planCard.footerHeight, 1e-4f)
        assertEquals(widget.top, planCard.top, 1e-4f)
        assertEquals(widget.footerHeight, planCard.bottom - widget.bottom, 1e-4f)
        assertEquals(widget.footerHeight, planCard.plotHeight - widget.plotHeight, 1e-4f)
        assertTrue("the card's plot is taller", planCard.plotHeight > widget.plotHeight)
    }

    @Test
    fun thePlanCardTextIsDenserOnACanvasThatWouldOtherwiseSpendItOnWords() {
        // A phone-sized card: 292 dp wide at 3x, the widget's own 420:220 shape.
        val width = 876
        val height = 456
        val widget = metrics(ChartProfile.WIDGET, width, height, scaledDensity = 3f)
        val planCard = metrics(ChartProfile.PLAN_CARD, width, height, scaledDensity = 3f)

        assertEquals(57f, widget.headerTextSize, 1e-4f)
        assertEquals(42f, widget.axisTextSize, 1e-4f)
        assertTrue("capped header", planCard.headerTextSize < widget.headerTextSize)
        assertTrue("capped axis", planCard.axisTextSize < widget.axisTextSize)
        assertEquals(456f * 0.115f, planCard.headerTextSize, 1e-4f)
        assertEquals(456f * 0.082f, planCard.axisTextSize, 1e-4f)
        assertTrue(planCard.headerTextSize >= 14f * 3f - 1e-4f)
        assertTrue(widget.plotHeight < planCard.plotHeight)
    }

    @Test
    fun bothProfilesShareTheSameGeometryScaleAndPriceBoxSoTheDataCannotDiffer() {
        // On a canvas where the caps bite, so the axis text really does differ.
        val width = 876
        val height = 456
        val widget = metrics(ChartProfile.WIDGET, width, height, scaledDensity = 3f)
        val planCard = metrics(ChartProfile.PLAN_CARD, width, height, scaledDensity = 3f)

        assertEquals(widget.scale, planCard.scale, 0f)
        assertEquals(widget.pad, planCard.pad, 0f)
        assertEquals(ChartLayout.scaleFor(width, height), planCard.scale, 1e-6f)
        assertEquals(widget.right, planCard.right, 0f)
        assertTrue(planCard.left < widget.left)
        assertTrue(planCard.plotWidth > widget.plotWidth)
    }

    @Test
    fun aMinuteOfTheDayAndItsPixelAreOneMappingBothWays() {
        val m = metrics(ChartProfile.PLAN_CARD)

        assertEquals(m.left, m.xAt(0f), 1e-4f)
        assertEquals(m.left + m.plotWidth / 2f, m.xAt(12 * 60f), 1e-4f)
        assertEquals(m.right, m.xAt(24 * 60f), 1e-4f)
        assertEquals(m.left + 30f / 1440f * m.plotWidth, m.xAt(30f), 1e-4f)
        assertEquals(m.xAt(30f), m.xAt(0f) + m.plotWidth * 30f / 1440f, 1e-4f)

        assertEquals(0f, m.minuteOfDayAt(m.left)!!, 1e-4f)
        assertEquals(12 * 60f, m.minuteOfDayAt(m.xAt(12 * 60f))!!, 1e-3f)
        assertEquals(24 * 60f, m.minuteOfDayAt(m.right)!!, 1e-4f)
        assertEquals(30f, m.minuteOfDayAt(m.xAt(30f))!!, 1e-3f)
        assertNull(m.minuteOfDayAt(m.left - 1f))
        assertNull(m.minuteOfDayAt(m.right + 1f))
        assertNull(ChartMetrics(1f, 0f, 0f, 0f, 0f, 0f, 0f, 10f, 10f).minuteOfDayAt(10f))
    }

    @Test
    fun theFitRuleStillShrinksAHeaderThatWouldOverlapItsOwnNumbers() {
        val tiny = ChartLayout.metrics(ChartProfile.WIDGET, 180, 120, 3f, true) { 900f }
        assertEquals(14f * 3f, tiny.headerTextSize, 1e-4f)
        assertTrue(tiny.headerTextSize < 19f * 3f)

        val degenerate = ChartLayout.metrics(ChartProfile.PLAN_CARD, 0, 0, 1f, false) { 0f }
        assertTrue(degenerate.plotHeight > 0f)
        assertNotEquals(0f, degenerate.right)
        assertTrue(degenerate.plotWidth > 0f)
    }
}
