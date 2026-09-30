package se.sensnology.spotnav.chart

import se.sensnology.spotnav.ha.authority.RemotePlan
import se.sensnology.spotnav.planning.ChargingPeriod
import se.sensnology.spotnav.planning.ChargingPlanner
import se.sensnology.spotnav.prices.PriceResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One drawable band of shading: a represented market date and the wall-clock span on it.
 *
 * [fromMinute] and [toMinute] are minutes of [date]'s own day; a band never runs backwards or spans a
 * midnight or offset transition. A band with `toMinute == fromMinute` is not drawable (see [isDrawable]).
 */
internal data class ChartBand(
    val date: LocalDate,
    val fromMinute: Float,
    /**
     * The band's end, in the same minutes, never above a whole day. On a fall-back day the repeated
     * hour is covered by two ordered bands even though the graph collapses them onto one x.
     */
    val toMinute: Float
) {
    init {
        require(fromMinute >= 0f) { "a band cannot start before its day" }
        require(toMinute <= MINUTES_PER_DAY) { "a band cannot end after its day" }
        require(toMinute >= fromMinute) { "a band cannot run backwards" }
    }

    val isDrawable: Boolean get() = toMinute > fromMinute

    private companion object {
        const val MINUTES_PER_DAY = 1440f
    }
}

/**
 * The stretches a price graph should shade, as data: a local plan's periods ([planned]), Home
 * Assistant's installed schedule ([installed], only when the dashboard says one is installed), or
 * [NONE].
 *
 * [bands] turns periods into wall-clock rectangles: converted by instant into the market zone,
 * clipped to the represented dates, split at local midnights and offset transitions, with
 * non-positive intervals dropped.
 */
internal data class ChartOverlay(val periods: List<ChargingPeriod>) {
    val isEmpty: Boolean get() = periods.isEmpty()

    /**
     * The bands this overlay draws on the days [result] represents, in the market's zone. Each date's
     * bands are the sorted union of what covers it (see `union`): one rectangle per shaded stretch.
     */
    fun bands(result: PriceResult, zoneId: ZoneId): List<ChartBand> {
        val days = representedDays(result, zoneId)
        val drawable = positive(periods)
        if (days.isEmpty() || drawable.isEmpty()) return emptyList()
        return days.flatMap { day -> union(day, dayBands(drawable, day, zoneId)) }
    }

    companion object {
        /** No shading at all. */
        val NONE = ChartOverlay(emptyList())

        fun planned(periods: List<ChargingPeriod>): ChartOverlay = ChartOverlay(positive(periods))

        /** The installed schedule one status reported; `null` or `installed == false` shades nothing. */
        fun installed(plan: RemotePlan?): ChartOverlay =
            if (plan != null && plan.installed) ChartOverlay(positive(plan.periods)) else NONE

        /** The local dates the price documents represent, in the market's zone; one column per date. */
        private fun representedDays(result: PriceResult, zoneId: ZoneId): List<LocalDate> =
            (result.today + result.tomorrow)
                .map { point -> point.start.atZoneSameInstant(zoneId).toLocalDate() }
                .distinct()
                .sorted()

        private fun positive(periods: List<ChargingPeriod>): List<ChargingPeriod> =
            periods.filter { period -> period.end.isAfter(period.start) }

        /**
         * One date's bands as the union of everything covering it: ordered, and merged where the next band
         * begins no later than the previous one ends, so the renderer paints one rectangle per stretch.
         */
        private fun union(day: LocalDate, bands: List<ChartBand>): List<ChartBand> {
            val ordered = bands.filter { it.isDrawable }.sortedWith(compareBy({ it.fromMinute }, { it.toMinute }))
            val merged = mutableListOf<ChartBand>()
            ordered.forEach { band ->
                val last = merged.lastOrNull()
                if (last != null && band.fromMinute <= last.toMinute) {
                    // Overlapping or touching: merge, keeping the later end.
                    merged[merged.lastIndex] = last.copy(toMinute = maxOf(last.toMinute, band.toMinute))
                } else {
                    merged += band
                }
            }
            return merged
        }

        /** One represented day's bands, from every period that reaches into it. */
        private fun dayBands(periods: List<ChargingPeriod>, day: LocalDate, zoneId: ZoneId): List<ChartBand> {
            val dayStart = day.atStartOfDay(zoneId).toInstant()
            val dayEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant()
            // The zone's calendar decides the day's length (23, 24 or 25 hours); offset transitions inside it are cut points.
            val transitions = transitionsBetween(dayStart, dayEnd, zoneId)
            val byInstant = transitions.associateBy { it.instant }
            return periods.flatMap { period ->
                val start = maxOf(period.start.toInstant(), dayStart)
                val end = minOf(period.end.toInstant(), dayEnd)
                if (!end.isAfter(start)) return@flatMap emptyList()
                val cuts = (listOf(start) + transitions.map { it.instant }.filter { it.isAfter(start) && it.isBefore(end) } + listOf(end))
                    .distinct()
                    .sorted()
                cuts.zipWithNext().mapNotNull { (from, to) ->
                    band(day, from, to, dayStart, dayEnd, zoneId, byInstant)
                }
            }
        }

        /**
         * One stretch between two cuts, as wall-clock geometry on [day]. A cut at an offset transition is
         * labelled with the offset that applies inside the stretch (`dateTimeBefore` for an end,
         * `dateTimeAfter` for a start), so a spring-forward gap is not shaded.
         */
        private fun band(
            day: LocalDate,
            from: Instant,
            to: Instant,
            dayStart: Instant,
            dayEnd: Instant,
            zoneId: ZoneId,
            transitions: Map<Instant, java.time.zone.ZoneOffsetTransition>
        ): ChartBand? {
            val fromLocal = transitions[from]?.dateTimeAfter?.toLocalTime() ?: from.atZone(zoneId).toLocalTime()
            val toLocal = transitions[to]?.dateTimeBefore?.toLocalTime() ?: to.atZone(zoneId).toLocalTime()
            val fromMinute = minuteOf(fromLocal, from, dayStart, dayEnd)
            val toMinute = minuteOf(toLocal, to, dayStart, dayEnd)
            if (toMinute <= fromMinute) return null
            return ChartBand(day, fromMinute, toMinute)
        }

        /**
         * The offset transitions between two instants. Uses [java.time.zone.ZoneRules.nextTransition]
         * rather than `ZoneRules.transitions`, which lists only transitions in the zone's data tables and
         * misses days years ahead.
         */
        private fun transitionsBetween(
            start: Instant,
            end: Instant,
            zoneId: ZoneId
        ): List<java.time.zone.ZoneOffsetTransition> {
            val found = mutableListOf<java.time.zone.ZoneOffsetTransition>()
            var next = zoneId.rules.nextTransition(start)
            while (next != null && next.instant.isBefore(end)) {
                found += next
                next = zoneId.rules.nextTransition(next.instant)
            }
            return found
        }

        /** A wall-clock label as a minute of its day; the day's own ends are pinned to 0 and 1440. */
        private fun minuteOf(local: java.time.LocalTime, instant: Instant, dayStart: Instant, dayEnd: Instant): Float = when {
            !instant.isAfter(dayStart) -> 0f
            !instant.isBefore(dayEnd) -> MINUTES_PER_DAY
            else -> local.hour * 60 + local.minute + local.second / 60f
        }

        private const val MINUTES_PER_DAY = 1440f
    }
}
