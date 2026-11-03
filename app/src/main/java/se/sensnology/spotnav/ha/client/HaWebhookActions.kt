package se.sensnology.spotnav.ha.client

import org.json.JSONArray
import org.json.JSONObject
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.DashboardGridTariff
import se.sensnology.spotnav.ha.dashboard.DashboardStatus
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.HaSettingsCodec
import java.time.Instant
import java.time.OffsetDateTime

/**
 * `plan_preview`: what a `settings` replacement of this body would plan, with nothing saved (`docs/api.md`, "Plan
 * preview over the webhook"). Asked only where the dashboard states `capabilities.plan_preview`.
 */
internal object PlanPreview {
    fun payload(replacement: HaPlanningSettings): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", "plan_preview")
        WebhookReads.put(this)
        put("api_version", 1)
        put("settings", HaSettingsCodec.encodeBody(replacement))
    }

    /** One period the plan would charge in. */
    data class Period(val start: Instant, val end: Instant)

    /** What a Save would plan; `cost` is in the major unit, `null` for an unpriced plan. */
    data class Plan(
        val periods: List<Period>,
        val plannedKwh: Double,
        val powerKw: Double?,
        val cost: Double?,
        val currency: String?,
        val finish: Instant?,
        val shortOfDeadline: Boolean
    )

    /** One quarter of the strip: its start, its price in the minor unit, and whether it is planned. */
    data class Quarter(val start: Instant, val price: Double, val planned: Boolean)

    data class Strip(val start: Instant, val end: Instant, val quarters: List<Quarter>)

    /** The answer: [plan] is `null` when no plan would be made, and [status] then says why. */
    data class Preview(val state: String?, val plan: Plan?, val status: DashboardStatus, val strip: Strip?)

    sealed interface Outcome {
        data class Previewed(val preview: Preview) : Outcome

        /** A body a Save would refuse, with the Save's own code. */
        data class Refused(val code: String) : Outcome

        /** More than 5 a second, or 4 at once across the card and the app: not an error, the next change asks again. */
        data object RateLimited : Outcome

        data object NotPermitted : Outcome

        data class Failed(val code: String?) : Outcome
    }

    fun answer(status: Int?, body: String?): Outcome {
        if (status == null) return Outcome.Failed(null)
        val head = WriteEnvelope.head(body) ?: return Outcome.Failed(null)
        if (!head.ok) return when (head.error) {
            "spotnav_rate_limited" -> Outcome.RateLimited
            PriceSourceUpdate.NOT_ADMIN, WriteEnvelope.NOT_PERMITTED_OVER_WEBHOOK -> Outcome.NotPermitted
            null -> Outcome.Failed(null)
            "spotnav_unsupported_api_version", "Unsupported action", "spotnav_body_too_large" -> Outcome.Failed(head.error)
            else -> if (status == 400 && head.json.isNull("preview")) Outcome.Refused(head.error) else Outcome.Failed(head.error)
        }
        return runCatching { Outcome.Previewed(preview(head.json.getJSONObject("preview"))) }
            .getOrElse { Outcome.Failed(null) }
    }

    private fun preview(json: JSONObject): Preview = Preview(
        state = json.optString("state").takeIf { it.isNotEmpty() },
        plan = json.optJSONObject("plan")?.let(::plan),
        status = Dashboard.parseStatus(json.getJSONObject("status")),
        strip = json.optJSONObject("strip")?.let(::strip)
    )

    private fun instant(text: String): Instant = OffsetDateTime.parse(text).toInstant()

    private fun plan(json: JSONObject): Plan {
        val periods = json.getJSONArray("periods").let { array ->
            (0 until array.length()).map { i ->
                val period = array.getJSONObject(i)
                Period(instant(period.getString("start")), instant(period.getString("end")))
            }
        }
        val cost = json.optJSONObject("cost")
        return Plan(
            periods = periods,
            plannedKwh = json.getDouble("planned_kwh"),
            powerKw = (json.opt("power_kw") as? Number)?.toDouble(),
            cost = (cost?.opt("value") as? Number)?.toDouble(),
            currency = cost?.opt("currency") as? String,
            finish = (json.opt("finish") as? String)?.let(::instant),
            shortOfDeadline = json.optBoolean("short_of_deadline", false)
        )
    }

    private fun strip(json: JSONObject): Strip {
        val quarters = json.getJSONArray("quarters").let { array: JSONArray ->
            (0 until array.length()).map { i ->
                val quarter = array.getJSONObject(i)
                Quarter(instant(quarter.getString("start")), quarter.getDouble("price"), quarter.getBoolean("planned"))
            }
        }
        return Strip(instant(json.getString("start")), instant(json.getString("end")), quarters)
    }
}

