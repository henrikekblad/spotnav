package se.sensnology.spotnav.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.prices.PriceAggregation
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.OffsetDateTime

/** The mark geometry: one primitive per value, and currentness that cannot resize anything. */
class ChartMarksTest {

    private val se4 = RelayFixtures.se4

    @Before
    fun loadCatalogue() {
        PriceMarkets.replace(listOf(se4))
    }

    private fun market(intervalMinutes: Int = 15): ChartMarket =
        ChartMarket.of(LocalPlanningInputs.of(WidgetSettings(area = "SE4", intervalMinutes = intervalMinutes)))

    private fun at(time: String) = OffsetDateTime.parse(time)

    private fun values(from: String, prices: List<Double>) = prices.mapIndexed { index, price ->
        at(from).plusMinutes(index * 15L) to price
    }

    private val day = values("2026-09-20T00:00:00+02:00", List(96) { 1.0 }).also { it }

    private fun plan(market: ChartMarket, nowMinute: Float?): List<ChartMarkDraw> = ChartMarks.plan(
        market = market,
        values = day,
        radius = ChartMarks.todayRadius(440, 1f),
        hourWidth = 30f,
        nowMinute = nowMinute,
        mean = 1.0,
        cheap = ChartTheme.Dark.cheap,
        expensive = ChartTheme.Dark.expensive
    )

    @Test
    fun aQuarterHourDayIsPointsAndAnHourlyDayIsSegments() {
        val points = ChartMarks.geometry(market(), ChartMarks.todayRadius(440, 1f), 30f)
        assertEquals(ChartMarkKind.POINT, points.kind)
        assertEquals(0f, points.halfLength, 0f)

        val segments = ChartMarks.geometry(market(intervalMinutes = 60), ChartMarks.todayRadius(440, 1f), 30f)
        assertEquals(ChartMarkKind.SEGMENT, segments.kind)
        assertEquals("a segment reaches 39% of its hour either side", 30f * .39f, segments.halfLength, 0.0001f)
        assertEquals(4f * 1f, segments.radius, 0.0001f)
    }

    @Test
    fun theCurrentIntervalResizesNothingAndAddsNoPrimitive() {
        val quarter = market()
        val current = ChartMarks.plan(quarter, day, 4f, 30f, ChartNow.markMinute(quarter, at("2026-09-20T12:15:00+02:00")), 1.0, 1, 2)
        val none = ChartMarks.plan(quarter, day, 4f, 30f, null, 1.0, 1, 2)

        assertEquals("one primitive per value, either way", day.size, current.size)
        assertEquals(day.size, none.size)
        assertEquals("the geometry is the same value", none.map { it.mark }, current.map { it.mark })
        assertEquals("on the same anchors", none.map { it.minute }, current.map { it.minute })
        assertEquals("at the same instants", none.map { it.start }, current.map { it.start })
        assertEquals("with the same colours", none.map { it.colour }, current.map { it.colour })
        // Only the softening of the marks after the current interval may differ -- no third state.
        val differing = current.indices.filter { none[it].alpha != current[it].alpha }
        assertTrue("the softer marks are the ones after the current interval", differing.all { current[it].minute > 735f })
        assertTrue("and there are some to soften", differing.isNotEmpty())
    }

    @Test
    fun anHourlyCurrentIntervalIsStillAnHourlySegment() {
        val hourly = market(intervalMinutes = 60)
        val hourlyValues = PriceAggregation.aggregate(day.map { PricePoint(it.first, it.second) }, hourly)
        val plan = ChartMarks.plan(hourly, hourlyValues, 4f, 30f, ChartNow.markMinute(hourly, at("2026-09-20T12:00:00+02:00")), 1.0, 1, 2)

        assertTrue("every mark of an hourly day is a segment, current or not", plan.all { it.mark.kind == ChartMarkKind.SEGMENT })
        assertTrue("and no mark is a circle", plan.none { it.mark.kind == ChartMarkKind.POINT })
        assertEquals("one segment per aggregated hour", 24, plan.size)
    }

    @Test
    fun theCurrentMarkIsNoWiderThanAnyOtherAndTheOldRadiusWouldNotHaveFitted() {
        val quarter = market()
        val radius = ChartMarks.todayRadius(440, 1f)
        val mark = ChartMarks.geometry(quarter, radius, 30f)
        val metrics = ChartLayout.metrics(ChartProfile.PLAN_CARD, 840, 440, 2f, hasChargingPlan = false) { it * 6f }
        val spacing = metrics.xAt(15f) - metrics.xAt(0f)

        assertEquals(radius * 2f, mark.diameter, 0f)
        assertEquals(
            "the current mark's occupied diameter is the normal one",
            ChartMarks.geometry(quarter, ChartMarks.todayRadius(440, 1f), 30f).diameter,
            mark.diameter,
            0f
        )
        assertTrue("the old enlarged radius reached past a neighbouring quarter", radius * 2.35f * 2f > spacing)
    }
}
