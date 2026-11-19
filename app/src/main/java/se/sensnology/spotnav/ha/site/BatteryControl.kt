package se.sensnology.spotnav.ha.site

import org.json.JSONObject
import se.sensnology.spotnav.ha.client.WriteEnvelope
import se.sensnology.spotnav.ha.house.StrictJson

/**
 * The site's battery control (webhook `battery_control`, the twin of `spotnav/battery_control`, `api_version` 1), as
 * far as the Settings screen reads it. Only the mode may be written over the webhook; the control switch
 * ("Styr batteriet") and the other options are Home Assistant's, shown read-only.
 */
internal data class BatteryStatus(
    val enabled: Boolean,
    /** `battery_control_shadow_only`: control on paper only (the card's "Test"). */
    val shadowOnly: Boolean,
    /** `self_consumption`, `balanced`, `max_profit`, or a newer word. */
    val mode: String,
    /** The control's state as a stable code (`off`, `on`, `shadow_only`, `verifying`, ...). */
    val state: String,
    /** The one line under the switch as a stable code (`off`, `waiting:retry_gap`, ...). */
    val line: String,
    val allowedActions: List<String>,
    val gridChargeCap: GridChargeCap,
    /** The plan's two fixed figures; `null` from a Home Assistant without them or a site with no price area. */
    val planCosts: PlanCosts? = null,
    /** Whether the site's battery facts name a battery (`status.battery_found`); `true` from a Home Assistant without the field. */
    val batteryFound: Boolean = true
) {
    /** What the status row says: the switch's position, and "test" while control is on paper only. */
    enum class Control { OFF, TEST, ON }

    val control: Control
        get() = when {
            state == "shadow_only" || (enabled && shadowOnly) -> Control.TEST
            enabled -> Control.ON
            else -> Control.OFF
        }

    /** What the grid charge may draw and what the learning of the house's peaks still needs. */
    data class GridChargeCap(
        val capKw: Double?,
        /** `learned`, `learning`, `conservative`, `import_limit`, `error` or a newer word. */
        val rule: String,
        val capLearnedKw: Double?,
        val daysMin: Int?,
        val daysSpread: Int?,
        val daysLeft: Int?
    )

    /** The battery plan's wear per kWh each way and Balanced's selling margin per kWh, in the area's minor unit. */
    data class PlanCosts(
        val wearMinorPerKwh: Double,
        val balancedMarginMinorPerKwh: Double,
        val majorUnit: String,
        val minorUnit: String
    )

    companion object {
        val MODES = listOf("self_consumption", "balanced", "max_profit")
    }
}

internal object BatteryControlRead {
    const val API_VERSION = 1

    sealed interface Outcome {
        data class Status(val status: BatteryStatus) : Outcome

        /** The mode (or any option) is Home Assistant's alone: nothing was written. */
        data object NotPermitted : Outcome

        /** A mode this Home Assistant does not take. */
        data object Invalid : Outcome

        /** The charger is in no loaded site. */
        data object NoSite : Outcome
        data object NotSupported : Outcome
        data class Failed(val code: String?) : Outcome
    }

    /** The status alone ([mode] `null`), or the status after writing the battery [mode]. */
    fun payload(mode: String? = null): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", "battery_control")
        put("api_version", API_VERSION)
        if (mode != null) put("changes", JSONObject().put("battery_mode", mode))
    }

    fun answer(status: Int?, body: String?): Outcome {
        if (status == null) return Outcome.Failed(null)
        val head = WriteEnvelope.head(body) ?: return Outcome.Failed(null)
        if (head.ok) return decode(head.json)
        return when (head.error) {
            WriteEnvelope.NOT_PERMITTED_OVER_WEBHOOK -> Outcome.NotPermitted
            WriteEnvelope.INVALID_VALUE -> Outcome.Invalid
            WriteEnvelope.UNSUPPORTED_VERSION -> Outcome.NotSupported
            WriteEnvelope.NO_SITE, WriteEnvelope.SITE_UNAVAILABLE -> Outcome.NoSite
            else -> Outcome.Failed(head.error)
        }
    }

    /** The document itself: a missing key the app reads makes it unreadable, an unknown key is not looked at. */
    fun decode(json: JSONObject): Outcome {
        val version = json.opt("api_version")
        if (version != API_VERSION) return if (version is Number) Outcome.NotSupported else Outcome.Failed(null)
        return runCatching { Outcome.Status(status(json)) }.getOrElse { Outcome.Failed(null) }
    }

    private fun status(json: JSONObject): BatteryStatus = with(StrictJson) {
        val status = obj(json.opt("status"), "status")
        val options = obj(status.opt("options"), "options")
        val cap = obj(status.opt("grid_charge_cap"), "grid_charge_cap")
        val learned = objOrNull(cap.opt("learned"), "learned")
        BatteryStatus(
            enabled = flag(options.opt("battery_control_enabled"), "battery_control_enabled"),
            shadowOnly = flag(options.opt("battery_control_shadow_only"), "battery_control_shadow_only"),
            mode = text(options.opt("battery_mode"), "battery_mode"),
            state = text(status.opt("state"), "state"),
            line = text(status.opt("line"), "line"),
            allowedActions = list(status.opt("allowed_actions"), "allowed_actions").map { text(it, "allowed_actions") },
            gridChargeCap = BatteryStatus.GridChargeCap(
                capKw = numberOrNull(cap.opt("cap_kw"), "cap_kw"),
                rule = text(cap.opt("rule"), "rule"),
                capLearnedKw = numberOrNull(cap.opt("cap_learned_kw"), "cap_learned_kw"),
                daysMin = learned?.let { count(it.opt("days_min"), "days_min") },
                daysSpread = learned?.let { count(it.opt("days_spread"), "days_spread") },
                daysLeft = learned?.let { count(it.opt("days_left"), "days_left") }
            ),
            planCosts = planCosts(status.opt("plan_costs")),
            batteryFound = additive(status, "battery_found", true) { flag(it, "battery_found") }
        )
    }

    /** Tolerant: a Home Assistant without the block, or one that sends it in a shape unknown here, shows no line. */
    private fun planCosts(raw: Any?): BatteryStatus.PlanCosts? = runCatching {
        with(StrictJson) {
            objOrNull(raw, "plan_costs")?.let {
                BatteryStatus.PlanCosts(
                    wearMinorPerKwh = number(it.opt("wear_minor_per_kwh"), "wear_minor_per_kwh"),
                    balancedMarginMinorPerKwh = number(it.opt("balanced_margin_minor_per_kwh"), "balanced_margin_minor_per_kwh"),
                    majorUnit = text(it.opt("major_unit"), "major_unit"),
                    minorUnit = text(it.opt("minor_unit"), "minor_unit")
                )
            }
        }
    }.getOrNull()
}
