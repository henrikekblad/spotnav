package se.sensnology.spotnav.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.planning.ChargingPeriod
import se.sensnology.spotnav.planning.FiscalInput
import se.sensnology.spotnav.prices.PriceAggregation
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.testing.FakeKeyValueStore
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * The chart's time axis, on the Home Assistant card's `CROSS_AXIS_FROM_NOW` scene: Stockholm, today
 * 2026-10-11 and tomorrow 2026-10-12 in quarter-hours, now 16:00, and a plan from 14:00 to 05:30
 * tomorrow. Day is the wall-clock overlay as before; From now runs from the start of the current hour to
 * the end of the last published day, 32 hours.
 */
class ChartTimeAxisTest {
    private val zone = ZoneId.of("Europe/Stockholm")
    private val today = LocalDate.of(2026, 10, 11)
    private val tomorrow = LocalDate.of(2026, 10, 12)
    private val now = at("2026-10-11T16:00:00+02:00")
    private val axisStart = now
    private val axisEnd = at("2026-10-13T00:00:00+02:00")
    private val span = 32 * 60f
    private val market = ChartMarket("SE3", FiscalInput.OFF, FiscalInput.OFF, FiscalInput.OFF)

    private fun at(text: String): Instant = OffsetDateTime.parse(text).toInstant()

    private fun day(date: LocalDate, base: Double): List<PricePoint> = (0 until 96).map { index ->
        PricePoint(date.atStartOfDay(zone).plusMinutes(index * 15L).toOffsetDateTime(), base + index / 100.0)
    }

    private fun result(withTomorrow: Boolean = true) =
        PriceResult(day(today, 0.6), if (withTomorrow) day(tomorrow, 0.45) else emptyList(), 0L)

    private fun period(from: String, to: String) = ChargingPeriod(OffsetDateTime.parse(from), OffsetDateTime.parse(to))

    private val plan = ChartOverlay.planned(listOf(period("2026-10-11T14:00:00+02:00", "2026-10-12T05:30:00+02:00")))

    private fun fromNow(result: PriceResult = result(), instant: Instant = now) =
        ChartTimeAxis.of(ChartAxisMode.FROM_NOW, result, zone, instant)

    private fun range(): ChartTimeAxis.Range = fromNow() as ChartTimeAxis.Range

    @Test
    fun fromNowRunsFromTheCurrentHourToTheEndOfTheLastPublishedDay() {
        val axis = range()
        assertEquals(axisStart, axis.start)
        assertEquals(axisEnd, axis.end)
        assertEquals(span, axis.spanMinutes, 0f)
        assertFalse("nothing is overlaid on the range", axis.overlay)
    }

    @Test
    fun itStartsAtTheHourNotTheInstantWhileTheClockIsInsideIt() {
        val later = now.plusSeconds(40 * 60)
        val axis = fromNow(instant = later) as ChartTimeAxis.Range
        assertEquals(axisStart, axis.start)
        // The current interval is 16:30, half an hour into the axis.
        assertEquals(30f, ChartNow.currentMarkMinute(result(), later.atZone(zone).toOffsetDateTime(), axis)!!, 0f)
    }

    @Test
    fun aPlanAcrossMidnightIsOneBandFromTheAxisStartAndNothingAtTodaysNight() {
        val bands = plan.bands(result(), zone, range())
        // Clipped at 16:00, on to 05:30 tomorrow: 13.5 hours of the 32, solid.
        assertEquals(listOf(ChartBand(today, 0f, 13.5f * 60, ChartBandKind.INSTALLED, tomorrow = false)), bands)
        assertTrue("nothing is drawn at today's 00:00-05:30", bands.none { it.fromMinute < 0f })

        val day = plan.bands(result(), zone)
        assertEquals(
            "the Day axis is the overlay as before, tomorrow's part hatched",
            listOf(
                ChartBand(today, 14f * 60, 24f * 60),
                ChartBand(tomorrow, 0f, 5.5f * 60, tomorrow = true)
            ),
            day
        )
    }

