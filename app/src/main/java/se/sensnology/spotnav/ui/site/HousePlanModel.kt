package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.house.Plan
import se.sensnology.spotnav.ui.common.CardStrings
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** A layer of the Plan box's stacked chart, with the card's word for it. */
internal enum class PlanLayer(val word: String) {
    HOUSE("house.plan.layer.house"),
    HEAT("house.plan.layer.heat"),
    WATER_HEATER("house.plan.layer.waterHeater"),
    CAR("house.plan.layer.car"),
    POOL_HEAT("house.plan.load.pool_heat"),
    POOL_CIRCULATION("house.plan.load.pool_circulation"),
    SPA("house.plan.load.spa"),
    SAUNA("house.plan.load.sauna"),
    BATTERY_IN("house.plan.layer.batteryIn"),
    SUN("house.plan.layer.sun"),
    BATTERY_OUT("house.plan.layer.batteryOut")
}

/** The price strip's tone of an hour: the cheapest third, the middle, the most expensive third of the window's prices. */
internal enum class PriceTone { CHEAP, MID, EXPENSIVE }

/**
 * The house page's Plan box, as the Home Assistant card's Plan tab has it (`frontend/src/house-plan.ts`): the summary
 * (the period's cost, the highest hour against the ceiling, the month's peak), the stacked chart (the loads above zero,
 * the sun and the battery's cover below, the net from the grid as a line, the ceiling labelled, a price strip), a lane
 * per load with one line of why, and the plan in words. A load SpotNav only watches is drawn faded and says "would":
 * the box never claims an action that is not taken. Pure: every figure is Home Assistant's; the views draw what this says.
 */
internal object HousePlanModel {
    data class Stat(val label: String, val value: String, val note: String, val over: Boolean?)

    data class Part(val layer: PlanLayer, val kw: Double, val would: Boolean)

    data class ChartHour(
        val above: List<Part>,
        val below: List<Part>,
        val netKw: Double?,
        val ceilingKw: Double?,
        val tone: PriceTone?,
        /** The hour's two-digit label, every third hour; `null` between. */
        val tick: String?,
        /** The day's cue ("tis 3") where a new day starts after the first hour. */
        val dayCue: String?,
        /** The net goes over the ceiling this hour. */
        val over: Boolean
    )

    data class Chart(
        val hours: List<ChartHour>,
        val maxKw: Double,
        val minKw: Double,
        /** The ceiling's words at its last hour ("Effektmål 6,0 kW"), and that hour's kW; `null` without a ceiling drawn. */
        val ceilingLabel: String?,
        val ceilingLabelKw: Double?,
        val priceStrip: String,
        val description: String
    )

    data class Block(val index: Int, val layer: PlanLayer, val would: Boolean, /** A heat lane's level: below zero lowered, above pre-heated. */ val level: Double?)

    data class Lane(
        /** The load's id: a car lane's is its charger's entry id. */
        val id: String,
        val kind: String,
        val name: String,
        val would: Boolean,
        val blocks: List<Block>,
        val reason: String,
        /** The car's "does not fit" line; `null` while it fits. */
        val doesNotFit: String?,
        /**
         * The line's buttons, as the card's Plan tab offers them: "Later departure" only with a time that fits (its
         * editor is the charger page's), "Allow the peak" asked again on the button ([acceptPeakAgain]).
         */
        val choices: List<DoesNotFitText.Button> = emptyList(),
        val acceptPeakAgain: String = ""
    )

    data class Said(val span: String, val sentence: String, val would: Boolean, val kind: String)

    data class Content(
        val title: String,
        val lede: String,
        val stats: List<Stat>,
        val noPrices: String?,
        val fromGrid: String,
        val chart: Chart,
        val legend: List<Pair<PlanLayer?, String>>,
        val wouldNote: String?,
        val perLoad: String?,
        /** "Per load"'s hint while folded: the number of lanes ("4"). */
        val perLoadHint: String,
        /**
         * A car lane says it does not fit: "Per load" is open under a plain heading and does not fold, so the line and
         * its choices are never folded away (the card's Plan tab does the same).
         */
        val perLoadOpen: Boolean,
        val lanes: List<Lane>,
        val rolling: String?,
        val inWords: String,
        /** "In words"' hint while folded: its number of lines ("6 rader"). */
        val inWordsHint: String,
        val words: List<Said>,
        val nothing: String?,
        /** The word after a watched load's sentence ("skulle"). */
        val wouldTag: String
    )

    /** Whether the card's words have [key] (the run and reason keys are built from the plan's codes). */
    private fun known(key: String): Boolean = key in CardStrings.IDS