/** `dismiss_setup_item`: a "Get started" row answered "Not needed", kept away for the whole installation. */
internal object SetupDismiss {
    fun payload(item: String): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", "dismiss_setup_item")
        WebhookReads.put(this)
        put("item", item)
    }

    sealed interface Outcome {
        data object Dismissed : Outcome

        data object NotDismissible : Outcome

        data class Failed(val code: String?) : Outcome
    }

    fun answer(status: Int?, body: String?): Outcome {
        if (status == null) return Outcome.Failed(null)
        val head = WriteEnvelope.head(body) ?: return Outcome.Failed(null)
        if (head.ok) return Outcome.Dismissed
        return if (head.error == "spotnav_setup_item_not_dismissible") Outcome.NotDismissible else Outcome.Failed(head.error)
    }
}

/**
 * `fix_own_mode`: one of the charger's own settings set back to the value SpotNav works with, the button of the status
 * lines `charger_own_mode` and `charger_disabled`. Home Assistant writes it only while it is still a conflict of this
 * charger and [value] is that conflict's own fix.
 */
internal object OwnModeFix {
    fun payload(entityId: String, value: String): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", "fix_own_mode")
        WebhookReads.put(this)
        put("api_version", 1)
        put("entity_id", entityId)
        put("value", value)
    }

    sealed interface Outcome {
        data object Written : Outcome

        /** The conflict has ended or is not this one: nothing was written. */
        data object NoConflict : Outcome

        /** The charger's integration refused the write. */
        data object Refused : Outcome

        data class Failed(val code: String?) : Outcome
    }

    fun answer(status: Int?, body: String?): Outcome {
        if (status == null) return Outcome.Failed(null)
        val head = WriteEnvelope.head(body) ?: return Outcome.Failed(null)
        if (head.ok) return Outcome.Written
        return when (head.error) {
            "spotnav_no_conflict" -> Outcome.NoConflict
            "spotnav_own_mode_failed" -> Outcome.Refused
            else -> Outcome.Failed(head.error)
        }
    }
}

/**
 * `update_grid_tariff`: the electricity price's grid tariff replaced in full, its percentage of spot, high-load fee and hours
 * (and, since 2.4, the demand charge's parts), every part as read but the one changed (`DashboardGridTariff`). Compare-and-set on the
 * electricity price's `write_revision`; the answer carries the block read again.
 */
internal object GridTariffUpdate {
    private const val REVISION_CONFLICT = "revision_conflict"

    fun payload(expectedRevision: Int, body: JSONObject): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", "update_grid_tariff")
        WebhookReads.put(this)
        put("expected_write_revision", expectedRevision)
        put("grid_tariff", body)
    }

    sealed interface Outcome {
        /** Written; [block] is the tariff read again (`null` when it could not be read). */
        data class Updated(val block: DashboardGridTariff?) : Outcome

        /** The electricity price was written elsewhere since it was read: nothing was written; [block] is the new one. */
        data class Conflict(val block: DashboardGridTariff?) : Outcome

        /** A value Home Assistant refuses (`invalid_fiscal`): nothing was written. */
        data object Refused : Outcome

        data object NotPermitted : Outcome

        data class Failed(val code: String?) : Outcome
    }

    fun answer(status: Int?, body: String?): Outcome {
        if (status == null) return Outcome.Failed(null)
        val head = WriteEnvelope.head(body) ?: return Outcome.Failed(null)
        val block = DashboardGridTariff.parse(head.json.opt("grid_tariff"))
        if (head.ok) return Outcome.Updated(block)
        return when (head.error) {
            REVISION_CONFLICT -> Outcome.Conflict(block)
            "invalid_fiscal" -> Outcome.Refused
            PriceSourceUpdate.NOT_ADMIN, WriteEnvelope.NOT_PERMITTED_OVER_WEBHOOK -> Outcome.NotPermitted
            else -> Outcome.Failed(head.error)
        }
    }
}
