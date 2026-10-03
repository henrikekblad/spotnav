package se.sensnology.spotnav.ha.client

import org.json.JSONArray
import org.json.JSONObject
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.DashboardForecastChoice
import se.sensnology.spotnav.ha.dashboard.DashboardSite
import se.sensnology.spotnav.ha.dashboard.DashboardVehicle
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The three bounded writes Home Assistant's webhook answers for a paired charger: `update_vehicle`,
 * `choose_vehicle_soc` and `update_site_settings` (each the webhook twin of a WebSocket command,
 * sharing its validation and its envelope).
 */
internal data class WriteFieldError(val field: String, val code: String)

internal object WriteEnvelope {
    const val CONFLICT = "spotnav_conflict"
    const val INVALID_VALUE = "spotnav_invalid_value"
    const val UNSUPPORTED_VERSION = "spotnav_unsupported_api_version"
    const val NOT_PERMITTED_OVER_WEBHOOK = "spotnav_not_permitted_over_webhook"
    const val NO_SITE = "spotnav_no_site"
    const val SITE_UNAVAILABLE = "spotnav_site_unavailable"

    /**
     * The envelope's own head: `ok`, the stable `error` code and the `field_errors`, and the whole
     * object.
     */
    class Head(val ok: Boolean, val error: String?, val fieldErrors: List<WriteFieldError>, val json: JSONObject)

    /** `null` when [body] is not an envelope (not an object, or `ok` is not a boolean). */
    fun head(body: String?): Head? {
        if (body.isNullOrBlank()) return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val ok = json.opt("ok") as? Boolean ?: return null
        val error = when (val raw = json.opt("error")) {
            null, JSONObject.NULL -> null
            is String -> raw.takeIf { it.isNotEmpty() }
            else -> return null
        }
        val fieldErrors = when (val raw = json.opt("field_errors")) {
            null, JSONObject.NULL -> emptyList()
            is JSONArray -> (0 until raw.length()).map {
                val entry = raw.opt(it) as? JSONObject ?: return null
                val field = entry.opt("field") as? String ?: return null
                val code = entry.opt("code") as? String ?: return null
                WriteFieldError(field, code)
            }
            else -> return null
        }
        return Head(ok, error, fieldErrors, json)
    }

    /** The body's object under [key], or `null` when absent, `null` or not an object. */
    fun block(head: Head, key: String): JSONObject? = head.json.opt(key) as? JSONObject
}

/**
 * A vehicle's editable properties as `update_vehicle` names them: capacity, consumption and the
 * onboard charger's phases (1 or 3).
 */
internal enum class VehicleField(val wire: String, val min: Double, val max: Double) {
    CAPACITY("capacity_kwh", 1.0, 500.0),
    CONSUMPTION("consumption_kwh_per_10km", 0.1, 50.0),
    ONBOARD_PHASES("onboard_phases", 1.0, 3.0)
}

/** Why the app (or the integration) refuses a typed value or a save. */
internal enum class VehicleFieldIssue { OUT_OF_RANGE, NOT_A_NUMBER, UNKNOWN }

/** `update_vehicle`: a vehicle's battery capacity or consumption, under compare-and-set. */
internal object VehicleUpdate {
    const val API_VERSION = 1

    /** A typed value, judged: the number to send (one decimal), or why it cannot be sent. */
    sealed interface Check {
        data class Valid(val value: Double) : Check
        data class Invalid(val issue: VehicleFieldIssue) : Check
    }

    fun check(field: VehicleField, text: String): Check {
        val number = text.trim().replace(',', '.').toDoubleOrNull()
        if (number == null || !number.isFinite()) return Check.Invalid(VehicleFieldIssue.NOT_A_NUMBER)
        if (number < field.min || number > field.max) return Check.Invalid(VehicleFieldIssue.OUT_OF_RANGE)
        return Check.Valid((number * 10).roundToLong() / 10.0)
    }

