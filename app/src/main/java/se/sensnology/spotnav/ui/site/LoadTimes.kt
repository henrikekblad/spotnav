package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.heat.HeatLoadPress
import se.sensnology.spotnav.ha.heat.HeatLoads
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The spa's bath times and the sauna's sessions as the card's times editor takes them (`heat-editors.ts`
 * `sessionsEditor`): up to [MAX] weekly times, each a clock time on its weekdays (Monday 1 to Sunday 7, as a car's
 * departures), and one one-off on a date. Pure: what is entered is checked here and turned into the presses that
 * change only what changed (the weekly times first, then the one-off), as the card sends them.
 */
internal object LoadTimes {
    /** The most weekly times a spa or a sauna takes (`spa/config.py`). */
    const val MAX = 4

    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** A new weekly time: every day, at the card's default hour. */
    fun fresh(kind: String) = HeatLoads.Session(listOf(1, 2, 3, 4, 5, 6, 7), defaultTime(kind))

    /** The card's default hour for a new time: 19:00 for a bath, 18:00 for a session. */
    fun defaultTime(kind: String): String = if (kind == "spa") "19:00" else "18:00"

    /** The clock time as the webhook takes it ("07:05"). */
    fun clock(hour: Int, minute: Int): String = LocalTime.of(hour, minute).format(CLOCK)

    /** The one-off's date and time to start the editor from: the stored one, else tomorrow at the default hour. */
    fun onceStart(kind: String, once: Instant?, now: Instant, zone: ZoneId): Pair<LocalDate, LocalTime> {
        once?.let { val local = it.atZone(zone); return local.toLocalDate() to local.toLocalTime().withSecond(0).withNano(0) }
        return now.atZone(zone).toLocalDate().plusDays(1) to LocalTime.parse(defaultTime(kind))
    }

    sealed interface Checked {
        /** The presses to send, in order; none when nothing changed. */
        data class Presses(val bodies: List<org.json.JSONObject>) : Checked

        /** The card's key of the words that say what is wrong. */
        data class Invalid(val key: String) : Checked
    }

    /**
     * Check what was entered against [current] (weekly) and [currentOnce]: a time without weekdays, or a one-off in
     * the past, is refused with the card's words; otherwise the presses for what changed.
     */
    fun check(
        kind: String,
        current: List<HeatLoads.Session>,
        currentOnce: Instant?,
        weekly: List<HeatLoads.Session>,
        once: Pair<LocalDate, LocalTime>?,
        now: Instant,
        zone: ZoneId
    ): Checked {
        if (weekly.size > MAX || weekly.any { it.weekdays.isEmpty() || !Regex("\\d{2}:\\d{2}").matches(it.time) }) return Checked.Invalid("heat.spa.timesInvalid")
        val sorted = weekly.map { it.copy(weekdays = it.weekdays.distinct().sorted()) }
        val at = once?.let { (date, time) -> date.atTime(time).atZone(zone).toInstant() }
        if (at != null && !at.isAfter(now)) return Checked.Invalid("heat.error.past")
        val bodies = buildList {
            if (sorted != current) add(HeatLoadPress.weekly(kind, sorted))
            if (at != currentOnce) add(HeatLoadPress.once(kind, at, zone))
        }
        return Checked.Presses(bodies)
    }

    /** The dial's range and step: the pool 18 to 40 and the spa 20 to 40 in half degrees, the sauna 40 to 110 in whole ones. */
    fun range(kind: String): Triple<Double, Double, Double> = when (kind) {
        "pool" -> Triple(18.0, 40.0, 0.5)
        "spa" -> Triple(20.0, 40.0, 0.5)
        else -> Triple(40.0, 110.0, 1.0)
    }

    /** [base] moved by one step up or down ([up]), on the step and inside the range. */
    fun stepped(kind: String, base: Double, up: Boolean): Double {
        val (low, high, step) = range(kind)
        val next = Math.round((base + if (up) step else -step) / step) * step
        return next.coerceIn(low, high)
    }
}
