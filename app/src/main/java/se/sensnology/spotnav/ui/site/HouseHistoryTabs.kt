package se.sensnology.spotnav.ui.site

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.HouseHistory
import se.sensnology.spotnav.ha.house.HouseHistory.HistoryKind
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.ha.session.HaSession
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.cardHeading
import se.sensnology.spotnav.ui.common.cardTitle
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** What a read of a period came to. */
internal sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>

    /** [unsupported]: Home Assistant does not offer it (an older integration or another version). */
    data class Failed(val unsupported: Boolean) : Load<Nothing>
}

/**
 * The reads the house page's period tabs share: the history of the period shown, and for a past day the house of that
 * day (its hours). Asked when a tab opens, when the period changes, and again every minute while the running period
 * is shown ([refresh]); an answer held is kept on screen while a newer one is on its way. The period shown is the
 * tabs' common choice ([choice], the card's `ui.choice`).
 */
internal class HouseHistoryStore(private val session: HaSession, private val changed: () -> Unit) {
    var choice: PeriodChoice = PeriodChoice(HistoryPeriod.DAY)

    private val histories = HashMap<String, Load<HouseHistory>>()
    private val days = HashMap<LocalDate, Load<House>>()
    private val inFlight = HashSet<String>()

    private fun key(choice: PeriodChoice) = "${choice.period.key}:${choice.date ?: "now"}"

    fun history(choice: PeriodChoice = this.choice): Load<HouseHistory>? = histories[key(choice)]

    fun day(date: LocalDate): Load<House>? = days[date]

    /**
     * Read what [needsHistory] and the day's house ask for [choice]. [force] reads again though an answer is held (the
     * running period every minute); a past period is read once.
     */
    fun request(choice: PeriodChoice = this.choice, needsHistory: Boolean, force: Boolean) {
        if (needsHistory) readHistory(choice, force)
        val date = choice.date
        if (choice.period == HistoryPeriod.DAY && date != null) readDay(date, force)
    }

    private fun readHistory(choice: PeriodChoice, force: Boolean) {
        val k = key(choice)
        if (k in inFlight || (!force && histories[k] is Load.Ready<*>)) return
        inFlight += k
        if (histories[k] == null) histories[k] = Load.Loading
        val kind = HistoryKind.entries.first { it.key == choice.period.key }
        session.houseHistory(kind, choice.date) { outcome ->
            inFlight -= k
            when (outcome) {
                is SiteRead.Read -> histories[k] = Load.Ready(outcome.value)
                // An answer held stays; with none, the period says it could not be read.
                else -> if (histories[k] !is Load.Ready<*>) histories[k] = Load.Failed(outcome == SiteRead.Unsupported)
            }
            changed()
        }
    }

    private fun readDay(date: LocalDate, force: Boolean) {
        val k = "day-house:$date"
        if (k in inFlight || (!force && days[date] is Load.Ready<*>)) return
        inFlight += k
        if (days[date] == null) days[date] = Load.Loading
        session.house(date) { outcome ->
            inFlight -= k
            when (outcome) {
                is SiteRead.Read -> days[date] = Load.Ready(outcome.value)
                else -> if (days[date] !is Load.Ready<*>) days[date] = Load.Failed(outcome == SiteRead.Unsupported)
            }
            changed()
        }
    }
}

/**
 * The Graf and the Pris och batteri tabs of the house page, one panel each ([tab]): the period picker, then what the
 * Home Assistant card draws under it (`house-view.ts` `graphPanel` and `pricePanel`).
 *
 * - Graf: a day by the hour (today with the forecast ahead and dashed), a month by the day, a year by the month; the car
 *   hidden until the legend shows it. Where a past day has no hours of its own the history's bars stand in.
 * - Pris och batteri: the day's buy and sell price with the battery's level and plan, under a line with the cost. A day
 *   only: the box is left out for a month and a year. What the battery earned is Intäkt's (no kr bars, no heading here),
 *   but a tapped hour still says it.
 *
 * Which parts the legend hid is remembered per site and view through [preferences].
 */
