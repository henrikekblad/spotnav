package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.heat.Heat
import se.sensnology.spotnav.ha.heat.HeatHistory
import se.sensnology.spotnav.ha.heat.HeatIndoorDay
import se.sensnology.spotnav.ha.house.House
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One hour of the day as the chart takes it: the setpoint, what lowered the heat, the hot water and the car. */
internal data class DayHour(
    val startMs: Long,
    val targetC: Double,
    /** The plan's action: `lower`, `preheat`, `night`, `away`, `return` or `normal`. */
    val action: String,
    val reason: String?,
    val executedMin: Double?,
    val raisedMin: Double?,
    val relayMin: Double?,
    val relayExpected: Boolean,
    val car: Boolean
)

/** A span of the day: a hot-water run, or an hour the plan heats water in. */
internal data class DaySpan(val startMs: Long, val endMs: Long)

/** What the Plan box's chart draws, apart from the view (the card's `DayChartInput`). */
internal class DayChart(
    val zone: ZoneId,
    val startMs: Long,
    val endMs: Long,
    val stepMs: Long,
    val points: List<Point>,
    val rooms: Map<String, String>,
    val hours: List<DayHour>,
    val water: List<DaySpan>,
    val lowC: Double,
    val highC: Double,
    /** The moment drawn as now, `null` for a past day. */
    val nowMs: Long?,
    /** SpotNav only watches (its control does not run): a planned raise reads "would raise". */
    val shadow: Boolean
) {
    class Point(val startMs: Long, val meanC: Double, val coldestC: Double, val coldest: String)

    /** Whether the coldest room is worth its own line: it differs from the mean somewhere. */
    val withColdest: Boolean get() = points.any { abs(it.coldestC - it.meanC) >= DIFFERS_C }

    val sorted: List<DayHour> = hours.sortedBy { it.startMs }

    companion object {
        /** The coldest room is drawn (and said) only where it differs from the mean by at least this (°C). */
        const val DIFFERS_C = 0.05
    }
}

/** A legend entry: [kind] picks its swatch and colour in the view. */
internal class DayLegend(val kind: String, val label: String)

/**
 * The Plan box as the Home Assistant card words it (`frontend/src/heat-day-chart.ts` and the review lines of
 * `heat-view.ts`): today's chart from the page's own answer (the rooms up to now, the plan's hours, the hot water),
 * a past day's from its history, the figures of a chosen time in words, the legend and yesterday's review. Pure.
 */
internal object HeatPlanModel {
    const val HOUR_MS = 3_600_000L
    private val RAISE_ACTIONS = setOf("preheat", "return")

    private fun hourOf(hour: se.sensnology.spotnav.ha.heat.HeatBandHour) = DayHour(
        hour.start.toEpochMilli(), hour.targetC, hour.action, hour.reason, hour.executedMin, hour.raisedMin, hour.relayMin, hour.relayExpected, hour.car
    )

    private fun points(day: HeatIndoorDay?): List<DayChart.Point> =
        day?.points?.map { DayChart.Point(it.start.toEpochMilli(), it.meanC, it.coldestC, it.coldest) }.orEmpty()

    /** Today's chart (the page's own answer); `null` with nothing to draw (no measured room and no hour of the plan). */
    fun today(heat: Heat, nowMs: Long): DayChart? {
        val hours = heat.band.map(::hourOf)
        val indoor = heat.indoorDay
        val start = indoor?.start?.toEpochMilli() ?: hours.minOfOrNull { it.startMs } ?: return null
        val end = indoor?.end?.toEpochMilli() ?: (hours.maxOf { it.startMs } + HOUR_MS)
        val chart = DayChart(
            zone = heat.zone, startMs = start, endMs = end, stepMs = (indoor?.stepMin ?: 60) * 60_000L,
            points = points(indoor), rooms = indoor?.rooms.orEmpty(), hours = hours,
            water = heat.band.filter { it.hotWater }.map { DaySpan(it.start.toEpochMilli(), it.start.toEpochMilli() + HOUR_MS) },
            lowC = indoor?.lowC ?: heat.comfort.lowC, highC = indoor?.highC ?: heat.comfort.highC,
            nowMs = nowMs, shadow = heat.shadow
        )
        return chart.takeIf { chart.points.isNotEmpty() || chart.hours.isNotEmpty() }?.takeIf { it.endMs > it.startMs }
    }

