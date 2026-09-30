package se.sensnology.spotnav.vehicles

import se.sensnology.spotnav.chargers.ChargerProfile

/**
 * One vehicle the Home Assistant integration detected automatically and reported in its dashboard
 * (the `soc` block and the `vehicles` row it names).
 */
data class VehicleStatus(
    val id: String,
    val name: String,
    val socPercent: Double,
    val targetSocPercentMax: Double? = null,
    val batteryCapacityKwh: Double? = null,
    val capacitySource: String? = null
)