    /** The value [row] shows for [field]: what the save is compared against. */
    fun shown(row: DashboardVehicle, field: VehicleField): Double? = when (field) {
        VehicleField.CAPACITY -> row.capacityKwh
        VehicleField.CONSUMPTION -> row.consumptionKwhPer10km
        VehicleField.ONBOARD_PHASES -> row.onboardPhases?.toDouble()
    }

    /**
     * Whether the row's capacity may be typed. A capacity the vehicle reports itself always wins
     * over a stored one, so a typed figure could never take effect: it is read-only, "reported by
     * the vehicle".
     */
    fun capacityEditable(row: DashboardVehicle): Boolean = !(row.capacitySource == "reported" && row.capacityKwh != null)

    /** One field of a save: the value to write and the value the row showed. */
    data class FieldChange(val field: VehicleField, val value: Double, val shown: Double?)

    /**
     * The request body for one field: the value to write and the value the row showed (`null` when
     * it showed none, which the server compares as "nothing stored").
     */
    fun payload(vehicleId: String, field: VehicleField, value: Double, shown: Double?): JSONObject =
        payload(vehicleId, listOf(FieldChange(field, value, shown)))

    /** The request body for one or more fields, each compared against what the row showed. */
    fun payload(vehicleId: String, changes: List<FieldChange>): JSONObject {
        require(changes.isNotEmpty()) { "a vehicle write names at least one field" }
        return JSONObject().apply {
            put("version", 1)
            WebhookReads.put(this)
            put("action", "update_vehicle")
            put("api_version", API_VERSION)
            put("vehicle_id", vehicleId)
            put("changes", JSONObject().also { body -> changes.forEach { body.put(it.field.wire, wireValue(it.field, it.value)) } })
            put("expected", JSONObject().also { body ->
                changes.forEach { body.put(it.field.wire, it.shown?.let { shown -> wireValue(it.field, shown) } ?: JSONObject.NULL) }
            })
        }
    }

    /** The phases are a whole number on the wire (`1`, not `1.0`); the other fields are decimals. */
    private fun wireValue(field: VehicleField, value: Double): Any =
        if (field == VehicleField.ONBOARD_PHASES) value.toInt() else value

    /** What pressing Save in the vehicle dialog means, decided from the typed texts. */
    sealed interface Draft {
        /** Nothing differs from what the row shows: close without writing. */
        data object Unchanged : Draft

        /** At least one typed value cannot be sent; nothing is written. */
        data class Invalid(val issues: Map<VehicleField, VehicleFieldIssue>) : Draft

        /** One request carrying every field that differs. */
        data class Write(val changes: List<FieldChange>) : Draft
    }

    /**
     * The dialog's two typed texts and the onboard charger's chosen phases (`null` when the dialog
     * offers no choice) against the row. A blank text for a field the row shows nothing for is "left
     * alone"; a capacity the vehicle reports itself is never sent.
     */
    fun draft(row: DashboardVehicle, capacityText: String, consumptionText: String, onboardPhases: Int? = null): Draft {
        val issues = LinkedHashMap<VehicleField, VehicleFieldIssue>()
        val changes = ArrayList<FieldChange>()
        val typed = buildList {
            if (capacityEditable(row)) add(VehicleField.CAPACITY to capacityText)
            add(VehicleField.CONSUMPTION to consumptionText)
        }
        for ((field, text) in typed) {
            val shown = shown(row, field)
            if (text.isBlank() && shown == null) continue
            when (val check = check(field, text)) {
                is Check.Invalid -> issues[field] = check.issue
                is Check.Valid -> if (check.value != shown) changes += FieldChange(field, check.value, shown)
            }
        }
        if (onboardPhases != null && onboardPhases != row.onboardPhases) {
            if (onboardPhases == 1 || onboardPhases == 3) {
                changes += FieldChange(VehicleField.ONBOARD_PHASES, onboardPhases.toDouble(), shown(row, VehicleField.ONBOARD_PHASES))
            } else {
                issues[VehicleField.ONBOARD_PHASES] = VehicleFieldIssue.OUT_OF_RANGE
            }
        }
        return when {
            issues.isNotEmpty() -> Draft.Invalid(issues)
            changes.isEmpty() -> Draft.Unchanged
            else -> Draft.Write(changes)
        }
    }

