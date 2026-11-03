package se.sensnology.spotnav.ha.dashboard

import org.json.JSONArray
import org.json.JSONObject

/**
 * The hours a high-load fee or a demand-charge part counts, on the area's clock (the card's `HighLoadWindow`): on
 * [days] (`all`, `weekdays` Monday to Friday, `mon_sat`), from [fromMonth] to [toMonth] (1-12, both included, wrapping
 * over the new year), whole hours [fromHour] 0-23 to [toHour] 1-24, wrapping over midnight when [fromHour] is after
 * [toHour] (never equal).
 */
internal data class HighLoadWindow(val days: String, val fromMonth: Int, val toMonth: Int, val fromHour: Int, val toHour: Int) {
    /** All twelve months, however the range is written. */
    val allYear: Boolean get() = toMonth % 12 + 1 == fromMonth

    fun toJson(): JSONObject = JSONObject()
        .put("days", days).put("from_month", fromMonth).put("to_month", toMonth)
        .put("from_hour", fromHour).put("to_hour", toHour)

    companion object {
        val DAYS: List<String> = listOf("all", "weekdays", "mon_sat")

        /** The window the editor offers when none is stored (the card's `SUGGESTED_WINDOW`). */
        val SUGGESTED = HighLoadWindow("weekdays", 11, 3, 7, 19)

        fun parse(raw: Any?): HighLoadWindow {
            val json = raw as? JSONObject ?: bad()
            exactKeys(json, "days", "from_month", "to_month", "from_hour", "to_hour")
            val days = (json.opt("days") as? String)?.takeIf { it in DAYS } ?: bad()
            val from = whole(json.opt("from_hour"), 0, 23)
            val to = whole(json.opt("to_hour"), 1, 24)
            if (from == to) bad()
            return HighLoadWindow(days, whole(json.opt("from_month"), 1, 12), whole(json.opt("to_month"), 1, 12), from, to)
        }
    }
}

/**
 * One part of a demand charge, as a write states it (the card's `DemandComponent`): the mean of the month's [topN]
 * highest hourly means on different days, counted in all hours ([window] `null`) or only in its own window; the price
 * per kW in winter (November to March) and summer, each `null` while unknown.
 */
internal data class DemandComponent(val window: HighLoadWindow?, val topN: Int, val winter: Double?, val summer: Double?) {
    fun toJson(): JSONObject = JSONObject()
        .put("window", window?.toJson() ?: JSONObject.NULL)
        .put("top_n", topN)
        .put("price_per_kw", JSONObject().put("winter", winter ?: JSONObject.NULL).put("summer", summer ?: JSONObject.NULL))

    companion object {
        val TOP_N: List<Int> = listOf(1, 3, 5)

        /** The most parts a demand charge may have. */
        const val MAX = 3

        fun parse(raw: Any?): DemandComponent {
            val json = raw as? JSONObject ?: bad()
            exactKeys(json, "window", "top_n", "price_per_kw")
            val topN = (json.opt("top_n") as? Number)?.toDouble()?.takeIf { it == Math.floor(it) }?.toInt()
                ?.takeIf { it in TOP_N } ?: bad()
            val prices = json.opt("price_per_kw") as? JSONObject ?: bad()
            exactKeys(prices, "winter", "summer")
            val window = json.opt("window").let { if (it == JSONObject.NULL) null else HighLoadWindow.parse(it) }
            return DemandComponent(window, topN, nonNegativeOrNull(prices.opt("winter")), nonNegativeOrNull(prices.opt("summer")))
        }
    }
}

/** A day's highest counted hour: the day it counts under, the hour's start and its mean import. */
internal data class DemandPeak(val date: String, val start: String, val kw: Double)

/** What one part bills so far this month, on the site's meter. */
internal data class DemandMonth(
    val barKw: Double?,
    val freeKw: Double?,
    val pricePerKw: Double?,
    val daysCounted: Int,
    val peaks: List<DemandPeak>
)

/** The clock hour running now: its start, the mean import over the time measured so far, and the energy so far. */
internal data class DemandHour(val start: String, val kwSoFar: Double, val kwhSoFar: Double)

/** The `grid_tariff.demand` block (since 2.4): the parts, what the site measured for them, and the import target. */
internal data class DemandBlock(
    val components: List<DemandComponent>,
    val available: Boolean,
    /** Why the site cannot measure: `no_site`, `needs_total_grid_power`; `null` while it can. */
    val reason: String?,
    /** The area's major unit per kW ("kr/kW"); `null` while the area is not listed. */
    val priceUnit: String?,
    /** Aligned with [components] while the site measures, else empty. */
    val perComponent: List<DemandMonth>,
    val hour: DemandHour?,
    val importTargetKw: Double?,
    /**
     * The level the running hour is held under (`effective_target_kw`, asked for with the `reads` value
     * `effective_target`): the import target lifted to the month's peak where a part counts the hour, else the target;
     * `null` without a target, where the site cannot measure, or from a Home Assistant that does not send it.
     */
    val effectiveTargetKw: Double? = null
)

