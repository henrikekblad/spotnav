package se.sensnology.spotnav.chargers

import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.HomeAssistantSettings
import se.sensnology.spotnav.vehicles.ChargeLimit
import se.sensnology.spotnav.vehicles.VehicleRefresh

/**
 * What the charger's integration can be asked to do, as the dashboard's `capabilities` last stated
 * it.
 */
data class ChargerCapabilities(
    /** Whether this integration can be asked to re-read one vehicle's own entities (see [VehicleRefresh]). */
    val refreshVehicle: Boolean = false,
    /** Whether it can *write* a vehicle's charge limit, which [ChargeLimit] drives. */
    val setChargeLimit: Boolean = false,
    /** Whether SpotNav may write a charging current to this charger (the settings overview words it). */
    val setCurrent: Boolean = false
)

/**
 * One SpotNav-controlled charger: a Home Assistant connection plus the charger identity and phase
 * metadata last read from its dashboard.
 */
data class ChargerProfile(
    val localId: String,
    val displayName: String,
    val baseUrl: String,
    val webhookId: String,
    val remoteChargerId: String? = null,
    val remoteChargerName: String? = null,
    val detectedPhases: Int? = null,
    val phaseDetectionSource: String? = null,
    val phaseDetectionConfidence: String? = null,
    val selectedVehicleId: String? = null,
    val targetSocPercent: Int? = null
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && webhookId.isNotBlank()

    /**
     * The label to show for this charger: the name Home Assistant reports, else [displayName], else
     * [fallback] -- never derived from a URL, webhook id or remote charger id.
     */
    fun label(fallback: String): String =
        remoteChargerName?.trim().orEmpty().ifBlank { displayName.trim() }.ifBlank { fallback }

    fun webhookUrl(): String {
        val base = baseUrl.trim().trimEnd('/')
        return "$base/api/webhook/${webhookId.trim()}"
    }

    /**
     * Deliberately overrides the data-class-generated `toString()`, which would otherwise include
     * [webhookId] verbatim in any log line, crash report, or debug print that stringifies a
     * profile.
     */
    override fun toString(): String = "ChargerProfile(localId=$localId, displayName=$displayName, " +
        "baseUrl=$baseUrl, webhookId=<redacted>, remoteChargerId=$remoteChargerId, " +
        "remoteChargerName=$remoteChargerName, " +
        "detectedPhases=$detectedPhases, " +
        "phaseDetectionSource=$phaseDetectionSource, phaseDetectionConfidence=$phaseDetectionConfidence, " +
        "selectedVehicleId=$selectedVehicleId, targetSocPercent=$targetSocPercent)"
}

/**
 * The URL/webhook pair [HomeAssistantClient] needs to talk to this charger — the one legitimate
 * place that mapping happens, so callers never duplicate it (or accidentally read the wrong pair of
 * fields) inline.
 */
fun ChargerProfile.toHomeAssistantSettings(): HomeAssistantSettings = HomeAssistantSettings(baseUrl, webhookId)
