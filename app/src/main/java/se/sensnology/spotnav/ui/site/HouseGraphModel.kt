package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.HouseHistory
import se.sensnology.spotnav.ui.common.CardWords
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The day chart's two views: the energy (Graf) and the price with the battery (Pris och batteri). */
internal enum class ChartView(val key: String) { ENERGY("house.graph"), PRICE("house.price") }

/** What a hour's bar is made of: its parts' keys (the legend's), as the card names them (`house-series.ts`). */
internal object Series {
    const val HOUSE = "house"
    const val HEAT = "heat"
    const val FORECAST = "forecast"
    const val SUN = "sun"
    const val CAR = "car"
    const val BATTERY = "battery"
    const val BUY = "buy"
    const val SELL_PRICE = "sellPrice"
    const val CHARGE = "charge"
    const val SELL = "sell"
    const val EARNED = "earned"
    const val COST = "cost"

    /** What a view starts with hidden: the car, which would otherwise crowd the house's bars. */
    fun hiddenByDefault(view: ChartView): Set<String> = if (view == ChartView.ENERGY) setOf(CAR) else emptySet()
}

/**
 * The Graf and Pris och batteri tabs' figures, as the Home Assistant card works them out (`house-chart.ts`,
 * `house-view.ts`, `house-period-view.ts`, `house-history.ts`): what an hour's bar shows, the words of a bar, the bars
 * of a month or a year. Pure: the views draw what this says.
 */
internal object HouseGraph {
    const val HOUR_MS = 3_600_000L

    /** The house's figure drawn in an hour: the measured load in the past, else the forecast. */
    fun houseShown(hour: House.Hour): Pair<Double?, Boolean> =
        if (hour.houseKw != null) hour.houseKw to true else hour.houseForecastKw to false

    /** The heat pump's part of an hour's bar, never above the house's own figure; `null` without one. */
    fun heatShown(hour: House.Hour): Double? {
        val (house, measured) = houseShown(hour)
        val heat = if (measured) hour.heatKw else hour.heatForecastKw
        if (heat == null || house == null) return null
        return max(0.0, min(heat, house))
    }

    /** The sun's figure drawn in an hour: the production in the past, the corrected forecast ahead (else the raw). */
    fun sunShown(hour: House.Hour, nowMs: Long): Double? =
        if (hour.start.toEpochMilli() + HOUR_MS <= nowMs) hour.sunKwh else hour.sunCorrectedKwh ?: hour.sunForecastKwh

    /** What the car's bar stands on in an hour: the house's bar where it is drawn, else the heat's, else the axis. */
    fun carBase(hour: House.Hour, shown: (String) -> Boolean): Double {
        val house = houseShown(hour).first
        if (shown(Series.HOUSE) && house != null && house > 0) return house
        val heat = if (shown(Series.HEAT) && house != null && house > 0) heatShown(hour) else null
        return if (heat != null && heat > 0) heat else 0.0
    }

    /** The tallest kW the energy view must reach with [hidden] parts left out (the scale follows what is shown). */
    fun energyTop(hours: List<House.Hour>, nowMs: Long, hidden: Set<String>): Double {
        val shown = { s: String -> s !in hidden }
        var top = 0.0
        for (h in hours) {
            val house = houseShown(h)
            if (shown(Series.HOUSE)) house.first?.let { top = max(top, it) }
            if (shown(Series.FORECAST) && h.houseKw != null) h.houseForecastKw?.let { top = max(top, it) }
            if (shown(Series.SUN)) {
                sunShown(h, nowMs)?.let { top = max(top, it) }
                h.sunForecastKwh?.let { top = max(top, it) }
            }
            if (shown(Series.HEAT)) heatShown(h)?.let { top = max(top, it) }
            if (shown(Series.CAR) && h.carKw != null) top = max(top, carBase(h, shown) + h.carKw)
        }
        return top
    }

    /** The tallest price (minor unit per kWh) of the price view, and the lowest below zero. */
    fun priceRange(hours: List<House.Hour>, hidden: Set<String>): Pair<Double, Double> {
        var low = 0.0
        var high = 1.0
        for (h in hours) {
            if (Series.BUY !in hidden) h.buy?.let { high = max(high, it); low = min(low, it) }
            if (Series.SELL_PRICE !in hidden) h.sell?.let { high = max(high, it); low = min(low, it) }
        }
        return low to high
    }

