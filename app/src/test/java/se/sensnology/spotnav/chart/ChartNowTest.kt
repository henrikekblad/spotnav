package se.sensnology.spotnav.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** The current interval and the one focus line, as pure geometry. */
class ChartNowTest {

    private val se4 = RelayFixtures.se4

    @Before
    fun loadCatalogue() {
        PriceMarkets.replace(listOf(se4))
    }

    private fun market(intervalMinutes: Int = 15): ChartMarket =
        ChartMarket.of(LocalPlanningInputs.of(WidgetSettings(area = "SE4", intervalMinutes = intervalMinutes)))

    private fun at(time: String) = OffsetDateTime.parse(time)

    /** Quarter-hour prices from midnight at [from]. */
    private fun day(from: String, prices: List<Double>) = prices.mapIndexed { index, price ->
        PricePoint(at(from).plusMinutes(index * 15L), price)
    }

    private fun result(today: List<PricePoint>, tomorrow: List<PricePoint> = emptyList()) =
        PriceResult(today, tomorrow, 0L)

    private val wholeDay = day("2026-09-20T00:00:00+02:00", List(96) { 1.0 })

    @Test
    fun theQuarterHourLineSitsAtTheContainingIntervalsOwnMark() {
        assertEquals(15f, ChartNow.currentMarkMinute(market(), result(wholeDay), at("2026-09-20T00:22:00+02:00"))!!, 0f)
        assertEquals(0f, ChartNow.currentMarkMinute(market(), result(wholeDay), at("2026-09-20T00:00:00+02:00"))!!, 0f)
        assertEquals(
            "the last minute of an interval still belongs to it",
            15f,
            ChartNow.currentMarkMinute(market(), result(wholeDay), at("2026-09-20T00:29:59+02:00"))!!,
            0f
        )
        assertEquals(
            "and the next interval's first minute belongs to the next",
            30f,
            ChartNow.currentMarkMinute(market(), result(wholeDay), at("2026-09-20T00:30:00+02:00"))!!,
            0f
        )
    }

    @Test
    fun theHourlyLineSitsAtTheCentreOfTheContainingHour() {
        val hourly = market(intervalMinutes = 60)
        assertEquals(30f, ChartNow.currentMarkMinute(hourly, result(wholeDay), at("2026-09-20T00:22:00+02:00"))!!, 0f)
        assertEquals(
            "12:22 is inside the 12:00 hour, whose centre is 12:30",
            750f,
            ChartNow.currentMarkMinute(hourly, result(wholeDay), at("2026-09-20T12:22:00+02:00"))!!,
            0f
        )
        assertEquals(
            "and the hour's last minute is still that hour",
            750f,
            ChartNow.currentMarkMinute(hourly, result(wholeDay), at("2026-09-20T12:59:59+02:00"))!!,
            0f
        )
    }

    @Test
    fun aMomentNoRowContainsHasNoMarkMinuteAndBorrowsNothing() {
        val short = result(day("2026-09-20T00:00:00+02:00", List(4) { 1.0 }), wholeDay)

        assertNull("after the last row", ChartNow.currentMarkMinute(market(), short, at("2026-09-20T01:10:00+02:00")))
        assertNull("before the first row", ChartNow.currentMarkMinute(market(), short, at("2026-09-19T23:50:00+02:00")))
        assertNull(
            "and tomorrow's rows are never borrowed for today's current interval",
            ChartNow.currentMarkMinute(market(), short, at("2026-09-21T00:22:00+02:00"))
        )

        val gapped = result(
            day("2026-09-20T00:00:00+02:00", List(4) { 1.0 }).filterIndexed { index, _ -> index != 2 }
        )
        assertNull("a gap is a gap", ChartNow.currentMarkMinute(market(), gapped, at("2026-09-20T00:35:00+02:00")))
    }

