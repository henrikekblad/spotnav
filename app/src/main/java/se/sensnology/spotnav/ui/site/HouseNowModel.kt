package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.app.noMinusZero
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.HouseNow
import se.sensnology.spotnav.ui.common.CardWords
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** A node around the house in the Now drawing. [key] is the card's word for it. */
internal enum class NodeRole(val key: String) { SUN("sun"), GRID("grid"), BATTERY("battery"), CAR("car") }

/** A part of the house in its ring, in this order. [key] is the card's word for it. */
internal enum class PartRole(val key: String) { HEAT("heat"), POOL("pool"), OTHER("other") }

/** Which way the power runs on a line: towards the house, away from it, or not at all. */
internal enum class Way(val key: String) { IN("in"), OUT("out"), STILL("still") }

/** What a figure or a line is coloured as: a part's own colour, an import or an export (the grid is its import's). */
internal enum class Tone { SUN, BATTERY, CAR, HEAT, GRID, IMPORT, EXPORT, HOUSE, POOL, OTHER }

/** One line of the drawing: its power as written (kW, never negative; `null` unread), its way, its colour, and whether
 * Home Assistant estimated it ("≈" before it). */
internal data class NowLine(val role: NodeRole, val kw: Double?, val way: Way, val tone: Tone, val estimated: Boolean = false)

/** One part of the house in its ring, as written. */
internal data class NowPart(val role: PartRole, val kw: Double, val estimated: Boolean)

/** The lines, the whole house and its parts as written: tenths that add up at the house and in its ring. */
internal data class NowFigures(
    val lines: List<NowLine>,
    /** The whole house, `null` unread. */
    val houseKw: Double?,
    val houseEstimated: Boolean,
    /** The house's parts, adding up to the house (none without its figure). */
    val parts: List<NowPart>
)

/** Which parts the site has. */
internal data class Present(val sun: Boolean, val battery: Boolean, val car: Boolean, val heat: Boolean, val pool: Boolean = false)

/** The running hour's price band among the plan's priced hours: the cheapest third, the middle, the most expensive third. */
internal enum class PriceBand(val key: String) { CHEAP("cheap"), MID("mid"), DEAR("dear") }

/** The running hour's buy price (minor unit per kWh) and its band. */
internal data class PriceNow(val minor: Double, val band: PriceBand)

/** The own share's tone: high from 80 %, mid from 40 %, else low. */
internal enum class OwnTone { HIGH, MID, LOW }

/** The headline row: the own share's pill, the price's pill and one sentence; each `null` where there is none. */
internal data class Headline(
    val own: String?,
    val ownTone: OwnTone?,
    val price: String?,
    val band: PriceBand?,
    val why: String?
)

/** A node's two captions: its state now, and today's kWh in its colour. */
internal data class Captions(val state: String, val today: String)

/** The Now box's live part, ready to draw: the headline, the drawing's figures and words, the ring's legend. */
internal data class NowPicture(
    val present: Present,
    val figures: NowFigures,
    val headline: Headline,
    val captions: Map<NodeRole, Captions>,
    /** Each flowing line's figure ("≈ 0,6 kW"). */
    val labels: Map<NodeRole, String>,
    /** The house's figure in the middle ("≈ 2,0 kW", "–" unread) and its name under it. */
    val houseFigure: String,
    val houseName: String,
    /** The house's day under its ring. */
    val houseToday: String,
    /** The battery's level for its ring and icon; `null` unknown. */
    val socPct: Double?,
    /** The ring's legend: each part with its figure ("Värme ≈ 1,8 kW"), parts of 0 left out. */
    val legend: List<Pair<PartRole, String>>,
    /** The drawing in words, for a screen reader. */
    val description: String
)

/** What the battery's plan does from an hour on: an action, or its own self-consumption (`self`). */
internal data class PlanRun(val act: String, val start: Instant)

/** What the house page's header says after the name. */
internal sealed interface HouseStatus {
    data class Said(val code: String, val text: String) : HouseStatus

