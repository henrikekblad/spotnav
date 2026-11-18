package se.sensnology.spotnav.ha.house

import org.json.JSONObject
import se.sensnology.spotnav.ha.client.WriteEnvelope
import java.time.LocalDate
import java.time.ZoneId

/**
 * One day, month or year of the house (webhook `get_house_history`, the twin of `spotnav/get_house_history`,
 * `api_version` 1), as far as the Graf and the Pris och batteri tabs read it: the buckets (an hour of a day, a day of a
 * month, a month of a year) with their energy, what the battery earned and the mean prices. The blocks behind the
 * details sheet (`cost`, `review`, `saving`, `own_control`, `forecast`, the buckets' savings) are not read here yet.
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
    val buckets: List<Bucket>
) {
    enum class HistoryKind(val key: String) { DAY("day"), MONTH("month"), YEAR("year") }

    /**
     * What the battery's moves in a bucket came to: the energy given back, what it was worth, what it cost out of the
     * stock, and the energy charged with its cost (kWh and the minor unit).
     */
    data class BatteryFlow(val discharged: Double, val worth: Double, val cost: Double, val charged: Double, val chargeCost: Double)

    /** What the electricity of the period cost (minor unit): bought, and sold (a sale is negative cost) and how many periods were priced. */
    data class Cost(val bought: Double, val sold: Double, val priced: Int)

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
        val sell: Double?
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
                HouseHistory.Cost(number(it.opt("bought"), "cost.bought"), number(it.opt("sold"), "cost.sold"), count(it.opt("priced"), "cost.priced"))
            },
            buckets = list(json.opt("buckets"), "buckets").map(::bucket)
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
            sell = later("sell")
        )
    }
}