    /** A nice step (1, 2 or 5 times a power of ten) from [low] to [high], about [aim] steps (the card's `niceStep`). */
    fun niceStep(low: Double, high: Double, aim: Int): Double {
        val span = max(high - low, 1e-9)
        val magnitude = 10.0.pow(floor(log10(span / aim)))
        var best = magnitude
        var bestScore = Double.POSITIVE_INFINITY
        for (power in listOf(magnitude / 10, magnitude, magnitude * 10)) {
            for (factor in listOf(1.0, 2.0, 5.0)) {
                val step = power * factor
                val count = ceil(high / step - 1e-9).toInt() - ceil(low / step - 1e-9).toInt()
                if (count < aim - 1) continue
                val score = abs(count - aim).toDouble()
                if (score < bestScore || (score == bestScore && step > best)) {
                    best = step
                    bestScore = score
                }
            }
        }
        return best
    }

    /** The top of an axis from zero to [peak]: a whole step, at least one. */
    fun axisTop(peak: Double, aim: Int): Pair<Double, Double> {
        val high = max(1.0, peak)
        val step = niceStep(0.0, high, aim)
        return Math.round(ceil(high / step - 1e-9) * step * 1e9) / 1e9 to step
    }

    /** A past day's hours with the history's prices where the day's own answer has none. */
    fun withPrices(hours: List<House.Hour>, history: HouseHistory?): List<House.Hour> {
        if (history == null || history.period != HouseHistory.HistoryKind.DAY || hours.any { it.buy != null }) return hours
        val byStart = history.buckets.associateBy { bucketStart(it.key) }
        return hours.map { h -> byStart[h.start]?.let { h.copy(buy = it.buy, sell = it.sell) } ?: h }
    }

    /** The day's hourly figures of what the battery earned, on the chart's hours (`null` where the history has none). */
    fun earnedOnHours(hours: List<House.Hour>, history: HouseHistory?): List<Double?> {
        if (history == null || history.period != HouseHistory.HistoryKind.DAY) return hours.map { null }
        val byStart = history.buckets.associateBy({ bucketStart(it.key) }, { it.arbitrage })
        return hours.map { byStart[it.start] }
    }

    fun flowOnHours(hours: List<House.Hour>, history: HouseHistory?): List<HouseHistory.BatteryFlow?> {
        if (history == null || history.period != HouseHistory.HistoryKind.DAY) return hours.map { null }
        val byStart = history.buckets.associateBy({ bucketStart(it.key) }, { it.batteryFlow })
        return hours.map { byStart[it.start] }
    }

    private fun bucketStart(key: String): Instant? = runCatching { java.time.OffsetDateTime.parse(key).toInstant() }.getOrNull()

    /** What the battery earned over the period (minor unit): the buckets' arbitrage summed; `null` with none. */
    fun earnedTotal(history: HouseHistory): Double? =
        history.buckets.mapNotNull { it.arbitrage }.takeIf { it.isNotEmpty() }?.sum()

    // ------------------------------------------------------------------------------------------ words

    /** The local clock of an hour, "12:00". */
    private fun clock(at: Instant, zone: ZoneId) = DateTimeFormatter.ofPattern("HH:mm").format(at.atZone(zone))

