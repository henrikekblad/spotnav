package se.sensnology.spotnav.ui.settings

import se.sensnology.spotnav.ha.client.SiteFacts
import se.sensnology.spotnav.ha.client.VehicleUpdate
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.DashboardSite
import se.sensnology.spotnav.ha.dashboard.DashboardSummary
import se.sensnology.spotnav.ha.dashboard.DashboardVehicle
import se.sensnology.spotnav.ha.dashboard.VehicleIdentificationSources
import se.sensnology.spotnav.vehicles.PairedVehicles
import se.sensnology.spotnav.vehicles.SocDisplay

/**
 * What the settings overview of a paired charger says, decided once from the dashboard and free of
 * any view: one card per area, each a few facts. The screen turns these into words; nothing here is
 * a code a person would see.
 *
 * The webhook does not hand out which entities the integration controls (that is administrator-only
 * and WebSocket-only), so the charger and site cards state only what the dashboard itself knows.
 */
internal object PairedOverview {
    /** What the vehicle's charge-level row shows. */
    sealed interface ChargeLevel {
        /** The car's state of charge as last read, whole percent. */
        data class Reading(val percent: Int, val estimated: Boolean = false) : ChargeLevel

        /** No charge-level sensor is chosen for the vehicle. */
        data object NoSensor : ChargeLevel

        /** A sensor is chosen but has no value now. */
        data object NoReading : ChargeLevel
    }

    data class VehicleCard(
        val id: String,
        val name: String,
        /** This charger plans for it. */
        val planned: Boolean,
        val chargeLevel: ChargeLevel,
        /** The friendly name of the charge-level sensor, when Home Assistant states it. */
        val sensorName: String?,
        val capacityKwh: Double?,
        /** The car reports the capacity itself, so it cannot be typed. */
        val capacityReported: Boolean,
        val consumptionKwhPer10km: Double?,
        /** The most phases the car's own charger takes (1 or 3), or `null` when Home Assistant does not say. */
        val onboardPhases: Int? = null,
        /** The car's own target (`null` when never set); shown only when the row [targetStated] it. */
        val targetPercent: Double? = null,
        val targetStated: Boolean = false,
        /** The car's identification sources, or `null` from a Home Assistant without them. */
        val sources: VehicleIdentificationSources? = null,
        /** The car's own charge limit (whole percent), when it reports one. */
        val chargeLimit: Int? = null,
        /** Whether Home Assistant can write that limit to the car (`set_charge_limit`). */
        val limitWritable: Boolean = false
    )

    /** One card per vehicle the dashboard lists, an [adopted] row (a write's answer) standing in for its own. */
    fun vehicles(dashboard: Dashboard, adopted: Map<String, DashboardVehicle> = emptyMap()): List<VehicleCard> =
        dashboard.vehicles.map { listed ->
            val row = adopted[listed.id] ?: listed
            val percent = PairedVehicles.socPercent(dashboard, null, row.id) ?: row.socPercent
            val sensorName = dashboard.summary?.vehicleSensorNames?.get(row.id)
            // The `soc` block marks a value it carried forward; a row's own reading is a measurement.
            val estimated = dashboard.soc?.let { it.vehicleId == row.id && it.value != null && it.estimated } == true
            VehicleCard(
                id = row.id,
                name = row.name,
                planned = row.id == dashboard.targetVehicleId,
                chargeLevel = when {
                    percent != null -> ChargeLevel.Reading(SocDisplay.wholePercent(percent), estimated)
                    row.socEntityId == null && sensorName == null -> ChargeLevel.NoSensor
                    else -> ChargeLevel.NoReading
                },
                sensorName = sensorName,
                capacityKwh = PairedVehicles.capacityKwh(dashboard, row, null, null, row.id),
                capacityReported = !VehicleUpdate.capacityEditable(row),
                consumptionKwhPer10km = row.consumptionKwhPer10km,
                onboardPhases = row.onboardPhases,
                targetPercent = row.targetPercent,
                targetStated = row.targetStated,
                sources = row.identification,
                chargeLimit = row.maxPercent?.takeIf { it.isFinite() }?.toInt(),
                limitWritable = dashboard.capabilities.setChargeLimit && row.maxPercent?.isFinite() == true
            )
        }

