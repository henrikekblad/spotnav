package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.heat.HeatHistory
import se.sensnology.spotnav.ha.house.HouseHistory.HistoryKind
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** One line of a details section: a `name … figure` row, a plain note, or the hot-water runs. */
internal sealed interface DetailRow {
    val key: String

    data class Figure(override val key: String, val name: String, val value: String) : DetailRow

    data class Note(override val key: String, val text: String) : DetailRow

    /** The period's cost bars (or its kWh where nothing is priced), with the plan's shadow saving hatched on top. */
    class Bars(
        override val key: String, val label: String, val bars: List<Bar>, val series: List<BarSeries>,
        val scale: (Double) -> String, val hint: String
    ) : DetailRow

    /** The kWh per degree day of each month: a bar per month, shown with the section. */
    data class Trend(override val key: String, val title: String, val bars: List<Bar>, val unit: String) : DetailRow
}

internal class DetailSection(val key: String, val title: String, val rows: List<DetailRow>)

/**
 * The heat details sheet's sections as the Home Assistant card words them (`frontend/src/heat-history.ts`, with every
 * section open): the use in figures with the immersion heater, kWh per degree day with its monthly trend, comfort,
 * what SpotNav's plan would have saved in shadow, a day's review, how well the house model forecasts the heat, and a
 * day's hot-water runs. Every figure is Home Assistant's. The cost bars with the shadow saving hatched on top come first
 * ([costBars]), drawn only where some bucket has that figure. Pure.
 */
internal object HeatDetailsModel {
    private fun kwh(text: SiteText, value: Double) = "${text.fixed(value, if (value >= 100) 0 else 1)} kWh"

    private fun celsius(text: SiteText, value: Double) = "${text.fixed(value, 1)} °C"

    private fun money(text: SiteText, minor: Double, answer: HeatHistory) = HeatUseModel.money(text, minor, answer.money)

    private fun dateLabel(text: SiteText, day: LocalDate) = DateTimeFormatter.ofPattern("EEE d MMM", text.locale).format(day).replace(".", "")

    fun monthLabel(text: SiteText, month: String): String =
        runCatching { DateTimeFormatter.ofPattern("LLLL yyyy", text.locale).format(YearMonth.parse(month).atDay(15)) }.getOrDefault(month)

    fun sections(text: SiteText, answer: HeatHistory): List<DetailSection> =
        listOfNotNull(costBars(text, answer), use(text, answer), degree(text, answer), comfort(text, answer), shadow(text, answer), review(text, answer), model(text, answer), runs(text, answer))

    const val COST = HouseDetails.COST
    const val SAVING = HouseDetails.SAVING

