package se.sensnology.spotnav.ha.house

import org.json.JSONObject
import se.sensnology.spotnav.ha.client.WriteEnvelope
import se.sensnology.spotnav.ha.dashboard.DoesNotFit
import java.time.Instant
import java.time.ZoneId

/**
 * The site's plan (webhook `get_plan`, the twin of `spotnav/get_plan`, `api_version` 1, Home Assistant 3.0.0b3): every
 * load SpotNav plans on one rolling time axis from the running hour to the end of the prices held, against the ceiling
 * that applies. What the house page's Plan box draws. Prices and costs are in the minor unit ([money]); powers kW.
 *
 * Read by [StrictJson]'s rule with two softenings for values Home Assistant may add later: a load of a kind the app does
 * not know is kept under its own kind (drawn as a lane, named by its name), and a ceiling of a kind it does not know
 * reads as none named (its kW is still drawn).
 */
internal data class Plan(
    val siteId: String,
    val zone: ZoneId,
    val money: House.Money?,
    val hours: List<Hour>,
    val loads: List<Load>,
    val powerTariff: Boolean,
    /** Some load is only watched: its part says "would". */
    val would: Boolean,
    val monthPeak: MonthPeak?,
    val summary: Summary,
    /** The sun is inside the house's figure (the sun is then not drawn below zero). */
    val sunInHouse: Boolean,
    /** What will not be ready in time across the site's deadlines (since 3.0); `null` when all fits or unreadable. */
    val notReady: NotReady? = null
) {
    data class Hour(
        val start: Instant,
        val price: Double?,
        val sell: Double?,
        val houseKw: Double?,
        val sunKw: Double?,
        val netKw: Double?,
        val ceilingKw: Double?,
        /** `import_target`, `power_level`, `power_tariff`, `fuse`, or `null` (none known, or a kind the app does not know). */
        val ceiling: String?
    )

    data class Run(val start: Instant, val end: Instant, val what: String, val reason: String?, val kw: Double?, val kwh: Double?)

    data class Load(
        val id: String,
        /** `car`, `home_battery`, `heat`, `heat_zone`, `water_heater`, or a later kind. */
        val kind: String,
        val name: String?,
        /** SpotNav only watches this load: "would", never "does". */
        val would: Boolean,
        val inHouse: Boolean,
        /** Per hour, along [Plan.hours]. */
        val kw: List<Double?>,
        /** Per hour (a lowering below zero, a pre-heat above), or `null` for a load without levels. */
        val level: List<Double>?,
        val runs: List<Run>,
        /** The kind's own figures (car `kwh`, battery `cover_kwh`, heat `lowered_hours`, ...): numbers only. */
        val summary: Map<String, Double>,
        val doesNotFit: DoesNotFit?,
        /** The kind's own texts (a spa's or a sauna's `ready_at`). */
        val summaryText: Map<String, String> = emptyMap()
    )

    data class MonthPeak(val kw: Double, val start: Instant)

    data class Summary(
        val cost: Double?,
        val peakKw: Double?,
        val peakStart: Instant?,
        val peakCeilingKw: Double?,
        val peakCeiling: String?,
        val over: Boolean
    )

    companion object {
        val CEILINGS = setOf("import_target", "power_level", "power_tariff", "fuse")
    }
}

internal object PlanRead {
    const val API_VERSION = 1

    /** Home Assistant's answer to an action it does not have (3.0.0b2 and older). */
    const val UNSUPPORTED_ACTION = "Unsupported action"

    fun payload(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", "get_plan")
        put("api_version", API_VERSION)
    }

    fun answer(status: Int?, body: String?): SiteRead<Plan> {
        if (status == null) return SiteRead.Failed(null)
        val head = WriteEnvelope.head(body) ?: return SiteRead.Failed(null)
        if (!head.ok) return SiteRead.Failed(head.error)
        return decode(head.json)
    }

    /** The document itself, as the webhook and the WebSocket both send it. */
    fun decode(json: JSONObject): SiteRead<Plan> {
        val version = json.opt("api_version")
        if (version != API_VERSION) return if (version is Number) SiteRead.Unsupported else SiteRead.Failed(null)
        return runCatching { SiteRead.Read(document(json)) }.getOrElse { SiteRead.Failed(null) }
    }

    private fun ceiling(raw: Any?, what: String): String? = with(StrictJson) { textOrNull(raw, what) }?.takeIf { it in Plan.CEILINGS }

