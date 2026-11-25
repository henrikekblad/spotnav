package se.sensnology.spotnav.ha.house

import org.json.JSONObject
import se.sensnology.spotnav.ha.client.WriteEnvelope
import java.time.LocalDate

/**
 * What the house (or the heat) used over a period, by the Energy dashboard's individual devices (webhook
 * `get_house_loads`, the twin of `spotnav/get_house_loads`, `api_version` 1): what the pie behind House in the Energy
 * flow shows. Energy is kWh. Shape: the integration's `site/house_loads.py`; the card's reader is
 * `frontend/src/house-loads.ts`. Where a meter belongs (`belongs`, `moved`) is read for what it says and never
 * written from here.
 */
internal data class HouseLoads(
    val siteId: String,
    /** `house` or `heat`. */
    val kind: String,
    val period: String,
    val date: LocalDate,
    val end: LocalDate,
    /** `null` where there is no figure for the house. */
    val houseKwh: Double?,
    val metersKwh: Double,
    /** The house less every meter; `null` where there is no house figure. */
    val otherKwh: Double?,
    /** How much more the meters add up to than the house. */
    val overKwh: Double,
    /** What a hybrid's inverter used of the house (its losses and standby), taken out of what no meter explains; `null`
     * where Home Assistant cannot count it, or from one before it. */
    val inverterKwh: Double? = null,
    /** What no meter explains, the inverter left out: [otherKwh] from a Home Assistant before it. */
    val unknownKwh: Double? = otherKwh,
    /** The estimates left out because the house had no room for them, summed; 0 from a Home Assistant before it. */
    val clampedKwh: Double = 0.0,
    val loads: List<Load>,
    val moved: List<Moved>,
    val implausible: List<Implausible>
) {
    /**
     * `source` is `meter` (an Energy dashboard device), `heat_pump`, `zone` or one of SpotNav's own loads. [estimated]:
     * some of its energy is an estimate (a part without its own trusted power sensor), named "(uppskattat)";
     * [clampedKwh] the estimate the house had no room for (left in the house).
     */
    data class Load(
        val statisticId: String, val name: String, val kwh: Double, val source: String, val changed: Boolean,
        val estimated: Boolean = false, val clampedKwh: Double = 0.0
    )

    data class Moved(val statisticId: String, val name: String, val belongs: String)

    /** A meter with hours that cannot be: [hours] are not counted, and with [leftOut] the meter is not in the pie. */
    data class Implausible(val statisticId: String, val name: String, val hours: Int, val leftOut: Boolean)
}

internal object HouseLoadsRead {
    const val API_VERSION = 1

    /** [kind] `house` or `heat`; [date] a day inside the period, `null` for the running one. */
    fun payload(kind: String, period: String, date: LocalDate?): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", "get_house_loads")
        put("api_version", API_VERSION)
        put("kind", kind)
        put("period", period)
        date?.let { put("date", it.toString()) }
    }

    fun answer(status: Int?, body: String?): SiteRead<HouseLoads> {
        if (status == null) return SiteRead.Failed(null)
        val head = WriteEnvelope.head(body) ?: return SiteRead.Failed(null)
        if (!head.ok) return SiteRead.Failed(head.error)
        return decode(head.json)
    }

    fun decode(json: JSONObject): SiteRead<HouseLoads> {
        val version = json.opt("api_version")
        if (version != API_VERSION) return if (version is Number) SiteRead.Unsupported else SiteRead.Failed(null)
        return runCatching { SiteRead.Read(document(json)) }.getOrElse { SiteRead.Failed(null) }
    }

    private val PERIODS = setOf("day", "month", "year")
    private val BELONGS = setOf("heat", "car", "none")
    private val SOURCES = setOf("meter", "heat_pump", "zone", "pool_heat", "pool_circulation", "spa", "sauna")

    private fun document(json: JSONObject): HouseLoads = with(StrictJson) {
        val period = text(json.opt("period"), "period")
        if (period !in PERIODS) bad("period")
        HouseLoads(
            siteId = text(json.opt("site_id"), "site_id"),
            // A backend before the heat's own pie did not say.
            kind = additive(json, "kind", "house") { raw -> text(raw, "kind").also { if (it != "house" && it != "heat") bad("kind") } },
            period = period,
            date = day(json.opt("date"), "date"),
            end = day(json.opt("end"), "end"),
            houseKwh = numberOrNull(json.opt("house_kwh"), "house_kwh"),
            metersKwh = number(json.opt("meters_kwh"), "meters_kwh"),
            otherKwh = numberOrNull(json.opt("other_kwh"), "other_kwh"),
            overKwh = number(json.opt("over_kwh"), "over_kwh"),
            inverterKwh = additive(json, "inverter_kwh", null) { raw -> numberOrNull(raw, "inverter_kwh") },
            unknownKwh = additive(json, "unknown_kwh", numberOrNull(json.opt("other_kwh"), "other_kwh")) { raw -> numberOrNull(raw, "unknown_kwh") },
            clampedKwh = additive(json, "clamped_kwh", 0.0) { raw -> number(raw, "clamped_kwh").coerceAtLeast(0.0) },
            loads = list(json.opt("loads"), "loads").map { entry ->
                val load = obj(entry, "load")
                HouseLoads.Load(
                    statisticId = text(load.opt("statistic_id"), "load.statistic_id"),
                    name = text(load.opt("name"), "load.name"),
                    kwh = number(load.opt("kwh"), "load.kwh").coerceAtLeast(0.0),
                    source = additive(load, "source", "meter") { raw -> text(raw, "load.source").also { if (it !in SOURCES) bad("load.source") } },
                    changed = additive(load, "changed", false) { raw -> flag(raw, "load.changed") },
                    estimated = additive(load, "estimated", false) { raw -> flag(raw, "load.estimated") },
                    clampedKwh = additive(load, "clamped_kwh", 0.0) { raw -> number(raw, "load.clamped_kwh").coerceAtLeast(0.0) }
                )
            },
            moved = additive(json, "moved", emptyList()) { raw ->
                list(raw, "moved").map { entry ->
                    val row = obj(entry, "moved")
                    HouseLoads.Moved(
                        statisticId = text(row.opt("statistic_id"), "moved.statistic_id"),
                        name = text(row.opt("name"), "moved.name"),
                        belongs = text(row.opt("belongs"), "moved.belongs").also { if (it !in BELONGS) bad("moved.belongs") }
                    )
                }
            },
            implausible = additive(json, "implausible", emptyList()) { raw ->
                list(raw, "implausible").map { entry ->
                    val row = obj(entry, "implausible")
                    HouseLoads.Implausible(
                        statisticId = text(row.opt("statistic_id"), "implausible.statistic_id"),
                        name = text(row.opt("name"), "implausible.name"),
                        hours = Math.round(number(row.opt("hours"), "implausible.hours")).toInt().coerceAtLeast(0),
                        leftOut = row.opt("left_out") == true
                    )
                }
            }
        )
    }
}