    /**
     * The cost bars with what SpotNav's plan would have saved hatched on top (the card's `heatBars`): none where no bucket
     * has both a cost and that figure. A bar is a day of a month or a month of a year; where nothing is priced it is the kWh.
     */
    fun costBars(text: SiteText, answer: HeatHistory): DetailSection? {
        val words = text.words
        val buckets = answer.buckets
        if (buckets.isEmpty() || buckets.all { it.kwh == null } || buckets.all { it.shadowValue == null || it.cost == null }) return null
        val priced = buckets.any { it.cost != null }
        val bars = buckets.mapIndexed { index, item ->
            val (`when`, axis) = HouseGraph.bucketWords(text.locale, answer.period, item.key, index, buckets.size)
            val kwh = item.kwh
            val said = if (kwh == null) words.say("hist.heat.barNone", "when" to `when`) else buildList {
                add("${`when`}: ${kwh(text, kwh)}")
                item.cost?.let { add(money(text, it, answer)) }
                if (item.shadowValue != null && item.cost != null) add(words.say("hist.heat.barSaving", "saving" to money(text, item.shadowValue, answer)))
                if (item.zonesKwh != null && item.zonesKwh > 0) add(words.say("hist.heat.barZones", "kwh" to kwh(text, item.zonesKwh)))
                item.hotWaterKwh?.let { add(words.say("hist.heat.barHotWater", "kwh" to kwh(text, it))) }
                item.outdoorC?.let { add(words.say("hist.heat.barOutdoor", "temp" to celsius(text, it))) }
                item.degreeDays?.let { add(words.say("hist.heat.barDegreeDays", "dd" to text.fixed(it, 1))) }
            }.joinToString(" · ")
            if (!priced) return@mapIndexed Bar(item.key, if (kwh == null || kwh <= 0) emptyList() else listOf(listOf(BarPart(COST, kwh))), said, axis)
            val parts = HouseDetails.moneyParts(if (kwh == null) null else item.cost, if (item.cost == null) null else item.shadowValue)
            val lane = listOf(BarPart(COST, parts.solid), BarPart(SAVING, parts.hatch)).filter { it.value > 0 }
            Bar(item.key, if (lane.isEmpty()) emptyList() else listOf(lane), said, axis)
        }
        val series = if (!priced) emptyList() else buildList {
            add(BarSeries(COST, words.say("hist.legend.cost"), 0))
            // The saving's swatch only where some bar has one.
            if (buckets.any { it.cost != null && (it.shadowValue ?: 0.0) > 0 }) add(BarSeries(SAVING, words.say("hist.heat.legend.saving"), 0, hatched = true))
        }
        val label = words.say("hist.heat.chart")
        val scale: (Double) -> String = if (priced) { v -> money(text, v, answer) } else { v -> kwh(text, v) }
        return DetailSection("bars", label, listOf(DetailRow.Bars("bars", label, bars, series, scale, words.say("hist.heat.hint"))))
    }

    /** The closing note: what the money is. */
    fun footnote(text: SiteText): String = text.words.say("hist.heat.footnote")

    private fun use(text: SiteText, answer: HeatHistory): DetailSection {
        val words = text.words
        val title = words.say("hist.heat.use")
        val energy = answer.energy
        if (answer.meter == null || energy == null) return DetailSection("use", title, listOf(DetailRow.Note("no-meter", words.say("hist.heat.noMeter"))))
        if (energy.hours == 0) return DetailSection("use", title, listOf(DetailRow.Note("no-energy", words.say("hist.heat.noEnergy"))))
        val rows = mutableListOf<DetailRow>(DetailRow.Figure("total", words.say("hist.heat.total"), kwh(text, energy.totalKwh)))
        if (energy.zonesKwh != null) {
            rows += DetailRow.Figure("zones", words.say("hist.heat.zones"), kwh(text, energy.zonesKwh))
            energy.zonesFrom?.let { rows += DetailRow.Note("zones-from", words.say("hist.heat.zonesFrom", "date" to dateLabel(text, it))) }
        }
        if (energy.hotWaterKwh != null) {
            rows += DetailRow.Figure("hot-water", words.say("hist.heat.hotWater"), kwh(text, energy.hotWaterKwh))
            if (energy.splitDays < energy.days) {
                rows += DetailRow.Note("split-days", words.say("hist.heat.splitDays", "days" to energy.splitDays.toString(), "total" to energy.days.toString()))
            }
        } else {
            rows += DetailRow.Note("no-split", words.say("hist.heat.noSplit"))
        }
        if (energy.cost != null) {
            rows += DetailRow.Figure("cost", words.say("hist.heat.cost"), money(text, energy.cost, answer))
            if (energy.pricedHours < energy.hours) {
                rows += DetailRow.Note("cost-partial", words.say("hist.house.costPartial", "share" to "${text.fixed(energy.pricedHours.toDouble() / energy.hours * 100, 0)} %"))
            }
        } else {
            rows += DetailRow.Note("no-cost", words.say("hist.house.noPrices"))
        }
        answer.immersion?.let {
            rows += DetailRow.Figure(
                "immersion", words.say("hist.heat.immersion"),
                words.say("hist.heat.immersionFigure", "hours" to it.hours.toString(), "kwh" to kwh(text, it.kwh))
            )
        }
        return DetailSection("use", title, rows)
    }

