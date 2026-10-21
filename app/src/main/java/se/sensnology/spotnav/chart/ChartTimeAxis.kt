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

    /**
     * The ticks every six hours, left to right: the day divider, and the hour labels when the
     * three-hourly ones would touch (see [ChartAxisLabels]).
     */
    fun ticks(): List<ChartTick> = ticks(GRID_STEP_HOURS)

    /** The same sequence every three hours: the chart's hour labels when they fit (see [ChartAxisLabels]). */
    fun labelTicks(): List<ChartTick> = ticks(LABEL_STEP_HOURS)

    /** The ticks every [stepHours] (a divisor of 24), left to right, the right edge included. */
    fun ticks(stepHours: Int): List<ChartTick>

    /**
     * The prices drawn at full strength, which the header's highest and lowest cover: today's on the
     * Day axis, where tomorrow is a subdued comparison, and everything on the axis on a range.
     */
    fun fullStrength(today: List<PriceInterval>, tomorrow: List<PriceInterval>): List<PriceInterval> =
        (if (overlay) today else today + tomorrow).filter(::shows)

    /** The wall-clock overlay: a time's place is its local time of day, whichever day it is on. */
    data object WallClock : ChartTimeAxis {
        override val spanMinutes: Float = MINUTES_PER_DAY
        override val overlay: Boolean = true
        override fun markMinute(interval: PriceInterval): Float = ChartNow.markMinute(interval)
        override fun shows(interval: PriceInterval): Boolean = true

        /**
         * Fixed ticks from 00 to 24 (five every six hours, nine every three), whichever days are
         * published, so 23-, 24- and 25-hour days draw the same axis.
         */
        override fun ticks(stepHours: Int): List<ChartTick> =
            (0..24 step stepHours).map { hour -> ChartTick(hour * 60f, hour, null) }
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
         * Every local 00, 06, 12 and 18 (every three hours with [stepHours] 3) inside the range (each
         * found by instant, so a 23- or 25-hour day keeps its ticks on those wall clocks), then the right
         * edge. A midnight strictly inside is a [ChartTick.dayStart]; the left edge, the current hour, has
         * no tick of its own.
         */
        override fun ticks(stepHours: Int): List<ChartTick> {
            val first = start.atZone(zone).toLocalDate()
            val last = end.atZone(zone).toLocalDate()
            val inside = generateSequence(first) { it.plusDays(1) }
                .takeWhile { !it.isAfter(last) }
                .flatMap { date -> (0 until 24 step stepHours).asSequence().map { hour -> hour to instantAt(date, hour) } }
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
        /** The divider's and the sparse labels' step: every six hours. */
        const val GRID_STEP_HOURS = 6

        /** The chart's hour labels' step when they fit: every three hours. */
        const val LABEL_STEP_HOURS = 3

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

/**
 * Which ticks a plot of a given width labels: all of them while their labels fit side by side, else
 * the six-hour ones (06 and 18) go first, then the noons and midnights, so a narrow chart, such as a
 * small widget, keeps the same axis with fewer labels. A day's start ([ChartTick.dayStart]) is always
 * kept; an edge that would collide with one gives way. Android-free.
 */
internal object ChartTickFit {
    /** Whether every one of [ticks] has room for its label, [gap] apart (see [fit] for the anchoring). */
    fun fits(
        ticks: List<ChartTick>,
        spanMinutes: Float,
        xAt: (Float) -> Float,
        labelWidth: (ChartTick) -> Float,
        gap: Float
    ): Boolean = fit(ticks, spanMinutes, xAt, labelWidth, gap).size == ticks.size

    fun fit(
        ticks: List<ChartTick>,
        spanMinutes: Float,
        xAt: (Float) -> Float,
        labelWidth: (ChartTick) -> Float,
        gap: Float
    ): List<ChartTick> {
        fun extent(tick: ChartTick): ClosedFloatingPointRange<Float> {
            val x = xAt(tick.minute)
            val w = labelWidth(tick)
            return when (tick.minute) {
                0f -> x..(x + w)
                spanMinutes -> (x - w)..x
                else -> (x - w / 2f)..(x + w / 2f)
            }
        }
        fun fits(chosen: List<ChartTick>): Boolean =
            chosen.zipWithNext().all { (a, b) -> extent(a).endInclusive + gap <= extent(b).start }
        fun rank(tick: ChartTick): Int = when {
            tick.dayStart || tick.minute == 0f || tick.minute == spanMinutes -> 0
            tick.hour == 0 || tick.hour == 12 || tick.hour == 24 -> 1
            else -> 2
        }
        for (most in 2 downTo 0) {
            val chosen = ticks.filter { rank(it) <= most }
            if (fits(chosen)) return chosen
        }
        // Only day starts and edges are left and still collide: the edges next to a day start give way.
        val starts = ticks.filter { it.dayStart }
        return ticks.filter { tick ->
            rank(tick) == 0 && (tick.dayStart || starts.none { start ->
                val a = extent(tick)
                val b = extent(start)
                a.start < b.endInclusive + gap && b.start < a.endInclusive + gap
            })
        }
    }
}
