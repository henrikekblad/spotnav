package se.sensnology.spotnav.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.DashboardSun
import se.sensnology.spotnav.ha.dashboard.SunHour
import se.sensnology.spotnav.ha.dashboard.SunQuarter
import se.sensnology.spotnav.testing.DashboardFixtures
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The sun in the price chart, placed as the card's `chart-sun.ts` places it. */
class ChartSunTest {
    private val zone = ZoneId.of("Europe/Stockholm")
    private val today = LocalDate.of(2026, 9, 22)

    private fun at(text: String) = Instant.parse(text)
    private fun hour(start: String, kw: Double, measured: Boolean) = SunHour(at(start), kw, measured)

    /** 08 and 09 measured, 10 (the running hour) to 12 forecast, and tomorrow's 09, all local time (UTC+2). */
    private val block = DashboardSun(
        hours = listOf(
            hour("2026-09-22T06:00:00Z", 1.0, true),
            hour("2026-09-22T07:00:00Z", 2.0, true),
            hour("2026-09-22T08:00:00Z", 3.0, false),
            hour("2026-09-22T09:00:00Z", 4.0, false),
            hour("2026-09-22T10:00:00Z", 3.5, false),
            hour("2026-09-23T07:00:00Z", 2.5, false)
        ),
        quarters = emptyList(),
        peakKw = null
    )

    @Test fun onTheDayAxisOnlyTodaysSunIsPlacedByItsWallClock() {
        val sun = ChartSuns.series(block, ChartTimeAxis.WallClock, zone, today)!!
        assertEquals(listOf(480f, 540f, 600f, 660f, 720f), sun.points.map { it.fromMinute })
        // Drawn at the middle of its hour.
        assertEquals(510f, sun.points.first().minute, 0f)
        assertEquals(listOf(true, true, false, false, false), sun.points.map { it.measured })
        assertTrue(sun.hasForecast)
        // Tomorrow's sun never lands on today's hours.
        assertTrue(sun.points.none { it.kw == 2.5 })
        // No peak is known: the day's own tallest hour is the scale.
        assertEquals(4.0, sun.maxKw, 0.0)
        assertNull(sun.peakKw)
    }

    @Test fun onTheFromNowAxisEverythingInViewIsPlacedByInstant() {
        val axis = ChartTimeAxis.Range(at("2026-09-22T08:00:00Z"), at("2026-09-23T22:00:00Z"), zone)
        val sun = ChartSuns.series(block, axis, zone, today)!!
        // What ended before the axis starts is not drawn; tomorrow's hour is, a day on.
        assertEquals(listOf(0f, 60f, 120f, 1380f), sun.points.map { it.fromMinute })
        assertFalse(sun.points.any { it.measured })
    }

    @Test fun theScaleIsThePeakOrATallerHourAndNeverBelowTheFloor() {
        assertEquals(8.4, ChartSuns.series(block.copy(peakKw = 8.4), ChartTimeAxis.WallClock, zone, today)!!.maxKw, 0.0)
        // A record hour above the stated peak still fits.
        val record = ChartSuns.series(block.copy(peakKw = 3.0), ChartTimeAxis.WallClock, zone, today)!!
        assertEquals(4.0, record.maxKw, 0.0)
        assertEquals(3.0, record.peakKw!!, 0.0)
        // A dull day is drawn small, not as the whole plot.
        val dull = DashboardSun(listOf(hour("2026-09-22T10:00:00Z", 0.1, true)), emptyList(), null)
        assertEquals(0.5, ChartSuns.series(dull, ChartTimeAxis.WallClock, zone, today)!!.maxKw, 0.0)
    }

    @Test fun measuredQuartersReplaceTheHoursTheyCoverAndTheForecastStartsWhereTheyEnd() {
        val withQuarters = block.copy(quarters = listOf(
            SunQuarter(at("2026-09-22T07:00:00Z"), 1.8),
            SunQuarter(at("2026-09-22T07:15:00Z"), 2.2),
            SunQuarter(at("2026-09-22T08:00:00Z"), 2.9)
        ))
        val sun = ChartSuns.series(withQuarters, ChartTimeAxis.WallClock, zone, today)!!
        // 08 measured stays an hour; 09 is its quarters; the running hour 10 has a quarter, so its forecast is left out.
        assertEquals(listOf(480f, 540f, 555f, 600f, 660f, 720f), sun.points.map { it.fromMinute })
        assertEquals(listOf(555f, 570f, 615f), sun.points.map { it.toMinute }.subList(1, 4))
        assertEquals(listOf(true, true, true, true, false, false), sun.points.map { it.measured })
    }

    @Test fun aMissingHourEndsARun() {
        val gap = DashboardSun(
            listOf(
                hour("2026-09-22T06:00:00Z", 1.0, true), hour("2026-09-22T07:00:00Z", 2.0, true),
                hour("2026-09-22T09:00:00Z", 4.0, false), hour("2026-09-22T10:00:00Z", 3.0, false)
            ),
            emptyList(), null
        )
        val runs = ChartSuns.runs(ChartSuns.series(gap, ChartTimeAxis.WallClock, zone, today)!!.points)
        assertEquals(listOf(2, 2), runs.map { it.size })
    }

    @Test fun theSunUnderAMarkIsTheSpanThatHoldsItsMinute() {
        val sun = ChartSuns.series(block, ChartTimeAxis.WallClock, zone, today)!!
        // An hourly mark is drawn at the middle of its hour, a quarter-hour's at its start.
        assertEquals(3.0, sun.at(630f)!!.kw, 0.0)
        assertEquals(3.0, sun.at(600f)!!.kw, 0.0)
        assertEquals(4.0, sun.at(660f)!!.kw, 0.0)
        assertNull(sun.at(300f))
    }

    @Test fun nothingToDrawIsNoSun() {
        assertNull(ChartSuns.series(null, ChartTimeAxis.WallClock, zone, today))
        assertNull(ChartSuns.series(DashboardSun(emptyList(), emptyList(), 8.0), ChartTimeAxis.WallClock, zone, today))
        // A block about another day than the one the clock is in.
        assertNull(ChartSuns.series(block, ChartTimeAxis.WallClock, zone, today.plusDays(5)))
    }

    @Test fun thePairedChartCarriesTheDashboardsSunAndTheRequestIsItsIdentity() {
        val dashboard = DashboardFixtures.dashboard("hybrid_derived_site_with_forecast.json")
        val now = Instant.parse("2026-09-22T06:00:00Z")
        val chart = DashboardChart.build(dashboard, now = now)!!
        assertEquals(dashboard.sun, chart.sun)
        val sun = ChartSuns.series(block, ChartTimeAxis.WallClock, zone, today)
        val plain = ChartRequests.of(chart.market, chart.bands, chart.prices, 1000, ChartProfile.PLAN_CARD)!!
        val sunny = ChartRequests.of(chart.market, chart.bands, chart.prices, 1000, ChartProfile.PLAN_CARD, sun = sun)!!
        // The sun changes the picture, so it is part of what a frame is drawn from.
        assertFalse(plain == sunny)
        assertEquals(sunny, sunny.copy(sun = ChartSuns.series(block, ChartTimeAxis.WallClock, zone, today)))
    }
}
