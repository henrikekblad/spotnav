package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.HouseHistory
import se.sensnology.spotnav.ha.house.HouseHistory.HistoryKind
import java.util.Locale

/**
 * The parts of the Intäkt box, in the order they stack, bottom up; the plan's would-be figure stands beside the stack
 * ([WOULD], hatched). [key] is the part's name in the legend and in what is remembered; [word] is the card's word for it.
 */
internal enum class EarnPart(val key: String, val word: String) {
    BATTERY("battery", "house.earn.part.battery"),
    OWN("own", "house.earn.part.own"),
    HEAT("heat", "house.earn.part.heat"),
    CAR("car", "house.earn.part.car"),
    WOULD("would", "house.earn.part.would");

    companion object {
        fun of(key: String): EarnPart? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The Intäkt box's figures, as the Home Assistant card works them out (`frontend/src/house-earnings.ts`): what SpotNav
 * saved over a day, month or year, one stack per hour, day or month with a part each for the battery's arbitrage, the
 * heat and the car; a part that cost money goes below the line. Before SpotNav controls the battery, the battery has its
 * own earnings (counted in the headline: it is money the house got) and, beside it in a lane of its own and hatched, what
 * SpotNav's plan would have earned (never in the headline: nothing was done). Pure: the view draws what this says.
 */
internal object HouseEarn {
    const val VIEW = "house.earn"

    /** A bucket's figure for a part (minor unit); `null` where the bucket has none. */
    fun bucketPart(bucket: HouseHistory.Bucket, part: EarnPart): Double? = when (part) {
        EarnPart.BATTERY -> bucket.savingBattery
        EarnPart.OWN -> bucket.ownBattery
        EarnPart.WOULD -> bucket.wouldBattery
        EarnPart.HEAT -> bucket.savingHeat
        EarnPart.CAR -> bucket.savingCar
    }

    /** The period's sum of a part: the answer's own sums (a day's heat is in them and in no hour); `null` without it. */
    fun partTotal(history: HouseHistory, part: EarnPart): Double? = when (part) {
        EarnPart.BATTERY -> history.saving?.battery
        EarnPart.OWN -> history.ownControl?.earned
        EarnPart.WOULD -> history.ownControl?.would
        EarnPart.HEAT -> history.saving?.heat
        EarnPart.CAR -> history.saving?.car
    }

    /** The parts the period has, in their order: a part with neither a sum nor a bucket's figure is left out. */
    fun parts(history: HouseHistory): List<EarnPart> =
        EarnPart.entries.filter { part -> partTotal(history, part) != null || history.buckets.any { bucketPart(it, part) != null } }

    /** The headline's sum: the parts drawn, never the plan's would-be figure; `null` with none of them. */
    fun total(history: HouseHistory, hidden: Set<String>): Double? =
        parts(history).filter { it != EarnPart.WOULD && it.key !in hidden }.mapNotNull { partTotal(history, it) }
            .takeIf { it.isNotEmpty() }?.sum()

    /** Whether any bucket has a figure for one of [parts] (else the period has only its sums). */
    fun hasBars(history: HouseHistory, parts: Collection<EarnPart>): Boolean =
        history.buckets.any { bucket -> parts.any { bucketPart(bucket, it) != null } }

    /** The plan's figure is a toggle, and a lane, only where some bucket of the period has it (a day's hours have it in words alone). */
    fun wouldDrawn(history: HouseHistory): Boolean = history.buckets.any { it.wouldBattery != null }

    /** The legend's parts, each with its sum in its label ("Bil 20 kr"). */
    fun legend(history: HouseHistory, text: SiteText, money: House.Money?): List<Pair<EarnPart, String>> =
        parts(history).filter { it != EarnPart.WOULD || wouldDrawn(history) }.map { part ->
            val word = text.words.say(part.word)
            val total = partTotal(history, part)
            part to (if (total == null) word else "$word ${text.amount(total, money)}")
        }

    /** The legend as the bars' series: the plan's figure hatched. */
    fun series(history: HouseHistory, colours: SiteColours, text: SiteText, money: House.Money?): List<BarSeries> =
        legend(history, text, money).map { (part, label) -> BarSeries(part.key, label, colour(part, colours), hatched = part == EarnPart.WOULD) }

    /** A part's colour: the battery's, the own control's half way to the muted text, the heat's, the car's. */
    fun colour(part: EarnPart, colours: SiteColours): Int = when (part) {
        EarnPart.BATTERY, EarnPart.WOULD -> colours.of(Tone.BATTERY)
        EarnPart.OWN -> SiteColours.mix(colours.of(Tone.BATTERY), colours.muted, 0.5f)
        EarnPart.HEAT -> colours.of(Tone.HEAT)
        EarnPart.CAR -> colours.of(Tone.CAR)
    }

    /** The bars: `null` when no bucket has a figure for a part drawn. */
    fun bars(text: SiteText, locale: Locale, history: HouseHistory, hidden: Set<String>, money: House.Money?): List<Bar>? {
        val drawn = parts(history).filter { it.key !in hidden }
        if (!hasBars(history, drawn)) return null
        val stacked = drawn.filter { it != EarnPart.WOULD }
        // The plan's figure has a lane of its own beside the stack wherever the period draws it at all.
        val beside = EarnPart.WOULD in drawn && wouldDrawn(history)
        val n = history.buckets.size
        return history.buckets.mapIndexed { index, bucket ->
            val (`when`, axis) = HouseGraph.bucketWords(locale, history.period, bucket.key, index, n)
            val figures = drawn.mapNotNull { part -> bucketPart(bucket, part)?.let { part to it } }
            val lanes = mutableListOf<List<BarPart>>()
            val slots = mutableListOf<Int>()
            val up = stacked.mapNotNull { part -> (bucketPart(bucket, part) ?: 0.0).takeIf { it > 0 }?.let { BarPart(part.key, it) } }
            val down = stacked.mapNotNull { part -> (bucketPart(bucket, part) ?: 0.0).takeIf { it < 0 }?.let { BarPart(part.key, it) } }
            for (lane in listOf(up, down)) if (lane.isNotEmpty()) { lanes += lane; slots += 0 }
            val would = bucket.wouldBattery
            if (beside && would != null && would != 0.0) {
                lanes += listOf(BarPart(EarnPart.WOULD.key, would))
                slots += 1
            }
            // The battery's figure explained in place of its two parts, where the backend kept it.
            val flow = bucket.batteryFlow?.takeIf { EarnPart.BATTERY in drawn || EarnPart.OWN in drawn }?.let { HouseGraph.flowWords(text, it, money) }
            val said = figures
                .filter { (part, _) -> flow == null || (part != EarnPart.BATTERY && part != EarnPart.OWN) }
                .map { (part, value) -> "${text.words.say(part.word)} ${text.amount(value, money)}" }
                .toMutableList()
            if (flow != null) said.add(0, flow)
            Bar(
                key = bucket.key, lanes = lanes, slots = slots, slotCount = if (beside) 2 else 1, axis = axis,
                words = if (said.isEmpty()) text.words.say("house.earn.barNone", "when" to `when`) else "${`when`}: ${said.joinToString(" · ")}"
            )
        }
    }

    /**
     * The notes under the bars, each a plain sentence: where the car's saving is counted, the heat's whole-day
     * estimate, the plan's would-be figure for a day, and an older day's hours.
     */
    fun notes(text: SiteText, history: HouseHistory, hidden: Set<String>, money: House.Money?): List<String> {
        val words = text.words
        val parts = parts(history).filter { it.key !in hidden }
        val day = history.period == HistoryKind.DAY
        val running = history.running
        val notes = mutableListOf<String>()
        val real = parts.filter { it != EarnPart.HEAT && it != EarnPart.WOULD }
        if (day && !hasBars(history, real) && real.isNotEmpty() && !running) notes += words.say("house.earn.note.noHours")
        if (EarnPart.OWN in parts) {
            val control = history.ownControl
            if (control != null && control.would != null) {
                if (day) notes += words.say("house.earn.note.wouldDay", "amount" to text.amount(control.would, money))
                else if (control.wouldDays < control.days) {
                    notes += words.say("house.earn.note.wouldDays", "reviewed" to control.wouldDays.toString(), "days" to control.days.toString())
                }
            } else if (control != null) {
                notes += words.say(if (running && day) "house.earn.note.wouldTonight" else "house.earn.note.wouldNone")
            }
        }
        if (EarnPart.CAR in parts) notes += words.say(if (day) "house.earn.note.carHour" else "house.earn.note.carDay")
        if (EarnPart.HEAT in parts) notes += words.say(if (day) "house.earn.note.heatDay" else "house.earn.note.heat")
        return notes
    }
}