    fun content(text: SiteText, plan: Plan): Content {
        val words = text.words
        val n = plan.hours.size
        val lanes = plan.loads.map { lane(text, plan, it) }
        val said = words(text, plan)
        val first = plan.hours.first().start
        val marks = listOf(0.0, 0.25, 0.5, 0.75).map { share -> text.clock(first.plusSeconds((share * n).roundToInt() * HOUR), plan.zone) }
        return Content(
            title = words.say("house.plan.title", "hours" to n.toString()),
            lede = words.say("house.plan.lede"),
            stats = stats(text, plan),
            noPrices = if (plan.hours.all { it.price == null }) words.say("house.plan.noPrices") else null,
            fromGrid = words.say("house.plan.fromGrid"),
            chart = chart(text, plan),
            legend = legend(text, plan),
            wouldNote = if (plan.would) words.say("house.plan.wouldNote") else null,
            perLoad = if (plan.loads.isNotEmpty()) words.say("house.plan.perLoad") else null,
            perLoadHint = lanes.size.toString(),
            perLoadOpen = lanes.any { it.doesNotFit != null },
            lanes = lanes,
            rolling = if (plan.loads.isNotEmpty()) "${marks.joinToString(" · ")} → ${words.say("house.plan.rolling")}" else null,
            inWords = words.say("house.plan.inWords"),
            inWordsHint = words.say(
                "house.plan.rows.${CountWords.form(text.locale.language.let { if (it == "no") "nb" else it }, said.size)}",
                "count" to said.size.toString()
            ),
            words = said,
            nothing = if (said.isEmpty()) words.say("house.plan.nothing") else null,
            wouldTag = words.say("house.plan.would")
        )
    }

    /** A power as the card's `kwText` writes it: one decimal ("3,7 kW"). */
    private fun kw(text: SiteText, value: Double): String = "${text.fixed(value, 1)} kW"

    private fun hourLabel(text: SiteText, at: Instant, plan: Plan): String = text.clock(at, plan.zone).substring(0, 2)

    /** A span of hours as the clock says it ("01–07"). */
    private fun span(text: SiteText, plan: Plan, start: Instant, end: Instant): String = "${hourLabel(text, start, plan)}–${hourLabel(text, end, plan)}"

    fun stats(text: SiteText, plan: Plan): List<Stat> {
        val words = text.words
        val s = plan.summary
        val out = mutableListOf(
            Stat(
                words.say("house.plan.cost"),
                s.cost?.let { text.amount(it, plan.money) } ?: "–",
                words.say("house.plan.costNote", "hours" to plan.hours.size.toString()),
                null
            )
        )
        val peakNote = s.peakStart?.let { at ->
            val key = when {
                s.over -> "house.plan.peakOver"
                s.peakCeilingKw == null -> "house.plan.peakAt"
                else -> "house.plan.peakUnder"
            }
            words.say(key, "at" to text.clock(at, plan.zone))
        }.orEmpty()
        out += Stat(words.say("house.plan.peak"), s.peakKw?.let { kw(text, it) } ?: "–", peakNote, if (s.peakCeilingKw == null) null else s.over)
        val peak = plan.monthPeak
        out += if (peak != null) {
            Stat(
                words.say("house.plan.monthPeak"), kw(text, peak.kw),
                words.say("house.plan.monthPeakNote", "when" to "${text.dayCue(peak.start, plan.zone)} ${text.clock(peak.start, plan.zone)}"), null
            )
        } else Stat(words.say("house.plan.tariff"), "–", words.say("house.plan.noTariff"), null)
        return out
    }

    /** The ceiling's words ("Effekttak 5,0 kW"); a kind without words by its kW alone. */
    fun ceilingWords(text: SiteText, kind: String, kwValue: Double): String {
        val key = "house.plan.ceiling.$kind"
        return if (known(key)) text.words.say(key, "kw" to kw(text, kwValue)) else kw(text, kwValue)
    }

    /** What a load is called: its own name, else its kind's word (the home battery always by its kind's). */
    fun loadName(text: SiteText, load: Plan.Load): String {
        if (load.kind == "home_battery") return text.words.say("house.plan.load.home_battery")
        val key = "house.plan.load.${load.kind}"
        return load.name ?: if (known(key)) text.words.say(key) else load.kind
    }

    /** One run in words ("Bilen laddar 14,8 kWh med 3,7 kW: billig timme"). */
    fun runWords(text: SiteText, load: Plan.Load, run: Plan.Run): String {
        val params = arrayOf(
            "name" to loadName(text, load),
            "kw" to (run.kw?.let { kw(text, abs(it)) } ?: ""),
            "kwh" to (run.kwh?.let { text.kwh(it) } ?: "")
        )
        val key = "house.plan.run.${load.kind}.${run.what}"
        val sentence = text.words.say(if (known(key)) key else "house.plan.run.other", *params)
        val why = run.reason?.let { "house.plan.reason.$it" }
        return if (why != null && known(why)) "$sentence: ${text.words.say(why)}" else sentence
    }

