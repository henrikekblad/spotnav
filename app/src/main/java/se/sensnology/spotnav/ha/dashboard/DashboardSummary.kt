package se.sensnology.spotnav.ha.dashboard

import org.json.JSONObject

/**
 * The dashboard's read-only `summary` block: the setup in words, so a client can show it without the
 * entity editor. Everything is decoded tolerantly: a missing block, a missing part, a value of the
 * wrong type or a code this build does not know leaves that part `null` (its row is left out) and is
 * never a decode failure. Names are friendly names; an entity id is never read here.
 */
internal data class DashboardSummary(
    val charger: Charger?,
    val site: Site?,
    /** Vehicle id to the friendly name of its charge-level sensor; a vehicle with none is absent. */
    val vehicleSensorNames: Map<String, String>
) {
    /** How the charging current is set, as far as this build knows the code. */
    enum class CurrentPath { CHANGE_CONFIGURATION, NUMBER, EASEE_DYNAMIC_LIMIT, NONE }

    /** How the site's phase currents are obtained, as far as this build knows the code. */
    enum class MeasurementMode { DIRECT, DERIVED }

    data class Charger(
        val startStopName: String?,
        val currentPath: CurrentPath?,
        val currentEntityName: String?,
        val energyName: String?,
        /** `null` when the block does not say. */
        val energyAutomatic: Boolean?
    )

    data class Site(
        val mainFuseA: Double?,
        val measurementMode: MeasurementMode?,
        val batteryName: String?
    )

    companion object {
        fun parse(raw: Any?): DashboardSummary? {
            val json = raw as? JSONObject ?: return null
            return DashboardSummary(
                charger = (json.opt("charger") as? JSONObject)?.let(::charger),
                site = (json.opt("site") as? JSONObject)?.let(::site),
                vehicleSensorNames = vehicles(json.opt("vehicles"))
            )
        }

        private fun charger(json: JSONObject) = Charger(
            startStopName = text(json, "start_stop_name"),
            currentPath = when (json.opt("current_path")) {
                "change_configuration" -> CurrentPath.CHANGE_CONFIGURATION
                "number" -> CurrentPath.NUMBER
                "easee_dynamic_limit" -> CurrentPath.EASEE_DYNAMIC_LIMIT
                "none" -> CurrentPath.NONE
                else -> null
            },
            currentEntityName = text(json, "current_entity_name"),
            energyName = text(json, "energy_name"),
            energyAutomatic = json.opt("energy_automatic") as? Boolean
        )

        private fun site(json: JSONObject) = Site(
            mainFuseA = (json.opt("main_fuse_a") as? Number)?.toDouble()?.takeIf { it.isFinite() },
            measurementMode = when (json.opt("measurement_mode")) {
                "direct_phase_current" -> MeasurementMode.DIRECT
                "derived_phase_current" -> MeasurementMode.DERIVED
                else -> null
            },
            batteryName = text(json, "battery_name")
        )

        private fun vehicles(raw: Any?): Map<String, String> {
            val json = raw as? JSONObject ?: return emptyMap()
            return json.keys().asSequence().mapNotNull { id ->
                val name = (json.opt(id) as? JSONObject)?.let { text(it, "soc_sensor_name") }
                name?.let { id to it }
            }.toMap()
        }

        private fun text(json: JSONObject, key: String): String? =
            (json.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
