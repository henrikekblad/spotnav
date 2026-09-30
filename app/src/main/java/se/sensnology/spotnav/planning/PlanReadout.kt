package se.sensnology.spotnav.planning

import se.sensnology.spotnav.chargers.ChargerPhases
import se.sensnology.spotnav.vehicles.VehicleEnergy
import se.sensnology.spotnav.vehicles.VehicleFacts
import java.time.OffsetDateTime

/**
 * The two readout decisions the plan card makes, kept free of any Android dependency so each can be
 * unit tested directly — like [VehicleFacts], [VehicleEnergy] and [ChargerPhases].
 */
object PlanReadout {
    /** How the departure row reads: the time it is set for, or nothing set. */
    data class Departure(val hour: Int, val minute: Int, val isSet: Boolean) {
        /**
         * The time as the row shows it (`HH:mm`), or `null` when no departure is in force. A
         * weekday will be added here in 4c if the design asks for it; the row itself needs no
         * change for that.
         */
        fun time(): String? = if (isSet) "%02d:%02d".format(hour, minute) else null
    }

    /** The reading for [enabled] with the stored [hour]/[minute]. */
    fun departureReading(enabled: Boolean, hour: Int, minute: Int): Departure =
        Departure(hour = hour, minute = minute, isSet = enabled)

    fun missesDeparture(
        firstStart: OffsetDateTime,
        durationMinutes: Long,
        enabled: Boolean,
        hour: Int,
        minute: Int
    ): Boolean {
        if (!enabled) return false
        val departure = firstStart.withHour(hour).withMinute(minute)
            .let { if (it <= firstStart) it.plusDays(1) else it }
        return firstStart.plusMinutes(durationMinutes) > departure
    }
}
