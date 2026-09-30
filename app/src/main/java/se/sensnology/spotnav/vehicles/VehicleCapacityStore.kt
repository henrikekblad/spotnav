package se.sensnology.spotnav.vehicles

import android.content.Context
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.widget.WidgetChargerBindingStore

/**
 * Remembers a battery capacity (kWh) per **vehicle**, for the vehicles Home Assistant reports
 * without one (`VehicleStatus.capacitySource` `"unknown"`, or no `battery_capacity_kwh` at all).
 */
internal class VehicleCapacityStore(private val store: KeyValueStore) {
    fun get(vehicleId: String): Double? {
        if (vehicleId.isBlank()) return null
        return store.getString(key(vehicleId))
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
    }

    /** Stores [kwh] for [vehicleId]. */
    fun set(vehicleId: String, kwh: Double) {
        require(vehicleId.isNotBlank()) { "A vehicle id is required" }
        require(kwh.isFinite() && kwh > 0.0) { "Battery capacity must be a positive number of kWh" }
        store.putString(key(vehicleId), kwh.toString())
    }

    companion object {
        private const val PREFS = "vehicle_capacities"
        private const val KEY_PREFIX = "capacity:"

        private fun key(vehicleId: String) = "$KEY_PREFIX$vehicleId"

        fun forContext(context: Context): VehicleCapacityStore =
            VehicleCapacityStore(SharedPreferencesKeyValueStore(context, PREFS))
    }
}
