package se.sensnology.spotnav.chart

import se.sensnology.spotnav.prices.PriceAggregation
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
 * containment in the market's aggregation interval (15 or 60 minutes): a `now` no row contains
 * yields no mark minute, and nothing is borrowed from tomorrow or the nearest row.
 */
internal object ChartNow {
    /**
     * The minute of the day a mark for an interval starting at [start] is drawn at: the start for
     * quarter-hour values, the centre of the hour for hourly ones. Selection, the now line and the
     * renderer all read this one definition.
     */
    fun markMinute(market: ChartMarket, start: OffsetDateTime): Float =
        (start.hour * 60 + start.minute + if (market.hourly) market.intervalMinutes / 2 else 0).toFloat()

    /**
     * The mark minute of the interval of today's rows that contains [now], or `null` when none does.
     * Today only: tomorrow's rows are a different record (see `ChartReadout`).
     */
    fun currentMarkMinute(market: ChartMarket, result: PriceResult, now: OffsetDateTime): Float? {
        val interval = market.intervalMinutes.toLong()
        return PriceAggregation.aggregate(result.today, market).firstOrNull { (start, _) ->
            !now.isBefore(start) && now.isBefore(start.plusMinutes(interval))
        }?.let { (start, _) -> markMinute(market, start) }
    }

    fun currentMarkMinute(market: ChartMarket, result: PriceResult): Float? =
        currentMarkMinute(
            market, result,
            OffsetDateTime.now(PriceMarkets.find(market.areaId)?.zoneId ?: java.time.ZoneId.systemDefault())
        )

    /**
     * Milliseconds until the interval containing [now] ends, or `null` when no row contains it. This is
     * the only instant at which the now line can move, so a view redraws here and nowhere else.
     */
    fun untilNextIntervalMillis(market: ChartMarket, result: PriceResult, now: OffsetDateTime): Long? {
        val interval = market.intervalMinutes.toLong()
        val start = PriceAggregation.aggregate(result.today, market)
            .map { it.first }
            .firstOrNull { !now.isBefore(it) && now.isBefore(it.plusMinutes(interval)) }
            ?: return null
        return java.time.Duration.between(now, start.plusMinutes(interval)).toMillis().coerceAtLeast(0L)
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