    /** A load's one line of why, from its plan's figures; a watched load's starts "Skulle:". */
    fun laneReason(text: SiteText, plan: Plan, load: Plan.Load): String {
        val words = text.words
        fun num(key: String): Double = load.summary[key] ?: 0.0
        val line = when {
            load.runs.isEmpty() -> words.say("house.plan.lane.none")
            load.kind == "car" -> words.say(
                "house.plan.lane.car", "kwh" to text.kwh(num("kwh")), "hours" to span(text, plan, load.runs.first().start, load.runs.last().end)
            )
            load.kind == "home_battery" -> buildList {
                add(words.say("house.plan.lane.batteryCover", "kwh" to text.kwh(num("cover_kwh"))))
                if (num("grid_charge_kwh") > 0) add(words.say("house.plan.lane.batteryGrid", "kwh" to text.kwh(num("grid_charge_kwh"))))
                val sun = num("charge_kwh") - num("grid_charge_kwh")
                if (sun > 0.05) add(words.say("house.plan.lane.batteryCharge", "kwh" to text.kwh(sun)))
            }.joinToString(" · ")
            load.kind == "heat" -> words.say(
                "house.plan.lane.heat", "lowered" to whole(num("lowered_hours")), "raised" to whole(num("raised_hours"))
            )
            load.kind == "heat_zone" -> words.say("house.plan.lane.zone", "lowered" to whole(num("lowered_hours")))
            load.kind == "water_heater" -> words.say("house.plan.lane.waterHeater", "hours" to text.fixed(num("on_hours"), 0))
            load.kind == "pool_circulation" -> words.say(
                "house.plan.lane.poolCirculation", "hours" to text.fixed(num("planned_h"), 0), "need" to text.upTo(num("need_h"), 2)
            )
            load.kind == "pool_heat" -> words.say("house.plan.lane.poolHeat", "hours" to text.fixed(num("planned_h"), 0))
            load.kind == "spa" || load.kind == "sauna" -> load.summaryText["ready_at"]
                ?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() }
                ?.let { ready -> words.say("house.plan.lane.deadlineReady", "temp" to text.upTo(num("target_c"), 1), "time" to text.clock(ready, plan.zone)) }
                ?: words.say("house.plan.lane.none")
            // A kind this app does not know yet: its runs are in the words below.
            else -> load.runs.joinToString(" · ") { runWords(text, load, it) }
        }
        return if (load.would) "${words.say("house.plan.wouldPrefix")} $line" else line
    }

    private fun whole(value: Double): String = if (value == floor(value)) value.toLong().toString() else value.toString()

    private fun layerOf(load: Plan.Load): PlanLayer? = when (load.kind) {
        "car" -> PlanLayer.CAR
        "heat" -> PlanLayer.HEAT
        "water_heater" -> PlanLayer.WATER_HEATER
        "pool_heat" -> PlanLayer.POOL_HEAT
        "pool_circulation" -> PlanLayer.POOL_CIRCULATION
        "spa" -> PlanLayer.SPA
        "sauna" -> PlanLayer.SAUNA
        else -> null
    }

    fun priceTones(hours: List<Plan.Hour>): List<PriceTone?> {
        val known = hours.mapNotNull { it.price }.sorted()
        if (known.isEmpty()) return hours.map { null }
        val low = known[known.size / 3]
        val high = known[(known.size * 2) / 3]
        return hours.map { hour ->
            val price = hour.price ?: return@map null
            when {
                price < low -> PriceTone.CHEAP
                price < high -> PriceTone.MID
                else -> PriceTone.EXPENSIVE
            }
        }
    }

    fun chart(text: SiteText, plan: Plan): Chart {
        val battery = plan.loads.firstOrNull { it.kind == "home_battery" }
        var top = 1.0
        var bottom = 0.0
        val tones = priceTones(plan.hours)
        val hours = plan.hours.mapIndexed { index, hour ->
            val up = mutableListOf<Part>()
            val down = mutableListOf<Part>()
            // The house's own part: its figure less the loads already inside it.
            val inside = plan.loads.filter { it.inHouse }.sumOf { max(0.0, it.kw[index] ?: 0.0) }
            up += Part(PlanLayer.HOUSE, max(0.0, (hour.houseKw ?: 0.0) - inside), false)
            for (load in plan.loads) {
                val layer = layerOf(load) ?: continue
                val value = load.kw[index] ?: 0.0
                if (value > 0) up += Part(layer, value, load.would)
            }
            val batteryKw = battery?.kw?.get(index) ?: 0.0
            if (batteryKw > 0) up += Part(PlanLayer.BATTERY_IN, batteryKw, battery!!.would)
            val sun = hour.sunKw
            if (sun != null && sun > 0 && !plan.sunInHouse) down += Part(PlanLayer.SUN, sun, false)
            if (batteryKw < 0) down += Part(PlanLayer.BATTERY_OUT, -batteryKw, battery!!.would)
            val ceilingKw = hour.ceilingKw
            top = maxOf(top, up.sumOf { it.kw }, hour.netKw ?: 0.0, if (ceilingKw != null && ceilingKw < 30) ceilingKw else 0.0)
            bottom = minOf(bottom, -down.sumOf { it.kw }, hour.netKw ?: 0.0)
            val local = hourLabel(text, hour.start, plan)
            val net = hour.netKw
            ChartHour(
                above = up, below = down, netKw = net, ceilingKw = ceilingKw, tone = tones[index],
                tick = if (index % 3 == 0) local else null,
                dayCue = if (local == "00" && index > 0) text.dayCue(hour.start, plan.zone) else null,
                over = net != null && ceilingKw != null && net > ceilingKw + 0.01
            )
        }
        val maxKw = ceil(top * 1.08)
        val minKw = if (bottom < 0) floor(bottom * 1.08) else 0.0
        val last = plan.hours.lastOrNull { it.ceilingKw != null && it.ceiling != null && it.ceilingKw <= maxKw }
        return Chart(
            hours = hours,
            maxKw = maxKw,
            minKw = minKw,
            ceilingLabel = last?.let { ceilingWords(text, it.ceiling!!, it.ceilingKw!!) },
            ceilingLabelKw = last?.ceilingKw,
            priceStrip = text.words.say("house.plan.priceStrip"),
            description = text.words.say("house.plan.chartLabel")
        )
    }

    /** The legend: the layers drawn, in the card's order, then the net's line (`null` layer). */
    fun legend(text: SiteText, plan: Plan): List<Pair<PlanLayer?, String>> {
        val kinds = plan.loads.map { it.kind }.toSet()
        val layers = buildList {
            add(PlanLayer.HOUSE)
            if ("heat" in kinds) add(PlanLayer.HEAT)
            if ("water_heater" in kinds) add(PlanLayer.WATER_HEATER)
            if ("car" in kinds) add(PlanLayer.CAR)
            if ("pool_heat" in kinds) add(PlanLayer.POOL_HEAT)
            if ("pool_circulation" in kinds) add(PlanLayer.POOL_CIRCULATION)
            if ("spa" in kinds) add(PlanLayer.SPA)
            if ("sauna" in kinds) add(PlanLayer.SAUNA)
            if ("home_battery" in kinds) add(PlanLayer.BATTERY_IN)
            if (plan.hours.any { it.sunKw != null } && !plan.sunInHouse) add(PlanLayer.SUN)
            if ("home_battery" in kinds) add(PlanLayer.BATTERY_OUT)
        }
        return layers.map { it to text.words.say(it.word) } + (null to text.words.say("house.plan.layer.net"))
    }

    fun lane(text: SiteText, plan: Plan, load: Plan.Load): Lane {
        val layer = layerOf(load)
        val blocks = plan.hours.indices.mapNotNull { index ->
            val value = load.kw[index] ?: 0.0
            val level = load.level?.get(index) ?: 0.0
            val start = plan.hours[index].start
            val drawn: PlanLayer? = when {
                load.kind == "home_battery" -> if (value > 0.05) PlanLayer.BATTERY_IN else if (value < -0.05) PlanLayer.BATTERY_OUT else null
                load.level != null -> if (level != 0.0) PlanLayer.HEAT else null
                value > 0 || load.runs.any { !it.start.isAfter(start) && start.isBefore(it.end) } -> layer ?: PlanLayer.HOUSE
                else -> null
            }
            drawn?.let { Block(index, it, load.would, if (load.level != null) level else null) }
        }
        val zone = plan.zone
        val words = DoesNotFitText(text, zone, plan.money)
        val block = load.doesNotFit
        return Lane(
            load.id, load.kind, loadName(text, load), load.would, blocks, laneReason(text, plan, load),
            block?.let(words::line),
            block?.let { words.buttons(it, releaseOffered = true).filter { button -> button.press != DoesNotFitText.Press.OPEN_DEPARTURE } }.orEmpty(),
            words.acceptPeakAgain()
        )
    }

    /** Every run of every load, by its start: the plan in words. */
    fun words(text: SiteText, plan: Plan): List<Said> = plan.loads
        .flatMap { load -> load.runs.map { load to it } }
        .sortedBy { it.second.start }
        .map { (load, run) -> Said(span(text, plan, run.start, run.end), runWords(text, load, run), load.would, load.kind) }

    private const val HOUR = 3600L
}