    @Test
    fun aPlanThatEndedBeforeTheAxisIsNotDrawn() {
        val over = ChartOverlay.planned(listOf(period("2026-10-11T02:00:00+02:00", "2026-10-11T05:00:00+02:00")))
        assertEquals(emptyList<ChartBand>(), over.bands(result(), zone, range()))
    }

    @Test
    fun aWaitingProposalStaysAfterTheRunningWindowOnTheRange() {
        val installed = ChartOverlay.planned(listOf(period("2026-10-11T14:00:00+02:00", "2026-10-11T16:15:00+02:00")))
        val proposal = ChartOverlay.planned(
            listOf(period("2026-10-11T16:15:00+02:00", "2026-10-12T05:30:00+02:00")), ChartBandKind.PROPOSAL
        )
        val shown = ShownChart(market, emptyList(), null, result(), listOf(installed, proposal), zone)
        val (axis, bands) = ChartRequests.placed(shown, ChartAxisMode.FROM_NOW, now)
        assertTrue(axis is ChartTimeAxis.Range)
        assertEquals(
            listOf(
                ChartBand(today, 0f, 15f, ChartBandKind.INSTALLED),
                ChartBand(today, 15f, 13.5f * 60, ChartBandKind.PROPOSAL)
            ),
            bands
        )
        assertTrue("every band on the range is solid", bands.none { it.tomorrow })
    }

    @Test
    fun onlyWhatHasNotEndedIsDrawnEachAtItsOwnTime() {
        val axis = range()
        val all = PriceAggregation.aggregate(result().today) + PriceAggregation.aggregate(result().tomorrow)
        val shown = all.filter(axis::shows)
        assertEquals(32 * 4, shown.size)
        assertTrue(shown.all { it.start.toInstant() >= axisStart })
        // Tomorrow's 00:00 is 8 of the 32 hours in.
        val midnight = shown.first { it.start.toInstant() == at("2026-10-12T00:00:00+02:00") }
        assertEquals(8 * 60f, axis.markMinute(midnight), 0f)
        // The now line is at the left edge, on the 16:00 quarter-hour.
        assertEquals(0f, ChartNow.currentMarkMinute(result(), now.atZone(zone).toOffsetDateTime(), axis)!!, 0f)
    }

    @Test
    fun ticksEverySixHoursByInstantWithTheDayCueAtMidnight() {
        val ticks = range().ticks()
        assertEquals(listOf(2f, 8f, 14f, 20f, 26f, 32f).map { it * 60 }, ticks.map { it.minute })
        assertEquals(listOf(18, 0, 6, 12, 18, 24), ticks.map { it.hour })
        assertEquals(listOf(false, true, false, false, false, false), ticks.map { it.dayStart })
        assertEquals(at("2026-10-12T00:00:00+02:00"), ticks[1].instant)

        val day = ChartTimeAxis.WallClock.ticks()
        assertEquals(listOf(0f, 360f, 720f, 1080f, 1440f), day.map { it.minute })
        assertTrue(day.none { it.dayStart })
    }

    @Test
    fun theHighestAndLowestCoverEverythingDrawnOnTheRangeAndTodayOnDay() {
        val todayIntervals = PriceAggregation.aggregate(result().today)
        val tomorrowIntervals = PriceAggregation.aggregate(result().tomorrow)
        val ranged = range().fullStrength(todayIntervals, tomorrowIntervals)
        // Today from 16:00 on, then all of tomorrow, whose cheaper night is the lowest.
        assertEquals(0.45, ranged.minOf { it.price }, 1e-9)
        assertEquals(0.6 + 0.95, ranged.maxOf { it.price }, 1e-9)
        val day = ChartTimeAxis.WallClock.fullStrength(todayIntervals, tomorrowIntervals)
        assertEquals(0.6, day.minOf { it.price }, 1e-9)
        assertEquals(0.6 + 0.95, day.maxOf { it.price }, 1e-9)
    }