    /** How the charging current is set, in the two words the dashboard can give. */
    enum class CurrentPath { SET_BY_SPOTNAV, KEPT_BY_CHARGER }

    /** The energy meter row: a name, found on its own, or not specified. */
    sealed interface EnergyMeter {
        data class Named(val name: String) : EnergyMeter
        data object Automatic : EnergyMeter
        data object NotSpecified : EnergyMeter
    }

    /**
     * The charger's card. [currentPath], [minA] and [maxA] are what the dashboard itself knows; [summary]
     * (`null` from a Home Assistant that does not send it) adds the names, and the rows it cannot fill
     * are `null` here and left out.
     */
    data class ChargerCard(
        val currentPath: CurrentPath,
        val minA: Int,
        val maxA: Int,
        val summary: DashboardSummary.Charger? = null
    ) {
        /** Whether the start and stop row shows (a missing name reads as not specified). */
        val showsStartStop: Boolean get() = summary != null

        /** How the current is set in the integration's own words, or `null` to keep the two-word answer. */
        val summaryPath: DashboardSummary.CurrentPath? get() = summary?.currentPath

        val energy: EnergyMeter?
            get() {
                val charger = summary ?: return null
                return when {
                    charger.energyName != null -> EnergyMeter.Named(charger.energyName)
                    charger.energyAutomatic == true -> EnergyMeter.Automatic
                    charger.energyAutomatic == false -> EnergyMeter.NotSpecified
                    else -> null
                }
            }
    }

    fun charger(dashboard: Dashboard): ChargerCard = ChargerCard(
        currentPath = if (dashboard.capabilities.setCurrent) CurrentPath.SET_BY_SPOTNAV else CurrentPath.KEPT_BY_CHARGER,
        minA = dashboard.currentRange.minA,
        maxA = dashboard.currentRange.maxA,
        summary = dashboard.summary?.charger
    )

    data class SiteCard(
        /** The site's own name; `null` when it has none, and the card is headed "Site". */
        val name: String?,
        val chargers: Int,
        val activeControlOn: Boolean,
        /** Why active load balancing is not available, worded by the screen. */
        val reason: SiteFacts.Reason,
        /** The dashboard says this connection may write the site's solar settings. */
        val writable: Boolean,
        /** The fuse, measurement and battery in words; `null` when the dashboard does not state the site's setup. */
        val setup: DashboardSummary.Site? = null
    )

    fun site(site: DashboardSite, setup: DashboardSummary.Site? = null): SiteCard = SiteCard(
        name = site.name?.trim()?.takeIf { it.isNotEmpty() },
        chargers = (site.chargerCount ?: 1).coerceAtLeast(1),
        activeControlOn = site.activeControlEnabled,
        reason = SiteFacts.reason(site.activeControlReason),
        writable = SiteFacts.solarEditable(site),
        setup = setup
    )

    data class SolarCard(
        /** One of [SiteFacts.PRIORITIES], or `null` when the dashboard states none. */
        val priority: String?,
        /** The titles of the selected forecast sources, in the choices' order. */
        val forecastTitles: List<String>,
        val hasForecastChoices: Boolean,
        val editable: Boolean
    )

    fun solar(site: DashboardSite): SolarCard = SolarCard(
        priority = site.solarPriority?.takeIf { it in SiteFacts.PRIORITIES },
        // A selected id the choices do not name is left out rather than shown as a code.
        forecastTitles = site.solarForecastSelected.mapNotNull { id ->
            site.solarForecastChoices.firstOrNull { it.id == id }?.title
        },
        hasForecastChoices = site.solarForecastChoices.isNotEmpty(),
        editable = SiteFacts.solarEditable(site)
    )

    /** The sources the solar dialog starts with: what the site shows as selected. */
    fun solarSelection(site: DashboardSite): Set<String> = site.solarForecastSelected.toSet()
}

/** A settings card's heading: its kind, and the name of what it is about when there is one ("Charger · HALO"). */
internal object SettingsHeading {
    fun named(kind: String, name: String?): String = name?.trim()?.takeIf { it.isNotEmpty() }?.let { "$kind · $it" } ?: kind
}