    /** One hour's figures in words, for the readout and the hour's accessible name. */
    fun hourWords(
        text: SiteText,
        hour: House.Hour,
        zone: ZoneId,
        nowMs: Long,
        view: ChartView,
        hidden: Set<String>
    ): String {
        val words = text.words
        val parts = mutableListOf("${clock(hour.start, zone)}–${clock(hour.start.plusMillis(HOUR_MS), zone)}")
        val (houseKw, measured) = houseShown(hour)
        val visible = { s: String -> s !in hidden }
        if (view == ChartView.PRICE && hour.buy != null && hour.sell != null) {
            val buy = text.fixed(hour.buy, 0)
            val sell = text.fixed(hour.sell, 0)
            when {
                visible(Series.BUY) && visible(Series.SELL_PRICE) -> parts += words.say("house.readout.price", "buy" to buy, "sell" to sell)
                visible(Series.BUY) -> parts += words.say("house.readout.priceBuy", "buy" to buy)
                visible(Series.SELL_PRICE) -> parts += words.say("house.readout.priceSell", "sell" to sell)
            }
        }
        if (view == ChartView.ENERGY && houseKw != null) {
            val forecast = hour.houseForecastKw != null && visible(Series.FORECAST)
            if (visible(Series.HOUSE) && measured && forecast) {
                parts += words.say("house.readout.houseBoth", "kw" to text.kw(houseKw), "forecast" to text.kw(hour.houseForecastKw!!))
            } else if (visible(Series.HOUSE)) {
                parts += words.say(if (measured) "house.readout.house" else "house.readout.houseForecast", "kw" to text.kw(houseKw))
            } else if (measured && forecast) {
                parts += words.say("house.readout.houseForecast", "kw" to text.kw(hour.houseForecastKw!!))
            }
        }
        val heat = heatShown(hour)
        if (view == ChartView.ENERGY && heat != null && visible(Series.HEAT)) {
            parts += words.say(if (measured) "house.readout.heat" else "house.readout.heatForecast", "kw" to text.kw(heat))
        }
        if (view == ChartView.ENERGY && hour.carKw != null && hour.carKw > 0 && visible(Series.CAR)) {
            parts += words.say("house.readout.car", "kw" to text.kw(hour.carKw))
        }
        val sun = sunShown(hour, nowMs)
        val past = hour.start.toEpochMilli() + HOUR_MS <= nowMs
        if (view == ChartView.ENERGY && visible(Series.SUN) && sun != null && (sun > 0 || (hour.sunForecastKwh ?: 0.0) > 0)) {
            parts += words.say(if (past) "house.readout.sun" else "house.readout.sunForecast", "kwh" to text.kwh(sun))
        }
        if (view == ChartView.PRICE && visible(Series.BATTERY)) {
            if (hour.socPct != null) parts += words.say("house.readout.soc", "pct" to text.percent(hour.socPct))
            else if (!past && hour.socPlanPct != null) parts += words.say("house.readout.socPlan", "pct" to text.percent(hour.socPlanPct))
        }
        val tint = when (hour.batteryAction) { "grid_charge" -> Series.CHARGE; "export_discharge" -> Series.SELL; else -> null }
        if (hour.batteryAction != null && visible(Series.BATTERY) && (view == ChartView.ENERGY || tint == null || visible(tint))) {
            parts += words.say("house.readout.action.${hour.batteryAction}")
        }
        return parts.joinToString(" · ")
    }

    private const val MIN_FLOW_KWH = 0.05

