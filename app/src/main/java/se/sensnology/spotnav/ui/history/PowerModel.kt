package se.sensnology.spotnav.ui.history

import se.sensnology.spotnav.ha.client.PowerMonthRead
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.DemandComponent
import se.sensnology.spotnav.ui.common.CardWords
import se.sensnology.spotnav.ui.settings.DemandText
import java.time.LocalDate
import java.util.Locale

/**
 * The history's Power view in words and columns, as the card's `power-view.ts` has it: one column per day of the month
 * (the day's highest hourly mean of import, split into the house and the car), or per hour of a tapped day; the
 * demand charge's parts named by their hours; the figures a column says when it is chosen. Nothing is recomputed:
 * every figure is Home Assistant's.
 */
internal class PowerModel(private val words: CardWords, private val text: DemandText, private val language: Locale) {
    /** One bar: [key] (the date, or the hour's start), its axis label, its power and the house's part, and its figures. */
    data class Column(
        val key: String,
        val label: String,
        val kw: Double?,
        val house: Double?,
        val counted: List<Int>,
        val held: Boolean,
        val paused: Boolean,
        val incomplete: Boolean,
        val figures: String
    )

    /** The parts' names: on their own ("Dag 06–23") and inside a sentence ("dag 06–23"). */
    data class PartNames(val names: List<String>, val inline: List<String>)

    private fun pad(hour: Int) = hour.toString().padStart(2, '0')

    /** A word as it reads inside a sentence: its first letter small, except where the language keeps it. */
    private fun inline(word: String, keep: Boolean = false): String =
        if (language.language == "de" || keep) word else word.replaceFirstChar { it.lowercase(language) }

    /**
     * How the view names the demand charge's parts: by their hours ("Dag 06–23", "Natt 23–06", "Alla timmar"), with
     * the days and months only where they are not all; numbered ("Effektavgift 1") where two would read the same.
     */
    fun partNames(components: List<DemandComponent>): PartNames {
        val names = mutableListOf<String>()
        val inlined = mutableListOf<String>()
        for (component in components) {
            val window = component.window
            if (window == null) {
                names += words.say("power.part.all")
                inlined += words.say("power.part.allInline")
                continue
            }
            val hours = "${pad(window.fromHour)}–${pad(window.toHour)}"
            val night = window.fromHour > window.toHour || window.toHour <= 7
            val extra = mutableListOf<String>()
            if (window.days != "all") extra += inline(text.daysText(window.days), language.language == "en" && window.days == "mon_sat")
            if (!window.allYear) extra += text.monthsText(window)
            val tail = if (extra.isNotEmpty()) ", " + extra.joinToString(", ") else ""
            names += words.say(if (night) "power.part.night" else "power.part.day", "hours" to hours) + tail
            inlined += words.say(if (night) "power.part.nightInline" else "power.part.dayInline", "hours" to hours) + tail
        }
        if (names.toSet().size < names.size) {
            val numbered = components.indices.map { words.say("demand.part", "n" to (it + 1).toString()) }
            return PartNames(numbered, numbered.map { inline(it) })
        }
        return PartNames(names, inlined)
    }

    /** The parts inside a sentence where there are several, else none (a single part is "the demand charge"). */
    private fun parts(month: PowerMonthRead.Month): List<String> =
        if (month.components.size > 1) partNames(month.components).inline else emptyList()

    /** The wall clock as Home Assistant wrote it (the area's own zone): `2026-09-22T19:00:00+02:00` is `19:00`. */
    private fun clockOf(iso: String): String = Regex("T(\\d{2}:\\d{2})").find(iso)?.groupValues?.get(1).orEmpty()

    private fun notes(kw: Double, house: Double?, counted: List<Int>, held: Boolean, paused: Boolean, incomplete: Boolean, parts: List<String>): List<String> {
        val notes = mutableListOf<String>()
        if (house != null) {
            notes += words.say("power.split", "house" to text.kw(minOf(house, kw)), "car" to text.kw(maxOf(0.0, kw - house)))
        }
        if (counted.isNotEmpty()) {
            notes += if (parts.size > 1) words.say("power.countedPart", "part" to counted.joinToString(", ") { parts.getOrElse(it) { "" } })
            else words.say("power.counted")
        }
        when {
            paused -> notes += words.say("power.paused")
            held -> notes += words.say("power.held")
        }
        if (incomplete) notes += words.say("power.incomplete")
        return notes
    }

    /** One day's figures: "tis 22 sep: högst 5,9 kW kl. 19:00 · huset 2,3 kW, bilen 3,6 kW · räknas för dag 06–23". */
    fun dayFigures(day: PowerMonthRead.Day, parts: List<String> = emptyList()): String {
        val head = words.say("power.day", "date" to text.dateText(day.date), "kw" to text.kw(day.peakKw), "time" to clockOf(day.peakStart))
        return (listOf(head) + notes(day.peakKw, day.houseKw, day.counted, day.held, day.paused, false, parts)).joinToString(" · ")
    }

