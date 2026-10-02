package se.sensnology.spotnav.ha.settings

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The days a dated departure may name, in the area's zone: today, a week on, and where a date
 * starts (the next occurrence of the time), as the card's own date picker offers them.
 */
internal class DepartureDays private constructor(
    val today: LocalDate,
    val max: LocalDate,
    private val timeNow: LocalTime
) {
    /** Today while [time] is still ahead in the zone, otherwise tomorrow. */
    fun nextOccurrence(time: LocalTime): LocalDate = if (time.isAfter(timeNow)) today else today.plusDays(1)

    /** Whether [date] has gone by: planning ignores it, and the next save clears it. */
    fun isPast(date: LocalDate): Boolean = date.isBefore(today)

    companion object {
        /** The days for [zone] at [now], or `null` when the area's zone is not known. */
        fun of(zone: ZoneId?, now: Instant): DepartureDays? {
            if (zone == null) return null
            val local = ZonedDateTime.ofInstant(now, zone)
            val today = local.toLocalDate()
            return DepartureDays(today, today.plusDays(AHEAD), local.toLocalTime())
        }

        /** How many local days ahead a dated departure may lie (the planner's own limit). */
        const val AHEAD = 7L
    }
}

/** A departure as one value, led by its day when it is dated (`tomorrow 08:00`, `Sun 4 Oct 08:00`). */
internal object DepartureText {
    /**
     * [date]'s name for the reader: today and tomorrow in words, a later day by its weekday and
     * date, and `null` for a day that has gone by. With no known [today] the date is just spelled.
     */
    fun day(date: LocalDate, today: LocalDate?, locale: Locale, todayWord: String, tomorrowWord: String): String? {
        val spelled = DateTimeFormatter.ofPattern("EEE d MMM", if (locale.language == "en") Locale.UK else locale)
            .format(date)
        return when {
            today == null -> spelled
            date.isBefore(today) -> null
            date == today -> todayWord
            date == today.plusDays(1) -> tomorrowWord
            else -> spelled
        }
    }

    fun text(
        date: LocalDate?,
        time: String,
        today: LocalDate?,
        locale: Locale,
        todayWord: String,
        tomorrowWord: String
    ): String {
        val named = date?.let { day(it, today, locale, todayWord, tomorrowWord) }
        return if (named == null) time else "$named $time"
    }
}
