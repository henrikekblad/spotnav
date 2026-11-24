package se.sensnology.spotnav.ui.settings

import se.sensnology.spotnav.ha.client.PhoneNotifications
import se.sensnology.spotnav.ha.client.PhoneNotificationsCall
import se.sensnology.spotnav.ha.settings.NotificationEvent

/**
 * The Notifications box for this phone where Home Assistant answers `phone_notifications`, decided as the card's phone
 * editor decides it (`frontend/src/notification-phones.ts`): the event groups the installation has, and what one Save
 * writes. Pure.
 */
internal object PhoneNotificationsEdit {
    enum class Group { CHARGING, POOL, SPA, SAUNA }

    data class EventGroup(val group: Group, val events: List<String>)

    private val CHARGER_EVENTS = NotificationEvent.entries.map { it.wire }

    /**
     * Charging with chargers, Pool with a pool, and *Not ready in time* once, in the first of Charging, Spa and Sauna
     * the installation has (one event covers the car, the bath and the session).
     */
    fun groups(loads: PhoneNotifications.Loads): List<EventGroup> = buildList {
        if (loads.chargers > 0) add(EventGroup(Group.CHARGING, CHARGER_EVENTS))
        if (loads.pool) add(EventGroup(Group.POOL, listOf(PhoneNotificationsCall.POOL_SEASON)))
        if (loads.chargers == 0 && loads.spa) {
            add(EventGroup(Group.SPA, listOf(NotificationEvent.PLAN_AT_RISK.wire)))
        } else if (loads.chargers == 0 && loads.sauna) {
            add(EventGroup(Group.SAUNA, listOf(NotificationEvent.PLAN_AT_RISK.wire)))
        }
    }

    /** The events this box offers, in order. */
    fun shown(loads: PhoneNotifications.Loads): List<String> = groups(loads).flatMap { it.events }

    /** Whether *Not ready in time* says it also covers a bath or a session (shown under Charging). */
    fun atRiskHelp(loads: PhoneNotifications.Loads, event: String): Boolean =
        event == NotificationEvent.PLAN_AT_RISK.wire && loads.chargers > 0 && (loads.spa || loads.sauna)

    /** Whether the chargers it follows are asked: only with more than one. */
    fun asksChargers(value: PhoneNotifications): Boolean = value.installationChargers.size > 1

    /** How many of the shown events are on (the row's "3 of 9"). */
    fun count(value: PhoneNotifications): Pair<Int, Int> {
        val shown = shown(value.loads)
        return shown.count { it in value.events } to shown.size
    }

    /**
     * What one Save changes against [value] as read: `null` when nothing does. Events not shown here are kept, in Home
     * Assistant's order; the chargers only where they are asked, every one ticked written as "all" (also one added later).
     */
    fun change(value: PhoneNotifications, on: Boolean, events: Collection<String>, chargers: Collection<String>): PhoneNotificationsCall.Change? {
        val shown = shown(value.loads)
        val order = value.allEvents + value.events.filter { it !in value.allEvents }
        val wanted = order.filter { if (it in shown) it in events else it in value.events }
        var chargerChange: PhoneNotificationsCall.Chargers? = null
        if (asksChargers(value)) {
            val ids = value.installationChargers.map { it.id }
            val followed = ids.filter { it in chargers }
            val before = ids.filter { value.follows(it) }
            if (followed != before) {
                chargerChange = if (followed.size == ids.size) PhoneNotificationsCall.Chargers.All else PhoneNotificationsCall.Chargers.Only(followed)
            }
        }
        val change = PhoneNotificationsCall.Change(
            on = on.takeIf { it != value.on },
            events = wanted.takeIf { it != value.events },
            chargers = chargerChange
        )
        return change.takeUnless { it.empty }
    }
}