    /** SpotNav controls nothing yet: "Test", with its explanation. */
    data object Test : HouseStatus
}

/**
 * The words and figures in the reader's language: the card's words ([CardWords]), day names in [locale], and numbers
 * as the rest of the screen writes them.
 */
internal class SiteText(val words: CardWords, val locale: Locale, private val numbers: Locale = locale) {
    fun fixed(value: Double, digits: Int): String = String.format(numbers, "%.${digits}f", noMinusZero(value, digits))

    /** At most [digits] decimals, without trailing zeros (the card's `formatNumber`: "8,25", "8,3", "8"). */
    fun upTo(value: Double, digits: Int): String {
        var shown = fixed(value, digits)
        val point = String.format(numbers, "%.1f", 0.5)[1]
        if (point in shown) shown = shown.trimEnd('0').trimEnd(point)
        return shown
    }

    /** At most one decimal, none for a whole figure ("1,4", "2"). */
    fun short(value: Double): String = fixed(value, 1).let { if (it.endsWith("0")) fixed(value, 0) else it }

    fun kw(kw: Double): String = "${fixed(kw, if (kw >= 10) 0 else 1)} kW"

    fun kwh(kwh: Double): String = "${bare(kwh)} kWh"

    /** A figure without its unit ("0,1"). */
    fun bare(kwh: Double): String = fixed(kwh, if (kwh >= 100) 0 else 1)

    fun percent(value: Double): String = "${fixed(value, 0)} %"

    /** Money given in the minor unit, shown in the major one: two decimals below 10 (exactly zero is "0"), whole units from 10 up (negatives by size). */
    fun amount(minor: Double, money: House.Money?): String {
        val major = minor / 100.0
        val unit = money?.majorUnit
        if (unit == null) {
            val small = money?.minorUnit?.takeIf { it.isNotEmpty() }
            return fixed(minor, 0) + (small?.let { " $it" } ?: "")
        }
        return "${fixed(major, if (abs(major) >= 10 || major == 0.0) 0 else 2)} $unit"
    }

    /** [amount], but a whole amount without its decimals: "−7 kr", not "−7,00 kr" (and never "−0 kr"); the card's `moneyTidy`. */
    fun tidy(minor: Double, money: House.Money?): String {
        val rounded = Math.round(minor)
        val unit = money?.majorUnit
        if (unit != null && rounded % 100 == 0L) return "${fixed(rounded / 100.0, 0)} $unit"
        return amount(minor, money)
    }

    /**
     * One rule for a column of amounts (minor unit), so its figures read alike: whole major units where any is 10 or
     * more ("24 kr", "0 kr"), else two decimals ("0,35 kr", "7,00 kr"); never "−0". The card's `moneyColumn`.
     */
    fun column(values: List<Double?>, money: House.Money?): (Double) -> String {
        val unit = money?.majorUnit ?: return { amount(it, money) }
        val decimals = if (values.any { it != null && abs(it) >= 1000 }) 0 else 2
        return { minor -> "${fixed(minor / 100.0, decimals)} $unit" }
    }

    fun clock(at: Instant, zone: ZoneId): String = CLOCK.format(at.atZone(zone))

    /** The day as a short cue ("lör 14"). */
    fun dayCue(at: Instant, zone: ZoneId): String = DateTimeFormatter.ofPattern("EEE d", locale).format(at.atZone(zone))

