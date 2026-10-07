package se.sensnology.spotnav.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.math.roundToInt

/** When the charging-plan card's graph is drawn, at what size, and when it must redraw. */
class PlanChartTest {
    private val settings = WidgetSettings(area = "SE4")

    /** The calculation inputs for these widget settings. */
    private fun inputs(settings: WidgetSettings): PlanningInputs = LocalPlanningInputs.of(settings)

    private fun prices(vararg values: Double): PriceResult {
        val start = OffsetDateTime.parse("2026-09-12T00:00:00+02:00")
        return PriceResult(
            values.mapIndexed { index, value -> PricePoint(start.plusMinutes(index * 15L), value) },
            emptyList(),
            0L
        )
    }

    @Test
    fun aGraphIsNeverRequestedBeforeThereIsAWidthToDrawInto() {
        assertNull(ChartRequests.of(inputs(settings), prices(0.5), 0, ChartProfile.PLAN_CARD))
        assertNull(ChartRequests.of(inputs(settings), null, 0, ChartProfile.PLAN_CARD))
        assertNull(ChartRequests.of(inputs(settings), prices(0.5), ChartRequests.MIN_WIDTH_PX - 1, ChartProfile.PLAN_CARD))
        assertEquals(0, ChartRequests.heightFor(0, ChartProfile.PLAN_CARD))
        assertEquals(0, ChartRequests.heightFor(ChartRequests.MIN_WIDTH_PX - 1, ChartProfile.PLAN_CARD))
        assertEquals(0, ChartRequests.heightFor(0, ChartProfile.WIDGET))
    }

    @Test
    fun theHeightFollowsTheAspectRatioAtEveryWidth() {
        val width = 1000
        val height = ChartRequests.heightFor(width, ChartProfile.PLAN_CARD)

        assertEquals((width * ChartRequests.PLAN_CARD_HEIGHT_PER_WIDTH).roundToInt(), height)
        assertTrue("a $width px card draws a $height px graph", height < width / 2)
        // The same width is always the same height, and the request agrees.
        assertEquals(height, ChartRequests.heightFor(width, ChartProfile.PLAN_CARD))
        assertEquals(height, ChartRequests.of(inputs(settings), prices(0.5), width, ChartProfile.PLAN_CARD)!!.heightPx)
    }

    @Test
    fun eachProfileHasItsOwnShapeAndTheWidgetKeepsItsOwn() {
        assertEquals(220f / 420f, ChartRequests.WIDGET_HEIGHT_PER_WIDTH, 1e-6f)
        assertEquals(220f / 420f, ChartRequests.heightPerWidth(ChartProfile.WIDGET), 1e-6f)
        assertEquals((876 * 220f / 420f).roundToInt(), ChartRequests.heightFor(876, ChartProfile.WIDGET))

        assertTrue(
            "the card's ratio is the measured widget shape",
            ChartRequests.PLAN_CARD_HEIGHT_PER_WIDTH in 0.41f..0.42f
        )
        assertEquals(
            ChartRequests.PLAN_CARD_HEIGHT_PER_WIDTH,
            ChartRequests.heightPerWidth(ChartProfile.PLAN_CARD),
            1e-6f
        )

        val card = ChartRequests.heightFor(1000, ChartProfile.PLAN_CARD)
        val widget = ChartRequests.heightFor(1000, ChartProfile.WIDGET)
        assertTrue("the card's graph is shorter", card < widget)
        assertEquals(
            (widget * ChartRequests.PLAN_CARD_HEIGHT_PER_WIDTH / ChartRequests.WIDGET_HEIGHT_PER_WIDTH).roundToInt(),
            card
        )
        // The width is the same decision in both: it is the parent's, not the profile's.
        assertEquals(
            ChartRequests.of(inputs(settings), prices(0.5), 1000, ChartProfile.WIDGET)!!.widthPx,
            ChartRequests.of(inputs(settings), prices(0.5), 1000, ChartProfile.PLAN_CARD)!!.widthPx
        )
    }

    @Test
    fun emptyPriceDataIsDrawnAsTheRenderersOwnEmptyState() {
        val request = ChartRequests.of(inputs(settings), null, 1000, ChartProfile.PLAN_CARD)!!
        assertTrue(request.result.today.isEmpty())
        assertTrue(request.result.tomorrow.isEmpty())
        assertEquals(request, ChartRequests.of(inputs(settings), null, 1000, ChartProfile.PLAN_CARD))
    }

    @Test
    fun theInputsAreTheWholeInvalidationRule() {
        val result = prices(0.5, 0.6)
        val base = ChartRequests.of(inputs(settings), result, 1000, ChartProfile.PLAN_CARD)!!

        // Same inputs, same size: nothing to redraw.
        assertEquals(base, ChartRequests.of(inputs(settings), result, 1000, ChartProfile.PLAN_CARD))
        // Every input that can change what the widget's renderer draws.
        assertNotEquals("a new area", base, ChartRequests.of(inputs(settings.copy(area = "NO1")), result, 1000, ChartProfile.PLAN_CARD))
        assertEquals(
            "with nothing to shade, the shading switch is not a different picture",
            base,
            ChartRequests.of(inputs(settings), result, 1000, ChartProfile.PLAN_CARD, null, showPlan = false)
        )
        assertNotEquals(
            "the plan's shading",
            base,
            ChartRequests.of(
                base.market,
                listOf(ChartBand(LocalDate.of(2026, 9, 12), 60f, 120f)),
                result,
                1000,
                ChartProfile.PLAN_CARD
            )
        )
        assertNotEquals("new prices", base, ChartRequests.of(inputs(settings), prices(0.5, 0.7), 1000, ChartProfile.PLAN_CARD))
        // And the card being a different width, which is a different graph.
        assertNotEquals("a wider card", base, ChartRequests.of(inputs(settings), result, 1200, ChartProfile.PLAN_CARD))
        assertNotEquals("the widget profile", base, ChartRequests.of(inputs(settings), result, 1000, ChartProfile.WIDGET))
        assertEquals(
            base,
            ChartRequests.of(inputs(settings), result, 1000, ChartProfile.PLAN_CARD)
        )
    }

    @Test
    fun aSelectionIsADifferentPictureOfTheSameData() {
        val result = prices(0.5, 0.6)
        assertNull(ChartRequests.of(inputs(settings), result, 1000, ChartProfile.PLAN_CARD, null)!!.selectionMinute)
        assertNull("the default is no line", ChartRequests.of(inputs(settings), result, 1000, ChartProfile.PLAN_CARD)!!.selectionMinute)

        val atMidnight = ChartRequests.of(inputs(settings), result, 1000, ChartProfile.PLAN_CARD, 0f)!!
        val atHalfPast = ChartRequests.of(inputs(settings), result, 1000, ChartProfile.PLAN_CARD, 30f)!!
        assertEquals(0f, atMidnight.selectionMinute!!, 0f)
        // A different position is a different frame: the line has to move.
        assertNotEquals(atMidnight, atHalfPast)
        assertNotEquals("selecting changes the frame", atMidnight, ChartRequests.of(inputs(settings), result, 1000, ChartProfile.PLAN_CARD))
        assertNotEquals("so does putting it away", atMidnight, ChartRequests.of(inputs(settings), result, 1000, ChartProfile.PLAN_CARD, null))
        // The same selection on the same graph is the same frame: no redraw.
        assertEquals(atHalfPast, ChartRequests.of(inputs(settings), result, 1000, ChartProfile.PLAN_CARD, 30f))
    }
}
