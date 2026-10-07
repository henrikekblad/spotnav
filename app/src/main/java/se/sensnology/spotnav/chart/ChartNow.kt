package se.sensnology.spotnav.chart

import se.sensnology.spotnav.prices.PriceAggregation
import se.sensnology.spotnav.prices.PriceInterval
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.PriceResult
import java.time.OffsetDateTime

/** Which focus line a frame draws: exactly one of the now line or the selection line, never both. */
internal enum class ChartLineKind { NOW, SELECTION }

/**
 * The one focus line a frame draws: its kind, and the minute of the day it belongs at.
 * The minute is a mark's anchor (see [ChartNow.markMinute]), so line, mark and hit test share one mapping.
 */
internal data class ChartLine(val kind: ChartLineKind, val minute: Float)

/**
 * The current interval and the one-line rule, as pure geometry.
 *
 * `now` is an input, read once where a request is assembled (see `ChartRequest`). It is matched by
 * containment in a published interval (15, 30 or 60 minutes): a `now` no row contains
 * yields no mark minute, and nothing is borrowed from tomorrow or the nearest row.
 */
internal object ChartNow {
    /**
     * The minute of the day a mark for an interval of [minutes] starting at [start] is drawn at: the
     * start for a quarter-hour, the centre of a longer interval (a half-hour or an hour). Selection, the
     * now line and the renderer all read this one definition.
     */
    fun markMinute(start: OffsetDateTime, minutes: Int): Float =
        (start.hour * 60 + start.minute + if (minutes > QUARTER_MINUTES) minutes / 2f else 0f)

    fun markMinute(interval: PriceInterval): Float = markMinute(interval.start, interval.minutes)

    private const val QUARTER_MINUTES = 15

    /**
     * The mark minute of the interval of today's rows that contains [now], or `null` when none does.
     * Today only: tomorrow's rows are a different record (see `ChartReadout`).
     */
    fun currentMarkMinute(result: PriceResult, now: OffsetDateTime): Float? =
        current(result, now)?.let(::markMinute)

    fun currentMarkMinute(market: ChartMarket, result: PriceResult): Float? =
        currentMarkMinute(
            result,
            OffsetDateTime.now(PriceMarkets.find(market.areaId)?.zoneId ?: java.time.ZoneId.systemDefault())
        )

    /**
     * Milliseconds until the interval containing [now] ends, or `null` when no row contains it. This is
     * the only instant at which the now line can move, so a view redraws here and nowhere else.
     */
    fun untilNextIntervalMillis(result: PriceResult, now: OffsetDateTime): Long? {
        val interval = current(result, now) ?: return null
        val end = interval.start.plusMinutes(interval.minutes.toLong())
        return java.time.Duration.between(now, end).toMillis().coerceAtLeast(0L)
    }

    /** Today's published interval that contains [now], or `null`. */
    private fun current(result: PriceResult, now: OffsetDateTime): PriceInterval? =
        PriceAggregation.aggregate(result.today).firstOrNull { interval ->
            !now.isBefore(interval.start) && now.isBefore(interval.start.plusMinutes(interval.minutes.toLong()))
        }

    /**
     * The one focus line a frame draws, or none. A selection hides the now line rather than drawing
     * both; the result depends only on the two inputs (see `ChartScrub`).
     */
    fun line(nowMinute: Float?, selectionMinute: Float?): ChartLine? = when {
        selectionMinute != null -> ChartLine(ChartLineKind.SELECTION, selectionMinute)
        nowMinute != null -> ChartLine(ChartLineKind.NOW, nowMinute)
        else -> null
    }
}
