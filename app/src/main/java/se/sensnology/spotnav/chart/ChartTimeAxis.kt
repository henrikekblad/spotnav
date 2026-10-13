package se.sensnology.spotnav.chart

import se.sensnology.spotnav.prices.PriceInterval
import se.sensnology.spotnav.prices.PriceResult
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * How the chart's x axis reads time, chosen per phone (see [ChartAxisPreference]): the wall clock of one
 * day ([DAY], the default) or real time from the current hour to the end of the last published day
 * ([FROM_NOW]). A display preference only; it is never sent to Home Assistant.
 */
internal enum class ChartAxisMode(val key: String) {
    DAY("day"),
    FROM_NOW("from_now");

    companion object {
        val DEFAULT = DAY

        /** The mode a stored [key] names; anything else, or nothing, is [DEFAULT]. */
        fun of(key: String?): ChartAxisMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * One tick under the plot: where it is ([minute], in the axis's own minutes), the instant it stands for,
 * and the hour it is labelled with (24 for the right edge of a day). [dayStart] is a local midnight
 * inside the [ChartTimeAxis.Range], drawn as a dashed divider and labelled with the new day rather than
 * its hour; never set on the wall-clock axis or on an edge.
 */
internal data class ChartTick(val minute: Float, val hour: Int, val instant: Instant?, val dayStart: Boolean = false)

/**
 * The one time mapping of a chart, as in the Home Assistant card's `TimeAxis`: everything that turns a
 * time into a place on the plot (marks, plan bands, ticks, the now line, the selection line and the hit
 * test) asks this object, in minutes from the left edge; [ChartMetrics.xAt] turns those into pixels over
 * [spanMinutes].
 *
 * [WallClock] is the overlay of one day, 00 to 24, today and tomorrow drawn over each other. [Range] is
 * real time from the start of the current local hour to the end of the last published day: nothing is
 * overlaid, so no mark is subdued and no band hatched, a run across midnight is one band, and what ended
 * before the axis starts is not drawn.
 */
internal sealed interface ChartTimeAxis {
    /** The time the plot's full width stands for, in minutes. */
    val spanMinutes: Float

    /** Whether other days are drawn over today's: their marks subdued, their plan bands hatched. */
    val overlay: Boolean

    /** The minute a mark for [interval] is drawn at: the start of a quarter-hour, the middle of a longer interval. */
    fun markMinute(interval: PriceInterval): Float

    /** Whether any part of [interval] is on the axis. */
    fun shows(interval: PriceInterval): Boolean

    /** The ticks under the plot, left to right. */
    fun ticks(): List<ChartTick>

    /** The wall-clock overlay: a time's place is its local time of day, whichever day it is on. */
    data object WallClock : ChartTimeAxis {
        override val spanMinutes: Float = MINUTES_PER_DAY
        override val overlay: Boolean = true
        override fun markMinute(interval: PriceInterval): Float = ChartNow.markMinute(interval)
        override fun shows(interval: PriceInterval): Boolean = true

        /** Five fixed ticks, whichever days are published, so 23-, 24- and 25-hour days draw the same axis. */
        override fun ticks(): List<ChartTick> =
            listOf(0, 6, 12, 18, 24).map { hour -> ChartTick(hour * 60f, hour, null) }
    }

    /** Real time from [start] to [end], read in [zone] for ticks and day dividers. */
    data class Range(val start: Instant, val end: Instant, val zone: ZoneId) : ChartTimeAxis {
        init {
            require(end.isAfter(start)) { "a range axis must run forwards" }
        }

        override val spanMinutes: Float get() = minuteOf(end)
        override val overlay: Boolean = false

        /** Minutes from the left edge to [instant]; negative before the axis starts. */
        fun minuteOf(instant: Instant): Float = Duration.between(start, instant).toMillis() / 60_000f

        override fun markMinute(interval: PriceInterval): Float =
            minuteOf(interval.start.toInstant()) + if (interval.minutes > QUARTER_MINUTES) interval.minutes / 2f else 0f

        override fun shows(interval: PriceInterval): Boolean {
            val from = interval.start.toInstant()
            val to = from.plus(Duration.ofMinutes(interval.minutes.toLong()))
            return to.isAfter(start) && from.isBefore(end)
        }

        /**
         * Every local 00, 06, 12 and 18 inside the range (each found by instant, so a 23- or 25-hour day
         * keeps its ticks on those wall clocks), then the right edge. A midnight strictly inside is a
         * [ChartTick.dayStart]; the left edge, the current hour, has no tick of its own.
         */
        override fun ticks(): List<ChartTick> {
            val first = start.atZone(zone).toLocalDate()
            val last = end.atZone(zone).toLocalDate()
            val inside = generateSequence(first) { it.plusDays(1) }
                .takeWhile { !it.isAfter(last) }
                .flatMap { date -> listOf(0, 6, 12, 18).asSequence().map { hour -> hour to instantAt(date, hour) } }
                .filter { (_, instant) -> !instant.isBefore(start) && instant.isBefore(end) }
                .map { (hour, instant) -> ChartTick(minuteOf(instant), hour, instant, dayStart = hour == 0 && instant.isAfter(start)) }
                .toList()
            // The range ends on a local midnight: the last day's 24.
            return inside + ChartTick(spanMinutes, 24, end)
        }

        private fun instantAt(date: LocalDate, hour: Int): Instant =
            if (hour == 0) date.atStartOfDay(zone).toInstant() else date.atTime(hour, 0).atZone(zone).toInstant()
    }

    companion object {
        private const val MINUTES_PER_DAY = 1440f
        private const val QUARTER_MINUTES = 15

        /**
         * The axis a chart of [result] draws in [mode] at [now]. [ChartAxisMode.FROM_NOW] is the range from
         * the start of the current local hour to the end of the last published day, but only while a day
         * after today (in [zone]) has prices; without one it is [WallClock], so the two modes then draw
         * the same.
         */
        fun of(mode: ChartAxisMode, result: PriceResult, zone: ZoneId, now: Instant): ChartTimeAxis {
            if (mode != ChartAxisMode.FROM_NOW) return WallClock
            val today = now.atZone(zone).toLocalDate()
            val dates = (result.today + result.tomorrow).map { it.start.atZoneSameInstant(zone).toLocalDate() }
            val last = dates.maxOrNull() ?: return WallClock
            if (!last.isAfter(today)) return WallClock
            val start = now.atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant()
            val end = last.plusDays(1).atStartOfDay(zone).toInstant()
            return if (end.isAfter(start)) Range(start, end, zone) else WallClock
        }
    }
}