internal class HouseHistoryPanel(
    private val scope: ViewScope,
    private val boxKind: HouseBox,
    private val store: HouseHistoryStore,
    private val preferences: SitePreferences,
    private val colours: SiteColours,
    private val text: SiteText,
    private val locale: Locale,
    private val site: () -> String,
    /** The page's own house (today's), and where to ask for it again. */
    private val house: () -> House?
) : HousePeriodBox {
    private val words get() = text.words
    private val view = if (boxKind == HouseBox.PRICE) ChartView.PRICE else ChartView.ENERGY
    private val priceTab = boxKind == HouseBox.PRICE

    /** The box's own name above its picture: the card's name of the tab ("Energigraf", "Pris och batteri"). */
    private val title = scope.cardTitle(words.say("house.tab.${boxKind.tabKey}"))
    private val sub = TextView(scope.context).apply {
        textSize = 13f
        setTextColor(scope.muted)
    }
    private val status = TextView(scope.context).apply {
        textSize = 14f
        setTextColor(scope.muted)
    }
    private val dayChart = HouseDayChart(scope, colours)
    private val bars = StackedBarsView(scope, colours)
    private var selectedHour: Int? = null
    private var selectedBar: String? = null

    /** The box: a card like the Now box, with its heading, its figures and its picture. */
    override val panel: LinearLayout = LinearLayout(scope.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(scope.dp(14), scope.dp(12), scope.dp(14), scope.dp(12))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(scope.cardBackground)
            cornerRadius = scope.dp(10).toFloat()
        }
        visibility = View.GONE
        addView(scope.cardHeading(if (priceTab) R.drawable.ic_tab_price else R.drawable.ic_tab_graph, title), wide())
        addView(sub, wide().apply { topMargin = scope.dp(6) })
        addView(status, wide().apply { topMargin = scope.dp(10) })
        addView(dayChart, wide().apply { topMargin = scope.dp(8) })
        addView(bars, wide().apply { topMargin = scope.dp(8) })
    }

    init {
        dayChart.onSelect = { selectedHour = it }
        dayChart.onHiddenChange = { hidden ->
            preferences.setHidden(site(), view.key, hidden)
            render()
        }
        bars.onSelect = { selectedBar = it }
        bars.onHiddenChange = { hidden ->
            preferences.setHidden(site(), view.key, hidden)
            render()
        }
    }

    /** The period changed (the page's shared picker): forget what was chosen on the old one. */
    override fun periodChanged() {
        selectedHour = null
        selectedBar = null
    }

    private fun hidden(): Set<String> = preferences.hiddenOr(site(), view.key, Series.hiddenByDefault(view))

    /** The tab came into view or its period changed: ask for what it draws. */
    override fun open(force: Boolean) {
        val choice = store.choice
        store.request(choice, needsHistory = needsHistory(choice), force = force)
    }

    /** Every minute while the tab is in view: the running period is read again, a past one is not. */
    override fun refresh() = open(force = store.choice.date == null)

    private fun needsHistory(choice: PeriodChoice): Boolean =
        if (priceTab) choice.period == HistoryPeriod.DAY else choice.period != HistoryPeriod.DAY || choice.date != null

    /** Draw the box; it is left out (and this says `false`) where there is nothing to show for the period. */
    override fun render(): Boolean {
        val now = house() ?: return false
        val choice = store.choice
        nothing = false
        val history = (store.history(choice) as? Load.Ready)?.value
        val money = history?.money ?: now.money
        listOf(sub, status, dayChart, bars).forEach { it.visibility = View.GONE }
        // Prices are hourly: a month or a year has none to show, and the battery's earnings are Intäkt's.
        if (priceTab && choice.period != HistoryPeriod.DAY) {
            nothing = true
        } else {
            if (priceTab) priceHeading(now, choice, history, money)
            drawContent(now, choice, history, money)
        }
        val shown = !nothing
        panel.visibility = if (shown) View.VISIBLE else View.GONE
        return shown
    }

    private var nothing = false

    private fun drawContent(now: House, choice: PeriodChoice, history: HouseHistory?, money: House.Money?) {

        if (choice.period == HistoryPeriod.DAY) {
            val shown = dayHours(now, choice)
            when (shown) {
                is Load.Loading -> return say("hist.loading")
                is Load.Ready -> return drawDay(shown.value, choice, history, money)
                is Load.Failed, null -> Unit // the history's own bars below
            }
        }
        val loaded = store.history(choice)
        when (loaded) {
            null, Load.Loading -> say("hist.loading")
            is Load.Failed -> say(if (loaded.unsupported) "hist.unsupported" else "hist.failed")
            is Load.Ready -> drawBars(loaded.value)
        }
    }

    private fun say(key: String) {
        status.text = words.say(key)
        status.visibility = View.VISIBLE
    }

    /** The day's hours: today's from the page's house, a past day's from its own read; `null` where the day has none. */
    private fun dayHours(now: House, choice: PeriodChoice): Load<House>? {
        val date = choice.date ?: return Load.Ready(now)
        val first = now.day?.first
        if (first != null && date < first) return null
        return when (val day = store.day(date)) {
            null, Load.Loading -> Load.Loading
            is Load.Failed -> null
            is Load.Ready -> if (day.value.day?.hasData == false) null else day
        }
    }

    // ---------------------------------------------------------------------------------------------- day

    private fun drawDay(shown: House, choice: PeriodChoice, history: HouseHistory?, money: House.Money?) {
        val past = choice.date != null
        val nowMs = if (past) choice.date!!.plusDays(2).atStartOfDay(shown.zone).toInstant().toEpochMilli() else System.currentTimeMillis()
        val hours = if (priceTab) HouseGraph.withPrices(shown.hours, history) else shown.hours
        if (priceTab && hours.none { it.buy != null }) {
            // No prices to draw, and the kr bars are Intäkt's: nothing here until the history is in, then the box is left out.
            val loaded = store.history(choice)
            if (loaded is Load.Ready) nothing = true else say(if (loaded is Load.Failed) "hist.failed" else "hist.loading")
            return
        }
        val hiddenSet = hidden()
        val battery = house()?.battery != null
        val earned = if (priceTab && battery) HouseGraph.earnedOnHours(hours, history) else null
        val flows = HouseGraph.flowOnHours(hours, history)
        val unit = money?.minorUnit.orEmpty()
        val tz = shown.zone
        val series = if (priceTab) priceSeries(hours, battery) else energySeries(shown, hours)
        dayChart.show(
            DaySpec(
                hours = hours,
                zone = tz,
                nowMs = nowMs,
                view = view,
                hidden = hiddenSet,
                series = series,
                label = words.say(if (priceTab) "house.chart.priceLabel" else "house.chart.label"),
                nowWord = words.say("house.now"),
                kw = { text.fixed(it, 1) },
                price = { text.fixed(it, 0) },
                priceUnit = if (unit.isEmpty()) "" else "$unit/kWh",
                money = { text.amount(it, money) },
                hourLabel = { i -> "%02d".format(hours[i].start.atZone(tz).hour) },
                dayCue = { i -> text.dayCue(hours[i].start, tz) },
                words = { i ->
                    var said = HouseGraph.hourWords(text, hours[i], tz, nowMs, view, hiddenSet)
                    val flow = flows.getOrNull(i)?.let { HouseGraph.flowWords(text, it, money) }
                    val value = earned?.getOrNull(i)
                    if (flow != null) said += " · $flow"
                    else if (value != null) {
                        said += " · " + words.say(if (value < 0) "house.value.hourCost" else "house.value.hour", "amount" to text.amount(Math.abs(value), money))
                    }
                    said
                },
                hint = words.say(if (!priceTab && !past) "house.hint.energy" else "house.hint"),
                selected = selectedHour
            )
        )
        dayChart.visibility = View.VISIBLE
    }

    private fun energySeries(shown: House, hours: List<House.Hour>): List<BarSeries> = buildList {
        add(BarSeries(Series.HOUSE, words.say("house.legend.house"), colours.of(Tone.HOUSE)))
        if (hours.any { HouseGraph.heatShown(it) != null }) add(BarSeries(Series.HEAT, words.say("house.legend.heat"), colours.of(Tone.HEAT)))
        if (hours.any { it.houseKw != null && it.houseForecastKw != null }) add(BarSeries(Series.FORECAST, words.say("house.legend.forecast"), colours.text, line = true))
        if (shown.sun != null) add(BarSeries(Series.SUN, words.say("house.legend.sun"), colours.of(Tone.SUN)))
        if (hours.any { (it.carKw ?: 0.0) > 0 }) add(BarSeries(Series.CAR, words.say("house.legend.car"), colours.of(Tone.CAR)))
    }

    private fun priceSeries(hours: List<House.Hour>, battery: Boolean): List<BarSeries> = buildList {
        add(BarSeries(Series.BUY, words.say("house.legend.buy"), colours.text, line = true))
        add(BarSeries(Series.SELL_PRICE, words.say("house.legend.sellPrice"), SELL, line = true))
        if (battery) {
            add(BarSeries(Series.BATTERY, words.say("house.legend.battery"), colours.of(Tone.BATTERY), line = true))
            if (hours.any { it.batteryAction == "grid_charge" }) add(BarSeries(Series.CHARGE, words.say("house.legend.charge"), colours.of(Tone.GRID)))
            if (hours.any { it.batteryAction == "export_discharge" }) add(BarSeries(Series.SELL, words.say("house.legend.sell"), SELL))
        }
    }

    // ---------------------------------------------------------------------------------------------- bars

    private fun drawBars(history: HouseHistory) {
        val hiddenSet = hidden()
        run {
            if (!HouseGraph.hasEnergy(history)) { nothing = true; return }
            val energy = HouseGraph.energyBars(text, locale, history)
            val series = buildList {
                add(BarSeries(Series.HOUSE, words.say("house.legend.house"), colours.of(Tone.HOUSE)))
                if (energy.series.heat) add(BarSeries(Series.HEAT, words.say("house.legend.heat"), colours.of(Tone.HEAT)))
                if (energy.series.car) add(BarSeries(Series.CAR, words.say("house.legend.car"), colours.of(Tone.CAR)))
                if (energy.series.sun) add(BarSeries(Series.SUN, words.say("house.legend.sun"), colours.of(Tone.SUN), line = true))
            }
            bars.show(
                BarsSpec(
                    label = words.say("house.graph.label"), bars = energy.bars, series = series, hidden = hiddenSet,
                    scale = { text.fixed(it, if (it >= 100) 0 else 1) + " kWh" }, hint = words.say("hist.house.hint"),
                    selected = selectedBar, line = energy.sun, lineSeries = Series.SUN
                )
            )
            bars.visibility = View.VISIBLE
        }
    }

    // ---------------------------------------------------------------------------------------------- heading

    private fun priceHeading(now: House, choice: PeriodChoice, history: HouseHistory?, money: House.Money?) {
        val running = choice.period == HistoryPeriod.DAY && choice.date == null
        val parts = mutableListOf<String>()
        // What it counts; no maker's app is named, as some count the battery's cash flow hour by hour.
        if (now.battery != null) parts += words.say("house.todayHint")
        val cost = history?.cost
        if (running && now.today != null) {
            parts += words.say("house.todayCost", "amount" to text.amount(now.today.bought, now.money))
            if (Math.abs(now.today.sold) >= 50) parts += words.say("house.todaySold", "amount" to text.amount(now.today.sold, now.money))
        } else if (cost != null && cost.priced > 0) {
            parts += words.say("house.todayCost", "amount" to text.amount(cost.bought, money))
            if (Math.abs(cost.sold) >= 50) parts += words.say("house.todaySold", "amount" to text.amount(cost.sold, money))
        }
        val line = parts.filter { it.isNotEmpty() }.joinToString(" · ")
        if (line.isNotEmpty() && (now.battery == null || history != null)) {
            sub.text = line
            sub.visibility = View.VISIBLE
        }
    }

    private fun wide() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private companion object {
        /** The price's sell colour, the card's (`--spotnav-sell`). */
        const val SELL = 0xFFC2185B.toInt()
    }
}