    private fun trend(text: SiteText, answer: HeatHistory): DetailRow.Trend? {
        val degree = answer.degreeDays ?: return null
        if (degree.trend.isEmpty() || degree.trend.all { it.kwhPerDegreeDay == null }) return null
        val words = text.words
        val bars = degree.trend.map { month ->
            val value = month.kwhPerDegreeDay
            val said = if (value == null) words.say("hist.heat.trendNone", "month" to monthLabel(text, month.month))
            else words.say(
                "hist.heat.trendBar",
                "month" to monthLabel(text, month.month), "value" to text.fixed(value, 2), "dd" to text.fixed(month.degreeDays ?: 0.0, 0)
            )
            val axis = runCatching { DateTimeFormatter.ofPattern("LLLLL", text.locale).format(YearMonth.parse(month.month).atDay(15)) }.getOrDefault("")
            Bar("trend-${month.month}", if (value == null || value <= 0) emptyList() else listOf(listOf(BarPart(TREND, value))), said, axis)
        }
        return DetailRow.Trend("trend", words.say("hist.heat.trend"), bars, words.say("hist.heat.perDegreeDayUnit"))
    }

    const val TREND = "trend"

    private fun degree(text: SiteText, answer: HeatHistory): DetailSection {
        val words = text.words
        val title = words.say("hist.heat.degreeDays")
        val degree = answer.degreeDays ?: return DetailSection("degree-days", title, listOf(DetailRow.Note("no-outdoor", words.say("hist.heat.noOutdoor"))))
        val rows = mutableListOf<DetailRow>()
        val value = degree.kwhPerDegreeDay
        if (value == null) {
            // No figure yet: one line on when there will be one, not the arithmetic of none.
            rows += DetailRow.Note("too-few", words.say("hist.heat.tooFewLine"))
        } else {
            rows += DetailRow.Figure("per-degree-day", words.say("hist.heat.perDegreeDay"), "${text.fixed(value, 2)} ${words.say("hist.heat.perDegreeDayUnit")}")
            rows += DetailRow.Note(
                "degree-note",
                words.say("hist.heat.degreeNote", "base" to text.fixed(degree.baseC, 0), "sum" to text.fixed(degree.sum, 0), "days" to degree.days.toString())
            )
            if (degree.outdoorSource == "house_forecast") rows += DetailRow.Note("from-forecast", words.say("hist.heat.fromForecast"))
        }
        trend(text, answer)?.let { rows += it }
        return DetailSection("degree-days", title, rows)
    }

    private fun comfort(text: SiteText, answer: HeatHistory): DetailSection {
        val words = text.words
        val title = words.say("hist.heat.comfort")
        val comfort = answer.comfort
        val coldest = comfort?.coldest
        if (comfort == null || coldest == null) {
            return DetailSection("comfort", title, listOf(DetailRow.Note("no-rooms", words.say(if (comfort == null) "hist.heat.noRooms" else "hist.heat.noRoomHours"))))
        }
        return DetailSection(
            "comfort", title,
            listOf(
                DetailRow.Figure("coldest", words.say("hist.heat.coldest"), HeatNowModel.roomName(coldest.name)),
                DetailRow.Figure("coldest-figure", "", words.say("hist.heat.coldestFigure", "mean" to celsius(text, coldest.meanC), "min" to celsius(text, coldest.minC))),
                DetailRow.Figure(
                    "below", words.say("hist.heat.below", "low" to celsius(text, comfort.lowC)),
                    words.say("hist.heat.belowFigure", "hours" to comfort.hoursBelow.toString(), "total" to comfort.hours.toString())
                )
            )
        )
    }

