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
    val shadow: Boolean,
    /** The boosts planned (hollow droplets with a bar) and the peaks they fill; none on a past day. */
    val planned: List<DayText> = emptyList(),
    val peaks: List<DayPeak> = emptyList(),
    /** The hours the hot water's rules took out of the heat plan, said in the readout only. */
    val notes: List<DayText> = emptyList()
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

/** A span of the day said in words: a planned boost (drawn) or an hour the hot water's rules took out of the plan (said only). */
internal data class DayText(val startMs: Long, val endMs: Long, val text: String)

/** A hot-water peak: a tick on the time axis, its words in the readout ("dusch 06:00"). */
internal data class DayPeak(val ms: Long, val label: String)

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

    /**
     * Today's chart (the page's own answer); `null` with nothing to draw (no measured room and no hour of the plan).
     * With [text] the hot water's planned boosts, peaks and cuts carry their words for the readout.
     */
    fun today(heat: Heat, nowMs: Long, text: SiteText? = null): DayChart? {
        val hours = heat.band.map(::hourOf)
        val indoor = heat.indoorDay
        val start = indoor?.start?.toEpochMilli() ?: hours.minOfOrNull { it.startMs } ?: return null
        val end = indoor?.end?.toEpochMilli() ?: (hours.maxOf { it.startMs } + HOUR_MS)
        val water = waterOf(heat, start, end, text)
        val chart = DayChart(
            zone = heat.zone, startMs = start, endMs = end, stepMs = (indoor?.stepMin ?: 60) * 60_000L,
            points = points(indoor), rooms = indoor?.rooms.orEmpty(), hours = hours,
            water = water.water,
            lowC = indoor?.lowC ?: heat.comfort.lowC, highC = indoor?.highC ?: heat.comfort.highC,
            nowMs = nowMs, shadow = heat.shadow,
            planned = water.planned, peaks = water.peaks, notes = water.notes
        )
        return chart.takeIf { chart.points.isNotEmpty() || chart.hours.isNotEmpty() }?.takeIf { it.endMs > it.startMs }
    }

    private class Water(val water: List<DaySpan>, val planned: List<DayText>, val peaks: List<DayPeak>, val notes: List<DayText>)

    /** The day chart's hot water: the measured runs, the boosts planned and the peaks; without the hot-water block, the
     * band's hours marked hot water (the card's `waterOf`). Words come with the chart's figures ([figures]). */
    private fun waterOf(heat: Heat, startMs: Long, endMs: Long, text: SiteText? = null): Water {
        val hotWater = heat.hotWater
            ?: return Water(heat.band.filter { it.hotWater }.map { DaySpan(it.start.toEpochMilli(), it.start.toEpochMilli() + HOUR_MS) }, emptyList(), emptyList(), emptyList())
        val runs = if (hotWater.runsToday.isNotEmpty()) hotWater.runsToday.map { it.start to it.end } else heat.today?.runs.orEmpty()
        val water = runs.map { DaySpan(it.first.toEpochMilli(), it.second.toEpochMilli()) }
        val planned = hotWater.planned
            .map { DayText(it.start.toEpochMilli(), it.end.toEpochMilli(), text?.let { t -> plannedWaterText(t, heat, it) } ?: "") }
            .filter { it.endMs > startMs && it.startMs < endMs }
        val date = heat.now.atZone(heat.zone).toLocalDate()
        val peaks = hotWater.peaks.mapNotNull { peak ->
            val ms = runCatching { date.atTime(java.time.LocalTime.parse(peak.readyBy)).atZone(heat.zone).toInstant().toEpochMilli() }.getOrNull()
            ms?.takeIf { it in startMs until endMs }?.let { DayPeak(it, text?.words?.say("heat.chart.peak", "time" to text.clock(Instant.ofEpochMilli(it), heat.zone)) ?: "") }
        }
        val notes = hotWater.blocking?.cuts.orEmpty()
            .map { DayText(it.start.toEpochMilli(), it.end.toEpochMilli(), text?.let { t -> cutText(t, it) } ?: "") }
            .filter { it.endMs > startMs && it.startMs < endMs }
        return Water(water, planned, peaks, notes)
    }

    /** A price per kWh in the minor unit ("42 öre"). */
    private fun perKwh(text: SiteText, minor: Double, money: House.Money?): String {
        val unit = money?.minorUnit
        return if (unit == null) text.fixed(minor, 0) else "${text.fixed(minor, 0)} $unit"
    }

    /** One planned boost in words: what SpotNav does or would do, and once past in shadow, what the pump did itself
     * ("Skulle ha värmt varmvatten 04:15 · pumpen körde själv 06:20"); the card's `plannedWaterText`. */
    fun plannedWaterText(text: SiteText, heat: Heat, item: se.sensnology.spotnav.ha.heat.HeatHotWater.Planned): String {
        val words = text.words
        val past = !item.start.isAfter(heat.now)
        val parts = mutableListOf<String>()
        if (!item.live && past) {
            parts += words.say("heat.chart.waterWouldPast", "time" to text.clock(item.start, heat.zone))
        } else {
            parts += words.say(
                if (item.live) "heat.chart.water" else "heat.chart.waterWould",
                "from" to text.clock(item.start, heat.zone), "to" to text.clock(item.end, heat.zone),
                "price" to perKwh(text, item.price, heat.today?.money), "peak" to item.peak
            )
        }
        if (item.reason == "sun") parts += words.say("heat.chart.waterSun")
        if (!item.live && item.pumpRun != null) parts += words.say("heat.chart.waterPumpRan", "time" to text.clock(item.pumpRun, heat.zone))
        return parts.joinToString(" · ")
    }

    private val CUT_KEYS = mapOf(
        "peak" to ("heat.chart.cutPeak" to "heat.chart.cutPeakWould"),
        "day_cap" to ("heat.chart.cutDay" to "heat.chart.cutDayWould"),
        "hygiene" to ("heat.chart.cutHygiene" to "heat.chart.cutHygieneWould"),
        "moved" to ("heat.chart.cutMoved" to "heat.chart.cutMovedWould")
    )

    /** An hour level A took out of the heat plan, in words: done (live) or what it would do (shadow); the card's `cutText`. */
    fun cutText(text: SiteText, cut: se.sensnology.spotnav.ha.heat.HeatHotWater.Cut): String {
        val keys = CUT_KEYS[cut.reason] ?: CUT_KEYS.getValue("day_cap")
        return text.words.say(if (cut.live) keys.first else keys.second, "time" to (cut.peak ?: ""))
    }

    /** A sentence's first letter in lower case, for a part after "·". */
    private fun lowerFirst(text: SiteText, line: String): String =
        if (line.isEmpty()) line else line.substring(0, 1).lowercase(text.locale) + line.substring(1)

    /**
     * The hot water's lines under the heat's review (the card's `hotWaterLines`): where the plan stands (in shadow, the
     * gate's progress; steered; back in shadow), level A's blocks, yesterday's review and each peak against what the
     * pump did. Nothing where the pump has no way to make hot water at SpotNav's word.
     */
    fun hotWaterLines(text: SiteText, heat: Heat): List<Pair<String, String>> {
        val hotWater = heat.hotWater ?: return emptyList()
        if (hotWater.level == null || hotWater.mode == "watch") return emptyList()
        val words = text.words
        val out = mutableListOf<Pair<String, String>>()
        val gate = hotWater.gate
        if (hotWater.mode == "live") {
            out += "water-live" to words.say("heat.water.live")
        } else if (hotWater.level == "B") {
            if (gate.open) {
                out += "water-gate" to words.say("heat.water.gateReady")
            } else {
                var line = words.say(
                    "heat.water.gate", "days" to min(gate.days, gate.daysNeeded).toString(), "needed" to gate.daysNeeded.toString(),
                    "hits" to gate.hits.toString(), "peaks" to max(gate.peaks, gate.peaksNeeded).toString()
                )
                if (gate.days >= gate.daysNeeded && gate.weekdays < 7) line += " · ${words.say("heat.water.gateWeekdays", "weekdays" to gate.weekdays.toString())}"
                out += "water-gate" to line
            }
            if (gate.backedOff != null) out += "water-backed-off" to words.say("heat.water.backedOff")
        }
        val blocking = hotWater.blocking
        if (blocking != null && blocking.mode != "watch") {
            val blockGate = blocking.gate
            out += when {
                blocking.mode == "live" -> "water-blocking-live" to words.say("heat.water.blockingLive")
                blockGate.open -> "water-blocking-gate" to words.say("heat.water.blockingReady")
                else -> "water-blocking-gate" to words.say(
                    "heat.water.blockingGate", "days" to min(blockGate.days, blockGate.daysNeeded).toString(), "needed" to blockGate.daysNeeded.toString(),
                    "hits" to blockGate.hits.toString(), "peaks" to max(blockGate.peaks, blockGate.peaksNeeded).toString()
                )
            }
            if (blocking.strict) out += "water-blocking-backed-off" to words.say("heat.water.blockingBackedOff")
        }
        val review = hotWater.review ?: return out
        val money = heat.today?.money
        var line = if (review.movedKwh > 0) {
            words.say("heat.water.review", "kwh" to "${text.fixed(review.movedKwh, 1)} kWh", "value" to HeatNowModel.amount(text, review.saved, money))
        } else words.say("heat.water.reviewNone")
        if (review.mode == "shadow") line += " ${words.say("heat.water.shadow")}"
        out += "water-review" to line
        val peaks = review.perPeak.map { item ->
            val key = when {
                item.coldRisk -> "heat.water.peakCold"
                item.miss -> "heat.water.peakMiss"
                item.hit -> "heat.water.peakHit"
                else -> "heat.water.peakNoUse"
            }
            val parts = mutableListOf(words.say(key, "peak" to item.peak))
            if (item.pick != null && review.mode == "shadow") {
                parts += lowerFirst(text, words.say("heat.chart.waterWouldPast", "time" to text.clock(item.pick, heat.zone)))
                item.pumpRun?.let { parts += words.say("heat.chart.waterPumpRan", "time" to text.clock(it, heat.zone)) }
            }
            parts.joinToString(" · ")
        }
        if (peaks.isNotEmpty()) out += "water-peaks" to words.say("heat.water.peaks", "list" to peaks.joinToString("; "))
        if (blocking != null && (review.blocksCut > 0 || review.coldRisks > 0)) {
            out += "water-blocking-review" to words.say(
                if (review.aMode == "live") "heat.water.blockingReview" else "heat.water.blockingReviewWould",
                "hours" to review.blocksCut.toString(), "risks" to review.coldRisks.toString()
            )
        }
        return out
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
            for (note in chart.notes) if (note.startMs < at + chart.stepMs && at < note.endMs) parts += note.text
            when (hour.action) {
                "preheat", "return" -> parts += words.say("heat.chart.preheat")
                "night" -> parts += words.say("heat.chart.night")
                "away" -> parts += words.say("heat.chart.away")
            }
        }
        val end = at + chart.stepMs
        if (chart.water.any { it.startMs < end && at < it.endMs }) parts += words.say("heat.chart.hotWater")
        for (span in chart.planned) if (span.startMs < end && at < span.endMs) parts += span.text
        for (peak in chart.peaks) if (peak.ms >= at && peak.ms < end) parts += peak.label
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
            if (chart.water.isNotEmpty() || chart.planned.isNotEmpty()) add(DayLegend("hot_water", words.say("heat.band.hotWater")))
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
        fun amount(minor: Double): String = text.amount(minor, money)
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
