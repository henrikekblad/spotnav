package se.sensnology.spotnav.prices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chart.ChartMarket
import se.sensnology.spotnav.planning.ChargingPeriod
import se.sensnology.spotnav.planning.ChargingPlan
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.LocalTime
import java.time.OffsetDateTime

class PriceTableModelTest {
    /** The calculation inputs for these widget settings. */
    private fun inputs(settings: WidgetSettings): PlanningInputs = LocalPlanningInputs.of(settings)
    @Test fun anHourlyAreaIsOneIntervalPerPublishedHour() {
        val start = OffsetDateTime.parse("2026-09-12T10:00:00+02:00")
        val points = List(8) { index -> PricePoint(start.plusMinutes(index * 15L), if (index < 4) 2.5 else 4.0, 60) }
        val aggregated = PriceAggregation.aggregate(points)
        assertEquals(listOf(PriceInterval(start, 2.5, 60), PriceInterval(start.plusHours(1), 4.0, 60)), aggregated)
    }

    @Test fun aQuarterHourAreaKeepsEveryQuarter() {
        val start = OffsetDateTime.parse("2026-09-12T10:00:00+02:00")
        val points = listOf(1.0, 2.0, 3.0, 4.0).mapIndexed { index, value -> PricePoint(start.plusMinutes(index * 15L), value) }
        val aggregated = PriceAggregation.aggregate(points)
        assertEquals(points.map { PriceInterval(it.start, it.pricePerKwh, 15) }, aggregated)
    }

    @Test fun aHalfHourAreaIsOneIntervalPerPublishedHalfHour() {
        val start = OffsetDateTime.parse("2026-09-12T10:00:00+02:00")
        val points = List(4) { index -> PricePoint(start.plusMinutes(index * 15L), if (index < 2) 1.0 else 3.0, 30) }
        val aggregated = PriceAggregation.aggregate(points)
        assertEquals(listOf(PriceInterval(start, 1.0, 30), PriceInterval(start.plusMinutes(30), 3.0, 30)), aggregated)
    }

    @Test fun aRepeatedClockChangeHourStaysTwoHours() {
        // 2026-10-25 in Stockholm: 02:00 happens at +02:00 and again at +01:00.
        val first = OffsetDateTime.parse("2026-10-25T02:00:00+02:00")
        val second = OffsetDateTime.parse("2026-10-25T02:00:00+01:00")
        val points = List(4) { PricePoint(first.plusMinutes(it * 15L), 1.0, 60) } +
            List(4) { PricePoint(second.plusMinutes(it * 15L), 2.0, 60) }
        assertEquals(listOf(PriceInterval(first, 1.0, 60), PriceInterval(second, 2.0, 60)), PriceAggregation.aggregate(points))
    }

    @Test fun marksCurrentRowAndCalculatesRanges() {
        val start = OffsetDateTime.parse("2026-09-12T10:00:00+02:00")
        val points = listOf(1.0, 2.0, 3.0).mapIndexed { index, value -> PricePoint(start.plusMinutes(index * 15L), value) }
        val model = PriceTableModels.create(PriceResult(points, emptyList(), 0), inputs(WidgetSettings()), start.plusMinutes(16))
        assertEquals(1, model.currentIndex)
        assertTrue(model.rows[1].current)
        assertEquals(100.0 to 300.0, model.todayRange)
    }

    @Test fun aMarketWithoutPlanningInputsStillProducesPriceRowsWithoutChargeCoverage() {
        val start = OffsetDateTime.parse("2026-09-12T10:00:00+02:00")
        val points = listOf(1.0, 2.0).mapIndexed { index, value ->
            PricePoint(start.plusMinutes(index * 15L), value)
        }
        val market = ChartMarket.of(inputs(WidgetSettings()))

        val model = PriceTableModels.create(
            PriceResult(points, emptyList(), 0),
            market,
            start.plusMinutes(16)
        )

        assertEquals("the market rows do not require planning inputs", 2, model.rows.size)
        assertTrue(model.rows.all { it.today?.coverage?.isEmpty == true })
        assertEquals(1, model.currentIndex)
        assertEquals(100.0 to 200.0, model.todayRange)
    }

    // charging marks: which cells a plan covers

    private fun at(time: String) = OffsetDateTime.parse(time)

    /** One day of quarter-hour prices from midnight: the normalized grid the app works on. */
    private fun day(date: String, count: Int): List<PricePoint> =
        (0 until count).map { index ->
            PricePoint(at("${date}T00:00:00+02:00").plusMinutes(index * 15L), 1.0 + index)
        }

