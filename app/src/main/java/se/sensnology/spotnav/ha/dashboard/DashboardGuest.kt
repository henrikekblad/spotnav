package se.sensnology.spotnav.ha.dashboard

import org.json.JSONObject

/**
 * The dashboard's `guest` block (Home Assistant 2.4, asked for with the `reads` value `guest`): whether this charger
 * plans for a guest car now (a person said so), the guest amount the next guest plan starts from, and whether "Guest
 * car" goes first in the open question (every car is ruled out, or the camera saw none of them).
 */
internal data class DashboardGuest(val active: Boolean, val amountKwh: Double, val first: Boolean) {
    companion object {
        /** The answer a guest car is chosen by (`identify_vehicle`), and the target vehicle of a guest plan. */
        const val VEHICLE_ID = "guest"

        /**
         * A guest car's name in Home Assistant's nine languages, as its charge history names a guest's session (the
         * history carries the car's name, never its id).
         */
        private val NAMES = setOf(
            "Guest car", "Gästbil", "Gæstebil", "Gjestebil", "Vierasauto", "Gastauto", "Coche de invitado", "Voiture invitée"
        )

        /** A session's car as the reader's language names it: a guest car is [guestName], any other car as it is. */
        fun sessionVehicle(name: String?, guestName: String): String? =
            if (name == VEHICLE_ID || name in NAMES) guestName else name

        /** The guest amount's range (kWh), as Home Assistant takes it. */
        const val KWH_MIN = 1.0
        const val KWH_MAX = 150.0

        /**
         * The block, or `null` when it is absent or unreadable (a field missing or of another type, an amount that is
         * not a positive number): the app then offers no guest car, never a guessed one.
         */
        fun parse(raw: Any?): DashboardGuest? {
            val block = raw as? JSONObject ?: return null
            val active = block.opt("active") as? Boolean ?: return null
            val first = block.opt("first") as? Boolean ?: return null
            val amount = (block.opt("amount_kwh") as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            return DashboardGuest(active, amount, first)
        }
    }
}
