package se.sensnology.spotnav.ha.dashboard

import org.json.JSONObject
import se.sensnology.spotnav.app.strictText
import se.sensnology.spotnav.prices.RelayAreasParser
import java.time.OffsetDateTime

/**
 * The dashboard's `charge_progress`: whether the vehicle is actually taking a charge that was
 * started.
 */
data class ChargeProgress(
    val state: String,
    val reason: String,
    val since: String?
)

/** The `charge_progress` value, read strictly and refused locally. */
internal object ChargeProgressContract {
    /** The one state that produces the charger screen's advisory. */
    const val VEHICLE_NOT_REQUESTING_CURRENT = "vehicle_not_requesting_current"

    /** The states this app knows. A value outside this set is a value it cannot read. */
    val STATES = setOf("normal", VEHICLE_NOT_REQUESTING_CURRENT, "unknown")

    /** The exact key set of the block: a missing key and an extra one are both not this value. */
    val KEYS = setOf("state", "reason", "since")

    /** A bound on the code this app will render, so no payload can put an essay on the screen. */
    const val MAX_REASON_LENGTH = 64

    /** The block, or `null` when it is not one this app can read. */
    fun of(json: JSONObject?): ChargeProgress? {
        if (json == null) return null
        if (json.length() != KEYS.size) return null
        for (key in KEYS) {
            if (!json.has(key)) return null
        }
        val state = json.strictText("state") ?: return null
        if (state !in STATES) return null
        val reason = json.strictText("reason") ?: return null
        if (reason.length > MAX_REASON_LENGTH) return null
        if (json.isNull("since")) return ChargeProgress(state, reason, null)
        val since = json.strictText("since") ?: return null
        // An instant this app cannot parse is not an instant it may show.
        if (runCatching { OffsetDateTime.parse(since) }.getOrNull() == null) return null
        return ChargeProgress(state, reason, since)
    }

    /** Whether this value puts the charger screen's advisory on screen. */
    fun advisory(progress: ChargeProgress?): Boolean =
        progress?.state == VEHICLE_NOT_REQUESTING_CURRENT
}
