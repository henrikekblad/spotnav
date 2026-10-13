package se.sensnology.spotnav.chart

import se.sensnology.spotnav.prices.PriceAggregation
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import java.time.LocalTime
import kotlin.math.abs

/** One value at the selected wall-clock position; the UTC offset tells repeated occurrences (DST fall-back) apart. */
internal data class ChartOccurrence(val price: Double, val offset: java.time.ZoneOffset)

/**
 * What a tap on the graph selects: one displayed wall-clock position and what each day shows there (all
 * values in instant order where a day repeats the position). Prices are the graph's own
 * (`market.apply`, minor unit). [today] and [tomorrow] are looked up separately; a day with nothing
 * there is empty.
 */
internal data class ChartReadout(
    /** The selected position's local wall clock. */
    val time: LocalTime,
    /**
     * The axis minute the graph draws this position at (see [ChartTimeAxis.markMinute]): [time]'s own
     * minute of the day on the Day axis for a quarter-hour, the middle of a half-hour or an hour. The
     * selection line is drawn here, and the cycling path steps by it.
     */
    val markMinute: Float,
    val today: List<ChartOccurrence>,
    val tomorrow: List<ChartOccurrence>
) {
    val isEmpty: Boolean get() = today.isEmpty() && tomorrow.isEmpty()

    /** Whether a day needs its offsets in words: only when it repeats the position. */
    val todayRepeats: Boolean get() = today.size > 1
    val tomorrowRepeats: Boolean get() = tomorrow.size > 1
}

/**
 * Hit-testing for the graph in the renderer's own coordinates, read from the [ChartMetrics] it drew with,
 * on the [ChartTimeAxis] it drew on. Android-free.
 *
 * A position is a mark's axis minute. On the Day axis today's and tomorrow's marks share positions and a
 * readout lists both days' values at that wall clock; on the From now axis every mark has its own
 * position and a readout is that one interval, in its own day's column.
 */
internal object ChartSelection {
    /** The interval a tap at [x] selects, or `null` outside the plot's horizontal span (the vertical check is the caller's). */
    fun nearest(
        x: Float,
        metrics: ChartMetrics,
        market: ChartMarket,
        result: PriceResult,
        axis: ChartTimeAxis = ChartTimeAxis.WallClock
    ): ChartReadout? {
        val tapMinute = metrics.minuteAt(x) ?: return null
        val today = intervals(result.today, market, axis)
        val tomorrow = intervals(result.tomorrow, market, axis)
        // On the Day axis both days' marks share positions, so duplicates are harmless; the readout looks both days up.
        val chosen = nearestMark(tapMinute, (today + tomorrow).map { it.minute }) ?: return null
        return readoutAt(chosen, today, tomorrow, axis)
    }

    /** Every displayed position, earliest first; a repeated wall clock on the Day axis appears once (see [readoutAt]). */
    fun positions(market: ChartMarket, result: PriceResult, axis: ChartTimeAxis = ChartTimeAxis.WallClock): List<Float> =
        (intervals(result.today, market, axis) + intervals(result.tomorrow, market, axis))
            .map { it.minute }
            .distinct()
            .sorted()

    /**
     * The position after [current], wrapping around: the non-spatial way through the graph for a screen
     * reader or keyboard. Nothing selected, or a [current] no longer displayed, gives the first.
     */
    fun next(current: Float?, positions: List<Float>): Float? = when {
        positions.isEmpty() -> null
        current == null -> positions.first()
        else -> positions[(positions.indexOf(current) + 1).mod(positions.size)]
    }

    /** The readout at a position, for the cycling path; `null` when nothing is shown there. */
    fun readoutAt(
        minute: Float,
        market: ChartMarket,
        result: PriceResult,
        axis: ChartTimeAxis = ChartTimeAxis.WallClock
    ): ChartReadout? = readoutAt(minute, intervals(result.today, market, axis), intervals(result.tomorrow, market, axis), axis)

    /**
     * The mark nearest [minute]; among equally distant marks the earlier wins. Sorts internally and takes
     * a minute rather than an x so ties can be tested exactly.
     */
    fun nearestMark(minute: Float, marks: List<Float>): Float? =
        marks.sorted().minByOrNull { abs(it - minute) }

    /**
     * Whether a selection may stay on screen: it belongs to the frame it was made on, so changed inputs,
     * profile or size invalidate it.
     */
    fun stillValid(selection: ChartReadout?, madeOn: ChartRequest?, drawn: ChartRequest?): Boolean =
        selection != null && madeOn != null && madeOn == drawn

    /** One displayed mark; [minute] is the axis minute the renderer draws it at. */
    private class Mark(val start: java.time.OffsetDateTime, val price: Double, val minute: Float)

    /**
     * The readout at [minute]: today's mark first, since it is where the selection line goes. On the Day
     * axis each column's own values at that mark's wall clock; on the From now axis the one mark alone.
     */
    private fun readoutAt(minute: Float, today: List<Mark>, tomorrow: List<Mark>, axis: ChartTimeAxis): ChartReadout? {
        val mark = today.firstOrNull { it.minute == minute }
            ?: tomorrow.firstOrNull { it.minute == minute }
            ?: return null
        val time = mark.start.toLocalTime()
        if (!axis.overlay) {
            val occurrence = listOf(ChartOccurrence(mark.price, mark.start.offset))
            val isToday = mark in today
            return ChartReadout(time, mark.minute, if (isToday) occurrence else emptyList(), if (isToday) emptyList() else occurrence)
        }
        return ChartReadout(time, mark.minute, occurrencesAt(time, today), occurrencesAt(time, tomorrow))
    }

    /**
     * Every value one day shows at [time], in instant order: usually one, two on a DST fall-back day,
     * where the renderer draws both at the same x. Offsets travel with them.
     */
    private fun occurrencesAt(time: LocalTime, marks: List<Mark>): List<ChartOccurrence> =
        marks.filter { it.start.toLocalTime() == time }
            .sortedBy { it.start.toInstant() }
            .map { ChartOccurrence(it.price, it.start.offset) }

    /**
     * The marks one day's column draws on [axis]: quarter-hour points at their start, longer intervals
     * through their middle, and on the From now axis only those still on it.
     */
    private fun intervals(points: List<PricePoint>, market: ChartMarket, axis: ChartTimeAxis): List<Mark> =
        PriceAggregation.aggregate(points).filter(axis::shows).map { interval ->
            // Same anchor as the now line and current-interval lookup.
            Mark(interval.start, market.apply(interval.price), axis.markMinute(interval))
        }
}
