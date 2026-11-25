package se.sensnology.spotnav.ha.house

import org.json.JSONObject
import se.sensnology.spotnav.ha.client.WriteEnvelope
import java.time.LocalDate
import java.time.ZoneId

/**
 * One day, month or year of the house (webhook `get_house_history`, the twin of `spotnav/get_house_history`,
 * `api_version` 1), as far as the boxes read it: the energy flow (Energiflöde) and the buckets (an hour of a day, a day of a
 * month, a month of a year) with their energy, what the battery earned and the mean prices. The blocks behind the
 * details sheet and the Intäkt box (`cost`, `review`, `saving`, `own_control`, `forecast`, the buckets' savings) are read
 * too; the keys the card requires are required here, the later ones (`saving`, `own_control`, a bucket's savings) are
 * additive, and a key not known is not looked at.
 * Shape: the integration's `site/house_period.py`; the card's reader is `frontend/src/house-history.ts`.
 */
internal data class HouseHistory(
    val siteId: String,
    val zone: ZoneId,
    val period: HistoryKind,
    /** The first day of the period asked for. */
    val date: LocalDate,
    val end: LocalDate,
    /** The site's local date when the answer was made. */
    val today: LocalDate,
    val complete: Boolean,
    val money: House.Money?,
    val cost: Cost?,
    val buckets: List<Bucket>,
    /** The energy flow over the period; `null` where the site has no meter to build it from. */
    val energyFlow: House.EnergyFlow?,
    /** The battery's review of the period's days; `null` without one. */
    val review: Review? = null,
    val forecast: Forecast = Forecast(null, null, null, null),
    /** What SpotNav saved over the period; `null` without any part and from a Home Assistant before it. */
    val saving: Saving? = null,
    /** Before SpotNav controlled the battery: what its own control earned and the plan would have; `null` otherwise. */
    val ownControl: OwnControl? = null
) {
    /** The period holds today: it is still running. */
    val running: Boolean get() = today >= date && today < end


    enum class HistoryKind(val key: String) { DAY("day"), MONTH("month"), YEAR("year") }

    /**
     * What the battery's moves in a bucket came to: the energy given back, what it was worth, what it cost out of the
     * stock, and the energy charged with its cost (kWh and the minor unit).
     */
    data class BatteryFlow(val discharged: Double, val worth: Double, val cost: Double, val charged: Double, val chargeCost: Double)

    /**
     * What the electricity of the period cost (minor unit): [bought], [sold] (what a sale brought) and the [net] of both,
     * the energy each way (kWh), and how many of the period's [periods] were [priced].
     */
    data class Cost(
        val bought: Double, val sold: Double, val priced: Int,
        val net: Double = bought - sold, val kwhIn: Double = 0.0, val kwhOut: Double = 0.0, val periods: Int = priced
    )

    /** A bucket; [key] is the local hour (`2026-11-15T12:00:00+01:00`), the day (`2026-11-16`) or the month (`2026-07`). */
    data class Bucket(
        val key: String,
        val house: Double,
        /** `null` where the site has no heat meter. */
        val heat: Double?,
        val car: Double,
        /** The sun's energy, `null` without a sun. */
        val solar: Double?,
        val use: Double,
        /** How many of the bucket's periods were measured: 0 is nothing measured. */
        val periods: Int,
        /** What the battery earned (minor unit), `null` without a battery or where nothing is kept. */
        val arbitrage: Double?,
        val batteryFlow: BatteryFlow?,
        /** The mean buy and sell price (minor unit per kWh), `null` unpriced. */
        val buy: Double?,
        val sell: Double?,
        val gridIn: Double = 0.0,
        val gridOut: Double = 0.0,
        /** What the bucket's electricity cost (minor unit), `null` unpriced. */
        val cost: Double? = null,
        /** The battery's money over the bucket's reviewed days (negative: dearer), `null` with none. */
        val batterySaving: Double? = null,
        val batteryDays: Int = 0,
        /** What SpotNav saved in the bucket, in all and per part; `null` where none of its days has it. */
        val saving: Double? = null,
        val savingBattery: Double? = null,
        val savingCar: Double? = null,
        val savingHeat: Double? = null,
        /** What the battery earned under its own control, and what SpotNav's plan would have earned on the same days. */
        val ownBattery: Double? = null,
        val wouldBattery: Double? = null
    )

    /** What SpotNav saved over the period, each part over the days that have it, with those days counted. */
    data class Saving(
        val total: Double, val battery: Double?, val car: Double?, val heat: Double?,
        val batteryDays: Int, val carDays: Int, val heatDays: Int, val days: Int,
        /** The days SpotNav controlled the car on (a charge it started); `null` from a Home Assistant before it. With
         * none, the car's 0 is a car SpotNav did not control. */
        val carControlledDays: Int? = null
    )

    /** What the battery's own control earned over the period's such days, and what SpotNav's plan would have (`null`: none reviewed). */
    data class OwnControl(val earned: Double, val would: Double?, val days: Int, val wouldDays: Int)

    data class ForecastError(val mae: Double, val days: Int)

    data class Forecast(val house: ForecastError?, val sun: ForecastError?, val sunCorrected: ForecastError?, val soc: ForecastError?)

    data class ModeFigure(val saving: Double?, val days: Int)

    /** The battery's work over reviewed days. */
    data class Work(val cycles: Double, val switches: Int, val gridChargeKwh: Double, val toGridKwh: Double)

    data class CellTemperature(val days: Int, val meanC: Double, val maxC: Double, val minutesAbove: Int)

    /**
     * The days against their perfect plans (`site/perfect_view.py`): each way's share of the perfect plan's saving
     * (`null`: it did not run or the difference was too small), the saving behind the shares, the battery's work.
     * The ways are `own`, `self_consumption`, `balanced`, `max_profit`; [work] also has `perfect`.
     */
    data class Perfect(
        val days: Int, val provisional: Int, val partial: Int, val mode: String?, val rules: String?, val saving: Double?,
        val shares: Map<String, Double?>, val savings: Map<String, Double?>, val clamped: List<String>,
        val work: Map<String, Work?>, val temperature: CellTemperature?
    )

    data class ReviewDay(
        val date: LocalDate, val batterySaving: Double?, val planSaving: Double, val ownSaving: Double,
        /**
         * The day's share of its perfect plan; `null` without one, with [shareNote] (`partial` or `small`) saying why;
         * [shareNote] `loss`: the battery lost money against no battery that day.
         */
        val ownShare: Double?, val planShare: Double?, val shareNote: String?,
        /** The day's ways against the same day with no battery, the energy left at midnight counted: what really
         * happened, SpotNav's plan in the site's mode (simulated) and the perfect plan; `null` without one. */
        val realVsNone: Double? = null,
        val planVsNone: Double? = null,
        val perfectVsNone: Double? = null,
        /** The share of the day SpotNav controlled the battery (0 to 1), `null` where not known. */
        val controlled: Double? = null
    )

    /** A year's month of reviewed days (`review.by_month`): the day figures summed over its [days] reviewed of
     * [monthDays] (its days before today), its share of the perfect plan over its whole days. [month] is `2026-11`. */
    data class ReviewMonth(
        val month: String, val days: Int, val monthDays: Int,
        val realVsNone: Double?, val planVsNone: Double?, val perfectVsNone: Double?,
        val ownShare: Double?, val shareNote: String?, val controlled: Double?
    )

    data class Review(
        val days: Int, val periodDays: Int, val archived: Int, val actualCost: Double,
        val batterySaving: Double?, val batteryDays: Int, val sunGave: Double?, val sunDays: Int,
        val againstNothing: Double?, val nothingDays: Int, val planSaving: Double, val ownSaving: Double,
        val modes: Map<String, ModeFigure>, val mode: String?, val byDay: List<ReviewDay>, val perfect: Perfect?,
        /** A year's months; `null` for a day or a month, and from a Home Assistant before it. */
        val byMonth: List<ReviewMonth>? = null
    )
}

