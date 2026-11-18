package se.sensnology.spotnav.ha.dashboard

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The dashboard's `departure_calendar` block (Home Assistant 3.0, asked for with the `reads` value
 * `departure_calendar`): the calendar whose events are the charger's departures, and what the last calculation made of
 * it. The calendar is chosen in Home Assistant; this app shows it and never writes it (a settings replacement leaves
 * the field out, and Home Assistant then keeps the stored calendar).
 *
 * [state] is one of [STATES], or `null` when no calendar is chosen or nothing is calculated yet. With [USED] the
 * calendar's [departure] is the plan's; [targetUsed] says which of the event's own targets the plan took (`percent`,
 * `kwh` or `null`). Read leniently: a block that does not say what the contract says is no block at all.
 */
internal data class DashboardDepartureCalendar(
    /** The chosen calendar's entity id, or `null` when none is chosen. */
    val entityId: String?,
    /** The chosen calendar's name as Home Assistant lists it, or `null`. */
    val name: String?,
    val state: String?,
    val departure: Instant?,
    val targetPercent: Double?,
    val targetKwh: Double?,
    val targetUsed: String?
) {
    companion object {
        const val USED = "used"
        const val DAILY = "daily"
        const val DATE = "date"
        const val UNAVAILABLE = "unavailable"
        const val NO_EVENT = "no_event"
        const val OFF = "off"
        val STATES = setOf(USED, DAILY, DATE, UNAVAILABLE, NO_EVENT, OFF)

        fun parse(raw: Any?): DashboardDepartureCalendar? {
            val block = raw as? JSONObject ?: return null
            return runCatching { decode(block) }.getOrNull()
        }

        private fun decode(block: JSONObject): DashboardDepartureCalendar {
            val entityId = textOrNull(block, "entity_id")
            val state = textOrNull(block, "state")
            require(state == null || state in STATES)
            val used = textOrNull(block, "target_used")
            require(used == null || used == "percent" || used == "kwh")
            val calendars = block.optJSONArray("calendars")
            val name = (0 until (calendars?.length() ?: 0))
                .mapNotNull { calendars?.opt(it) as? JSONObject }
                .firstOrNull { it.opt("entity_id") == entityId }
                ?.let { it.opt("name") as? String }
                ?.takeIf { it.isNotBlank() }
            return DashboardDepartureCalendar(
                entityId = entityId,
                name = name,
                state = state,
                departure = textOrNull(block, "departure")?.let { OffsetDateTime.parse(it).toInstant() },
                targetPercent = numberOrNull(block, "target_percent"),
                targetKwh = numberOrNull(block, "target_kwh"),
                targetUsed = used
            )
        }

        /** The key must be there: text, or JSON null. */
        private fun textOrNull(block: JSONObject, key: String): String? {
            require(block.has(key))
            return when (val value = block.opt(key)) {
                JSONObject.NULL -> null
                is String -> value
                else -> throw IllegalArgumentException("$key is not text")
            }
        }

        private fun numberOrNull(block: JSONObject, key: String): Double? {
            require(block.has(key))
            return when (val value = block.opt(key)) {
                JSONObject.NULL -> null
                is Number -> value.toDouble().takeIf { it.isFinite() } ?: throw IllegalArgumentException("$key is not finite")
                else -> throw IllegalArgumentException("$key is not a number")
            }
        }
    }
}
