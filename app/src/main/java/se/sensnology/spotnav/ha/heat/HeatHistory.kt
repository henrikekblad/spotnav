package se.sensnology.spotnav.ha.heat

import org.json.JSONObject
import se.sensnology.spotnav.ha.client.WriteEnvelope
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.HouseHistory.HistoryKind
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.ha.house.StrictJson
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One day, month or year of the heating (webhook `get_heat_history`, the twin of `spotnav/get_heat_history`,
 * `api_version` 1): the use by what is measured (`parts`), what SpotNav saved on heat, and what the details sheet
 * shows (degree days, comfort, the shadow comparison, the house model's error, the immersion heater, the hot-water
 * runs, the day's review and its indoor curve). Shape: the integration's `heat/history.py`; the card's reader is
 * `frontend/src/heat-history.ts`.
 */
internal data class HeatHistory(
    val siteId: String,
    val zone: ZoneId,
    val period: HistoryKind,
    val date: LocalDate,
    val end: LocalDate,
    val today: LocalDate,
    val complete: Boolean,
    val money: House.Money?,
    val meter: Meter?,
    val energy: Energy?,
    val buckets: List<Bucket>,
    val degreeDays: DegreeDays?,
    val comfort: Comfort?,
    val shadow: Shadow?,
    val immersion: Immersion?,
    val runs: List<Run>,
    val review: HeatReview?,
    val indoorDay: HeatIndoorDay?,
    val parts: List<Part>,
    val saving: Saving?,
    val model: Model?
) {
    /** The period holds today: it is still running. */
    val running: Boolean get() = today >= date && today < end

    data class Meter(val entityId: String, val name: String?)

    data class Energy(
        val totalKwh: Double,
        val hours: Int,
        val hotWaterKwh: Double?,
        val splitDays: Int,
        val days: Int,
        val cost: Double?,
        val pricedHours: Int,
        val zonesKwh: Double?,
        val zonesFrom: LocalDate?
    )

    /** A bucket is an hour of a day, a day of a month or a month of a year; [key] is its local hour, day or month. */
    data class Bucket(
        val key: String,
        val kwh: Double?,
        val hotWaterKwh: Double?,
        val zonesKwh: Double?,
        val cost: Double?,
        val degreeDays: Double?,
        val outdoorC: Double?,
        val shadowValue: Double?,
        val parts: List<BucketPart>,
        val saving: Double?
    )

    data class BucketPart(val key: String, val kwh: Double, val cost: Double?)

    /** [kind] is `pump`, `hot_water`, `zone` or `other`; a zone's [name] is the household's. */
    data class Part(val key: String, val kind: String, val name: String?, val kwh: Double, val cost: Double?)

    data class TrendMonth(val month: String, val days: Int, val kwh: Double?, val degreeDays: Double?, val kwhPerDegreeDay: Double?)

    data class DegreeDays(
        val baseC: Double,
        val outdoorSource: String,
        val sum: Double,
        val days: Int,
        val kwh: Double,
        val kwhPerDegreeDay: Double?,
        val trend: List<TrendMonth>
    )

    data class Coldest(val name: String, val meanC: Double, val minC: Double)

    data class Comfort(val lowC: Double, val sensors: Int, val coldest: Coldest?, val hours: Int, val hoursBelow: Int)

    data class Shadow(
        val days: Int,
        val loweredHours: Int,
        val movedKwh: Double,
        val value: Double,
        val keptDays: Int,
        val lowerShare: Double,
        val rebound: Double,
        val blocks: Int
    )

    data class Immersion(val hours: Int, val kwh: Double)

    /** A hot-water run; [cause] is `draw`, `standby` or `null` (not known). */
    data class Run(val start: Instant, val end: Instant, val kwh: Double, val cause: String?)

    data class Saving(val value: Double, val days: Int)

    data class Model(val maeKw: Double, val days: Int)
}

internal object HeatHistoryRead {
    const val API_VERSION = 1
    private val PART_KINDS = setOf("pump", "hot_water", "zone", "other")

    fun payload(period: HistoryKind, date: LocalDate? = null): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", "get_heat_history")
        put("api_version", API_VERSION)
        put("period", period.key)
        date?.let { put("date", it.toString()) }
    }

    fun answer(status: Int?, body: String?): SiteRead<HeatHistory> {
        if (status == null) return SiteRead.Failed(null)
        val head = WriteEnvelope.head(body) ?: return SiteRead.Failed(null)
        if (!head.ok) return SiteRead.Failed(head.error)
        return decode(head.json)
    }

    /** The document itself, as the webhook and the WebSocket both send it. */
    fun decode(json: JSONObject): SiteRead<HeatHistory> {
        val version = json.opt("api_version")
        if (version != API_VERSION) return if (version is Number) SiteRead.Unsupported else SiteRead.Failed(null)
        return runCatching { SiteRead.Read(document(json)) }.getOrElse { SiteRead.Failed(null) }
    }

    private fun document(json: JSONObject): HeatHistory = with(StrictJson) {
        HeatHistory(
            siteId = text(json.opt("site_id"), "site_id"),
            zone = runCatching { ZoneId.of(text(json.opt("time_zone"), "time_zone")) }.getOrElse { bad("time_zone") },
            period = when (text(json.opt("period"), "period")) {
                "day" -> HistoryKind.DAY
                "month" -> HistoryKind.MONTH
                "year" -> HistoryKind.YEAR
                else -> bad("period")
            },
            date = day(json.opt("date"), "date"),
            end = day(json.opt("end"), "end"),
            today = day(json.opt("today"), "today"),
            complete = flag(json.opt("complete"), "complete"),
            money = objOrNull(json.opt("money"), "money")?.let {
                House.Money(
                    currency = text(it.opt("currency"), "money.currency"),
                    majorUnit = textOrNull(it.opt("major_unit"), "money.major_unit"),
                    minorUnit = textOrNull(it.opt("minor_unit"), "money.minor_unit")
                )
            },
            meter = objOrNull(json.opt("meter"), "meter")?.let {
                HeatHistory.Meter(text(it.opt("entity_id"), "meter.entity_id"), textOrNull(it.opt("name"), "meter.name"))
            },
            energy = objOrNull(json.opt("energy"), "energy")?.let(::energy),
            buckets = list(json.opt("buckets"), "buckets").map(::bucket),
            degreeDays = objOrNull(json.opt("degree_days"), "degree_days")?.let(::degreeDays),
            comfort = objOrNull(json.opt("comfort"), "comfort")?.let(::comfort),
            shadow = objOrNull(json.opt("shadow"), "shadow")?.let(::shadow),
            immersion = objOrNull(json.opt("immersion"), "immersion")?.let {
                HeatHistory.Immersion(count(it.opt("hours"), "immersion.hours"), number(it.opt("kwh"), "immersion.kwh"))
            },
            runs = list(json.opt("runs"), "runs").map(::run),
            review = objOrNull(json.opt("review"), "review")?.let(HeatDayRead::review),
            indoorDay = objOrNull(json.opt("indoor_day"), "indoor_day")?.let(HeatDayRead::indoorDay),
            // These came after the first heat history: absent reads as none (an older integration), present is strict.
            parts = additive(json, "parts", emptyList()) { raw -> list(raw, "parts").map(::part) },
            saving = additive(json, "saving", null) { raw ->
                objOrNull(raw, "saving")?.let { HeatHistory.Saving(number(it.opt("value"), "saving.value"), count(it.opt("days"), "saving.days")) }
            },
            model = additive(json, "model", null) { raw ->
                objOrNull(raw, "model")?.let { HeatHistory.Model(number(it.opt("mae_kw"), "model.mae_kw"), count(it.opt("days"), "model.days")) }
            }
        )
    }

    private fun energy(json: JSONObject): HeatHistory.Energy = with(StrictJson) {
        HeatHistory.Energy(
            totalKwh = number(json.opt("total_kwh"), "energy.total_kwh"),
            hours = count(json.opt("hours"), "energy.hours"),
            hotWaterKwh = numberOrNull(json.opt("hot_water_kwh"), "energy.hot_water_kwh"),
            splitDays = count(json.opt("split_days"), "energy.split_days"),
            days = count(json.opt("days"), "energy.days"),
            cost = numberOrNull(json.opt("cost"), "energy.cost"),
            pricedHours = count(json.opt("priced_hours"), "energy.priced_hours"),
            zonesKwh = additive(json, "zones_kwh", null) { numberOrNull(it, "energy.zones_kwh") },
            zonesFrom = additive(json, "zones_from", null) { dayOrNull(it, "energy.zones_from") }
        )
    }

    private fun bucket(raw: Any?): HeatHistory.Bucket = with(StrictJson) {
        val json = obj(raw, "bucket")
        HeatHistory.Bucket(
            key = text(json.opt("key"), "bucket.key"),
            kwh = numberOrNull(json.opt("kwh"), "bucket.kwh"),
            hotWaterKwh = numberOrNull(json.opt("hot_water_kwh"), "bucket.hot_water_kwh"),
            zonesKwh = additive(json, "zones_kwh", null) { numberOrNull(it, "bucket.zones_kwh") },
            cost = numberOrNull(json.opt("cost"), "bucket.cost"),
            degreeDays = numberOrNull(json.opt("degree_days"), "bucket.degree_days"),
            outdoorC = numberOrNull(json.opt("outdoor_c"), "bucket.outdoor_c"),
            shadowValue = numberOrNull(json.opt("shadow_value"), "bucket.shadow_value"),
            parts = additive(json, "parts", emptyList()) { value ->
                list(value, "bucket.parts").map {
                    val part = obj(it, "bucket.part")
                    HeatHistory.BucketPart(text(part.opt("key"), "part.key"), number(part.opt("kwh"), "part.kwh"), numberOrNull(part.opt("cost"), "part.cost"))
                }
            },
            saving = additive(json, "saving", null) { numberOrNull(it, "bucket.saving") }
        )
    }

    private fun part(raw: Any?): HeatHistory.Part = with(StrictJson) {
        val json = obj(raw, "part")
        val kind = text(json.opt("kind"), "part.kind")
        if (kind !in PART_KINDS) bad("part.kind")
        HeatHistory.Part(text(json.opt("key"), "part.key"), kind, textOrNull(json.opt("name"), "part.name"), number(json.opt("kwh"), "part.kwh"), numberOrNull(json.opt("cost"), "part.cost"))
    }

    private fun degreeDays(json: JSONObject): HeatHistory.DegreeDays = with(StrictJson) {
        val outdoor = obj(json.opt("outdoor"), "degree_days.outdoor")
        HeatHistory.DegreeDays(
            baseC = number(json.opt("base_c"), "degree_days.base_c"),
            outdoorSource = text(outdoor.opt("source"), "outdoor.source"),
            sum = number(json.opt("sum"), "degree_days.sum"),
            days = count(json.opt("days"), "degree_days.days"),
            kwh = number(json.opt("kwh"), "degree_days.kwh"),
            kwhPerDegreeDay = numberOrNull(json.opt("kwh_per_degree_day"), "degree_days.kwh_per_degree_day"),
            trend = list(json.opt("trend"), "degree_days.trend").map {
                val month = obj(it, "trend.month")
                val name = text(month.opt("month"), "trend.month")
                if (!Regex("""\d{4}-\d{2}""").matches(name)) bad("trend.month")
                HeatHistory.TrendMonth(
                    name, count(month.opt("days"), "trend.days"), numberOrNull(month.opt("kwh"), "trend.kwh"),
                    numberOrNull(month.opt("degree_days"), "trend.degree_days"), numberOrNull(month.opt("kwh_per_degree_day"), "trend.kwh_per_degree_day")
                )
            }
        )
    }

    private fun comfort(json: JSONObject): HeatHistory.Comfort = with(StrictJson) {
        HeatHistory.Comfort(
            lowC = number(json.opt("low_c"), "comfort.low_c"),
            sensors = count(json.opt("sensors"), "comfort.sensors"),
            coldest = objOrNull(json.opt("coldest"), "comfort.coldest")?.let {
                text(it.opt("entity_id"), "coldest.entity_id")
                HeatHistory.Coldest(text(it.opt("name"), "coldest.name"), number(it.opt("mean_c"), "coldest.mean_c"), number(it.opt("min_c"), "coldest.min_c"))
            },
            hours = count(json.opt("hours"), "comfort.hours"),
            hoursBelow = count(json.opt("hours_below"), "comfort.hours_below")
        )
    }

    private fun shadow(json: JSONObject): HeatHistory.Shadow = with(StrictJson) {
        HeatHistory.Shadow(
            days = count(json.opt("days"), "shadow.days"),
            loweredHours = count(json.opt("lowered_hours"), "shadow.lowered_hours"),
            movedKwh = number(json.opt("moved_kwh"), "shadow.moved_kwh"),
            value = number(json.opt("value"), "shadow.value"),
            keptDays = count(json.opt("kept_days"), "shadow.kept_days"),
            lowerShare = number(json.opt("lower_share"), "shadow.lower_share"),
            rebound = number(json.opt("rebound"), "shadow.rebound"),
            blocks = count(json.opt("blocks"), "shadow.blocks")
        )
    }

    private fun run(raw: Any?): HeatHistory.Run = with(StrictJson) {
        val json = obj(raw, "run")
        HeatHistory.Run(
            start = instant(json.opt("start"), "run.start"),
            end = instant(json.opt("end"), "run.end"),
            kwh = number(json.opt("kwh"), "run.kwh"),
            cause = textOrNull(json.opt("cause"), "run.cause")?.also { if (it != "draw" && it != "standby") bad("run.cause") }
        )
    }
}