    private fun planOf(vararg windows: Pair<String, String>): ChargingPlan {
        val periods = windows.map { ChargingPeriod(at(it.first), at(it.second)) }
        return ChargingPlan(
            start = periods.first().start, end = periods.last().end,
            powerKw = 11.0, energyKwh = 10.0, distanceMil = 5.0, cost = 1.0,
            unpricedSlots = 0, periods = periods
        )
    }

    private fun table(
        today: List<PricePoint>,
        tomorrow: List<PricePoint> = emptyList(),
        plan: ChargingPlan? = null,
        settings: WidgetSettings = WidgetSettings(),
        now: OffsetDateTime = at("2026-09-12T00:00:00+02:00")
    ) = PriceTableModels.create(PriceResult(today, tomorrow, 0), inputs(settings), now, plan)

    @Test fun oneMarkedQuarterCellInTodaysColumn() {
        val model = table(
            day("2026-09-12", 4),
            plan = planOf("2026-09-12T00:15:00+02:00" to "2026-09-12T00:30:00+02:00")
        )

        val cell = model.rows[1].today!!
        assertEquals(at("2026-09-12T00:15:00+02:00"), cell.start)
        assertEquals(15, cell.minutes)
        assertEquals(1, cell.coverage.segmentCount)
        assertEquals(1, cell.coverage.selectedCount)
        // Exactly one cell in the whole table is marked, and this row has no tomorrow column to
        // mark.
        assertEquals(1, model.rows.count { !it.today!!.coverage.isEmpty })
        assertTrue(model.rows.all { it.tomorrow == null })
    }

    @Test fun theSameWallTimeInEachColumnIsMarkedIndependently() {
        val model = table(
            day("2026-09-12", 4), day("2026-09-13", 4),
            // Only tomorrow's 00:30 is charged.
            plan = planOf("2026-09-13T00:30:00+02:00" to "2026-09-13T00:45:00+02:00")
        )

        val row = model.rows[2]
        // Both cells display as 00:30 and are a day apart as instants.
        assertEquals(at("2026-09-12T00:30:00+02:00"), row.today!!.start)
        assertEquals(at("2026-09-13T00:30:00+02:00"), row.tomorrow!!.start)
        assertEquals(row.position.time, row.today!!.start.toLocalTime())
        assertEquals(1, row.position.occurrence)
        assertTrue("today's 00:30 is not charged", row.today!!.coverage.isEmpty)
        assertEquals(1, row.tomorrow!!.coverage.selectedCount)
    }

    @Test fun aPeriodAcrossMidnightMarksEachDateAtItsOwnInstant() {
        val model = table(
            day("2026-09-12", 96), day("2026-09-13", 96),
            plan = planOf("2026-09-12T23:45:00+02:00" to "2026-09-13T00:15:00+02:00")
        )

        val lastToday = model.rows[95]
        assertEquals(at("2026-09-12T23:45:00+02:00"), lastToday.today!!.start)
        assertEquals(1, lastToday.today!!.coverage.selectedCount)
        assertTrue("tomorrow's 23:45 is untouched", lastToday.tomorrow!!.coverage.isEmpty)

        val firstTomorrow = model.rows[0]
        assertEquals(at("2026-09-13T00:00:00+02:00"), firstTomorrow.tomorrow!!.start)
        assertEquals(1, firstTomorrow.tomorrow!!.coverage.selectedCount)
        assertTrue("today's 00:00 is untouched", firstTomorrow.today!!.coverage.isEmpty)

        assertEquals(1, model.rows.count { !it.today!!.coverage.isEmpty })
        assertEquals(1, model.rows.count { !it.tomorrow!!.coverage.isEmpty })
    }

    @Test fun aPlanEndingWhereACellBeginsDoesNotMarkThatCell() {
        val model = table(
            day("2026-09-12", 4),
            plan = planOf("2026-09-12T00:00:00+02:00" to "2026-09-12T00:15:00+02:00")
        )

        assertEquals(1, model.rows[0].today!!.coverage.selectedCount)
        assertTrue("a window that ends at 00:15 does not charge the 00:15 cell", model.rows[1].today!!.coverage.isEmpty)
    }

    @Test fun noPlanMeansNoMarkersAnywhere() {
        val model = table(day("2026-09-12", 6), day("2026-09-13", 6))

        assertTrue(model.rows.all { it.today!!.coverage.isEmpty && it.tomorrow!!.coverage.isEmpty })
    }

