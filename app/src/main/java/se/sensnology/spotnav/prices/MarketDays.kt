package se.sensnology.spotnav.prices

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Two calendars, and the one place they meet (contract v2's `market_tz`).
 *
 * A day file `<MM-DD>` covers `[MM-DD 00:00, next day 00:00)` on the area's **market** calendar,
 * while the app shows days ("today", "tomorrow", the chart, the plan) on the area's **display**
 * calendar. For almost every area the two are one zone and a display day is exactly one file. Great
 * Britain is not: Agile is published 23:00 to 23:00 UK time, which is a Paris day, so a London day is
 * the last 23 hours of one file and the first hour of the next, and on the evening of the 4th in
 * London it is already the 5th in Paris. Portugal in v2 (Lisbon shown, Madrid calendar) has the same
 * shape.
 *
 * Everything here is instants: a display day is `[its first instant, the next day's first instant)`
 * in the display zone, and a file belongs to it when any part of the file's market day overlaps that
 * span, so a clock-change day of 46 or 50 half-hours needs no case of its own. The relay's web page
 * (`market-day.ts`) and Home Assistant (`market_day.py`) follow the same rules.
 */
internal object MarketDays {
    /** The first and the first-after instants of [date] in [zone]. */
    fun span(date: LocalDate, zone: ZoneId): Pair<Instant, Instant> =
        date.atStartOfDay(zone).toInstant() to date.plusDays(1).atStartOfDay(zone).toInstant()

    /**
     * The market-day files that cover the display date [date], in date order: one when the two
     * calendars agree, two when they do not (never more for zones within a day of each other).
     */
    fun keysFor(date: LocalDate, tz: String, marketTz: String): List<LocalDate> {
        if (tz == marketTz) return listOf(date)
        val market = ZoneId.of(marketTz)
        val (start, end) = span(date, ZoneId.of(tz))
        val first = start.atZone(market).toLocalDate()
        val last = end.minusNanos(1).atZone(market).toLocalDate()
        val keys = mutableListOf(first)
        // Bounded: two zones on this planet are never more than a calendar day apart.
        while (keys.size < 3 && keys.last() < last) keys.add(keys.last().plusDays(1))
        return keys
    }

    /**
     * The market day holding the middle of the display date: the file most of it comes from, and the
     * one whose publication decides whether the display day exists ("tomorrow published"). A London
     * day is 23 hours of one Paris file and one hour of the next, so it is the first. Equal to [date]
     * when the calendars agree.
     */
    fun principal(date: LocalDate, tz: String, marketTz: String): LocalDate {
        if (tz == marketTz) return date
        val (start, end) = span(date, ZoneId.of(tz))
        val middle = start.plusMillis((end.toEpochMilli() - start.toEpochMilli()) / 2)
        return middle.atZone(ZoneId.of(marketTz)).toLocalDate()
    }

    /**
     * The points of the display date [date] in [zone], cut from whichever market files' points are
     * given: distinct by instant (two files never overlap, but a defensive repeat is dropped), in time
     * order.
     */
    fun cut(points: List<PricePoint>, date: LocalDate, zone: ZoneId): List<PricePoint> {
        val (start, end) = span(date, zone)
        val byInstant = LinkedHashMap<Instant, PricePoint>()
        for (point in points) {
            val instant = point.start.toInstant()
            if (instant < start || instant >= end) continue
            byInstant.putIfAbsent(instant, point)
        }
        return byInstant.values.sortedBy { it.start.toInstant() }
    }

    /** The points of [points] that a market day [date] in [marketZone] prices. */
    fun within(points: List<PricePoint>, date: LocalDate, marketZone: ZoneId): List<PricePoint> {
        val (start, end) = span(date, marketZone)
        return points.filter { val instant = it.start.toInstant(); instant >= start && instant < end }
    }
}
