package se.sensnology.spotnav.ha.house

import org.json.JSONObject
import se.sensnology.spotnav.ha.client.WriteEnvelope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The house (webhook `get_house`, the twin of `spotnav/get_house`, `api_version` 1), as far as the house page reads
 * it: the hours with the battery plan, the battery, the day's money and energy flow, and what is being learned. The
 * blocks behind the details (`house`, `days`, `calendar`, `day_review`, `sources`, `flow`, the battery's `modes` and
 * `review`) are not read here yet. Shape: the integration's `site/house_view.py`; the card's reader is
 * `frontend/src/house-model.ts`.
 */
internal data class House(
    val siteId: String,
    val zone: ZoneId,
    val generatedAt: Instant,
    val money: Money?,
    val hours: List<Hour>,
    /** `null` without a sun forecast. */
    val sun: Sun?,
    val learning: List<LearningHold>,
    val battery: Battery?,
    val today: TodayMoney?,
    val energyFlow: EnergyFlow?,
    val day: ShownDay?,
    val heat: Heat?,
    val saving: SavingDay?
) {
    /** The price area's money: amounts come in the minor unit. */
    data class Money(val currency: String?, val majorUnit: String?, val minorUnit: String?)

    data class Hour(
        val start: Instant,
        val houseKw: Double?,
        val houseForecastKw: Double?,
        val sunKwh: Double?,
        val sunForecastKwh: Double?,
        val sunCorrectedKwh: Double?,
        val socPct: Double?,
        val socPlanPct: Double?,
        /** `grid_charge`, `export_discharge` or `hold`; `null` for none and for one a newer Home Assistant names. */
        val batteryAction: String?,
        val buy: Double?,
        val sell: Double?,
        val heatKw: Double?,
        val heatForecastKw: Double?,
        val carKw: Double?
    )

    data class Sun(val status: String, val daysLeft: Int?)

    /** A learning period that holds something back now ([what] is open: one not known is not shown). */
    data class LearningHold(val what: String, val daysLeft: Int, val capKw: Double)

    data class Battery(
        val brand: String?,
        val label: String?,
        val socPct: Double?,
        val usableKwh: Double?,
        val management: String,
        /** `off`, `shadow_only`, `verifying`, `waiting`, `on`, `failed`; only `on` is SpotNav controlling. */
        val control: String?,
        val status: String,
        val reason: String?,
        val mode: String,
        val madeAt: Instant?,
        val horizonEnd: Instant?,
        val now: Now?
    ) {
        /** The action SpotNav's control runs (`null`: none) and the measured power (kW, + charging). */
        data class Now(val action: String?, val powerKw: Double?)
    }

    /** The day's money in the minor unit. */
    data class TodayMoney(
        val date: LocalDate,
        val sold: Double,
        val arbitrage: Double,
        val bought: Double,
        val net: Double,
        val mode: String,
        val storedKwh: Double?,
        val averageCost: Double?,
        val estimated: Boolean
    )

    /** What SpotNav saved on the shown day (minor units); a part is `null` where the site does not have it. */
    data class SavingDay(
        val date: LocalDate,
        val total: Double?,
        val battery: Double?,
        val car: Double?,
        val heat: Double?,
        val batteryShare: Double?,
        val carSince: LocalDate?
    )

    data class FlowNode(val id: String, val source: Boolean, val role: String, val kwh: Double, val socPct: Double?)

    data class FlowLink(val from: String, val to: String, val kwh: Double)

    /** The day so far as sources and uses (`site/energy_flow.py`). */
    data class EnergyFlow(
        val date: LocalDate,
        val through: Instant,
        val hours: Int,
        val stepMin: Int,
        val carHours: Int,
        val heatHours: Int,
        val heatSources: Int,
        val nodes: List<FlowNode>,
        val links: List<FlowLink>
    ) {
        /** The energy of the node with [role] on the source or the use side, `null` without one. */
        fun kwh(source: Boolean, role: String): Double? = nodes.firstOrNull { it.source == source && it.role == role }?.kwh
    }

    data class ShownDay(val date: LocalDate, val today: Boolean, val first: LocalDate, val hasData: Boolean)

    /** The heat pump's meter: `found`, `ambiguous`, `unusable` or a newer word. */
    data class Heat(val status: String, val name: String?)
}

internal object HouseRead {
    const val API_VERSION = 1