    /** A past day's chart from its history: the rooms by the hour, SpotNav's setpoint and the hot-water runs. */
    fun past(history: HeatHistory): DayChart? {
        val day = history.indoorDay ?: return null
        val hours = day.setpoint.orEmpty().map { DayHour(it.start.toEpochMilli(), it.targetC, it.action, null, null, null, null, false, false) }
        val chart = DayChart(
            zone = history.zone, startMs = day.start.toEpochMilli(), endMs = day.end.toEpochMilli(), stepMs = day.stepMin * 60_000L,
            points = points(day), rooms = day.rooms, hours = hours,
            water = history.runs.map { DaySpan(it.start.toEpochMilli(), it.end.toEpochMilli()) },
            lowC = day.lowC, highC = day.highC, nowMs = null, shadow = false
        )
        return chart.takeIf { (it.points.isNotEmpty() || it.hours.isNotEmpty()) && it.endMs > it.startMs }
    }

    /** What lowered the heat in an hour, as the columns draw it. */
    class Lowering(
        /** SpotNav's own lowering, minutes (solid). */
        val executed: Double,
        /** SpotNav's plan lowers the hour (light, the whole hour). */
        val planned: Boolean,
        /** The relay closed by anyone else, minutes, and whether that is only expected. */
        val relay: Double,
        val relayExpected: Boolean,
        /** SpotNav's own raise on SG Ready's B, minutes (solid, warm). */
        val raised: Double,
        /** SpotNav's plan raises the hour (light, warm): a pre-heat or return hour where the pump can be raised. */
        val raisePlanned: Boolean
    )

    fun lowering(hour: DayHour): Lowering {
        val executed = (hour.executedMin ?: 0.0).coerceIn(0.0, 60.0)
        val relay = if (executed > 0) 0.0 else (hour.relayMin ?: 0.0).coerceIn(0.0, 60.0)
        val raisable = hour.raisedMin != null
        return Lowering(
            executed = executed,
            planned = hour.action == "lower",
            relay = relay,
            relayExpected = relay > 0 && hour.relayExpected,
            raised = if (raisable) (hour.raisedMin ?: 0.0).coerceIn(0.0, 60.0) else 0.0,
            raisePlanned = raisable && hour.action in RAISE_ACTIONS
        )
    }

    /** The step a time falls in: its start (ms). */
    fun stepStart(chart: DayChart, ms: Long): Long {
        val count = max(1, ceil((chart.endMs - chart.startMs).toDouble() / chart.stepMs).toInt())
        val index = min(count - 1, max(0, floor((ms - chart.startMs).toDouble() / chart.stepMs).toInt()))
        return chart.startMs + index * chart.stepMs
    }

    /** The index of the step that holds [ms]. */
    fun stepIndex(chart: DayChart, ms: Long): Int = ((stepStart(chart, ms) - chart.startMs) / chart.stepMs).toInt()

    fun stepCount(chart: DayChart): Int = max(1, ceil((chart.endMs - chart.startMs).toDouble() / chart.stepMs).toInt())

    private fun hourAt(chart: DayChart, ms: Long): DayHour? = chart.hours.firstOrNull { it.startMs <= ms && ms < it.startMs + HOUR_MS }

    private fun pointAt(chart: DayChart, ms: Long): DayChart.Point? = chart.points.firstOrNull { it.startMs == ms }

    private fun clock(text: SiteText, chart: DayChart, ms: Long) = text.clock(Instant.ofEpochMilli(ms), chart.zone)

    private fun degrees(text: SiteText, value: Double) = HeatNowModel.degrees(text, value)

