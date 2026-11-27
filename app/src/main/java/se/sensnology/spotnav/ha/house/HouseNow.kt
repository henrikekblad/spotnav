package se.sensnology.spotnav.ha.house

import org.json.JSONObject
import se.sensnology.spotnav.ha.client.WriteEnvelope
import java.time.Instant

/**
 * The site's live flows (webhook `get_house_now`, the twin of `spotnav/get_house_now`, `api_version` 1): what the
 * house page's Now tab draws. Powers are kW; `null` is unread or not there. The battery's line is [batteryKw], the
 * battery's power as read (what its own app shows), and [houseKw] is the balance with it (`site/house_now.py`), so the
 * lines add up around the house: on a hybrid that reads the battery at the battery (DC) the inverter's loss and standby
 * are in the house, as in the energy flow.
 */
internal data class HouseNow(
    val siteId: String,
    val at: Instant,
    /** + from the grid, - to it. */
    val gridKw: Double?,
    val sunKw: Double?,
    val sunFrom: String?,
    /** + charging, - giving. */
    val batteryKw: Double?,
    val socPct: Double?,
    val carKw: Double?,
    /** Whether a car is plugged in; `null` without chargers or unread. */
    val carConnected: Boolean?,
    val carSocPct: Double?,
    val heatKw: Double?,
    val heatFrom: String?,
    val houseKw: Double?,
    val has: Has,
    /** The battery on the AC side, as the review and the plan count it; not drawn. [batteryKw] from a Home Assistant before it. */
    val batteryAcKw: Double? = batteryKw,
    /** How the house is had: `plant` (the plant's own load) or `balance`; `null` unread or from a Home Assistant before it. */
    val houseFrom: String? = null,
    /** The house less its heat; [houseKw] less [heatKw] (never below 0) from a Home Assistant before it. */
    val restKw: Double? = houseKw?.let { maxOf(0.0, it - (heatKw ?: 0.0)) },
    /**
     * The roles whose figure is estimated (`grid`, `sun`, `battery`, `car`, `heat`, `pool`): Home Assistant balances the
     * figures to the hundredth (`grid + sun = house + car + battery`, `heat + pool + other = house`) and marks the role
     * it moved. None from a Home Assistant before it.
     */
    val estimated: Set<String> = emptySet(),
    /** The pool's, the spa's and the sauna's draw now; `null` without them or from a Home Assistant before it. */
    val poolKw: Double? = null,
    /** The house less its heat and pool; [restKw] from a Home Assistant before it. */
    val otherKw: Double? = restKw
) {
    /** What the site has to draw; [pool] is false from a Home Assistant before it. */
    data class Has(val sun: Boolean, val battery: Boolean, val car: Boolean, val heat: Boolean, val pool: Boolean = false)
}

internal object HouseNowRead {
    const val API_VERSION = 1

    fun payload(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", "get_house_now")
        put("api_version", API_VERSION)
    }

    fun answer(status: Int?, body: String?): SiteRead<HouseNow> {
        if (status == null) return SiteRead.Failed(null)
        val head = WriteEnvelope.head(body) ?: return SiteRead.Failed(null)
        if (!head.ok) return SiteRead.Failed(head.error)
        return decode(head.json)
    }

    /** The document itself, as the webhook and the WebSocket both send it. */
    fun decode(json: JSONObject): SiteRead<HouseNow> {
        val version = json.opt("api_version")
        if (version != API_VERSION) return if (version is Number) SiteRead.Unsupported else SiteRead.Failed(null)
        return runCatching { SiteRead.Read(document(json)) }.getOrElse { SiteRead.Failed(null) }
    }

    private fun document(json: JSONObject): HouseNow = with(StrictJson) {
        val has = obj(json.opt("has"), "has")
        val houseKw = numberOrNull(json.opt("house_kw"), "house_kw")
        val heatKw = numberOrNull(json.opt("heat_kw"), "heat_kw")
        val restKw = additive(json, "rest_kw", houseKw?.let { maxOf(0.0, it - (heatKw ?: 0.0)) }) { numberOrNull(it, "rest_kw") }
        HouseNow(
            siteId = text(json.opt("site_id"), "site_id"),
            at = instant(json.opt("at"), "at"),
            gridKw = numberOrNull(json.opt("grid_kw"), "grid_kw"),
            sunKw = numberOrNull(json.opt("sun_kw"), "sun_kw"),
            sunFrom = textOrNull(json.opt("sun_from"), "sun_from"),
            batteryKw = numberOrNull(json.opt("battery_kw"), "battery_kw"),
            socPct = numberOrNull(json.opt("soc_pct"), "soc_pct"),
            carKw = numberOrNull(json.opt("car_kw"), "car_kw"),
            carConnected = when (val raw = json.opt("car_connected")) {
                JSONObject.NULL -> null
                else -> flag(raw, "car_connected")
            },
            carSocPct = numberOrNull(json.opt("car_soc_pct"), "car_soc_pct"),
            heatKw = heatKw,
            heatFrom = textOrNull(json.opt("heat_from"), "heat_from"),
            houseKw = houseKw,
            has = HouseNow.Has(
                sun = flag(has.opt("sun"), "has.sun"),
                battery = flag(has.opt("battery"), "has.battery"),
                car = flag(has.opt("car"), "has.car"),
                heat = flag(has.opt("heat"), "has.heat"),
                pool = additive(has, "pool", false) { flag(it, "has.pool") }
            ),
            batteryAcKw = additive(json, "battery_ac_kw", numberOrNull(json.opt("battery_kw"), "battery_kw")) { numberOrNull(it, "battery_ac_kw") },
            houseFrom = additive(json, "house_from", null) { textOrNull(it, "house_from") },
            restKw = restKw,
            estimated = additive(json, "estimated", emptySet()) { raw -> list(raw, "estimated").map { text(it, "estimated") }.toSet() },
            poolKw = additive(json, "pool_kw", null) { numberOrNull(it, "pool_kw") },
            otherKw = additive(json, "other_kw", restKw) { numberOrNull(it, "other_kw") }
        )
    }
}
