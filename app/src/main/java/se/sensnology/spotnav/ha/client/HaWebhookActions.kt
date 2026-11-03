package se.sensnology.spotnav.ha.client

import org.json.JSONArray
import org.json.JSONObject
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.DashboardGridTariff
import se.sensnology.spotnav.ha.dashboard.DemandComponent
import se.sensnology.spotnav.ha.dashboard.DashboardStatus
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.HaSettingsCodec
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth

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
 * `release_import_target`: the site's import target set aside for this charger's charge until its plan's departure,
 * the button beside the status line `import_target_at_risk` ("Release the import target for this charge"). Any user may press
 * it, as a Start. Offered only where the dashboard states `capabilities.release_import_target`.
 */
internal object ReleaseImportTarget {
    fun payload(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", "release_import_target")
        WebhookReads.put(this)
        put("api_version", 1)
    }

    sealed interface Outcome {
        data object Released : Outcome

        /**
         * Nothing to set aside: the site has no import target, no site, or the plan no departure ahead
         * (`spotnav_no_import_target`, `spotnav_no_site`, `spotnav_no_departure`). The read after it shows how it stands.
         */
        data class NothingToRelease(val code: String) : Outcome

        data class Failed(val code: String?) : Outcome
    }

    private val NOTHING = setOf("spotnav_no_import_target", "spotnav_no_site", "spotnav_no_departure")

    fun answer(status: Int?, body: String?): Outcome {
        if (status == null) return Outcome.Failed(null)
        val head = WriteEnvelope.head(body) ?: return Outcome.Failed(null)
        if (head.ok) return Outcome.Released
        val code = head.error ?: return Outcome.Failed(null)
        return if (code in NOTHING) Outcome.NothingToRelease(code) else Outcome.Failed(code)
    }
}

/**
 * `update_grid_tariff`: the electricity price's grid tariff replaced in full, here to set the demand charge's parts
 * (`demand_components`) with the three fees as read (`DashboardGridTariff.bodyWith`). Compare-and-set on the
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

/**
 * `get_power_month`: one month of the charger's site's measured import, by day and (for the running month and the
 * month before) by hour, against the demand charge's parts and the import target (the card's `power-view.ts`). Asked
 * only where the dashboard states `capabilities.get_power_month`.
 */
internal object PowerMonthRead {
    fun payload(month: YearMonth?): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", "get_power_month")
        WebhookReads.put(this)
        put("api_version", 1)
        month?.let { put("month", it.toString()) }
    }

    data class Day(
        val date: LocalDate,
        val peakKw: Double,
        /** The peak hour's start as Home Assistant wrote it: local time with its offset. */
        val peakStart: String,
        val houseKw: Double?,
        /** The parts (indexes into [Month.components]) whose counted peaks fall on this day. */
        val counted: List<Int>,
        val held: Boolean,
        val paused: Boolean,
        /** The peak hour was read from Home Assistant's statistics, not measured (`false` from an older one). */
        val imported: Boolean = false,
        /** The level the month's peak lifted the import target to at the peak hour, `null` where it did not. */
        val monthPeakKw: Double? = null
    )

    data class Hour(
        val start: String,
        val kw: Double,
        val houseKw: Double?,
        val incomplete: Boolean,
        val held: Boolean,
        val paused: Boolean,
        val counted: List<Int>,
        val imported: Boolean = false,
        val monthPeakKw: Double? = null
    )

    data class Month(
        val month: YearMonth,
        /** Newest first. */
        val availableMonths: List<YearMonth>,
        val importTargetKw: Double?,
        val components: List<DemandComponent>,
        val days: List<Day>,
        /** `null` for a month that keeps its days only. */
        val hours: List<Hour>?,
        val bars: List<Double?>
    )

    sealed interface Outcome {
        data class Read(val month: Month) : Outcome

        /** An answer of another version. */
        data object Unsupported : Outcome

        data class Failed(val code: String?) : Outcome
    }

    fun answer(status: Int?, body: String?): Outcome {
        if (status == null) return Outcome.Failed(null)
        val head = WriteEnvelope.head(body) ?: return Outcome.Failed(null)
        if (!head.ok) return Outcome.Failed(head.error)
        val version = head.json.opt("api_version")
        if (version != 1) return if (version is Number) Outcome.Unsupported else Outcome.Failed(null)
        return runCatching { Outcome.Read(decode(head.json)) }.getOrElse { Outcome.Failed(null) }
    }

    private val MONTH = Regex("^\\d{4}-(0[1-9]|1[0-2])$")
    private val INSTANT = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}.*")

    /** The answer, strictly typed as the card reads it: a missing or mistyped key is no answer at all. */
    fun decode(json: JSONObject): Month {
        require(json.opt("unit") == "kW")
        val components = list(json.opt("components")).map(DemandComponent::parse)
        val count = components.size
        val bars = list(json.opt("bars")).map(::powerOrNull)
        require(bars.size == count)
        val days = list(json.opt("days")).map { entry ->
            val day = entry as JSONObject
            Day(
                date = LocalDate.parse(day.get("date") as String),
                peakKw = power(day.opt("peak_kw")),
                peakStart = instantText(day.opt("peak_start")),
                houseKw = powerOrNull(day.opt("house_kw")),
                counted = indexes(day.opt("counted"), count),
                held = day.get("held") as Boolean,
                paused = day.get("paused") as Boolean,
                imported = additive(day, "imported", false) { it as Boolean },
                monthPeakKw = additive(day, "month_peak_kw", null, ::powerOrNull)
            )
        }
        val hours = json.opt("hours").let { raw ->
            if (raw == JSONObject.NULL) null else list(raw).map { entry ->
                val hour = entry as JSONObject
                Hour(
                    start = instantText(hour.opt("start")),
                    kw = power(hour.opt("kw")),
                    houseKw = powerOrNull(hour.opt("house_kw")),
                    incomplete = hour.get("incomplete") as Boolean,
                    held = hour.get("held") as Boolean,
                    paused = hour.get("paused") as Boolean,
                    counted = indexes(hour.opt("counted"), count),
                    imported = additive(hour, "imported", false) { it as Boolean },
                    monthPeakKw = additive(hour, "month_peak_kw", null, ::powerOrNull)
                )
            }
        }
        return Month(
            month = yearMonth(json.opt("month")),
            availableMonths = list(json.opt("available_months")).map(::yearMonth),
            importTargetKw = powerOrNull(json.opt("import_target_kw")),
            components = components,
            days = days,
            hours = hours,
            bars = bars
        )
    }

    /** A field an older Home Assistant did not state: absent reads as [fallback], present must be its shape. */
    private fun <T> additive(json: JSONObject, key: String, fallback: T, read: (Any?) -> T): T =
        if (json.has(key)) read(json.opt(key)) else fallback

    private fun list(raw: Any?): List<Any?> {
        val array = raw as JSONArray
        return (0 until array.length()).map { array.opt(it) }
    }

    private fun yearMonth(raw: Any?): YearMonth {
        val text = raw as String
        require(MONTH.matches(text))
        return YearMonth.parse(text)
    }

    private fun instantText(raw: Any?): String = (raw as String).also { require(INSTANT.matches(it)) }

    private fun power(raw: Any?): Double = (raw as Number).toDouble().also { require(it.isFinite() && it >= 0) }

    private fun powerOrNull(raw: Any?): Double? = if (raw == JSONObject.NULL) null else power(raw)

    private fun indexes(raw: Any?, count: Int): List<Int> = list(raw).map { entry ->
        val number = (entry as Number).toDouble()
        require(number == Math.floor(number) && number >= 0 && number < count)
        number.toInt()
    }
}