    @Test
    fun fromNowWithoutTomorrowIsExactlyDay() {
        val withoutTomorrow = result(withTomorrow = false)
        assertEquals(ChartTimeAxis.WallClock, fromNow(withoutTomorrow))
        val shown = ShownChart(market, plan.bands(withoutTomorrow, zone), null, withoutTomorrow, listOf(plan), zone)
        assertEquals(
            ChartRequests.placed(shown, ChartAxisMode.DAY, now),
            ChartRequests.placed(shown, ChartAxisMode.FROM_NOW, now)
        )
        assertEquals(
            ChartSelection.positions(market, withoutTomorrow),
            ChartSelection.positions(market, withoutTomorrow, fromNow(withoutTomorrow))
        )
    }

    @Test
    fun onTheRangeEachIntervalIsItsOwnPositionAndReadout() {
        val axis = range()
        val positions = ChartSelection.positions(market, result(), axis)
        assertEquals(32 * 4, positions.size)
        // 16:00 tomorrow is a different position from 16:00 today, and reads as tomorrow's.
        val readout = ChartSelection.readoutAt(24 * 60f, market, result(), axis)!!
        assertEquals(java.time.LocalTime.of(16, 0), readout.time)
        assertEquals(emptyList<ChartOccurrence>(), readout.today)
        assertEquals(1, readout.tomorrow.size)
    }

    @Test
    fun thePreferenceIsDayUntilFromNowIsChosen() {
        val store = FakeKeyValueStore()
        val preference = ChartAxisPreference(store)
        assertEquals(ChartAxisMode.DAY, preference.mode())
        store.setRaw(ChartAxisPreference.KEY, "sideways")
        assertEquals(ChartAxisMode.DAY, preference.mode())
        preference.save(ChartAxisMode.FROM_NOW)
        assertEquals("from_now", store.rawOrNull(ChartAxisPreference.KEY))
        assertEquals(ChartAxisMode.FROM_NOW, preference.mode())
        assertEquals(ChartTimeAxis.WallClock, ChartTimeAxis.of(ChartAxisMode.DEFAULT, result(), zone, now))
    }

    /** Ticks fitted to a plot [width] wide, each label [label] pixels wide. */
    private fun fitted(width: Float, label: Float = 60f): List<ChartTick> {
        val axis = range()
        return ChartTickFit.fit(axis.ticks(), axis.spanMinutes, { it / axis.spanMinutes * width }, { label }, 8f)
    }

    @Test
    fun aWidePlotLabelsEveryTick() {
        assertEquals(range().ticks(), fitted(2000f))
    }

    @Test
    fun aNarrowPlotDropsTheSixHourTicksFirstAndKeepsTheDayStart() {
        // 32 hours over 500 px: a tick every 94 px is too close for 60 px labels with ends of their own.
        val narrow = fitted(500f, label = 90f)
        assertTrue(narrow.none { it.hour == 6 || it.hour == 18 })
        assertTrue(narrow.any { it.dayStart })
        assertTrue(narrow.any { it.hour == 12 })
        assertEquals("the right edge stays", 24, narrow.last().hour)
    }

    @Test
    fun aTinyPlotKeepsOnlyTheDayStartAndTheEdgesThatFit() {
        val tiny = fitted(200f, label = 60f)
        assertTrue(tiny.any { it.dayStart })
        assertTrue(tiny.all { it.dayStart || it.minute == 0f || it.minute == span })
    }

    @Test
    fun theDayAxisKeepsItsFiveTicksOnAWidgetWidth() {
        val ticks = ChartTimeAxis.WallClock.ticks()
        assertEquals(ticks, ChartTickFit.fit(ticks, 1440f, { it / 1440f * 700f }, { 40f }, 8f))
    }
}