/** One fee of the grid tariff: `off`, `manual` or `included` (the area's price already holds the grid fee). */
internal data class GridTariffFee(val policy: String, val explicitValue: Double?, val effectiveValue: Double?, val unit: String?) {
    /** The fee as a write states it, unchanged. */
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", policy == "manual")
        .put("value", explicitValue ?: JSONObject.NULL)
}

/**
 * The dashboard's `grid_tariff` block (asked for with the `reads` value `grid_tariff`): the electricity price's grid
 * tariff (a percentage of spot, a high-load fee and its hours) and, since 2.4, its demand charge with the site's import
 * target. Read strictly, as the card does: a block this
 * app cannot read is no block at all.
 */
internal data class DashboardGridTariff(
    val entryId: String,
    /** The electricity price's write counter, which a write names. */
    val writeRevision: Int,
    val transferPercent: GridTariffFee,
    val transferHigh: GridTariffFee,
    val highLoadWindow: HighLoadWindow?,
    /** `null` from a Home Assistant before the demand charge: the tariff is then written without it. */
    val demand: DemandBlock?,
    val writable: Boolean
) {
    /** Whether the area's published price already holds the grid fee: the tariff's rows are then locked. */
    val included: Boolean get() = transferPercent.policy == "included" || transferHigh.policy == "included"

    /**
     * The body of `update_grid_tariff` with the tariff as stored (the card's `storedBody`): a write replaces the three
     * parts in full, so every change is made on the block held when it is sent. The demand charge's parts go only to a
     * Home Assistant that states them (an older one refuses the key, and keeps its parts without it).
     */
    fun storedBody(): JSONObject = JSONObject()
        .put("transfer_percent", transferPercent.toJson())
        .put("transfer_high", transferHigh.toJson())
        .put("high_load_window", highLoadWindow?.toJson() ?: JSONObject.NULL)
        .also { body -> demand?.let { body.put("demand_components", JSONArray(it.components.map { part -> part.toJson() })) } }

    /** A fee turned on with [value], or off (`null`) keeping its stored figure, so turning it on again is one step. */
    private fun feeChange(fee: GridTariffFee, value: Double?): JSONObject =
        if (value == null) JSONObject().put("enabled", false).put("value", fee.explicitValue ?: JSONObject.NULL)
        else JSONObject().put("enabled", true).put("value", value)

    /** The percentage of spot set (`null` is off), the rest as stored (the card's `withPercent`). */
    fun bodyWithPercent(value: Double?): JSONObject = storedBody().put("transfer_percent", feeChange(transferPercent, value))

    /**
     * The high-load fee set (`null` is off), with [window] when the person has just confirmed one, else the stored
     * window (the card's `withHigh`). Home Assistant refuses a fee that is on without a window; none is made up here.
     */
    fun bodyWithHigh(value: Double?, window: HighLoadWindow? = null): JSONObject =
        storedBody().put("transfer_high", feeChange(transferHigh, value)).also { body ->
            window?.let { body.put("high_load_window", it.toJson()) }
        }

    /** The high-load hours set, the fees as stored (the card's `withWindow`). */
    fun bodyWithWindow(window: HighLoadWindow): JSONObject = storedBody().put("high_load_window", window.toJson())

    /**
     * The body of `update_grid_tariff` with the demand charge's parts set to [components] and the rest as stored (the
     * card's `withComponents`): a write replaces the three fees in full, so they are sent as read.
     */
    fun bodyWith(components: List<DemandComponent>): JSONObject = storedBody()
        .put("demand_components", JSONArray(components.map { it.toJson() }))

    /** The part at [index] replaced (`null` removes it; an index past the end adds one), the rest as stored. */
    fun bodyWith(index: Int, component: DemandComponent?): JSONObject {
        val parts = demand?.components.orEmpty().toMutableList()
        when {
            component == null -> if (index in parts.indices) parts.removeAt(index)
            index >= parts.size -> parts.add(component)
            else -> parts[index] = component
        }
        return bodyWith(parts)
    }

    companion object {
        private val POLICIES = listOf("off", "manual", "included")

        /** The block, or `null` when it is absent or cannot be read (the price section then shows no tariff rows). */
        fun parse(raw: Any?): DashboardGridTariff? {
            if (raw !is JSONObject) return null
            return try {
                decode(raw)
            } catch (malformed: Malformed) {
                null
            }
        }

        fun decode(json: JSONObject): DashboardGridTariff {
            val keys = arrayOf("entry_id", "write_revision", "transfer_percent", "transfer_high", "high_load_window", "writable")
            if (json.has("demand")) exactKeys(json, *keys, "demand") else exactKeys(json, *keys)
            val entryId = (json.opt("entry_id") as? String)?.takeIf { it.isNotEmpty() } ?: bad()
            return DashboardGridTariff(
                entryId = entryId,
                writeRevision = whole(json.opt("write_revision"), 0, Int.MAX_VALUE),
                transferPercent = fee(json.opt("transfer_percent")),
                transferHigh = fee(json.opt("transfer_high")),
                highLoadWindow = json.opt("high_load_window").let { if (it == JSONObject.NULL) null else HighLoadWindow.parse(it) },
                demand = if (json.has("demand")) demand(json.opt("demand")) else null,
                writable = json.opt("writable") as? Boolean ?: bad()
            )
        }

        private fun fee(raw: Any?): GridTariffFee {
            val json = raw as? JSONObject ?: bad()
            exactKeys(json, "policy", "explicit_value", "effective_value", "unit")
            val policy = (json.opt("policy") as? String)?.takeIf { it in POLICIES } ?: bad()
            val effective = numberOrNull(json.opt("effective_value"))
            if ((policy == "manual") != (effective != null)) bad()
            return GridTariffFee(policy, numberOrNull(json.opt("explicit_value")), effective, textOrNull(json.opt("unit")))
        }

        private fun demand(raw: Any?): DemandBlock {
            val json = raw as? JSONObject ?: bad()
            val keys = arrayOf("components", "available", "reason", "price_unit", "per_component", "hour", "import_target_kw")
            // `effective_target_kw` is additive: sent only to an app that reads it.
            if (json.has("effective_target_kw")) exactKeys(json, *keys, "effective_target_kw") else exactKeys(json, *keys)
            val components = list(json.opt("components")).map(DemandComponent::parse)
            if (components.size > DemandComponent.MAX) bad()
            val months = list(json.opt("per_component")).map(::month)
            if (months.isNotEmpty() && months.size != components.size) bad()
            val hour = json.opt("hour").let { value ->
                if (value == JSONObject.NULL) null else {
                    val item = value as? JSONObject ?: bad()
                    exactKeys(item, "start", "kw_so_far", "kwh_so_far")
                    DemandHour(text(item.opt("start")), number(item.opt("kw_so_far")), number(item.opt("kwh_so_far")))
                }
            }
            return DemandBlock(
                components = components,
                available = json.opt("available") as? Boolean ?: bad(),
                reason = textOrNull(json.opt("reason")),
                priceUnit = textOrNull(json.opt("price_unit")),
                perComponent = months,
                hour = hour,
                importTargetKw = nonNegativeOrNull(json.opt("import_target_kw")),
                effectiveTargetKw = if (json.has("effective_target_kw")) nonNegativeOrNull(json.opt("effective_target_kw")) else null
            )
        }

        private fun month(raw: Any?): DemandMonth {
            val json = raw as? JSONObject ?: bad()
            exactKeys(json, "bar_kw", "free_kw", "price_per_kw", "days_counted", "peaks")
            return DemandMonth(
                barKw = nonNegativeOrNull(json.opt("bar_kw")),
                freeKw = nonNegativeOrNull(json.opt("free_kw")),
                pricePerKw = nonNegativeOrNull(json.opt("price_per_kw")),
                daysCounted = whole(json.opt("days_counted"), 0, 31),
                peaks = list(json.opt("peaks")).map { entry ->
                    val peak = entry as? JSONObject ?: bad()
                    exactKeys(peak, "date", "start", "kw")
                    val kw = number(peak.opt("kw")).takeIf { it >= 0 } ?: bad()
                    DemandPeak(text(peak.opt("date")), text(peak.opt("start")), kw)
                }
            )
        }
    }
}

/** A block this app cannot read: the reader then has none. */
internal class Malformed : RuntimeException()

private fun bad(): Nothing = throw Malformed()

private fun exactKeys(json: JSONObject, vararg keys: String) {
    if (json.length() != keys.size || keys.any { !json.has(it) }) bad()
}

private fun whole(value: Any?, min: Int, max: Int): Int {
    val number = (value as? Number)?.toDouble() ?: bad()
    if (number != Math.floor(number) || number < min || number > max) bad()
    return number.toInt()
}

private fun number(value: Any?): Double = (value as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: bad()

private fun numberOrNull(value: Any?): Double? = if (value == JSONObject.NULL) null else number(value)

private fun nonNegativeOrNull(value: Any?): Double? = numberOrNull(value)?.also { if (it < 0) bad() }

private fun text(value: Any?): String = value as? String ?: bad()

private fun textOrNull(value: Any?): String? = if (value == JSONObject.NULL) null else text(value)

private fun list(value: Any?): List<Any?> {
    val array = value as? JSONArray ?: bad()
    return (0 until array.length()).map { array.opt(it) }
}
