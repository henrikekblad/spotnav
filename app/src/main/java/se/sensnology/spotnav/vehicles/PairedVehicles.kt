package se.sensnology.spotnav.vehicles

import se.sensnology.spotnav.ha.client.SiteUpdate
import se.sensnology.spotnav.ha.client.VehicleField
import se.sensnology.spotnav.ha.client.VehicleFieldIssue
import se.sensnology.spotnav.ha.client.VehicleUpdate
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.DashboardSite
import se.sensnology.spotnav.ha.dashboard.DashboardVehicle
import se.sensnology.spotnav.ha.dashboard.DashboardVehicleRef

/**
 * Which vehicle a paired charger's vehicle card is about, and what it shows and edits for it -- all
 * read from Home Assistant's dashboard by the dashboard's **own ids**.
 */
internal object PairedVehicles {
    /** The vehicle picker's entries: only when there is a choice to make. */
    fun choices(dashboard: Dashboard): List<DashboardVehicleRef> =
        PairedTarget.choices(dashboard.soc).ifEmpty {
            dashboard.vehicles.takeIf { it.size > 1 }?.map { DashboardVehicleRef(it.id, it.name) }.orEmpty()
        }

    /**
     * The vehicle the card is about, by id: the reader's pick when it is one of the dashboard's
     * vehicles, else the vehicle the `soc` block resolved, else `target_vehicle_id`, else the only
     * vehicle there is.
     */
    fun shownId(dashboard: Dashboard, pickedId: String?): String? {
        val known = dashboard.vehicles.map { it.id } + (dashboard.soc?.vehicles?.map { it.id }.orEmpty())
        pickedId?.takeIf { it in known }?.let { return it }
        dashboard.soc?.vehicleId?.takeIf { it.isNotEmpty() }?.let { return it }
        dashboard.targetVehicleId?.takeIf { it.isNotEmpty() }?.let { return it }
        return dashboard.vehicles.singleOrNull()?.id
    }

    /** The dashboard's row for [id]; an [adopted] row (a write's answer) stands in until the next dashboard. */
    fun row(dashboard: Dashboard, adopted: Map<String, DashboardVehicle>, id: String?): DashboardVehicle? =
        id?.let { adopted[it] ?: dashboard.vehicles.firstOrNull { row -> row.id == it } }

    /** The vehicle's name: its row's, else the `soc` block's own entry for it. */
    fun name(dashboard: Dashboard, id: String?): String? =
        dashboard.vehicles.firstOrNull { it.id == id }?.name
            ?: dashboard.soc?.vehicles?.firstOrNull { it.id == id }?.name

    /** The status list's entry with exactly this id, or `null`: never the "only" or "same name" one. */
    fun statusVehicle(list: List<VehicleStatus>, id: String?): VehicleStatus? =
        id?.let { wanted -> list.firstOrNull { it.id == wanted } }

    /**
     * The charge level to show for the vehicle the card is about, in percent: Home Assistant's own
     * stated value when this is the vehicle the `soc` block resolved (it may be an estimate carried
     * forward), else the status list's reading for the same id.
     */
    fun socPercent(dashboard: Dashboard, statusVehicle: VehicleStatus?, id: String?): Double? {
        val soc = dashboard.soc
        if (soc != null && id != null && soc.vehicleId == id) return soc.value ?: statusVehicle?.socPercent
        return statusVehicle?.socPercent
    }

    /**
     * The battery capacity the card states for vehicle [id]: the dashboard row's own figure
     * (reported by the car, or stored in Home Assistant), else the `soc` block's figure when it is
     * about this vehicle, else the status list's reported figure, else what this phone remembered.
     */
    fun capacityKwh(
        dashboard: Dashboard?,
        row: DashboardVehicle?,
        statusVehicle: VehicleStatus?,
        rememberedKwh: Double?,
        id: String?
    ): Double? {
        val soc = dashboard?.soc?.takeIf { id != null && it.vehicleId == id }?.capacityKwh
        return listOf(row?.capacityKwh, soc, statusVehicle?.batteryCapacityKwh, rememberedKwh)
            .firstOrNull { it != null && it.isFinite() && it > 0.0 }
    }

    /** What one vehicle write answered, as the card says it. */
    enum class Notice { CONFLICT, UNKNOWN_VEHICLE, NOT_SUPPORTED, FAILED, REFUSED }

    /** How the card takes one `update_vehicle` answer. */
    data class VehicleFeedback(
        val adopted: DashboardVehicle?,
        val issues: Map<VehicleField, VehicleFieldIssue>,
        val notice: Notice?,
        val close: Boolean,
        val reload: Boolean
    )

    fun feedback(outcome: VehicleUpdate.Outcome): VehicleFeedback = when (outcome) {
        is VehicleUpdate.Outcome.Updated ->
            VehicleFeedback(outcome.row, emptyMap(), null, close = true, reload = true)
        is VehicleUpdate.Outcome.Conflict ->
            VehicleFeedback(outcome.row, emptyMap(), Notice.CONFLICT, close = true, reload = true)
        is VehicleUpdate.Outcome.Refused -> when {
            outcome.issues.isNotEmpty() ->
                VehicleFeedback(null, outcome.issues, null, close = false, reload = false)
            outcome.unknownVehicle ->
                VehicleFeedback(null, emptyMap(), Notice.UNKNOWN_VEHICLE, close = true, reload = true)
            else -> VehicleFeedback(null, emptyMap(), Notice.REFUSED, close = false, reload = false)
        }
        VehicleUpdate.Outcome.NotSupported ->
            VehicleFeedback(null, emptyMap(), Notice.NOT_SUPPORTED, close = false, reload = false)
        is VehicleUpdate.Outcome.Failed ->
            VehicleFeedback(null, emptyMap(), Notice.FAILED, close = false, reload = false)
    }

    /** How the site section takes one `update_site_settings` answer. */
    enum class SiteNotice { CONFLICT, INVALID, NOT_PERMITTED, UNAVAILABLE, NOT_SUPPORTED, FAILED }

    data class SiteFeedback(val adopted: DashboardSite?, val notice: SiteNotice?, val reload: Boolean)

    fun feedback(outcome: SiteUpdate.Outcome): SiteFeedback = when (outcome) {
        is SiteUpdate.Outcome.Updated -> SiteFeedback(outcome.site, null, reload = true)
        is SiteUpdate.Outcome.Conflict -> SiteFeedback(outcome.site, SiteNotice.CONFLICT, reload = true)
        is SiteUpdate.Outcome.Refused -> SiteFeedback(outcome.site, SiteNotice.INVALID, reload = false)
        is SiteUpdate.Outcome.NotPermitted -> SiteFeedback(outcome.site, SiteNotice.NOT_PERMITTED, reload = false)
        SiteUpdate.Outcome.Unavailable -> SiteFeedback(null, SiteNotice.UNAVAILABLE, reload = true)
        SiteUpdate.Outcome.NotSupported -> SiteFeedback(null, SiteNotice.NOT_SUPPORTED, reload = false)
        is SiteUpdate.Outcome.Failed -> SiteFeedback(null, SiteNotice.FAILED, reload = false)
    }
}