    /**
     * What a bucket's battery earnings are made of, in words for a readout: "urladdat 3,5 kWh, värt 8,1 kr, minus 5,6 kr
     * som energin kostade att ladda = 2,5 kr", and what it charged; both where it did both; `null` where it moved nothing.
     */
    fun flowWords(text: SiteText, flow: HouseHistory.BatteryFlow, money: House.Money?): String? {
        val parts = mutableListOf<String>()
        if (flow.discharged >= MIN_FLOW_KWH) {
            parts += text.words.say(
                "house.flow.out",
                "kwh" to text.kwh(flow.discharged), "worth" to text.amount(flow.worth, money),
                "cost" to text.amount(flow.cost, money), "net" to text.amount(flow.worth - flow.cost, money)
            )
        }
        if (flow.charged >= MIN_FLOW_KWH) {
            parts += text.words.say("house.flow.in", "kwh" to text.kwh(flow.charged), "cost" to text.amount(flow.chargeCost, money))
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** A bucket's name in words and on the axis: an hour of a day, a day of a month, a month of a year. */
    fun bucketWords(locale: Locale, period: HouseHistory.HistoryKind, key: String, index: Int, total: Int): Pair<String, String> {
        when (period) {
            HouseHistory.HistoryKind.DAY -> {
                val clock = Regex("T(\\d{2}):(\\d{2})").find(key)
                val hour = clock?.groupValues?.get(1).orEmpty()
                return (if (clock == null) key else "$hour:${clock.groupValues[2]}") to
                    (if (hour.isNotEmpty() && hour.toInt() % 6 == 0) hour else "")
            }
            HouseHistory.HistoryKind.MONTH -> {
                val day = runCatching { LocalDate.parse(key) }.getOrNull()
                val label = day?.let { DateTimeFormatter.ofPattern("EEE d MMM", locale).format(it).replace(".", "") } ?: key
                return label to (if (axisShown(index, total, 5)) (index + 1).toString() else "")
            }
            HouseHistory.HistoryKind.YEAR -> {
                val month = key.substring(5, 7).toInt()
                val first = LocalDate.of(2026, month, 15)
                return DateTimeFormatter.ofPattern("LLLL", locale).format(first) to
                    DateTimeFormatter.ofPattern("LLLLL", locale).format(first)
            }
        }
    }

    /** Whether the label of bar [index] of [count] is drawn on a bars' axis: the first, the last, and every [every]th. */
    fun axisShown(index: Int, count: Int, every: Int): Boolean {
        val number = index + 1
        return number == 1 || number == count || (number % every == 0 && count - number >= ceil(every / 2.0).toInt())
    }

    // ------------------------------------------------------------------------------------------ bars

    /** Which parts the energy chart of a period has: the heat, the car and the sun where any bucket has them. */
    class EnergySeries(val heat: Boolean, val car: Boolean, val sun: Boolean)

    fun energySeries(history: HouseHistory) = EnergySeries(
        heat = history.buckets.any { (it.heat ?: 0.0) > 0 },
        car = history.buckets.any { it.car > 0 },
        sun = history.buckets.any { it.solar != null }
    )

    /** Whether a period has any energy to draw: no buckets, or none measured, is nothing. */
    fun hasEnergy(history: HouseHistory): Boolean = history.buckets.isNotEmpty() && history.buckets.any { it.periods > 0 }

    /**
     * The energy bars of a month or a year, a bar a day or a month: the house, the heat and the car stacked (the view leaves out
     * what the legend hid); with the sun as the line over them. Words and axis as the card's.
     */
    fun energyBars(text: SiteText, locale: Locale, history: HouseHistory): EnergyBars {
        val series = energySeries(history)
        val n = history.buckets.size
        val bars = history.buckets.mapIndexed { index, b ->
            val (`when`, axis) = bucketWords(locale, history.period, b.key, index, n)
            val parts = buildList {
                add(BarPart(Series.HOUSE, b.house))
                if (series.heat) add(BarPart(Series.HEAT, b.heat ?: 0.0))
                if (series.car) add(BarPart(Series.CAR, b.car))
            }
            val words = if (b.periods == 0) {
                text.words.say("hist.house.barNone", "when" to `when`)
            } else {
                buildList {
                    add(`when`)
                    add("${text.words.say("house.legend.house")} ${text.kwh(b.house)}")
                    if (series.heat && b.heat != null) add("${text.words.say("house.legend.heat")} ${text.kwh(b.heat)}")
                    if (series.car && b.car > 0) add("${text.words.say("house.legend.car")} ${text.kwh(b.car)}")
                    if (series.sun && b.solar != null) add("${text.words.say("house.legend.sun")} ${text.kwh(b.solar)}")
                }.joinToString(" · ")
            }
            Bar(b.key, listOf(parts), words, axis)
        }
        val line = history.buckets.map { b -> if (series.sun && b.periods > 0) b.solar else null }
        return EnergyBars(series, bars, line)
    }

    class EnergyBars(val series: EnergySeries, val bars: List<Bar>, val sun: List<Double?>)

    /** The kr bars of a month or a year, a bar a day or a month; `null` without a figure. */
    fun earnedBars(text: SiteText, locale: Locale, history: HouseHistory, money: House.Money?): List<Bar>? {
        if (earnedTotal(history) == null) return null
        val n = history.buckets.size
        return history.buckets.mapIndexed { index, b ->
            val (`when`, axis) = bucketWords(locale, history.period, b.key, index, n)
            val value = b.arbitrage
            val flow = b.batteryFlow?.let { flowWords(text, it, money) }
            val words = when {
                flow != null -> "${`when`}: $flow"
                value == null -> text.words.say("hist.house.barNone", "when" to `when`)
                else -> text.words.say(
                    if (value < 0) "house.value.barCost" else "house.value.bar",
                    "when" to `when`, "amount" to text.amount(abs(value), money)
                )
            }
            Bar(
                b.key,
                if (value == null || value == 0.0) emptyList() else listOf(listOf(BarPart(if (value < 0) Series.COST else Series.EARNED, value))),
                words, axis
            )
        }
    }
}