    private companion object {
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

/**
 * The house page's Now box, as the Home Assistant card's Now tab has it (`frontend/src/house-now.ts` and
 * `house-view.ts`): a headline of what is happening now (the own share, the price now, one sentence), the drawing's
 * lines, the house and its ring of parts with figures that add up as written, two captions at each node, the sentence
 * of what the battery does and does next, today's money, and the small notes. Pure: the view draws what this says.
 */
internal object HouseNowModel {
    /** Power below this (kW, either way) is drawn as no flow. */
    const val STILL_KW = 0.05

    /** Measured battery power below this (kW, either way) is the battery at rest. */
    private const val REST_KW = 0.1

    private const val HOUR_SECONDS = 3600L

    /** The battery brands whose own optimiser has a name. */
    private val OPTIMISERS = mapOf("sigenergy" to "Sigenergy AI")

    /** The battery's own optimiser's name for its brand, `null` where the card does not know one (the card's `ownOptimiser`). */
    fun optimiser(brand: String?): String? = OPTIMISERS[brand.orEmpty()]

    private val MODES = setOf("self_consumption", "balanced", "max_profit")

    /** Whether a car is plugged in: the live answer's word; unknown without it. */
    fun carPlugged(live: HouseNow?): Boolean = live?.carConnected == true

    /** Which nodes the site has: the live answer's word, else what the day's energy flow and the house show. */
    fun present(live: HouseNow?, house: House): Present {
        if (live != null) return Present(live.has.sun, live.has.battery, live.has.car, live.has.heat, live.has.pool)
        val roles = house.energyFlow?.nodes.orEmpty().map { it.role }.toSet()
        return Present(
            sun = house.sun != null || "solar" in roles,
            battery = house.battery != null,
            car = "car" in roles,
            heat = house.heat?.status == "found" || "heat" in roles
        )
    }

    fun tone(role: NodeRole): Tone = when (role) {
        NodeRole.SUN -> Tone.SUN
        NodeRole.GRID -> Tone.GRID
        NodeRole.BATTERY -> Tone.BATTERY
        NodeRole.CAR -> Tone.CAR
    }

    fun tone(role: PartRole): Tone = when (role) {
        PartRole.HEAT -> Tone.HEAT
        PartRole.POOL -> Tone.POOL
        PartRole.OTHER -> Tone.OTHER
    }

    private fun toneOf(role: NodeRole, way: Way): Tone = if (role == NodeRole.GRID) (if (way == Way.OUT) Tone.EXPORT else Tone.IMPORT) else tone(role)

    /**
     * Whole tenths for [values] (kW, never negative) that add up to [total] tenths: each rounded down, and the tenths
     * left given to the largest remainders (ties to the earlier), so the written figures add up as Home Assistant's do.
     */
    fun largestRemainder(values: List<Double>, total: Int): List<Int> {
        val scaled = values.map { maxOf(0.0, it) * 10 }
        val floors = scaled.map { Math.floor(it + 1e-9).toInt() }.toMutableList()
        var left = total - floors.sum()
        // Remainders compared to a millionth, so a float's last bit never breaks a tie.
        val order = scaled.indices.sortedWith(compareByDescending<Int> { Math.round((scaled[it] - floors[it]) * 1e6) }.thenBy { it })
        var step = 0
        while (left > 0 && order.isNotEmpty()) {
            floors[order[step % order.size]] += 1
            left -= 1
            step += 1
        }
        step = order.size - 1
        while (left < 0 && step >= 0) {
            val index = order[step]
            if (floors[index] > 0) {
                floors[index] -= 1
                left += 1
            }
            step -= 1
        }
        return floors
    }

    /** One flow at the house: into it or out of it (the house among those out), with its size (kW). */
    private data class Flow(val key: NodeRole?, val kw: Double, val into: Boolean)

    /** The flows in rounded to their sum's tenths, the flows out (the house among them) to the same. */
    private fun balancedTenths(flows: List<Flow>): Map<NodeRole?, Int> {
        val inflows = flows.filter { it.into }
        val outflows = flows.filter { !it.into }
        val sumIn = inflows.sumOf { it.kw }
        val sumOut = outflows.sumOf { it.kw }
        // Home Assistant's figures balance to the hundredth; one before it may not: each side then rounds alone.
        val meets = abs(sumIn - sumOut) < 0.011
        fun total(sum: Double) = Math.round(sum * 10 + 1e-9).toInt()
        val shown = HashMap<NodeRole?, Int>()
        fun place(side: List<Flow>, tenths: Int) = largestRemainder(side.map { it.kw }, tenths).forEachIndexed { index, value -> shown[side[index].key] = value }
        place(inflows, total(sumIn))
        place(outflows, if (meets) total(sumIn) else total(sumOut))
        return shown
    }

    private fun tenth(kw: Double): Double = Math.round(kw * 10) / 10.0

    /**
     * The lines, the house and its parts as written (the card's `nowFigures`): every node the site has has a line; the
     * lines are rounded to tenths that balance at the house, and the ring's parts to the house's tenths. Without the
     * live figures (or with one unread) each figure is rounded alone.
     */
    fun figures(live: HouseNow?, house: House): NowFigures {
        val has = present(live, house)
        val estimated = live?.estimated.orEmpty()
        // Each role's signed reading and whether + flows into the house.
        val readings = buildList {
            if (has.sun) add(Triple(NodeRole.SUN, live?.sunKw, true))
            add(Triple(NodeRole.GRID, live?.gridKw, true))
            // + charging: away from the house.
            if (has.battery) add(Triple(NodeRole.BATTERY, live?.batteryKw ?: house.battery?.now?.powerKw, false))
            // No car plugged in draws nothing, read or not (its charger may not be read yet after a restart).
            if (has.car) add(Triple(NodeRole.CAR, live?.carKw ?: (if (live?.carConnected == false) 0.0 else null), false))
        }
        val inward = readings.associate { (role, signed, inPositive) -> role to (signed != null && signed != 0.0 && (signed > 0) == inPositive) }
        val raw = readings.map { (role, signed, _) ->
            if (signed == null) {
                NowLine(role, null, Way.STILL, toneOf(role, Way.STILL))
            } else {
                val kw = abs(signed)
                val way = if (kw < STILL_KW) Way.STILL else if (inward.getValue(role)) Way.IN else Way.OUT
                NowLine(role, kw, way, toneOf(role, way), role.key in estimated)
            }
        }
        // The house from the lines where the answer has none but every line is read: what comes in less what goes out.
        val signedIn = if (raw.any { it.kw == null }) null else raw.sumOf { if (inward.getValue(it.role)) it.kw!! else -it.kw!! }
        val houseKw = if (live == null) null else live.houseKw ?: signedIn?.let { Math.round(maxOf(0.0, it) * 100) / 100.0 }
        val anyEstimated = estimated.isNotEmpty()
        val parts = ArrayList<NowPart>()
        if (live != null && houseKw != null) {
            if (has.heat && live.heatKw != null) parts.add(NowPart(PartRole.HEAT, live.heatKw, "heat" in estimated))
            if (has.pool && live.poolKw != null) parts.add(NowPart(PartRole.POOL, live.poolKw, "pool" in estimated))
            if (live.houseKw == null) {
                // Parts never larger than the house worked out from the lines.
                var room = houseKw
                for (index in parts.indices) {
                    val kw = minOf(parts[index].kw, room)
                    parts[index] = parts[index].copy(kw = kw)
                    room -= kw
                }
            }
            val other = (if (live.houseKw == null) null else live.otherKw) ?: maxOf(0.0, houseKw - parts.sumOf { it.kw })
            parts.add(NowPart(PartRole.OTHER, other, anyEstimated))
        }
        if (live == null || houseKw == null || raw.any { it.kw == null }) {
            return NowFigures(
                lines = raw.map { if (it.kw == null) it else it.copy(kw = tenth(it.kw)) },
                houseKw = houseKw?.let(::tenth),
                houseEstimated = houseKw != null && anyEstimated,
                parts = parts.map { it.copy(kw = tenth(it.kw)) }
            )
        }
        val flows = raw.map { Flow(it.role, it.kw!!, inward.getValue(it.role)) } + Flow(null, houseKw, false)
        val tenths = balancedTenths(flows)
        val lines = raw.map { line ->
            val kw = tenths.getValue(line.role) / 10.0
            // The way as written: still at 0,0 kW, else by the sign (a still figure given a tenth shows it).
            val way = if (kw == 0.0) Way.STILL else if (inward.getValue(line.role)) Way.IN else Way.OUT
            line.copy(kw = kw, way = way, tone = toneOf(line.role, way))
        }
        val houseTenths = tenths.getValue(null)
        val partTenths = largestRemainder(parts.map { it.kw }, houseTenths)
        return NowFigures(lines, houseTenths / 10.0, anyEstimated, parts.mapIndexed { index, part -> part.copy(kw = partTenths[index] / 10.0) })
    }

    /** The running hour's buy price and its band among the plan's priced hours; `null` without a price now. */
    fun priceNow(house: House, now: Instant): PriceNow? {
        val running = house.hours.firstOrNull { !it.start.isAfter(now) && it.start.plusSeconds(HOUR_SECONDS).isAfter(now) } ?: return null
        val buy = running.buy ?: return null
        val known = house.hours.mapNotNull { it.buy }.sorted()
        val low = known[known.size / 3]
        val high = known[known.size * 2 / 3]
        return PriceNow(buy, if (buy < low) PriceBand.CHEAP else if (buy < high) PriceBand.MID else PriceBand.DEAR)
    }

    /** The share (%) of what the site uses now (the house, the car and a charging battery) that is not bought; `null`
     * without the figures. */
    fun ownShare(figures: NowFigures): Int? {
        val house = figures.houseKw ?: return null
        if (figures.lines.any { it.kw == null }) return null
        val out = figures.lines.filter { it.way == Way.OUT && it.role != NodeRole.GRID }.sumOf { it.kw!! }
        val bought = figures.lines.firstOrNull { it.role == NodeRole.GRID && it.way == Way.IN }?.kw ?: 0.0
        return Math.round(100 * (1 - bought / maxOf(house + out, 0.01))).toInt().coerceIn(0, 100)
    }

    /** The headline's sentence, as its card key; `null` where nothing is worth a sentence (the card's `whyKey`). */
    fun whyKey(figures: NowFigures, band: PriceBand?): String? {
        // Without the grid's reading (no live figures) there is nothing to say.
        if (figures.lines.any { it.kw == null }) return null
        fun way(role: NodeRole): Way = figures.lines.firstOrNull { it.role == role }?.way ?: Way.STILL
        val sun = way(NodeRole.SUN) == Way.IN
        val buying = way(NodeRole.GRID) == Way.IN
        val selling = way(NodeRole.GRID) == Way.OUT
        val discharging = way(NodeRole.BATTERY) == Way.IN
        val charging = way(NodeRole.BATTERY) == Way.OUT
        val car = way(NodeRole.CAR) == Way.OUT
        val key = when {
            discharging && band == PriceBand.DEAR -> if (sun) "batterySunDear" else "batteryDear"
            buying && band == PriceBand.CHEAP && (charging || car) -> if (charging && car) "cheapBatteryCar" else if (charging) "cheapBattery" else "cheapCar"
            sun && selling -> if (charging) "sunBatterySold" else "sunSold"
            sun && discharging -> if (buying) "sunBatteryPart" else "sunBatteryAll"
            sun && charging && !buying -> "sunBattery"
            sun && !buying && !discharging -> "sunAll"
            sun -> "sunPart"
            discharging -> if (buying) "batteryPart" else "battery"
            car -> "car"
            buying -> "gridAll"
            else -> null
        }
        return key?.let { "house.nowTab.why.$it" }
    }

    /** The price in the major unit with two decimals ("1,23 kr"), else in the minor one. */
    private fun priceWords(text: SiteText, house: House, minor: Double): String {
        val major = house.money?.majorUnit
        if (!major.isNullOrEmpty()) return "${text.fixed(Math.round(minor) / 100.0, 2)} $major"
        return text.fixed(minor, 0) + (house.money?.minorUnit?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: "")
    }

    /** The headline row: the own share (hidden without sun and battery), the price now, and one sentence. */
    fun headline(text: SiteText, house: House, live: HouseNow?, now: Instant, figures: NowFigures): Headline {
        val has = present(live, house)
        val own = if (has.sun || has.battery) ownShare(figures) else null
        val price = priceNow(house, now)
        val why = if (live == null) null else whyKey(figures, price?.band)
        return Headline(
            own = own?.let { text.words.say("house.nowTab.own", "pct" to text.fixed(it.toDouble(), 0)) },
            ownTone = own?.let { if (it >= 80) OwnTone.HIGH else if (it >= 40) OwnTone.MID else OwnTone.LOW },
            price = price?.let { text.words.say("house.nowTab.price.${it.band.key}", "price" to priceWords(text, house, it.minor)) },
            band = price?.band,
            why = why?.let { text.words.say(it) }
        )
    }

    /** A node's two captions: its state now, and today's kWh (the day's energy flow). */
    fun captions(text: SiteText, role: NodeRole, house: House, live: HouseNow?, line: NowLine): Captions {
        val flow = house.energyFlow
        fun t(key: String, vararg values: Pair<String, String>) = text.words.say("house.nowTab.$key", *values)
        fun value(kwh: Double?) = kwh?.let(text::bare) ?: "–"
        val unread = line.kw == null
        return when (role) {
            NodeRole.SUN -> Captions(
                if (unread || line.way == Way.IN) t("state.sun") else t("state.sunNone"),
                t("today.sun", "kwh" to (flow?.kwh(true, "solar")?.let(text::kwh) ?: "–"))
            )
            NodeRole.GRID -> Captions(
                when {
                    unread -> t("state.grid")
                    line.way == Way.IN -> t("state.buying")
                    line.way == Way.OUT -> t("state.selling")
                    else -> t("state.gridIdle")
                },
                t("today.grid", "bought" to value(flow?.kwh(true, "grid")), "sold" to value(flow?.kwh(false, "grid")))
            )
            NodeRole.BATTERY -> {
                val soc = live?.socPct ?: house.battery?.socPct
                val doing = when {
                    unread -> null
                    line.way == Way.IN -> t("state.covers")
                    line.way == Way.OUT -> t("state.charging")
                    else -> t("state.holds")
                }
                val state = listOfNotNull(soc?.let(text::percent), doing).joinToString(" · ").ifEmpty { t("state.battery") }
                Captions(state, t("today.battery", "in" to value(flow?.kwh(false, "battery")), "out" to value(flow?.kwh(true, "battery"))))
            }
            NodeRole.CAR -> {
                val soc = if (carPlugged(live)) live?.carSocPct else null
                val level = soc?.let { " · ${text.percent(it)}" } ?: ""
                val state = when {
                    line.way == Way.OUT -> t("state.carCharging") + level
                    carPlugged(live) -> t("state.carPlugged") + level
                    live?.carConnected == false -> t("state.noCar")
                    else -> t("state.car")
                }
                Captions(state, t("today.car", "kwh" to (flow?.kwh(false, "car")?.let(text::kwh) ?: "–")))
            }
        }
    }

    /** A live figure with one decimal at any size, so the written figures add up; "≈" before it when estimated. */
    fun kwText(text: SiteText, kw: Double, estimated: Boolean = false): String = "${if (estimated) "≈ " else ""}${text.fixed(kw, 1)} kW"

    /** The Now box's live part (the card's `renderNow`): the headline, the drawing's figures and words, the legend. */
    fun picture(text: SiteText, house: House, live: HouseNow?, now: Instant): NowPicture {
        val words = text.words
        val figures = figures(live, house)
        val captions = figures.lines.associate { it.role to captions(text, it.role, house, live, it) }
        val flowing = figures.lines.filter { it.kw != null && it.way != Way.STILL }
        val estimate = " ${words.say("house.loads.estimated")}"
        val flow = house.energyFlow
        val day = flow?.let { (it.kwh(false, "house") ?: 0.0) + (it.kwh(false, "heat") ?: 0.0) }
        val houseToday = words.say("house.nowTab.today.house", "kwh" to (day?.let(text::kwh) ?: "–"))
        val said = ArrayList<String>()
        figures.houseKw?.let { said.add("${words.say("house.nowTab.houseNow")} ${kwText(text, it)}${if (figures.houseEstimated) estimate else ""}") }
        for (line in flowing) {
            said.add(words.say("house.nowTab.line.${line.role.key}.${line.way.key}", "kw" to kwText(text, line.kw!!) + if (line.estimated) estimate else ""))
        }
        for (line in figures.lines) captions.getValue(line.role).let { said.add("${it.state}, ${it.today}") }
        said.add(houseToday)
        return NowPicture(
            present = present(live, house),
            figures = figures,
            headline = headline(text, house, live, now, figures),
            captions = captions,
            labels = flowing.associate { it.role to kwText(text, it.kw!!, it.estimated) },
            houseFigure = figures.houseKw?.let { kwText(text, it, figures.houseEstimated) } ?: "–",
            houseName = words.say("house.nowTab.houseName"),
            houseToday = houseToday,
            socPct = if (present(live, house).battery) live?.socPct ?: house.battery?.socPct else null,
            legend = figures.parts.filter { it.kw > 0 }.map { it.role to "${words.say("house.nowTab.part.${it.role.key}")} ${kwText(text, it.kw, it.estimated)}" },
            description = said.joinToString(". ")
        )
    }

    /** The plan's runs from the running hour on: each change of what the battery does, until the plan's last hour. */
    fun planRuns(house: House, now: Instant): List<PlanRun> {
        val runs = ArrayList<PlanRun>()
        for (hour in house.hours) {
            if (!hour.start.plusSeconds(HOUR_SECONDS).isAfter(now)) continue
            if (hour.socPlanPct == null && hour.batteryAction == null) {
                // The plan ends here.
                if (runs.isNotEmpty()) break
                continue
            }
            val act = hour.batteryAction ?: "self"
            if (runs.isEmpty() || runs.last().act != act) runs.add(PlanRun(act, hour.start))
        }
        return runs
    }

    private fun whenText(text: SiteText, zone: ZoneId, now: Instant, at: Instant): String {
        val same = now.atZone(zone).toLocalDate() == at.atZone(zone).toLocalDate()
        return if (same) text.clock(at, zone) else "${text.dayCue(at, zone)} ${text.clock(at, zone)}"
    }

    /**
     * The Now tab's sentence: what the battery does now and until when, then what comes next; while SpotNav does not
     * control the battery, who does and what SpotNav would do. `null` without a battery or anything to say.
     */
    fun planSentence(text: SiteText, house: House, now: Instant): String? {
        val words = text.words
        val battery = house.battery ?: return null
        if (battery.status == "waiting") return words.say("house.saving.waiting")
        if (battery.status == "no_plan") return words.say("house.saving.noPlan")
        val runs = planRuns(house, now)
        val controlling = battery.control == "on"
        val verb = if (controlling) "act" else "inf"
        val who = if (battery.management == "self_managed") {
            words.say("house.plan.who.own", "name" to (OPTIMISERS[battery.brand.orEmpty()] ?: words.say("house.compare.own")))
        } else {
            words.say("house.plan.who.test")
        }
        if (runs.isEmpty()) return if (controlling) null else who
        val first = words.say("house.plan.$verb.${runs[0].act}")
        val next = runs.getOrNull(1)
            ?: return if (controlling) words.say("house.plan.whole", "act" to first)
            else words.say("house.plan.wouldWhole", "who" to who, "act" to first)
        // Controlled, the second clause has its own subject ("sedan säljer det till nätet").
        val then = words.say("house.plan.${if (controlling) "then" else "inf"}.${next.act}")
        val time = whenText(text, house.zone, now, next.start)
        return if (controlling) words.say("house.plan.next", "act" to first, "time" to time, "then" to then)
        else words.say("house.plan.wouldNext", "who" to who, "act" to first, "time" to time, "then" to then)
    }

    /** Today's money, one line: "I dag: batteriet tjänade 11 kr · SpotNav sparade 17 kr", with "(egen styrning)" after the
     * battery's figure when it ran under its own control; `null` with neither. */
    fun moneyLine(text: SiteText, house: House): String? {
        val words = text.words
        val parts = ArrayList<String>()
        val today = house.today
        if (house.battery != null && today != null) {
            // Under its own control (SpotNav off or in test, Sigenergy's AI) the battery's earnings are its own.
            val key = if (house.saving?.batteryShare == 0.0) "house.nowTab.moneyBatteryOwn" else "house.nowTab.moneyBattery"
            parts.add(words.say(key, "amount" to text.amount(today.arbitrage, house.money)))
        }
        house.saving?.total?.let { parts.add(words.say("house.nowTab.moneySaved", "amount" to text.amount(it, house.money))) }
        return if (parts.isEmpty()) null else words.say("house.nowTab.money", "parts" to parts.joinToString(" · "))
    }

    fun modeName(text: SiteText, mode: String): String = if (mode in MODES) text.words.say("house.mode.$mode") else mode

    /**
     * The small grey line under the money: the battery's mode, and that there are no live figures while they cannot
     * be read. `null` with neither.
     */
    fun notesLine(text: SiteText, house: House, live: HouseNow?): String? {
        val notes = ArrayList<String>()
        house.battery?.let { notes.add(text.words.say("house.sub.mode", "mode" to modeName(text, house.today?.mode ?: it.mode))) }
        if (live == null) notes.add(text.words.say("house.nowTab.noLive"))
        if (notes.isEmpty()) return null
        return notes.joinToString(" · ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(text.locale) else it.toString() }
    }

    /** "13 dygn kvar": a learning period's whole days left. */
    fun daysLeft(text: SiteText, days: Int): String =
        text.words.say("learning.daysLeft.${if (days == 1) "one" else "other"}", "days" to days.toString())

    /** One line per learning period that holds something back now; none when nothing is held back. */
    fun learningLines(text: SiteText, house: House): List<String> = house.learning.filter { it.what == "phase_peaks" }.map { hold ->
        val left = daysLeft(text, hold.daysLeft)
        if (hold.capKw > 0) {
            text.words.say("house.learning.cappedWhile", "kw" to text.short(hold.capKw), "left" to left)
        } else {
            "${text.words.say("house.learning.peaksLine", "left" to left)} ${text.words.say("house.learning.noGridUntil")}"
        }
    }

    /**
     * What the battery does now, as the header says it after the name: while SpotNav controls it, the action its
     * control runs, else what the measured power says; "Egen styrning" while the battery's own optimiser runs it;
     * [HouseStatus.Test] while SpotNav controls nothing yet; `null` without a battery or (controlling) a reading.
     */
    fun status(words: CardWords, house: House): HouseStatus? {
        val battery = house.battery ?: return null
        if (battery.control != "on") {
            return if (battery.management == "self_managed") HouseStatus.Said("own", words.say("house.now.own")) else HouseStatus.Test
        }
        val said = mapOf(
            "grid_charge" to "house.legend.charge",
            "hold" to "house.now.hold",
            "export_discharge" to "house.legend.sell",
            "discharge_house" to "house.now.discharge"
        )
        val action = battery.now?.action
        said[action]?.let { return HouseStatus.Said(action!!, words.say(it)) }
        val power = battery.now?.powerKw ?: return null
        return when {
            power >= REST_KW -> HouseStatus.Said("sun", words.say("house.now.sun"))
            power <= -REST_KW -> HouseStatus.Said("discharge", words.say("house.now.discharge"))
            else -> HouseStatus.Said("idle", words.say("house.now.idle"))
        }
    }
}