    sealed interface Outcome {
        data class Updated(val row: DashboardVehicle) : Outcome

        /**
         * Somebody else changed the row first: adopt [row] (when it came back) and say so. Nothing
         * was written.
         */
        data class Conflict(val row: DashboardVehicle?) : Outcome

        /**
         * Refused by field: each field's [VehicleFieldIssue], and whether the vehicle itself is
         * gone. Nothing was written; [row] is the row as it stands.
         */
        data class Refused(
            val issues: Map<VehicleField, VehicleFieldIssue>,
            val unknownVehicle: Boolean,
            val row: DashboardVehicle?
        ) : Outcome

        /** The integration speaks another version of this contract. */
        data object NotSupported : Outcome

        /** Anything else, including no answer at all. */
        data class Failed(val code: String?) : Outcome
    }

    fun answer(status: Int?, body: String?): Outcome {
        if (status == null) return Outcome.Failed(null)
        val head = WriteEnvelope.head(body) ?: return Outcome.Failed(null)
        val row = runCatching {
            WriteEnvelope.block(head, "vehicle")?.let { Dashboard.parseVehicle(it) }
        }.getOrNull()
        if (head.ok) {
            return if (head.error == null && row != null) Outcome.Updated(row) else Outcome.Failed(head.error)
        }
        return when (head.error) {
            WriteEnvelope.CONFLICT -> Outcome.Conflict(row)
            WriteEnvelope.UNSUPPORTED_VERSION -> Outcome.NotSupported
            WriteEnvelope.INVALID_VALUE -> {
                val issues = LinkedHashMap<VehicleField, VehicleFieldIssue>()
                for (error in head.fieldErrors) {
                    val field = VehicleField.entries.firstOrNull { it.wire == error.field } ?: continue
                    issues[field] = when (error.code) {
                        "invalid_capacity", "invalid_consumption", "invalid_onboard_phases" -> VehicleFieldIssue.OUT_OF_RANGE
                        else -> VehicleFieldIssue.UNKNOWN
                    }
                }
                Outcome.Refused(issues, head.fieldErrors.any { it.code == "unknown_vehicle" }, row)
            }
            else -> Outcome.Failed(head.error)
        }
    }

    /** A figure as the field shows it: one decimal, in the user's own decimal separator. */
    fun display(value: Double, locale: Locale): String = String.format(locale, "%.1f", value)
}

/**
 * `update_site_settings` at `api_version` 1: solar priority and the solar forecast sources of the
 * charger's site, under compare-and-set.
 */
internal object SiteUpdate {
    const val API_VERSION = 1
    const val CAR_FIRST = "car_first"
    const val BATTERY_FIRST = "battery_first"

    /**
     * One write: what the site was shown to be, and what it should become. At least one field is
     * set.
     */
    data class Request(
        val expectedPriority: String? = null,
        val priority: String? = null,
        val expectedForecast: List<String>? = null,
        val forecast: List<String>? = null
    ) {
        init {
            require(priority != null || forecast != null) { "a site write names at least one field" }
        }

        fun payload(): JSONObject = JSONObject().apply {
            put("version", 1)
            WebhookReads.put(this)
            put("action", "update_site_settings")
            put("api_version", API_VERSION)
            val expected = JSONObject()
            val changes = JSONObject()
            if (priority != null) {
                expectedPriority?.let { expected.put("solar_priority", it) }
                changes.put("solar_priority", priority)
            }
            if (forecast != null) {
                expectedForecast?.let { expected.put("solar_forecast", JSONArray(it)) }
                changes.put("solar_forecast", JSONArray(forecast))
            }
            put("expected", expected)
            put("changes", changes)
        }
    }

    /** Solar priority alone: `expected` is the value the screen shows. */
    fun priorityRequest(shown: String?, chosen: String) = Request(expectedPriority = shown, priority = chosen)

