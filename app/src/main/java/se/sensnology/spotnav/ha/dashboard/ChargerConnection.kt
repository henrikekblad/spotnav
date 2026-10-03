package se.sensnology.spotnav.ha.dashboard

import org.json.JSONObject
import se.sensnology.spotnav.app.strictText
import kotlin.math.floor
import kotlin.math.min

/** The charger's connection state as Home Assistant 1.6 publishes it. */
internal enum class ConnectionState(val wire: String) {
    DISCONNECTED("disconnected"),
    CONNECTED("connected"),
    CHARGING("charging"),
    PAUSED("paused"),
    FINISHED("finished"),
    ERROR("error")
}

/** The dashboard's optional `connection: {state, source}` block. */
internal object ChargerConnectionContract {
    /**
     * The state to show, or `null` for an absent block (older Home Assistant), `unknown`, or a
     * value this app does not know: then nothing is shown.
     */
    fun of(json: JSONObject?): ConnectionState? {
        val state = json?.strictText("state") ?: return null
        return ConnectionState.entries.firstOrNull { it.wire == state }
    }
}

/** When the `vehicle_not_requesting_current` advisory is not a fault, as the card judges it. */
internal object FullCarRule {
    private const val NEED_MET_LINE = "hybrid_satisfied"

    /** Whether the car needs no charge: its charge is at its target or maximum, or the need is 0 kWh. */
    fun carNeedsNoCharge(soc: DashboardSoc?): Boolean {
        val value = soc?.value ?: return false
        if (soc.needKwh != null && soc.needKwh <= 0.0) return true
        val ceiling = soc.vehicleMaxPercent?.takeIf { it.isFinite() }
            ?.let { floor(it).toInt().coerceIn(0, 100) } ?: 100
        // Python's round (half to even), as the integration and the card read the target.
        val target = soc.targetPercent?.takeIf { it.isFinite() }?.let { min(Math.rint(it).toInt(), ceiling) }
        val stop = target ?: ceiling
        return value >= min(stop, ceiling)
    }

    fun needAlreadyMet(status: DashboardStatus): Boolean = status.lines.any { it.code == NEED_MET_LINE }

    /** Whether this dashboard puts the advisory on screen. */
    fun advisory(dashboard: Dashboard?): Boolean =
        dashboard != null &&
            ChargeProgressContract.advisory(dashboard.chargeProgress) &&
            !carNeedsNoCharge(dashboard.soc) &&
            !needAlreadyMet(dashboard.status)
}

/** The card's header line: the vehicle's charge, then the connection state, joined with " · ". */
internal object ChargerStatusLine {
    /** The parts of the line, or `null` when there is nothing to show. */
    fun text(vehicleName: String?, charge: String?, connectionWord: String?): String? {
        val parts = listOfNotNull(
            vehicleName?.trim()?.takeIf { it.isNotEmpty() && charge != null },
            charge,
            connectionWord
        )
        return if (parts.isEmpty()) null else parts.joinToString(" \u00B7 ")
    }
}