    /** The setpoint's legend words: it says when the step down is SpotNav's lowering. */
    fun setpointWords(text: SiteText, hours: List<DayHour>): String =
        text.words.say(if (hours.any { it.action == "lower" }) "heat.indoor.setpointLowers" else "heat.indoor.setpoint")

    /** One step's figures in words: "17:15 · rooms 21,0 °C · coldest Bad 20,6 °C · setpoint 20,5 °C · …". */
    fun figures(text: SiteText, chart: DayChart, ms: Long): String {
        val words = text.words
        val at = stepStart(chart, ms)
        val parts = mutableListOf(clock(text, chart, at))
        pointAt(chart, at)?.let { point ->
            parts += words.say("heat.chart.mean", "temp" to degrees(text, point.meanC))
            if (abs(point.coldestC - point.meanC) >= DayChart.DIFFERS_C) {
                val name = chart.rooms[point.coldest]
                parts += words.say("heat.chart.coldest", "room" to HeatNowModel.roomName(name ?: point.coldest), "temp" to degrees(text, point.coldestC))
            }
        }
        val hour = hourAt(chart, at)
        if (hour != null) {
            parts += words.say("heat.chart.setpoint", "temp" to degrees(text, hour.targetC))
            val lowering = lowering(hour)
            if (lowering.executed > 0) {
                parts += words.say("heat.band.executedHour", "minutes" to lowering.executed.roundToInt().toString())
            } else if (lowering.relay > 0) {
                parts += words.say(if (lowering.relayExpected) "heat.band.relayHourExpected" else "heat.band.relayHour", "minutes" to lowering.relay.roundToInt().toString())
            }
            if (lowering.planned && lowering.executed == 0.0) parts += words.say("heat.chart.plannedHour")
            if (lowering.raised > 0) {
                parts += words.say("heat.band.raisedHour", "minutes" to lowering.raised.roundToInt().toString())
            } else if (lowering.raisePlanned) {
                parts += words.say(if (chart.shadow) "heat.chart.wouldRaiseHour" else "heat.chart.plannedRaiseHour")
            }
            if (lowering.planned && hour.reason == "room_warm") parts += words.say("heat.chart.roomWarm")
            when (hour.action) {
                "preheat", "return" -> parts += words.say("heat.chart.preheat")
                "night" -> parts += words.say("heat.chart.night")
                "away" -> parts += words.say("heat.chart.away")
            }
        }
        val end = at + chart.stepMs
        if (chart.water.any { it.startMs < end && at < it.endMs }) parts += words.say("heat.chart.hotWater")
        if (hour?.car == true) parts += words.say("heat.chart.car")
        return parts.joinToString(" · ")
    }

    /** The legend: only what the day has, in the card's order. */
    fun legend(text: SiteText, chart: DayChart): List<DayLegend> {
        val words = text.words
        val lowerings = chart.sorted.map(::lowering)
        return buildList {
            if (chart.points.isNotEmpty()) add(DayLegend("indoor_mean", words.say("heat.indoor.mean")))
            if (chart.withColdest) add(DayLegend("indoor_coldest", words.say("heat.indoor.coldest")))
            if (chart.sorted.isNotEmpty()) add(DayLegend("setpoint", setpointWords(text, chart.sorted)))
            add(DayLegend("comfort", words.say("heat.indoor.comfort", "low" to text.fixed(chart.lowC, 1), "high" to text.fixed(chart.highC, 1))))
            if (lowerings.any { it.executed > 0 }) add(DayLegend("executed", words.say("heat.band.executed")))
            if (lowerings.any { it.planned }) add(DayLegend("planned", words.say("heat.chart.planned")))
            if (lowerings.any { it.raised > 0 }) add(DayLegend("raised", words.say("heat.band.raised")))
            if (lowerings.any { it.raisePlanned }) add(DayLegend("raise_planned", words.say(if (chart.shadow) "heat.chart.wouldRaise" else "heat.chart.plannedRaise")))
            if (lowerings.any { it.relay > 0 && !it.relayExpected }) add(DayLegend("relay", words.say("heat.band.relay")))
            if (lowerings.any { it.relayExpected }) add(DayLegend("relay_expected", words.say("heat.band.relayExpected")))
            if (chart.water.isNotEmpty()) add(DayLegend("hot_water", words.say("heat.band.hotWater")))
            if (chart.sorted.any { it.car }) add(DayLegend("car", words.say("heat.band.car")))
        }
    }