    /**
     * The solar dialog's Save: the priority and the forecast sources that differ from what [site]
     * shows, in one request -- or `null` when nothing differs. [chosenForecast] is a set; the list
     * sent keeps the choices' own order.
     */
    fun solarRequest(site: DashboardSite, chosenPriority: String, chosenForecast: Set<String>): Request? {
        val ordered = site.solarForecastChoices.map { it.id }.filter { it in chosenForecast } +
            site.solarForecastSelected.filter { it in chosenForecast && site.solarForecastChoices.none { c -> c.id == it } }
        val priorityChanged = chosenPriority != site.solarPriority
        val forecastChanged = ordered != site.solarForecastSelected
        if (!priorityChanged && !forecastChanged) return null
        return Request(
            expectedPriority = site.solarPriority.takeIf { priorityChanged },
            priority = chosenPriority.takeIf { priorityChanged },
            expectedForecast = site.solarForecastSelected.takeIf { forecastChanged },
            forecast = ordered.takeIf { forecastChanged }
        )
    }

    /** The forecast selection alone, under the identical rule. */
    fun forecastRequest(shown: List<String>, chosen: List<String>) = Request(expectedForecast = shown, forecast = chosen)

    /**
     * [selected] with [id] switched on or off, in the choices' own order (the order the server
     * keeps).
     */
    fun toggled(choices: List<DashboardForecastChoice>, selected: List<String>, id: String, on: Boolean): List<String> {
        val next = selected.toMutableSet()
        if (on) next += id else next -= id
        val ordered = choices.map { it.id }.filter { it in next }
        return ordered + selected.filter { it in next && it !in ordered }
    }

    sealed interface Outcome {
        data class Updated(val site: DashboardSite) : Outcome

        /**
         * Changed elsewhere first: adopt [site] (when it came back) and say so. Nothing was
         * written.
         */
        data class Conflict(val site: DashboardSite?) : Outcome

        /** The value was refused as invalid. Nothing was written. */
        data class Refused(val site: DashboardSite?) : Outcome

        /** The webhook may not make this write at all. Nothing was written. */
        data class NotPermitted(val site: DashboardSite?) : Outcome

        data object Unavailable : Outcome

        data object NotSupported : Outcome

        data class Failed(val code: String?) : Outcome
    }

    fun answer(status: Int?, body: String?): Outcome {
        if (status == null) return Outcome.Failed(null)
        val head = WriteEnvelope.head(body) ?: return Outcome.Failed(null)
        val site = runCatching { WriteEnvelope.block(head, "site")?.let { Dashboard.parseSite(it) } }.getOrNull()
        if (head.ok) {
            return if (head.error == null && site != null) Outcome.Updated(site) else Outcome.Failed(head.error)
        }
        return when (head.error) {
            WriteEnvelope.CONFLICT -> Outcome.Conflict(site)
            WriteEnvelope.INVALID_VALUE -> Outcome.Refused(site)
            WriteEnvelope.NOT_PERMITTED_OVER_WEBHOOK -> Outcome.NotPermitted(site)
            WriteEnvelope.NO_SITE, WriteEnvelope.SITE_UNAVAILABLE -> Outcome.Unavailable
            WriteEnvelope.UNSUPPORTED_VERSION -> Outcome.NotSupported
            else -> Outcome.Failed(head.error)
        }
    }
}

/** What the site section of a paired charger shows, decided once from the dashboard's `site`. */
internal object SiteFacts {
    /** Why active load balancing is not available, as the card words it. */
    enum class Reason { NONE, DUPLICATE_MEMBERSHIP, NO_COMMANDABLE_CHARGER, MEASUREMENT, UNKNOWN }

    fun reason(code: String?): Reason = when {
        code == null -> Reason.NONE
        code == "legacy_duplicate_membership" -> Reason.DUPLICATE_MEMBERSHIP
        code == "no_commandable_charger" -> Reason.NO_COMMANDABLE_CHARGER
        code.startsWith("site_measurement_") -> Reason.MEASUREMENT
        else -> Reason.UNKNOWN
    }

    fun solarEditable(site: DashboardSite): Boolean = site.writable

    /** The priority values the chooser offers, in the card's order. */
    val PRIORITIES: List<String> = listOf(SiteUpdate.CAR_FIRST, SiteUpdate.BATTERY_FIRST)
}