internal object HouseHistoryRead {
    const val API_VERSION = 1

    fun payload(period: HouseHistory.HistoryKind, date: LocalDate? = null): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", "get_house_history")
        put("api_version", API_VERSION)
        put("period", period.key)
        date?.let { put("date", it.toString()) }
    }

    fun answer(status: Int?, body: String?): SiteRead<HouseHistory> {
        if (status == null) return SiteRead.Failed(null)
        val head = WriteEnvelope.head(body) ?: return SiteRead.Failed(null)
        if (!head.ok) return SiteRead.Failed(head.error)
        return decode(head.json)
    }

    /** The document itself, as the webhook and the WebSocket both send it. */
    fun decode(json: JSONObject): SiteRead<HouseHistory> {
        val version = json.opt("api_version")
        if (version != API_VERSION) return if (version is Number) SiteRead.Unsupported else SiteRead.Failed(null)
        return runCatching { SiteRead.Read(document(json)) }.getOrElse { SiteRead.Failed(null) }
    }

    private fun document(json: JSONObject): HouseHistory = with(StrictJson) {
        HouseHistory(
            siteId = text(json.opt("site_id"), "site_id"),
            zone = runCatching { ZoneId.of(text(json.opt("time_zone"), "time_zone")) }.getOrElse { bad("time_zone") },
            period = when (text(json.opt("period"), "period")) {
                "day" -> HouseHistory.HistoryKind.DAY
                "month" -> HouseHistory.HistoryKind.MONTH
                "year" -> HouseHistory.HistoryKind.YEAR
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
            cost = objOrNull(json.opt("cost"), "cost")?.let {
                HouseHistory.Cost(
                    bought = number(it.opt("bought"), "cost.bought"), sold = number(it.opt("sold"), "cost.sold"), priced = count(it.opt("priced"), "cost.priced"),
                    net = number(it.opt("net"), "cost.net"), kwhIn = number(it.opt("kwh_in"), "cost.kwh_in"),
                    kwhOut = number(it.opt("kwh_out"), "cost.kwh_out"), periods = count(it.opt("periods"), "cost.periods")
                )
            },
            buckets = list(json.opt("buckets"), "buckets").map(::bucket),
            energyFlow = objOrNull(json.opt("energy_flow"), "energy_flow")?.let(HouseRead::energyFlow),
            review = objOrNull(json.opt("review"), "review")?.let(::review),
            forecast = obj(json.opt("forecast"), "forecast").let {
                HouseHistory.Forecast(
                    house = forecastError(it.opt("house"), "house"), sun = forecastError(it.opt("sun"), "sun"),
                    sunCorrected = forecastError(it.opt("sun_corrected"), "sun_corrected"), soc = forecastError(it.opt("soc"), "soc")
                )
            },
            saving = additive(json, "saving", null) { raw ->
                objOrNull(raw, "saving")?.let {
                    HouseHistory.Saving(
                        total = number(it.opt("total"), "saving.total"), battery = numberOrNull(it.opt("battery"), "saving.battery"),
                        car = numberOrNull(it.opt("car"), "saving.car"), heat = numberOrNull(it.opt("heat"), "saving.heat"),
                        batteryDays = count(it.opt("battery_days"), "saving.battery_days"), carDays = count(it.opt("car_days"), "saving.car_days"),
                        heatDays = count(it.opt("heat_days"), "saving.heat_days"), days = count(it.opt("days"), "saving.days"),
                        carControlledDays = additive(it, "car_controlled_days", null) { raw -> count(raw, "saving.car_controlled_days") }
                    )
                }
            },
            ownControl = additive(json, "own_control", null) { raw ->
                objOrNull(raw, "own_control")?.let {
                    HouseHistory.OwnControl(
                        earned = number(it.opt("earned"), "own_control.earned"), would = numberOrNull(it.opt("would"), "own_control.would"),
                        days = count(it.opt("days"), "own_control.days"), wouldDays = count(it.opt("would_days"), "own_control.would_days")
                    )
                }
            }
        )
    }

    private fun forecastError(raw: Any?, what: String): HouseHistory.ForecastError? = with(StrictJson) {
        objOrNull(raw, "forecast.$what")?.let { HouseHistory.ForecastError(number(it.opt("mae"), "forecast.$what.mae"), count(it.opt("days"), "forecast.$what.days")) }
    }

    /** Why a day has no share of its perfect plan, as far as the sheet has words for it. */
    private val SHARE_NOTES = setOf("partial", "small", "loss")

    private val MONTH = Regex("""\d{4}-\d{2}""")

    /** The ways a perfect plan is compared with: what happened, and the battery's modes. */
    private val WAYS = listOf("own", "self_consumption", "balanced", "max_profit")

    private fun review(json: JSONObject): HouseHistory.Review = with(StrictJson) {
        val modes = obj(json.opt("modes"), "review.modes").let { raw ->
            raw.keys().asSequence().associateWith { key ->
                obj(raw.opt(key), "review.modes.$key").let { HouseHistory.ModeFigure(numberOrNull(it.opt("saving"), "mode.saving"), count(it.opt("days"), "mode.days")) }
            }
        }
        HouseHistory.Review(
            days = count(json.opt("days"), "review.days"), periodDays = count(json.opt("period_days"), "review.period_days"),
            archived = count(json.opt("archived"), "review.archived"), actualCost = number(json.opt("actual_cost"), "review.actual_cost"),
            batterySaving = numberOrNull(json.opt("battery_saving"), "review.battery_saving"),
            batteryDays = count(json.opt("battery_days"), "review.battery_days"),
            sunGave = numberOrNull(json.opt("sun_gave"), "review.sun_gave"), sunDays = count(json.opt("sun_days"), "review.sun_days"),
            againstNothing = numberOrNull(json.opt("against_nothing"), "review.against_nothing"),
            nothingDays = count(json.opt("nothing_days"), "review.nothing_days"),
            planSaving = number(json.opt("plan_saving"), "review.plan_saving"), ownSaving = number(json.opt("own_saving"), "review.own_saving"),
            modes = modes, mode = textOrNull(json.opt("mode"), "review.mode"),
            byDay = list(json.opt("by_day"), "review.by_day").map { raw ->
                val day = obj(raw, "review.day")
                HouseHistory.ReviewDay(
                    date = day(day.opt("date"), "review.day.date"), batterySaving = numberOrNull(day.opt("battery_saving"), "day.battery_saving"),
                    planSaving = number(day.opt("plan_saving"), "day.plan_saving"), ownSaving = number(day.opt("own_saving"), "day.own_saving"),
                    ownShare = additive(day, "own_share", null) { numberOrNull(it, "day.own_share") },
                    planShare = additive(day, "plan_share", null) { numberOrNull(it, "day.plan_share") },
                    // A reason a newer Home Assistant names is shown as none.
                    shareNote = additive(day, "share_note", null) { textOrNull(it, "day.share_note")?.takeIf { note -> note in SHARE_NOTES } },
                    realVsNone = additive(day, "real_vs_none", null) { numberOrNull(it, "day.real_vs_none") },
                    planVsNone = additive(day, "plan_vs_none", null) { numberOrNull(it, "day.plan_vs_none") },
                    perfectVsNone = additive(day, "perfect_vs_none", null) { numberOrNull(it, "day.perfect_vs_none") },
                    controlled = additive(day, "controlled", null) { numberOrNull(it, "day.controlled") }
                )
            },
            byMonth = additive(json, "by_month", null) { raw ->
                if (raw == null || raw == JSONObject.NULL) null else list(raw, "review.by_month").map { entry ->
                    val month = obj(entry, "review.month")
                    HouseHistory.ReviewMonth(
                        month = text(month.opt("month"), "month.month").also { if (!MONTH.matches(it)) bad("month.month") },
                        days = count(month.opt("days"), "month.days"), monthDays = count(month.opt("month_days"), "month.month_days"),
                        realVsNone = numberOrNull(month.opt("real_vs_none"), "month.real_vs_none"),
                        planVsNone = numberOrNull(month.opt("plan_vs_none"), "month.plan_vs_none"),
                        perfectVsNone = numberOrNull(month.opt("perfect_vs_none"), "month.perfect_vs_none"),
                        ownShare = numberOrNull(month.opt("own_share"), "month.own_share"),
                        // A reason a newer Home Assistant names is shown as none.
                        shareNote = textOrNull(month.opt("share_note"), "month.share_note")?.takeIf { it in SHARE_NOTES },
                        controlled = numberOrNull(month.opt("controlled"), "month.controlled")
                    )
                }
            },
            perfect = additive(json, "perfect", null) { objOrNull(it, "review.perfect")?.let(::perfect) }
        )
    }

    private fun perfect(json: JSONObject): HouseHistory.Perfect = with(StrictJson) {
        fun <T> perWay(raw: Any?, what: String, ways: List<String>, read: (Any?) -> T): Map<String, T> =
            obj(raw, what).let { from -> ways.associateWith { way -> if (from.has(way)) read(from.opt(way)) else bad("$what.$way") } }
        fun work(raw: Any?): HouseHistory.Work? = objOrNull(raw, "perfect.work")?.let {
            HouseHistory.Work(
                cycles = number(it.opt("cycles"), "work.cycles"), switches = count(it.opt("switches"), "work.switches"),
                gridChargeKwh = number(it.opt("grid_charge_kwh"), "work.grid_charge_kwh"), toGridKwh = number(it.opt("to_grid_kwh"), "work.to_grid_kwh")
            )
        }
        HouseHistory.Perfect(
            days = count(json.opt("days"), "perfect.days"), provisional = count(json.opt("provisional"), "perfect.provisional"),
            partial = count(json.opt("partial"), "perfect.partial"), mode = textOrNull(json.opt("mode"), "perfect.mode"),
            rules = textOrNull(json.opt("rules"), "perfect.rules"), saving = numberOrNull(json.opt("saving"), "perfect.saving"),
            shares = perWay(json.opt("shares"), "perfect.shares", WAYS) { numberOrNull(it, "perfect.share") },
            savings = perWay(json.opt("savings"), "perfect.savings", WAYS) { numberOrNull(it, "perfect.savings") },
            clamped = list(json.opt("clamped"), "perfect.clamped").map { text(it, "perfect.clamped") },
            work = perWay(json.opt("work"), "perfect.work", WAYS + "perfect", ::work),
            temperature = objOrNull(json.opt("temperature"), "perfect.temperature")?.let {
                HouseHistory.CellTemperature(
                    days = count(it.opt("days"), "temperature.days"), meanC = number(it.opt("mean_c"), "temperature.mean_c"),
                    maxC = number(it.opt("max_c"), "temperature.max_c"), minutesAbove = count(it.opt("minutes_above"), "temperature.minutes_above")
                )
            }
        )
    }

    private fun bucket(raw: Any?): HouseHistory.Bucket = with(StrictJson) {
        val json = obj(raw, "bucket")
        fun later(key: String): Double? = additive(json, key, null) { numberOrNull(it, "bucket.$key") }
        HouseHistory.Bucket(
            key = text(json.opt("key"), "bucket.key"),
            house = number(json.opt("house"), "bucket.house"),
            heat = numberOrNull(json.opt("heat"), "bucket.heat"),
            car = number(json.opt("car"), "bucket.car"),
            solar = numberOrNull(json.opt("solar"), "bucket.solar"),
            use = number(json.opt("use"), "bucket.use"),
            periods = count(json.opt("periods"), "bucket.periods"),
            arbitrage = later("arbitrage"),
            batteryFlow = additive(json, "battery_flow", null) { value ->
                objOrNull(value, "bucket.battery_flow")?.let {
                    HouseHistory.BatteryFlow(
                        discharged = number(it.opt("discharged"), "battery_flow.discharged"),
                        worth = number(it.opt("worth"), "battery_flow.worth"),
                        cost = number(it.opt("cost"), "battery_flow.cost"),
                        charged = number(it.opt("charged"), "battery_flow.charged"),
                        chargeCost = number(it.opt("charge_cost"), "battery_flow.charge_cost")
                    )
                }
            },
            buy = later("buy"),
            sell = later("sell"),
            gridIn = number(json.opt("grid_in"), "bucket.grid_in"),
            gridOut = number(json.opt("grid_out"), "bucket.grid_out"),
            cost = numberOrNull(json.opt("cost"), "bucket.cost"),
            batterySaving = numberOrNull(json.opt("battery_saving"), "bucket.battery_saving"),
            batteryDays = count(json.opt("battery_days"), "bucket.battery_days"),
            saving = later("saving"),
            savingBattery = later("saving_battery"),
            savingCar = later("saving_car"),
            savingHeat = later("saving_heat"),
            ownBattery = later("own_battery"),
            wouldBattery = later("would_battery")
        )
    }
}
