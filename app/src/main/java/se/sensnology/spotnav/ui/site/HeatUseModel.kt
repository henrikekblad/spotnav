package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.heat.HeatHistory
import se.sensnology.spotnav.ha.house.House
import java.util.Locale
import kotlin.math.abs

/** What the Förbrukning box has to say about a period. */
internal sealed interface HeatUseContent {
    /** The site has no heat meter in this answer. */
    data object NoMeter : HeatUseContent

    /** The period holds no heat energy. */
    data object NoEnergy : HeatUseContent

    data object Ready : HeatUseContent
}

/**
 * The Förbrukning box as the Home Assistant card words it (`frontend/src/heat-use.ts`): what the heating used over a
 * day, month or year, a stack per bar split only as far as something measures it (the heat pump less its hot water, the
 * hot water, each heat zone with a sensor of its own, every other heat), in kWh or in money. Pure.
 */
internal object HeatUseModel {
    /** The series key a part is drawn and toggled by (the part's own key, which is what the remembered choice holds). */
    /** The parts SpotNav counts itself (when they ran, at their power). */
    private val COUNTED = setOf("pool", "spa", "sauna")

    private val KIND_WORDS = mapOf(
        "pump" to "heat.use.part.pump", "hot_water" to "heat.use.part.hotWater", "other" to "heat.use.part.other",
        "pool" to "heat.use.part.pool", "spa" to "heat.use.part.spa", "sauna" to "heat.use.part.sauna"
    )

    fun content(answer: HeatHistory): HeatUseContent = when {
        answer.meter == null -> HeatUseContent.NoMeter
        answer.parts.isEmpty() || answer.energy == null || answer.energy.hours == 0 -> HeatUseContent.NoEnergy
        else -> HeatUseContent.Ready
    }

    /** A part's name: a zone's own, else the card's words for its kind. */
    fun partWord(text: SiteText, part: HeatHistory.Part): String =
        if (part.kind == "zone") part.name ?: text.words.say("heat.use.part.zone") else text.words.say(KIND_WORDS.getValue(part.kind))

    /** The colour slot a part is drawn in: its kind, and for the zones their place among them. */
    fun slot(parts: List<HeatHistory.Part>, part: HeatHistory.Part): String =
        if (part.kind != "zone") part.kind else "zone-${parts.filter { it.kind == "zone" }.indexOf(part) % 4}"

    /** Money in the minor unit shown in the major one, as the card writes it everywhere. */
    fun money(text: SiteText, minor: Double, units: House.Money?): String = text.amount(minor, units)

    /** Whether the period can be shown in money: some part has a priced hour. */
    fun hasMoney(answer: HeatHistory): Boolean = answer.money != null && answer.parts.any { it.cost != null }

    /** Whether money is drawn: the one chosen, kWh where the period has no money. */
    fun inMoney(answer: HeatHistory, wanted: Boolean): Boolean = wanted && hasMoney(answer)

    private fun kwh(text: SiteText, value: Double) = text.kwh(value)

    private fun figure(text: SiteText, answer: HeatHistory, money: Boolean, kwh: Double, cost: Double?): String? =
        if (!money) kwh(text, kwh) else cost?.let { money(text, it, answer.money) }

    /** The sum of the parts drawn in the unit shown; `null` with none drawn (or none priced). */
    fun total(answer: HeatHistory, hidden: Set<String>, money: Boolean): Double? {
        val values = answer.parts.filter { it.key !in hidden }.mapNotNull { if (money) it.cost else it.kwh }
        return if (values.isEmpty()) null else values.sum()
    }

    class Headline(val head: String, val sub: String?, val saved: Boolean)

    /** The headline: what SpotNav saved on heat over the period, else the heat's total; and under it the total of the parts drawn. */
    fun headline(text: SiteText, answer: HeatHistory, hidden: Set<String>, wantMoney: Boolean): Headline {
        val money = inMoney(answer, wantMoney)
        val total = total(answer, hidden, money)
        val words = total?.let { if (money) money(text, it, answer.money) else kwh(text, it) }
        val totalLine = if (words == null) text.words.say("house.earn.noneShown") else text.words.say("heat.use.total", "amount" to words)
        val saving = answer.saving
        return if (saving != null) Headline(text.words.say("heat.use.saved", "amount" to money(text, saving.value, answer.money)), totalLine, true)
        else Headline(totalLine, null, false)
    }

