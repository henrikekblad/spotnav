package se.sensnology.spotnav.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.sensnology.spotnav.prices.PriceAggregation
import se.sensnology.spotnav.prices.PriceInterval
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.RelayDayDocument
import se.sensnology.spotnav.prices.RelayDayPoints
import se.sensnology.spotnav.testing.RelayFixtures
import java.time.OffsetDateTime
import java.time.ZoneId

/** The mark geometry: one primitive per value, and currentness that cannot resize anything. */
class ChartMarksTest {

    private val se4 = RelayFixtures.se4

    @Before
    fun loadCatalogue() {
        PriceMarkets.replace(listOf(se4))
    }

    private fun at(time: String) = OffsetDateTime.parse(time)

    private fun values(from: String, prices: List<Double>) = prices.mapIndexed { index, price ->
        PriceInterval(at(from).plusMinutes(index * 15L), price, 15)
    }

    private val day = values("2026-09-20T00:00:00+02:00", List(96) { 1.0 }).also { it }

    private fun plan(nowMinute: Float?): List<ChartMarkDraw> = ChartMarks.plan(
        values = day,
        radius = ChartMarks.todayRadius(440, 1f),
        hourWidth = 30f,
        nowMinute = nowMinute,
        mean = 1.0,
        cheap = ChartTheme.Dark.cheap,
        expensive = ChartTheme.Dark.expensive
    )

    @Test
    fun aQuarterHourIsAPointAndLongerIntervalsAreSegmentsAsLongAsTheyAre() {
        val points = ChartMarks.geometry(15, ChartMarks.todayRadius(440, 1f), 30f)
        assertEquals(ChartMarkKind.POINT, points.kind)
        assertEquals(0f, points.halfLength, 0f)

        val segments = ChartMarks.geometry(60, ChartMarks.todayRadius(440, 1f), 30f)
        assertEquals(ChartMarkKind.SEGMENT, segments.kind)
        assertEquals("a segment reaches 39% of its hour either side", 30f * .39f, segments.halfLength, 0.0001f)
        assertEquals(4f * 1f, segments.radius, 0.0001f)

        val halves = ChartMarks.geometry(30, ChartMarks.todayRadius(440, 1f), 30f)
        assertEquals(ChartMarkKind.SEGMENT, halves.kind)
        assertEquals("a half-hour reaches 39% of its half-hour either side", 15f * .39f, halves.halfLength, 0.0001f)
    }

    /** One day of the relay's prices at [res] minutes, as the app reads it, drawn as today's marks. */
    private fun relayDay(res: Int): List<ChartMarkDraw> {
        val document = RelayDayDocument(
            area = "SE4", date = "2026-09-20", marketTz = "Europe/Stockholm",
            start = at("2026-09-20T00:00:00+02:00"), resMinutes = res,
            prices = List(24 * 60 / res) { 0.1 + it / 1000.0 }, fxRate = 1.0
        )
        val points = RelayDayPoints.points(document, ZoneId.of("Europe/Stockholm"))
        assertEquals("the planner always reads quarters", 96, points.size)
        return ChartMarks.plan(PriceAggregation.aggregate(points), 4f, 30f, null, 0.1, 1, 2)
    }

    @Test
    fun eachAreaIsDrawnAtItsPublishedInterval() {
        val quarters = relayDay(15)
        assertEquals(96, quarters.size)
        assertTrue(quarters.all { it.mark.kind == ChartMarkKind.POINT })
        assertEquals("a quarter is drawn at its start", 15f, quarters[1].minute, 0f)

        val halves = relayDay(30)
        assertEquals(48, halves.size)
        assertTrue(halves.all { it.mark.kind == ChartMarkKind.SEGMENT })
        assertEquals("a half-hour is drawn through its middle", 45f, halves[1].minute, 0f)
        assertEquals("as half an hour's stroke", 15f * .39f, halves[1].mark.halfLength, 0.0001f)

        val hours = relayDay(60)
        assertEquals(24, hours.size)
        assertTrue(hours.all { it.mark.kind == ChartMarkKind.SEGMENT })
        assertEquals("an hour is drawn through its middle", 90f, hours[1].minute, 0f)
        assertEquals(30f * .39f, hours[1].mark.halfLength, 0.0001f)
    }

    @Test
    fun theCurrentIntervalResizesNothingAndAddsNoPrimitive() {
        val current = ChartMarks.plan(day, 4f, 30f, ChartNow.markMinute(at("2026-09-20T12:15:00+02:00"), 15), 1.0, 1, 2)
        val none = ChartMarks.plan(day, 4f, 30f, null, 1.0, 1, 2)

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
        val hourlyValues = PriceAggregation.aggregate(day.map { PricePoint(it.start, it.price, 60) })
        val plan = ChartMarks.plan(hourlyValues, 4f, 30f, ChartNow.markMinute(at("2026-09-20T12:00:00+02:00"), 60), 1.0, 1, 2)

        assertTrue("every mark of an hourly day is a segment, current or not", plan.all { it.mark.kind == ChartMarkKind.SEGMENT })
        assertTrue("and no mark is a circle", plan.none { it.mark.kind == ChartMarkKind.POINT })
        assertEquals("one segment per aggregated hour", 24, plan.size)
    }

    @Test
    fun theCurrentMarkIsNoWiderThanAnyOtherAndTheOldRadiusWouldNotHaveFitted() {
        val radius = ChartMarks.todayRadius(440, 1f)
        val mark = ChartMarks.geometry(15, radius, 30f)
        val metrics = ChartLayout.metrics(ChartProfile.PLAN_CARD, 840, 440, 2f, hasChargingPlan = false) { it * 6f }
        val spacing = metrics.xAt(15f) - metrics.xAt(0f)

        assertEquals(radius * 2f, mark.diameter, 0f)
        assertEquals(
            "the current mark's occupied diameter is the normal one",
            ChartMarks.geometry(15, ChartMarks.todayRadius(440, 1f), 30f).diameter,
            mark.diameter,
            0f
        )
        assertTrue("the old enlarged radius reached past a neighbouring quarter", radius * 2.35f * 2f > spacing)
    }
}