/**
 * `suggest_import_target`: a suggested import target for this charger's site from Home Assistant's statistics (the
 * card's `target-suggestion.ts`), read when the target's editor opens. Asked only where the dashboard states
 * `capabilities.suggest_import_target`. Home Assistant does every sum; this only reads them.
 */
internal object SuggestImportTarget {
    fun payload(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", "suggest_import_target")
        WebhookReads.put(this)
        put("api_version", 1)
    }

    data class Suggestion(
        val available: Boolean,
        /** Why there is none: `no_recorder`, `no_grid_statistics`, `too_few_days`, `no_chargers`. */
        val reason: String?,
        val suggestionKw: Double?,
        /** The house's typical daily peak: the median of the days' highest hours. */
        val typicalKw: Double?,
        val highestKw: Double?,
        val highestDate: LocalDate?,
        /** What the car gets at the suggestion in a typical evening or night hour. */
        val carKw: Double?,
        val days: Int,
        /** A member charger's own draw is not in the statistics: the grid alone stood for the house. */
        val chargerMissing: Boolean
    )

    /** The answer, or `null` for one this app does not read (a refusal, another version, or not its shape). */
    fun answer(status: Int?, body: String?): Suggestion? {
        if (status != 200) return null
        val json = runCatching { JSONObject(body ?: return null) }.getOrNull() ?: return null
        if (json.opt("ok") != true || json.opt("api_version") != 1) return null
        val available = json.opt("available") as? Boolean ?: return null
        fun power(key: String): Double? = (json.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
        val highest = json.optJSONObject("highest")
        val highestKw = (highest?.opt("kw") as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
        val highestDate = (highest?.opt("date") as? String)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val chargers = json.optJSONArray("chargers")
        val suggestion = Suggestion(
            available = available,
            reason = json.opt("reason") as? String,
            suggestionKw = power("suggestion_kw"),
            typicalKw = power("typical_kw"),
            highestKw = highestKw.takeIf { highestDate != null },
            highestDate = highestDate.takeIf { highestKw != null },
            carKw = power("car_kw"),
            days = (json.opt("days") as? Int) ?: 0,
            chargerMissing = chargers != null && (0 until chargers.length()).any { chargers.optJSONObject(it)?.opt("has_statistics") == false }
        )
        if (available && listOf(suggestion.suggestionKw, suggestion.typicalKw, suggestion.highestKw, suggestion.carKw).any { it == null }) return null
        return suggestion
    }
}
