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
     * The minute of day the graph draws this position at: [time]'s own minute for quarter-hour
     * presentation, the middle of its hour for hourly. The selection line is drawn here.
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

/** Hit-testing for the graph in the renderer's own coordinates, read from the [ChartMetrics] it drew with. Android-free. */
internal object ChartSelection {
    /** The interval a tap at [x] selects, or `null` outside the plot's horizontal span (the vertical check is the caller's). */
    fun nearest(x: Float, metrics: ChartMetrics, market: ChartMarket, result: PriceResult): ChartReadout? {
        val tapMinute = metrics.minuteOfDayAt(x) ?: return null
        val today = intervals(result.today, market)
        val tomorrow = intervals(result.tomorrow, market)
        // Both days' marks share positions, so duplicates are harmless; the readout looks both days up.
        val chosen = nearestMark(tapMinute, (today + tomorrow).map { it.minuteOfDay }) ?: return null
        // Today's mark first: it is where the selection line goes.
        val mark = today.firstOrNull { it.minuteOfDay == chosen }
            ?: tomorrow.firstOrNull { it.minuteOfDay == chosen }
            ?: return null
        return readout(mark, today, tomorrow)
    }

    /** Every displayed wall-clock position, earliest first; a repeated wall clock appears once (see [readoutAt]). */
    fun positions(market: ChartMarket, result: PriceResult): List<LocalTime> =
        (intervals(result.today, market) + intervals(result.tomorrow, market))
            .map { it.start.toLocalTime() }
            .distinct()
            .sorted()

    /**
     * The position after [current], wrapping around: the non-spatial way through the graph for a screen
     * reader or keyboard. Nothing selected, or a [current] no longer displayed, gives the first.
     */
    fun next(current: LocalTime?, positions: List<LocalTime>): LocalTime? = when {
        positions.isEmpty() -> null
        current == null -> positions.first()
        else -> positions[(positions.indexOf(current) + 1).mod(positions.size)]
    }

    /** The readout at a position, for the cycling path; `null` when nothing is shown there. */
    fun readoutAt(time: LocalTime, market: ChartMarket, result: PriceResult): ChartReadout? {
        val today = intervals(result.today, market)
        val tomorrow = intervals(result.tomorrow, market)
        val mark = (today + tomorrow).firstOrNull { it.start.toLocalTime() == time } ?: return null
        return readout(mark, today, tomorrow)
    }

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

    /** One displayed mark; [minuteOfDay] is the anchor the renderer draws it at. */
    private class Mark(val start: java.time.OffsetDateTime, val price: Double, val minuteOfDay: Float)

    /** The readout for a chosen mark: each column's own values at its wall clock. */
    private fun readout(mark: Mark, today: List<Mark>, tomorrow: List<Mark>): ChartReadout {
        val time = mark.start.toLocalTime()
        return ChartReadout(time, mark.minuteOfDay, occurrencesAt(time, today), occurrencesAt(time, tomorrow))
    }

    /**
     * Every value one day shows at [time], in instant order: usually one, two on a DST fall-back day,
     * where the renderer draws both at the same x. Offsets travel with them.
     */
    private fun occurrencesAt(time: LocalTime, marks: List<Mark>): List<ChartOccurrence> =
        marks.filter { it.start.toLocalTime() == time }
            .sortedBy { it.start.toInstant() }
            .map { ChartOccurrence(it.price, it.start.offset) }

    /** The marks one day's column draws: quarter-hour points at their start, hourly intervals through the middle of the hour. */
    private fun intervals(points: List<PricePoint>, market: ChartMarket): List<Mark> =
        PriceAggregation.aggregate(points, market).map { (start, price) ->
            // Same anchor as the now line and current-interval lookup.
            Mark(start, market.apply(price), ChartNow.markMinute(market, start))
        }
}