    private fun shadow(text: SiteText, answer: HeatHistory): DetailSection? {
        if (answer.meter == null) return null
        val words = text.words
        val title = words.say("hist.heat.shadow")
        val shadow = answer.shadow ?: return DetailSection("shadow", title, listOf(DetailRow.Note("no-shadow", words.say("hist.heat.shadowNone"))))
        fun percent(share: Double) = "${text.fixed(share * 100, 0)} %"
        return DetailSection(
            "shadow", title,
            listOf(
                DetailRow.Figure("value", words.say("hist.heat.shadowValue"), money(text, shadow.value, answer)),
                DetailRow.Note(
                    "shadow-line",
                    words.say("hist.heat.shadowLine", "kwh" to kwh(text, shadow.movedKwh), "hours" to shadow.loweredHours.toString(), "days" to shadow.days.toString())
                ),
                DetailRow.Note(
                    "shadow-note",
                    words.say(
                        "hist.heat.shadowNote",
                        "kept" to shadow.keptDays.toString(), "days" to shadow.days.toString(),
                        "share" to percent(shadow.lowerShare), "rebound" to percent(shadow.rebound)
                    )
                ),
                DetailRow.Note(
                    "effect",
                    if (shadow.blocks > 0) words.say("hist.heat.shadowLearned", "blocks" to shadow.blocks.toString()) else words.say("hist.heat.shadowPrior")
                )
            )
        )
    }

    private fun review(text: SiteText, answer: HeatHistory): DetailSection? {
        val review = answer.review
        if (answer.period != HistoryKind.DAY || review == null) return null
        val words = text.words
        fun hours(value: Double) = "${text.fixed(value, if (value == Math.floor(value)) 0 else 1)} h"
        fun comfort(part: se.sensnology.spotnav.ha.heat.HeatReview.Part) =
            if (part.coldestMinC == null) words.say("hist.heat.reviewNoRooms")
            else words.say("hist.heat.reviewComfort", "min" to celsius(text, part.coldestMinC), "minutes" to part.minutesBelow.toString())
        return DetailSection(
            "review", words.say("hist.heat.review"),
            listOf(
                DetailRow.Figure("review-relay", words.say("hist.heat.reviewRelay", "hours" to hours(review.relay.hours)), money(text, review.relay.value, answer)),
                DetailRow.Figure("review-relay-comfort", "", comfort(review.relay)),
                DetailRow.Figure("review-plan", words.say("hist.heat.reviewPlan", "hours" to hours(review.plan.hours)), money(text, review.plan.value, answer)),
                DetailRow.Figure("review-plan-comfort", "", comfort(review.plan)),
                DetailRow.Note("review-note", words.say("hist.heat.reviewNote", "low" to celsius(text, review.lowC), "overlap" to hours(review.plan.overlapHours)))
            )
        )
    }

    /** How well the house model forecasts the heat: its mean error against the meter over the reviewed days. */
    private fun model(text: SiteText, answer: HeatHistory): DetailSection? {
        if (answer.meter == null) return null
        val words = text.words
        val title = words.say("hist.heat.model")
        val model = answer.model ?: return DetailSection("model", title, listOf(DetailRow.Note("no-model", words.say("hist.heat.modelNone"))))
        return DetailSection(
            "model", title,
            listOf(
                DetailRow.Figure(
                    "model-error", words.say("hist.heat.modelError"),
                    "±${text.fixed(model.maeKw, if (model.maeKw < 1) 2 else 1)} kW (${words.say("hist.days", "days" to model.days.toString())})"
                ),
                DetailRow.Note("model-note", words.say("hist.heat.modelNote"))
            )
        )
    }

    private fun runs(text: SiteText, answer: HeatHistory): DetailSection? {
        if (answer.period != HistoryKind.DAY || answer.meter == null) return null
        val words = text.words
        val title = words.say("hist.heat.runs")
        if (answer.runs.isEmpty()) {
            return DetailSection("runs", title, listOf(DetailRow.Note("no-runs", words.say(if (answer.energy?.hotWaterKwh == null) "hist.heat.noSplit" else "hist.heat.noRuns"))))
        }
        val rows = answer.runs.map { run ->
            val cause = when (run.cause) {
                "draw" -> words.say("hist.heat.causeDraw")
                "standby" -> words.say("hist.heat.causeStandby")
                else -> words.say("hist.heat.causeUnknown")
            }
            DetailRow.Figure(
                "run-${run.start}",
                "${text.clock(run.start, answer.zone)}–${text.clock(run.end, answer.zone)}",
                "${kwh(text, run.kwh)} · $cause"
            )
        } + DetailRow.Note("runs-note", words.say("hist.heat.runsNote"))
        return DetailSection("runs", title, rows)
    }
}
