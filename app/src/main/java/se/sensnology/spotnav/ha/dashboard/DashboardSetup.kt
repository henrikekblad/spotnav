package se.sensnology.spotnav.ha.dashboard

import org.json.JSONArray
import org.json.JSONObject

/**
 * One row of the dashboard's `setup.items` (asked for with the `reads` value `setup`): a "Get started" thing still left
 * for this charger, [id] being one of [IDS] and [dismissible] whether it can be answered "Not needed". A grid sign row
 * (`grid_power_sign`, `grid_current_sign`) also names the site option that fixes it, [field], and the value its fix
 * writes, [value]; both are `null` on every other row, and on a grid sign row that names them wrongly.
 */
internal data class SetupRow(val id: String, val dismissible: Boolean, val field: String? = null, val value: Boolean? = null) {
    companion object {
        /** The rows this app words, in the order the card lists them; a row it does not know is skipped. */
        val IDS: List<String> = listOf(
            "vehicle", "car_level", "site", "grid_power_sign", "grid_current_sign", "solar_forecast", "demand_grid_power",
            "phase_switching", "phone", "app"
        )

        /** The rows of the block in the card's order, or none when the block is absent or unreadable. */
        fun parse(raw: Any?): List<SetupRow> {
            val items = (raw as? JSONObject)?.opt("items") as? JSONArray ?: return emptyList()
            val found = (0 until items.length()).mapNotNull { index ->
                val item = items.opt(index) as? JSONObject ?: return emptyList()
                val id = item.opt("id") as? String ?: return emptyList()
                val dismissible = item.opt("dismissible") as? Boolean ?: return emptyList()
                SetupRow(id, dismissible, item.opt("field") as? String, item.opt("value") as? Boolean)
            }
            return IDS.mapNotNull { id -> found.firstOrNull { it.id == id } }
        }
    }
}