    @Test fun aRowWithOnlyOneColumnBorrowsNothingFromTheOther() {
        val model = table(
            day("2026-09-12", 2), day("2026-09-13", 1),
            plan = planOf("2026-09-12T00:15:00+02:00" to "2026-09-12T00:30:00+02:00")
        )

        assertEquals(2, model.rows.size)
        // The row both days have: each cell its own start, neither charged.
        val shared = model.rows[0]
        assertEquals(at("2026-09-12T00:00:00+02:00"), shared.today!!.start)
        assertEquals(at("2026-09-13T00:00:00+02:00"), shared.tomorrow!!.start)
        assertTrue(shared.today!!.coverage.isEmpty)
        assertTrue(shared.tomorrow!!.coverage.isEmpty)
        // The row only today has: no borrowed tomorrow cell, no borrowed time.
        val todayOnly = model.rows[1]
        assertEquals(null, todayOnly.tomorrow)
        assertEquals(java.time.LocalTime.of(0, 15), todayOnly.position.time)
        assertEquals(at("2026-09-12T00:15:00+02:00"), todayOnly.today!!.start)
        assertEquals(1, todayOnly.today!!.coverage.selectedCount)
    }

    /** The quarter-hour points the repository actually produces for one relay day. */
    private fun relayPoints(
        area: PriceMarket,
        date: String,
        start: String,
        prices: List<Double>,
        res: Int
    ): List<PricePoint> {
        val parsed = RelayDayParser.parse(
            RelayFixtures.dayBody(area, date, prices, start, res = res), area.id, area.tz, area.currency, date
        )
        assertTrue("expected a valid document, got $parsed", parsed is DayParse.Ok)
        return RelayDayPoints.points((parsed as DayParse.Ok).document, area.zoneId)
    }

    @Test fun anHourlyAreasQuartersBecomeOneHourCell() {
        val points = relayPoints(
            RelayFixtures.no1, "2026-09-20", "2026-09-20T00:00:00+02:00", RelayFixtures.prices(4, base = 0.10), res = 60
        )

        val model = table(
            points,
            plan = planOf(
                "2026-09-20T00:15:00+02:00" to "2026-09-20T00:30:00+02:00",
                "2026-09-20T00:45:00+02:00" to "2026-09-20T01:00:00+02:00"
            )
        )

        val hour = model.rows[0].today!!
        assertEquals(60, hour.minutes)
        assertEquals(4, hour.coverage.segmentCount)
        assertEquals(listOf(false, true, false, true), (0 until 4).map { hour.coverage.isSelected(it) })
    }

    @Test fun aMissingQuarterStaysMissingAndDoesNotExpandItsNeighbours() {
        // Every other quarter present: a sparse grid is still a quarter-hour grid.
        val dense = relayPoints(
            RelayFixtures.se4, "2026-09-20", "2026-09-20T00:00:00+02:00", RelayFixtures.prices(8, base = 0.10), res = 15
        )
        assertEquals(8, dense.size)
        val sparse = dense.filterIndexed { index, _ -> index % 2 == 0 }

        val model = table(sparse)

        assertEquals(4, model.rows.size)
        assertTrue("a sparse grid is still a quarter-hour grid", model.rows.all { it.today!!.minutes == 15L })
        assertTrue(model.rows.all { it.today!!.coverage.segmentCount == 1 })
        // The missing quarter is simply absent rather than folded into a neighbour, and the present
        // ones keep their own instants and prices.
        assertTrue(model.rows.none { it.position.time == LocalTime.of(0, 15) })
        assertEquals(at("2026-09-20T00:00:00+02:00"), model.rows[0].today!!.start)
        assertEquals(at("2026-09-20T00:30:00+02:00"), model.rows[1].today!!.start)
        assertEquals(4, model.rows.map { it.today!!.price }.distinct().size)
    }

    @Test fun coverageChangesNothingAboutTheRestOfTheTable() {
        val now = at("2026-09-12T01:00:00+02:00")
        val plain = table(day("2026-09-12", 6), day("2026-09-13", 6), now = now)
        val marked = table(
            day("2026-09-12", 6), day("2026-09-13", 6), now = now,
            plan = planOf("2026-09-12T00:30:00+02:00" to "2026-09-12T00:45:00+02:00")
        )

        assertEquals(plain.currentIndex, marked.currentIndex)
        assertEquals(plain.todayAverage, marked.todayAverage)
        assertEquals(plain.tomorrowAverage, marked.tomorrowAverage)
        assertEquals(plain.todayRange, marked.todayRange)
        assertEquals(plain.tomorrowRange, marked.tomorrowRange)
        assertEquals(plain.rows.map { it.position }, marked.rows.map { it.position })
        assertEquals(plain.rows.map { it.current }, marked.rows.map { it.current })
        assertEquals(plain.rows.map { it.today?.price }, marked.rows.map { it.today?.price })
        // ...and the plan did mark something, so the comparison above is not passing merely because
        // nothing happened.
        assertEquals(1, marked.rows.count { !it.today!!.coverage.isEmpty })
    }
}
