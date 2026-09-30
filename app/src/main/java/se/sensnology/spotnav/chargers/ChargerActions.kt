package se.sensnology.spotnav.chargers

import se.sensnology.spotnav.ha.dashboard.AutoControl

/**
 * One thing the charger card can ask Home Assistant to do: start a charge now, or stop the one
 * running.
 */
enum class ChargerAction { START, STOP }

/** Which of the charger card's actions the charging state calls for. */
object ChargerActions {
    /** The action the charging state calls for: stop while it is charging, start otherwise. */
    fun primaryAction(chargingEnabled: Boolean?): ChargerAction =
        if (chargingEnabled == true) ChargerAction.STOP else ChargerAction.START
}