    private fun document(json: JSONObject): Plan = with(StrictJson) {
        val house = obj(json.opt("house"), "house")
        val summary = obj(json.opt("summary"), "summary")
        val hours = list(json.opt("hours"), "hours").map { raw ->
            val hour = obj(raw, "hour")
            Plan.Hour(
                start = instant(hour.opt("start"), "hour.start"),
                price = numberOrNull(hour.opt("price"), "hour.price"),
                sell = numberOrNull(hour.opt("sell"), "hour.sell"),
                houseKw = numberOrNull(hour.opt("house_kw"), "hour.house_kw"),
                sunKw = numberOrNull(hour.opt("sun_kw"), "hour.sun_kw"),
                netKw = numberOrNull(hour.opt("net_kw"), "hour.net_kw"),
                ceilingKw = numberOrNull(hour.opt("ceiling_kw"), "hour.ceiling_kw"),
                ceiling = ceiling(hour.opt("ceiling"), "hour.ceiling")
            )
        }
        if (hours.isEmpty()) bad("hours")
        val money = objOrNull(json.opt("money"), "money")?.let {
            House.Money(textOrNull(it.opt("currency"), "money.currency"), textOrNull(it.opt("major_unit"), "money.major_unit"), textOrNull(it.opt("minor_unit"), "money.minor_unit"))
        }
        val peak = objOrNull(json.opt("month_peak"), "month_peak")
        Plan(
            siteId = text(json.opt("site_id"), "site_id"),
            zone = runCatching { ZoneId.of(text(json.opt("time_zone"), "time_zone")) }.getOrElse { bad("time_zone") },
            money = money,
            hours = hours,
            loads = list(json.opt("loads"), "loads").map { load(it, hours.size) },
            powerTariff = flag(json.opt("power_tariff"), "power_tariff"),
            would = flag(json.opt("would"), "would"),
            monthPeak = peak?.let { Plan.MonthPeak(numberOrNull(it.opt("kw"), "month_peak.kw") ?: 0.0, instant(it.opt("start"), "month_peak.start")) },
            summary = Plan.Summary(
                cost = numberOrNull(summary.opt("cost"), "summary.cost"),
                peakKw = numberOrNull(summary.opt("peak_kw"), "summary.peak_kw"),
                peakStart = instantOrNull(summary.opt("peak_start"), "summary.peak_start"),
                peakCeilingKw = numberOrNull(summary.opt("peak_ceiling_kw"), "summary.peak_ceiling_kw"),
                peakCeiling = ceiling(summary.opt("peak_ceiling"), "summary.peak_ceiling"),
                over = flag(summary.opt("over"), "summary.over")
            ),
            sunInHouse = flag(house.opt("sun_in_house"), "house.sun_in_house"),
            notReady = NotReadyRead.orNull(json.opt("not_ready"))
        )
    }

    private fun load(raw: Any?, count: Int): Plan.Load = with(StrictJson) {
        val load = obj(raw, "load")
        val kw = list(load.opt("kw"), "load.kw").map { numberOrNull(it, "load.kw[]") }
        val level = if (load.opt("level") === JSONObject.NULL) null else list(load.opt("level"), "load.level").map { numberOrNull(it, "load.level[]") ?: 0.0 }
        if (kw.size != count || (level != null && level.size != count)) bad("load.kw")
        val summary = obj(load.opt("summary"), "load.summary")
        Plan.Load(
            id = text(load.opt("id"), "load.id"),
            kind = text(load.opt("kind"), "load.kind"),
            name = textOrNull(load.opt("name"), "load.name"),
            would = text(load.opt("state"), "load.state") == "would",
            inHouse = flag(load.opt("in_house"), "load.in_house"),
            kw = kw,
            level = level,
            runs = list(load.opt("runs"), "load.runs").map { item ->
                val run = obj(item, "run")
                Plan.Run(
                    start = instant(run.opt("start"), "run.start"),
                    end = instant(run.opt("end"), "run.end"),
                    what = text(run.opt("what"), "run.what"),
                    reason = textOrNull(run.opt("reason"), "run.reason"),
                    kw = numberOrNull(run.opt("kw"), "run.kw"),
                    kwh = numberOrNull(run.opt("kwh"), "run.kwh")
                )
            },
            // Only the figures: a departure or another text in the summary is not drawn from here.
            summary = summary.keys().asSequence().mapNotNull { key -> (summary.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }?.let { key to it } }.toMap(),
            doesNotFit = DoesNotFit.parse(load.opt("does_not_fit")),
            summaryText = summary.keys().asSequence().mapNotNull { key -> (summary.opt(key) as? String)?.let { key to it } }.toMap()
        )
    }
}