    fun hourFigures(hour: PowerMonthRead.Hour, parts: List<String> = emptyList()): String {
        val head = words.say("power.hour", "time" to clockOf(hour.start), "kw" to text.kw(hour.kw))
        return (listOf(head) + notes(hour.kw, hour.houseKw, hour.counted, hour.held, hour.paused, hour.incomplete, parts)).joinToString(" · ")
    }

    /** Every day of the month, measured or not: the 1st, every 5th (not too near the end) and the last are labelled. */
    fun monthColumns(month: PowerMonthRead.Month): List<Column> {
        val byDate = month.days.associateBy { it.date }
        val length = month.month.lengthOfMonth()
        val parts = parts(month)
        return (1..length).map { number ->
            val date = month.month.atDay(number)
            val day = byDate[date]
            val labelled = number == 1 || number == length || (number % 5 == 0 && length - number >= 3)
            Column(
                key = date.toString(),
                label = if (labelled) number.toString() else "",
                kw = day?.peakKw,
                house = day?.houseKw,
                counted = day?.counted.orEmpty(),
                held = day?.held ?: false,
                paused = day?.paused ?: false,
                incomplete = false,
                figures = day?.let { dayFigures(it, parts) } ?: words.say("power.noData", "date" to text.dateText(date))
            )
        }
    }

    /** A tapped day's measured hours: 00, 06, 12 and 18 are labelled. */
    fun hourColumns(month: PowerMonthRead.Month, date: LocalDate): List<Column> {
        val parts = parts(month)
        return month.hours.orEmpty()
            .filter { it.start.startsWith(date.toString()) }
            .mapIndexed { index, hour ->
                val time = clockOf(hour.start)
                val labelled = time.take(2) in setOf("00", "06", "12", "18") && time.endsWith(":00")
                Column(
                    key = "${hour.start}#$index",
                    label = if (labelled) time.take(2) else "",
                    kw = hour.kw,
                    house = hour.houseKw,
                    counted = hour.counted,
                    held = hour.held,
                    paused = hour.paused,
                    incomplete = hour.incomplete,
                    figures = hourFigures(hour, parts)
                )
            }
    }

    /** The month's figure per part: "Dag 06–23: 6,6 kW · Natt 23–06: 5,9 kW" (one part: "Effektavgift: 5,9 kW"); `null` without parts. */
    fun summary(month: PowerMonthRead.Month): String? {
        if (month.components.isEmpty()) return null
        val names = partNames(month.components)
        return month.bars.mapIndexed { index, bar ->
            val name = if (month.components.size > 1) names.names.getOrElse(index) { "" } else words.say("demand.title")
            "$name: ${bar?.let(text::kw) ?: words.say("power.noPeak")}"
        }.joinToString(" · ")
    }

    /** The legend's entries, as the card lists them: house, car, unsplit where a bar is, the target, each part, the marks. */
    fun legend(month: PowerMonthRead.Month, columns: List<Column>): List<Pair<LegendKey, String>> {
        val entries = mutableListOf(
            LegendKey.House to words.say("power.legend.house"),
            LegendKey.Car to words.say("power.legend.car")
        )
        if (columns.any { it.kw != null && it.house == null }) entries += LegendKey.Unsplit to words.say("power.legend.unsplit")
        month.importTargetKw?.let { entries += LegendKey.Target to words.say("power.legend.target", "kw" to text.kw(it)) }
        val names = partNames(month.components)
        month.components.indices.forEach { index ->
            entries += LegendKey.Counted(index + 1) to words.say("power.legend.countedPart", "part" to names.inline.getOrElse(index) { "" })
        }
        if (columns.any { it.held || it.paused }) entries += LegendKey.Marks to words.say("power.legend.marks")
        return entries
    }

    sealed interface LegendKey {
        data object House : LegendKey
        data object Car : LegendKey
        data object Unsplit : LegendKey
        data object Target : LegendKey
        data class Counted(val number: Int) : LegendKey
        data object Marks : LegendKey
    }

    companion object {
        /** The steps a scale's middle tick may take (kW): the top is two of them, so the axis reads 0, a step, the top. */
        private val NICE_STEPS = listOf(0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 15.0, 20.0, 25.0, 30.0, 40.0, 50.0, 60.0, 80.0, 100.0)

        /** The top of the scale: twice the smallest nice step that leaves the highest bar or the target some headroom. */
        fun scaleTop(values: List<Double>, target: Double?): Double {
            val highest = (values + listOf(0.0, target ?: 0.0)).max() * 1.08
            val step = NICE_STEPS.firstOrNull { it * 2 >= highest } ?: (Math.ceil(highest / 200) * 100)
            return step * 2
        }

        /**
         * Whether the history offers its Power view (the card's `powerView`): Home Assistant answers `get_power_month`,
         * and the charger's site measures its import (solar is offered, or the demand block says it measures).
         */
        fun offered(dashboard: Dashboard?): Boolean = dashboard != null && dashboard.capabilities.getPowerMonth &&
            (dashboard.strategyOptions.any { it.wire == "solar" } || dashboard.gridTariff?.demand?.available == true)
    }
}