    /** The notes under the bars: the days the hot water is split out for, and what the money is. */
    fun notes(text: SiteText, answer: HeatHistory, wantMoney: Boolean): List<String> = buildList {
        val energy = answer.energy
        if (energy != null && energy.days > 0 && energy.splitDays < energy.days) {
            add(
                if (energy.splitDays == 0) text.words.say("hist.heat.noSplit")
                else text.words.say("hist.heat.splitDays", "days" to energy.splitDays.toString(), "total" to energy.days.toString())
            )
        }
        if (inMoney(answer, wantMoney)) add(text.words.say("hist.heat.footnote"))
        // The pool's, the spa's and the sauna's energy is SpotNav's count from when they ran, not a meter's.
        if (answer.parts.any { it.kind in COUNTED }) add(text.words.say("heat.use.loadsNote"))
        // An estimate limited to what the house measured: said once, with what was left out.
        val clamped = answer.parts.sumOf { it.clampedKwh }
        if (clamped > 0) add(text.words.say("house.loads.clamped", "kwh" to LoadsModel.kwh(text, clamped)))
    }

    /** The legend: a toggle per part with its swatch colour and its sum in the unit shown. */
    fun series(text: SiteText, answer: HeatHistory, wantMoney: Boolean, colour: (String) -> Int): List<BarSeries> {
        val money = inMoney(answer, wantMoney)
        return answer.parts.map { part ->
            val sum = figure(text, answer, money, part.kwh, part.cost)
            val words = if (part.estimated) "${partWord(text, part)} ${text.words.say("house.loads.estimated")}" else partWord(text, part)
            BarSeries(part.key, if (sum == null) words else "$words $sum", colour(slot(answer.parts, part)))
        }
    }

    /** The bars, a stack per bucket of the parts drawn; their words say each part and the day's saving. */
    fun bars(text: SiteText, locale: Locale, answer: HeatHistory, hidden: Set<String>, wantMoney: Boolean): List<Bar> {
        val money = inMoney(answer, wantMoney)
        val drawn = answer.parts.filter { it.key !in hidden }
        val n = answer.buckets.size
        return answer.buckets.mapIndexed { index, bucket ->
            val (`when`, axis) = HouseGraph.bucketWords(locale, answer.period, bucket.key, index, n)
            val lane = mutableListOf<BarPart>()
            val said = mutableListOf<String>()
            var sum = 0.0
            for (part in drawn) {
                val figures = bucket.parts.firstOrNull { it.key == part.key } ?: continue
                val amount = (if (money) figures.cost else figures.kwh) ?: continue
                if (amount <= 0) continue
                lane += BarPart(part.key, amount)
                sum += amount
                said += "${partWord(text, part)} ${figure(text, answer, money, figures.kwh, figures.cost).orEmpty()}".trim()
            }
            if (lane.size > 1) said.add(0, if (money) money(text, sum, answer.money) else kwh(text, sum))
            bucket.saving?.let { said += text.words.say("heat.use.barSaved", "amount" to money(text, it, answer.money)) }
            val words = if (said.isEmpty()) text.words.say(if (bucket.kwh == null) "hist.heat.barNone" else "heat.use.barNone", "when" to `when`)
            else "$`when`: ${said.joinToString(" · ")}"
            Bar(bucket.key, if (lane.isEmpty()) emptyList() else listOf(lane), words, axis)
        }
    }

    /** Whether some bar has something to draw. */
    fun drawn(bars: List<Bar>): Boolean = bars.any { it.lanes.isNotEmpty() }

    /** The scale's words for a value. */
    fun scale(text: SiteText, answer: HeatHistory, wantMoney: Boolean): (Double) -> String =
        if (inMoney(answer, wantMoney)) { v -> money(text, v, answer.money) } else { v -> kwh(text, v) }
}
