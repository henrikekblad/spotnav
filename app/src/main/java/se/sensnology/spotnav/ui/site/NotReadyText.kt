package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.dashboard.DoesNotFit
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.NotReady
import java.time.Instant
import java.time.ZoneId

/**
 * The choice sheet of what will not be ready in time across the site, as the card's `not-ready-sheet.ts` words it: a
 * line per load ("Garage 07:30: 4 kWh saknas", "Spa 19:00: når 35 °C") and every choice as a button in the backend's
 * rank, the most freed first ("Tillåt toppen för alla", "Spa: Senare badtid (20:00)"). Pure: [zone] is the clock the
 * times are said in, [money] the units a peak's cost is given in.
 */
internal class NotReadyText(private val text: SiteText, private val zone: ZoneId, private val money: House.Money?) {
    private val words get() = text.words

    /** A press: the choice and the loads it answers for (one, or every one under the ceiling). */
    data class Press(val code: String, val loads: List<Load>)

    /** A load a press goes to: a car's charger ([id] its entry id), the spa or the sauna. */
    data class Load(val id: String, val kind: String)

    data class Button(val label: String, val press: Press)

    private fun clock(at: Instant?): String = at?.let { text.clock(it, zone) } ?: ""

    private fun degrees(value: Double?): String = "${text.fixed(value ?: 0.0, 0)} °C"

    fun title(): String = words.say("notReady.title")

    fun lede(): String = words.say("notReady.lede")

    /** A load's name in the sheet: the car's charger, "Spa", "Bastu". */
    fun loadName(item: NotReady.Item): String =
        if (item.kind == "car") item.name?.trim()?.takeIf { it.isNotEmpty() } ?: words.say("notReady.car")
        else words.say("heat.tab.${item.kind}")

    /** One item's line, or what was chosen for it. */
    fun line(item: NotReady.Item): String {
        val name = loadName(item)
        val time = clock(item.deadline)
        return when {
            item.chosen == NotReady.TAKES_WHAT_FITS -> words.say("notReady.line.chosen", "name" to name, "time" to time)
            item.kind == "car" -> words.say("notReady.line.car", "name" to name, "time" to time, "kwh" to text.kwh(item.shortKwh ?: 0.0))
            else -> words.say("notReady.line.heat", "name" to name, "time" to time, "reach" to degrees(item.reachC))
        }
    }

    /** A choice's words for its load ("Senare avresa (08:00)", "Senare badtid (20:00)", "Ta det som ryms (35 °C)"). */
    private fun choiceWords(item: NotReady.Item, choice: NotReady.Choice): String {
        if (item.kind == "car") {
            return DoesNotFitText(text, zone, money).choice(DoesNotFit.Choice(choice.code, choice.kwh, choice.peakKw, choice.cost, choice.later))
        }
        return when (choice.code) {
            NotReady.LATER -> words.say("heat.${item.kind}.choice.later", "time" to clock(choice.later))
            NotReady.TAKES_WHAT_FITS -> words.say("heat.spa.choice.fits", "reach" to degrees(item.reachC ?: choice.reachC))
            else -> words.say("heat.spa.choice.peak")
        }
    }

    /** Every choice in the backend's rank, with its words and what it answers; at most [limit] (a notice's three). */
    fun buttons(block: NotReady, limit: Int = Int.MAX_VALUE): List<Button> {
        val asking = block.items.filter { it.chosen == null }
        val several = asking.size > 1
        val out = mutableListOf<Button>()
        for (entry in block.ranked) {
            if (out.size >= limit) break
            if (entry.loadId == null) {
                val under = asking.filter { item -> item.choices.any { it.code == NotReady.ACCEPT_PEAK } }
                if (under.isEmpty()) continue
                val label = if (under.size == 1) choiceWords(under[0], under[0].choices.first { it.code == NotReady.ACCEPT_PEAK })
                else words.say("notReady.peakAll")
                out += Button(label, Press(NotReady.ACCEPT_PEAK, under.map { Load(it.loadId, it.kind) }))
                continue
            }
            val item = asking.firstOrNull { it.loadId == entry.loadId } ?: continue
            val choice = item.choices.firstOrNull { it.code == entry.code } ?: continue
            if (choice.code == NotReady.LATER && choice.later == null) continue
            val said = choiceWords(item, choice)
            out += Button(
                if (several) words.say("notReady.button", "load" to loadName(item), "choice" to said) else said,
                Press(choice.code, listOf(Load(item.loadId, item.kind)))
            )
        }
        return out
    }

    /** "Yes, allow the peak": the second press "Allow the peak" asks for. */
    fun acceptPeakAgain(): String = words.say("doesNotFit.choice.acceptPeakAgain")

    companion object {
        /**
         * The webhook action a press sends for one load: a car's through its charger (`take_what_fits`,
         * `later_departure`, `release_import_target`), a bath's or a session's as `spa_action` / `sauna_action`.
         */
        fun action(code: String, load: Load): String = when (load.kind) {
            "car" -> when (code) {
                NotReady.ACCEPT_PEAK -> "release_import_target"
                NotReady.TAKES_WHAT_FITS -> "take_what_fits"
                else -> "later_departure"
            }
            else -> when (code) {
                NotReady.LATER -> if (load.kind == "spa") "later_bath" else "later_session"
                NotReady.ACCEPT_PEAK -> "accept_peak"
                else -> "take_what_fits"
            }
        }
    }
}
