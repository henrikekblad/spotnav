package se.sensnology.spotnav.chart

import java.time.Instant
import java.time.ZoneId

/**
 * The instant the drawn day changes: the next local midnight. A chart drawn from held rows is cut by the
 * local clock (see [DashboardChart.prices]), so a redraw at this instant is all a new day needs, with no
 * network. Computed from the date, not by adding 24 hours, so a 23- or 25-hour DST day ends at midnight.
 */
internal object DayBoundary {
    /** The first instant after [now] at which [zone]'s date has changed. */
    fun nextMidnight(now: Instant, zone: ZoneId): Instant =
        now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()

    /** The earliest next midnight over [zones], or `null` for none. */
    fun nextMidnight(now: Instant, zones: Collection<ZoneId>): Instant? =
        zones.map { nextMidnight(now, it) }.minOrNull()
}
