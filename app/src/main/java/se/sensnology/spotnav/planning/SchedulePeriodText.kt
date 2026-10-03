package se.sensnology.spotnav.planning

import se.sensnology.spotnav.prices.PriceMarkets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The clock a market's own wall clock is: the area's `tz`, and the device's **only** when no market
 * is known at all.
 */
internal object MarketZone {
    /** [areaId]'s own zone, or the device's when the catalogue does not know that area (or none was named). */
    fun of(areaId: String?): ZoneId =
        areaId?.let { PriceMarkets.find(it)?.zoneId } ?: ZoneId.systemDefault()

    /**
     * The calendar [areaId]'s relay day files are dated on (contract v2's `market_tz`): the zone a
     * publication is expected in. Equal to [of] for every area with one calendar.
     */
    fun marketCalendarOf(areaId: String?): ZoneId =
        areaId?.let { PriceMarkets.find(it)?.marketZoneId } ?: of(areaId)
}

/** One endpoint of a schedule period, in the market's own wall clock. */
internal data class ScheduleMoment(
    /** The exact instant this endpoint names; never re-derived, so the interval is preserved. */
    val instant: Instant,
    /** The market-local date this instant falls on. */
    val date: LocalDate,
    /** The market-local wall-clock time this instant reads as. */
    val time: LocalTime
)

/** One schedule period's two endpoints, both stated in the market's own clock. */
internal data class ScheduleWindow(
    val start: ScheduleMoment,
    val end: ScheduleMoment
) {
    /** Whether the two endpoints fall on different market-local dates. */
    val crossesLocalMidnight: Boolean get() = start.date != end.date

    /** The start's market-local label, in [locale]. */
    fun startText(locale: Locale): String = label(start, locale)

    /** The end's market-local label, in [locale]. */
    fun endText(locale: Locale): String = label(end, locale)

    private fun label(moment: ScheduleMoment, locale: Locale): String =
        (if (crossesLocalMidnight) DAY_AND_TIME else TIME_ONLY)
            .withLocale(locale)
            .format(LocalDateTime.of(moment.date, moment.time))

    companion object {
        /** Same local date: weekday and `HH:mm`, exactly the shape the plan card has always shown. */
        private val TIME_ONLY = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ROOT)

        /** Different local dates: the day is part of the statement, so it is part of the label. */
        private val DAY_AND_TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ROOT)
    }
}

/**
 * The one human-readable form of a schedule period: both endpoints converted **by instant** into
 * the market's own zone, and nothing else.
 */
internal object SchedulePeriodText {
    /** The market-local window [period] names, at [zoneId]. */
    fun window(period: ChargingPeriod, zoneId: ZoneId): ScheduleWindow = ScheduleWindow(
        start = moment(period.start, zoneId),
        end = moment(period.end, zoneId)
    )

    /** One period's line, through [compose] — which is where a caller's own localization belongs. */
    fun line(
        period: ChargingPeriod,
        zoneId: ZoneId,
        locale: Locale,
        compose: (String, String) -> String
    ): String {
        val window = window(period, zoneId)
        return compose(window.startText(locale), window.endText(locale))
    }

    private fun moment(at: OffsetDateTime, zoneId: ZoneId): ScheduleMoment {
        val instant = at.toInstant()
        val local = instant.atZone(zoneId)
        return ScheduleMoment(instant, local.toLocalDate(), local.toLocalTime())
    }
}