    /** [date]: a past day; `null` asks for today. */
    fun payload(date: LocalDate? = null): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", "get_house")
        put("api_version", API_VERSION)
        date?.let { put("date", it.toString()) }
    }

    fun answer(status: Int?, body: String?): SiteRead<House> {
        if (status == null) return SiteRead.Failed(null)
        val head = WriteEnvelope.head(body) ?: return SiteRead.Failed(null)
        if (!head.ok) return SiteRead.Failed(head.error)
        return decode(head.json)
    }

    /** The document itself, as the webhook and the WebSocket both send it. */
    fun decode(json: JSONObject): SiteRead<House> {
        val version = json.opt("api_version")
        if (version != API_VERSION) return if (version is Number) SiteRead.Unsupported else SiteRead.Failed(null)
        return runCatching { SiteRead.Read(document(json)) }.getOrElse { SiteRead.Failed(null) }
    }

    private val ACTIONS = setOf("grid_charge", "export_discharge", "hold")

    private fun document(json: JSONObject): House = with(StrictJson) {
        val hours = list(json.opt("hours"), "hours").map(::hour)
        if (hours.isEmpty()) bad("hours")
        House(
            siteId = text(json.opt("site_id"), "site_id"),
            zone = runCatching { ZoneId.of(text(json.opt("time_zone"), "time_zone")) }.getOrElse { bad("time_zone") },
            generatedAt = instant(json.opt("generated_at"), "generated_at"),
            money = objOrNull(json.opt("money"), "money")?.let {
                House.Money(
                    currency = textOrNull(it.opt("currency"), "money.currency"),
                    majorUnit = textOrNull(it.opt("major_unit"), "money.major_unit"),
                    minorUnit = textOrNull(it.opt("minor_unit"), "money.minor_unit")
                )
            },
            hours = hours,
            sun = objOrNull(json.opt("sun"), "sun")?.let {
                House.Sun(
                    status = text(it.opt("status"), "sun.status"),
                    daysLeft = additive(it, "days_left", null) { raw -> if (raw === JSONObject.NULL) null else count(raw, "sun.days_left") }
                )
            },
            learning = list(json.opt("learning"), "learning").map { entry ->
                val hold = obj(entry, "learning")
                House.LearningHold(
                    what = text(hold.opt("what"), "learning.what"),
                    daysLeft = count(hold.opt("days_left"), "learning.days_left"),
                    capKw = number(hold.opt("cap_kw"), "learning.cap_kw")
                )
            },
            battery = objOrNull(json.opt("battery"), "battery")?.let(::battery),
            today = objOrNull(json.opt("today"), "today")?.let(::today),
            energyFlow = objOrNull(json.opt("energy_flow"), "energy_flow")?.let(::energyFlow),
            day = objOrNull(json.opt("day"), "day")?.let {
                House.ShownDay(
                    date = day(it.opt("date"), "day.date"),
                    today = flag(it.opt("today"), "day.today"),
                    first = day(it.opt("first"), "day.first"),
                    hasData = flag(it.opt("has_data"), "day.has_data")
                )
            },
            heat = objOrNull(json.opt("heat"), "heat")?.let {
                House.Heat(status = text(it.opt("status"), "heat.status"), name = textOrNull(it.opt("name"), "heat.name"))
            },
            saving = objOrNull(json.opt("saving"), "saving")?.let(::saving)
        )
    }

    private fun hour(raw: Any?): House.Hour = with(StrictJson) {
        val json = obj(raw, "hour")
        House.Hour(
            start = instant(json.opt("start"), "hour.start"),
            houseKw = numberOrNull(json.opt("house_kw"), "hour.house_kw"),
            houseForecastKw = numberOrNull(json.opt("house_forecast_kw"), "hour.house_forecast_kw"),
            sunKwh = numberOrNull(json.opt("sun_kwh"), "hour.sun_kwh"),
            sunForecastKwh = numberOrNull(json.opt("sun_forecast_kwh"), "hour.sun_forecast_kwh"),
            sunCorrectedKwh = numberOrNull(json.opt("sun_corrected_kwh"), "hour.sun_corrected_kwh"),
            socPct = numberOrNull(json.opt("soc_pct"), "hour.soc_pct"),
            socPlanPct = numberOrNull(json.opt("soc_plan_pct"), "hour.soc_plan_pct"),
            // An action a newer Home Assistant names is drawn as none.
            batteryAction = textOrNull(json.opt("battery_action"), "hour.battery_action")?.takeIf { it in ACTIONS },
            buy = numberOrNull(json.opt("buy"), "hour.buy"),
            sell = numberOrNull(json.opt("sell"), "hour.sell"),
            heatKw = numberOrNull(json.opt("heat_kw"), "hour.heat_kw"),
            heatForecastKw = numberOrNull(json.opt("heat_forecast_kw"), "hour.heat_forecast_kw"),
            carKw = numberOrNull(json.opt("car_kw"), "hour.car_kw")
        )
    }

    private fun battery(json: JSONObject): House.Battery = with(StrictJson) {
        House.Battery(
            brand = textOrNull(json.opt("brand"), "battery.brand"),
            label = textOrNull(json.opt("label"), "battery.label"),
            socPct = numberOrNull(json.opt("soc_pct"), "battery.soc_pct"),
            usableKwh = numberOrNull(json.opt("usable_kwh"), "battery.usable_kwh"),
            management = text(json.opt("management"), "battery.management"),
            control = textOrNull(json.opt("control"), "battery.control"),
            status = text(json.opt("status"), "battery.status"),
            reason = textOrNull(json.opt("reason"), "battery.reason"),
            mode = text(json.opt("mode"), "battery.mode"),
            madeAt = instantOrNull(json.opt("made_at"), "battery.made_at"),
            horizonEnd = instantOrNull(json.opt("horizon_end"), "battery.horizon_end"),
            now = objOrNull(json.opt("now"), "battery.now")?.let {
                House.Battery.Now(
                    action = textOrNull(it.opt("action"), "battery.now.action"),
                    powerKw = numberOrNull(it.opt("power_kw"), "battery.now.power_kw")
                )
            }
        )
    }

    private fun today(json: JSONObject): House.TodayMoney = with(StrictJson) {
        House.TodayMoney(
            date = day(json.opt("date"), "today.date"),
            sold = number(json.opt("sold"), "today.sold"),
            arbitrage = number(json.opt("arbitrage"), "today.arbitrage"),
            bought = number(json.opt("bought"), "today.bought"),
            net = number(json.opt("net"), "today.net"),
            mode = text(json.opt("mode"), "today.mode"),
            storedKwh = numberOrNull(json.opt("stored_kwh"), "today.stored_kwh"),
            averageCost = numberOrNull(json.opt("average_cost"), "today.average_cost"),
            estimated = flag(json.opt("estimated"), "today.estimated")
        )
    }

    private fun saving(json: JSONObject): House.SavingDay = with(StrictJson) {
        House.SavingDay(
            date = day(json.opt("date"), "saving.date"),
            total = numberOrNull(json.opt("total"), "saving.total"),
            battery = numberOrNull(json.opt("battery"), "saving.battery"),
            car = numberOrNull(json.opt("car"), "saving.car"),
            heat = numberOrNull(json.opt("heat"), "saving.heat"),
            batteryShare = numberOrNull(json.opt("battery_share"), "saving.battery_share"),
            carSince = dayOrNull(json.opt("car_since"), "saving.car_since")
        )
    }

    internal fun energyFlow(json: JSONObject): House.EnergyFlow = with(StrictJson) {
        fun kwh(raw: Any?, what: String): Double = number(raw, what).also { if (it < 0) bad(what) }
        val nodes = list(json.opt("nodes"), "energy_flow.nodes").map { entry ->
            val node = obj(entry, "energy_flow.node")
            House.FlowNode(
                id = text(node.opt("id"), "node.id"),
                source = when (text(node.opt("side"), "node.side")) {
                    "source" -> true
                    "use" -> false
                    else -> bad("node.side")
                },
                role = text(node.opt("role"), "node.role"),
                kwh = kwh(node.opt("kwh"), "node.kwh"),
                socPct = numberOrNull(node.opt("soc_pct"), "node.soc_pct")
            )
        }
        val ids = nodes.map { it.id }.toSet()
        val links = list(json.opt("links"), "energy_flow.links").map { entry ->
            val link = obj(entry, "energy_flow.link")
            House.FlowLink(text(link.opt("from"), "link.from"), text(link.opt("to"), "link.to"), kwh(link.opt("kwh"), "link.kwh"))
        }
        House.EnergyFlow(
            date = day(json.opt("date"), "energy_flow.date"),
            through = instant(json.opt("through"), "energy_flow.through"),
            hours = count(json.opt("hours"), "energy_flow.hours"),
            stepMin = count(json.opt("step_min"), "energy_flow.step_min"),
            carHours = count(json.opt("car_hours"), "energy_flow.car_hours"),
            heatHours = count(json.opt("heat_hours"), "energy_flow.heat_hours"),
            heatSources = count(json.opt("heat_sources"), "energy_flow.heat_sources"),
            nodes = nodes,
            // A link to a node the answer does not have is not drawn.
            links = links.filter { it.from in ids && it.to in ids }
        )
    }
}
