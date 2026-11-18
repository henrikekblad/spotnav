package se.sensnology.spotnav.ui.charging

import se.sensnology.spotnav.ha.dashboard.DashboardDepartureCalendar
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * What the last plan made of the charger's departure calendar, as the card's Plan dialog says it under the calendar
 * (`calendarNote` in the card's `settings-editor.ts`). The calendar is chosen in Home Assistant; the app only says
 * what it does to the departure shown here.
 */
internal sealed interface DepartureCalendarNote {
    /**
     * The calendar's event is the plan's departure, on [date] at [time] in the market's zone. [targetPercent] or
     * [targetKwh] is the event's own target when the plan took it; [targetIgnored] when the event states one of the
     * other kind (a level for an amount, or energy for a target), which is not used.
     */
    data class Used(
        val date: LocalDate,
        val time: LocalTime,
        val targetPercent: Double?,
        val targetKwh: Double?,
        val targetIgnored: Boolean
    ) : DepartureCalendarNote

    /** No event in the next days moves the departure: the daily one is used. */
    data object Daily : DepartureCalendarNote

    /** A chosen date goes before the calendar. */
    data object Date : DepartureCalendarNote

    /** The calendar cannot be read: the daily departure is used until it can. */
    data object Unavailable : DepartureCalendarNote

    /** The calendar alone decides and has no event ahead: there is no departure. */
    data object NoEvent : DepartureCalendarNote

    companion object {
        /**
         * The note for [block], or `null` when there is nothing to say: no calendar chosen, nothing calculated yet,
         * the departure switched off, or a used calendar whose departure cannot be placed (no instant, no [zone]).
         */
        fun of(block: DashboardDepartureCalendar?, departureEnabled: Boolean, zone: ZoneId?): DepartureCalendarNote? {
            if (block == null || block.entityId == null) return null
            return when (block.state) {
                DashboardDepartureCalendar.USED -> {
                    val at = block.departure?.takeIf { departureEnabled }?.atZone(zone ?: return null) ?: return null
                    val percent = block.targetPercent?.takeIf { block.targetUsed == "percent" }
                    val kwh = block.targetKwh?.takeIf { block.targetUsed == "kwh" }
                    Used(
                        date = at.toLocalDate(),
                        time = at.toLocalTime().truncatedTo(ChronoUnit.MINUTES),
                        targetPercent = percent,
                        targetKwh = kwh,
                        targetIgnored = block.targetUsed == null && (block.targetPercent != null || block.targetKwh != null)
                    )
                }
                DashboardDepartureCalendar.DAILY -> Daily
                DashboardDepartureCalendar.DATE -> Date
                DashboardDepartureCalendar.UNAVAILABLE -> Unavailable
                DashboardDepartureCalendar.NO_EVENT -> NoEvent
                else -> null
            }
        }
    }
}
