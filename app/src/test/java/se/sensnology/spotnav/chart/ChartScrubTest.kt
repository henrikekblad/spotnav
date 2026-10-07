package se.sensnology.spotnav.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.LocalTime
import java.time.OffsetDateTime

/** Dragging across the in-app graph: gesture classification and scrub results. */
class ChartScrubTest {

    /** The calculation inputs for these widget settings. */
    private fun inputs(settings: WidgetSettings): PlanningInputs = LocalPlanningInputs.of(settings)
    @Before
    fun loadCatalogue() {
        PriceMarkets.replace(listOf(RelayFixtures.se4))
    }

    /** The 8 dp slop a phone reports, in the units these movements are in. */
    private val slop = 8f

    /** The metrics a 840x440 plan-card graph is drawn with: the real numbers. */
    private val metrics = ChartLayout.metrics(ChartProfile.PLAN_CARD, 840, 440, 2f, hasChargingPlan = false) { it * 6f }

    /** A whole day of quarter-hour prices from midnight: what a scrub moves across. */
    private val result = PriceResult(
        (0 until 96).map { slot ->
            PricePoint(OffsetDateTime.parse("2026-09-20T00:00:00+02:00").plusMinutes(slot * 15L), slot.toDouble())
        },
        emptyList(),
        0L
    )

    @Test
    fun belowTheSlopTheGestureIsStillATap() {
        assertEquals(ChartGesture.UNDECIDED, ChartScrub.onMove(ChartGesture.UNDECIDED, 3f, 4f, slop))
        assertEquals(ChartGesture.UNDECIDED, ChartScrub.onMove(ChartGesture.UNDECIDED, 0f, 7.9f, slop))
        assertEquals(ChartGesture.UNDECIDED, ChartScrub.onMove(ChartGesture.UNDECIDED, 7f, 3f, slop))

        // And exactly the slop is enough: "below" is strict.
        assertEquals(ChartGesture.SCRUB, ChartScrub.onMove(ChartGesture.UNDECIDED, slop, 0f, slop))
    }

    @Test
    fun aSidewaysMovementStartsAScrub() {
        assertEquals(ChartGesture.SCRUB, ChartScrub.onMove(ChartGesture.UNDECIDED, 30f, 5f, slop))
        assertEquals(ChartGesture.SCRUB, ChartScrub.onMove(ChartGesture.UNDECIDED, -30f, 20f, slop))
    }

    @Test
    fun aDownwardMovementIsThePages() {
        assertEquals(ChartGesture.SCROLL, ChartScrub.onMove(ChartGesture.UNDECIDED, 5f, 30f, slop))
        assertEquals(ChartGesture.SCROLL, ChartScrub.onMove(ChartGesture.UNDECIDED, -20f, -30f, slop))
        assertEquals(ChartGesture.SCROLL, ChartScrub.onMove(ChartGesture.UNDECIDED, 25f, 25f, slop))
    }

    @Test
    fun whatTheFirstSlopDecidedHoldsForTheRestOfTheGesture() {
        assertEquals(ChartGesture.SCRUB, ChartScrub.onMove(ChartGesture.SCRUB, 4f, 120f, slop))
        assertEquals(ChartGesture.SCRUB, ChartScrub.onMove(ChartGesture.SCRUB, -300f, -300f, slop))
        // And a page scroll is not taken back by the graph halfway down it.
        assertEquals(ChartGesture.SCROLL, ChartScrub.onMove(ChartGesture.SCROLL, 200f, 4f, slop))
    }

    @Test
    fun aScrubIsClampedToThePlotSoItsEndsAreReachable() {
        assertNull(ChartSelection.nearest(metrics.left - 40f, metrics, inputs(WidgetSettings()), result))
        assertNull(ChartSelection.nearest(metrics.right + 40f, metrics, inputs(WidgetSettings()), result))

        val first = ChartSelection.nearest(ChartScrub.clampedX(metrics.left - 40f, metrics.left, metrics.right), metrics, inputs(WidgetSettings()), result)
        val last = ChartSelection.nearest(ChartScrub.clampedX(metrics.right + 40f, metrics.left, metrics.right), metrics, inputs(WidgetSettings()), result)
        assertEquals(LocalTime.MIDNIGHT, first!!.time)
        assertEquals(LocalTime.of(23, 45), last!!.time)
        assertEquals(
            ChartScrub.clampedX(metrics.left - 40f, metrics.left, metrics.right),
            ChartScrub.clampedX(metrics.left - 4000f, metrics.left, metrics.right),
            0.0001f
        )
    }

    @Test
    fun aDragInsideOneMarkIsTheSameSelection() {
        val settings = WidgetSettings()
        val first = ChartSelection.nearest(metrics.xAt(14f), metrics, inputs(settings), result)
        assertNotNull(first)
        assertEquals(first, ChartSelection.nearest(metrics.xAt(21f), metrics, inputs(settings), result))
        assertNotEquals(first, ChartSelection.nearest(metrics.xAt(30f), metrics, inputs(settings), result))
    }

    @Test
    fun anHourlyScrubFollowsTheSameCentreTheMarkIsDrawnAt() {
        val hourly = PriceResult(result.today.map { it.copy(pricePerKwh = (it.start.hour).toDouble(), minutes = 60) }, emptyList(), 0L)
        val chosen = ChartSelection.nearest(metrics.xAt(9 * 60f + 30f), metrics, inputs(WidgetSettings()), hourly)
        assertEquals(LocalTime.of(9, 0), chosen!!.time)
        assertEquals("the line lands on the hour's own centre", 9 * 60f + 30f, chosen.markMinute, 0.001f)
    }
}