    @Test
    fun aSelectionHidesTheNowLineAndNeverDoublesIt() {
        assertEquals(ChartLine(ChartLineKind.SELECTION, 400f), ChartNow.line(100f, 400f))
        assertEquals(
            "selecting the current mark itself is still one dashed line",
            ChartLine(ChartLineKind.SELECTION, 100f),
            ChartNow.line(100f, 100f)
        )
        assertEquals(
            "drawing no selection is the only way to draw the now line",
            ChartLine(ChartLineKind.NOW, 100f),
            ChartNow.line(100f, null)
        )
        assertEquals(
            "a selection with no current interval is still one line",
            ChartLine(ChartLineKind.SELECTION, 400f),
            ChartNow.line(null, 400f)
        )
        assertNull("and with neither, no line at all", ChartNow.line(null, null))
    }

    @Test
    fun clearingTheSelectionRestoresTheNowLine() {
        assertEquals(ChartLineKind.SELECTION, ChartNow.line(100f, 400f)!!.kind)
        assertEquals(ChartLine(ChartLineKind.NOW, 100f), ChartNow.line(100f, null))
    }

    @Test
    fun theBoundaryAppointmentIsTheEndOfTheContainingInterval() {
        val prices = result(day("2026-09-20T00:00:00+02:00", List(8) { 1.0 }))

        assertEquals(
            "00:22 is 8 minutes from the end of the 00:15 interval",
            8 * 60_000L,
            ChartNow.untilNextIntervalMillis(market(), prices, at("2026-09-20T00:22:00+02:00"))!!
        )
        assertEquals(
            "and 38 minutes from the end of the 00:00 hour",
            38 * 60_000L,
            ChartNow.untilNextIntervalMillis(market(60), prices, at("2026-09-20T00:22:00+02:00"))!!
        )
        assertNull(
            "no containing interval, no appointment: the day ends at 02:00 and 02:10 is outside it",
            ChartNow.untilNextIntervalMillis(market(), prices, at("2026-09-20T02:10:00+02:00"))
        )
    }

    @Test
    fun theAnchorIsTheVeryXHitTestingSelectsAt() {
        val inputs = LocalPlanningInputs.of(WidgetSettings(area = "SE4", intervalMinutes = 60))
        val prices = result(day("2026-09-20T00:00:00+02:00", List(4) { 1.0 }))
        val metrics = ChartLayout.metrics(ChartProfile.PLAN_CARD, 840, 440, 2f, hasChargingPlan = false) { it * 6f }
        val anchor = ChartNow.currentMarkMinute(market(60), prices, at("2026-09-20T00:22:00+02:00"))!!

        // A tap at the anchor lands on the mark the line is drawn at: one mapping, not two.
        val readout = ChartSelection.nearest(metrics.xAt(anchor), metrics, inputs, prices)!!
        assertEquals(LocalTime.of(0, 0), readout.time)
        assertEquals(anchor, readout.markMinute, 0f)
    }

    @Test
    fun theRequestIdentityMovesWithTheIntervalAndNotInsideIt() {
        val prices = result(wholeDay)
        val market = market()

        fun requestAt(time: String) = ChartRequests.of(
            market, emptyList(), prices, 840, ChartProfile.PLAN_CARD, null, null,
            ChartNow.currentMarkMinute(market, prices, at(time))
        )!!

        val first = requestAt("2026-09-20T00:22:00+02:00")
        assertEquals(
            "the same interval is the same request, whatever the second",
            first,
            requestAt("2026-09-20T00:22:59+02:00")
        )
        val next = requestAt("2026-09-20T00:30:00+02:00")
        assertNotEquals("the next interval is a different picture", first, next)
        assertEquals("and it carries its own mark", 30f, next.nowMinute!!, 0f)
    }

    @Test
    fun theAnchorFollowsTheWallClockTheAxisDraws() {
        val prices = result(wholeDay)
        val instant = at("2026-09-20T12:22:00+02:00")

        assertEquals(735f, ChartNow.currentMarkMinute(market(), prices, instant)!!, 0f)
        assertEquals(
            735f,
            ChartNow.currentMarkMinute(market(), prices, instant.withOffsetSameInstant(ZoneOffset.UTC))!!,
            0f
        )
    }
}