    /** The temperature axis: the comfort band, the rooms and the setpoint, never less than two degrees. */
    fun temperatureRange(chart: DayChart): Pair<Double, Double> {
        val all = listOf(chart.lowC, chart.highC) + chart.points.flatMap { listOf(it.meanC, it.coldestC) } + chart.hours.map { it.targetC }
        var low = all.min() - 0.3
        var high = all.max() + 0.3
        if (high - low < MIN_SPAN_C) {
            val middle = (high + low) / 2
            low = middle - MIN_SPAN_C / 2
            high = middle + MIN_SPAN_C / 2
        }
        return low to high
    }

    private const val MIN_SPAN_C = 2.0

    /** The car's hours, a run of them as one bar. */
    fun carRuns(chart: DayChart): List<DaySpan> {
        val runs = mutableListOf<DaySpan>()
        for (hour in chart.sorted) {
            if (!hour.car) continue
            val last = runs.lastOrNull()
            if (last != null && last.endMs == hour.startMs) runs[runs.size - 1] = DaySpan(last.startMs, hour.startMs + HOUR_MS)
            else runs += DaySpan(hour.startMs, hour.startMs + HOUR_MS)
        }
        return runs
    }

    private fun hoursWord(text: SiteText, hours: Double): String = "${text.fixed(hours, if (hours == Math.floor(hours)) 0 else 1)} h"

    /**
     * Yesterday's review in two lines: what the relay really did and what SpotNav's plan would have done, each with what it
     * saved and the coldest room. Under today's chart only; [money] is today's money units.
     */
    fun reviewLines(text: SiteText, heat: Heat): List<Pair<String, String>> {
        val review = heat.review ?: return emptyList()
        val words = text.words
        val money: House.Money? = heat.today?.money
        fun amount(minor: Double): String = when {
            money?.majorUnit == null -> text.fixed(minor, 0)
            else -> {
                val major = minor / 100
                "${text.fixed(major, if (abs(major) >= 10) 0 else 2)} ${money.majorUnit}"
            }
        }
        fun comfort(part: se.sensnology.spotnav.ha.heat.HeatReview.Part): List<String> {
            val out = mutableListOf<String>()
            val coldest = part.coldestMinC ?: return out
            out += words.say("heat.review.coldest", "temp" to degrees(text, coldest).replace(" ", NBSP))
            if (part.minutesBelow > 0) {
                out += words.say("heat.review.below", "minutes" to part.minutesBelow.toString(), "low" to degrees(text, review.lowC).replace(" ", NBSP))
            }
            return out
        }
        val relay = if (review.relay.hours > 0) {
            listOf(words.say("heat.review.relay", "hours" to hoursWord(text, review.relay.hours), "value" to amount(review.relay.value))) + comfort(review.relay)
        } else listOf(words.say("heat.review.relayNone"))
        val plan = (if (review.plan.hours > 0) {
            listOf(words.say("heat.review.plan", "hours" to hoursWord(text, review.plan.hours), "value" to amount(review.plan.value))) + comfort(review.plan)
        } else listOf(words.say("heat.review.planNone"))).toMutableList()
        if (review.plan.hours > 0 && review.plan.overlapHours > 0) {
            plan += words.say("heat.review.overlap", "hours" to hoursWord(text, review.plan.overlapHours))
        }
        return listOf("review-relay" to relay.joinToString(" · "), "review-plan" to plan.joinToString(" · "))
    }

    private const val NBSP = "\u00a0"
}
