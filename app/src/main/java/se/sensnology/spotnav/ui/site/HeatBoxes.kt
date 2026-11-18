package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.heat.Heat
import se.sensnology.spotnav.ha.heat.HeatHistory
import se.sensnology.spotnav.ha.house.HouseHistory.HistoryKind
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.ha.session.HaSession

/**
 * Which boxes the heat page has under its Now box, decided from the answer's own fields (a box with nothing to show is
 * left out, never drawn empty; plan section 8): Plan where there is a day to draw (the rooms measured or the plan's
 * hours), Förbrukning where the site has a heat meter. The one shared picker is drawn only while a box needs it, and
 * Plan is for one day only: on Månad or År it is left out (it keeps no day of its own in the app), so without a meter
 * the picker offers days alone.
 */
internal object HeatBoxes {
    /** Förbrukning: the heat meter's energy by part. A box whose period turns out to hold none hides itself too. */
    fun use(heat: Heat?): Boolean = heat?.hasMeter == true

    /** Plan: today has a chart to draw (its rooms or its hours); a past day's is read when it is asked for. */
    fun plan(heat: Heat?, nowMs: Long): Boolean = heat != null && HeatPlanModel.today(heat, nowMs) != null

    /** The details sheet (the "i"): something the history has figures for. */
    fun details(heat: Heat?): Boolean = heat != null && (heat.hasMeter || heat.indoor.sensorsRead > 0 || heat.zones > 0)

    /** The kinds of period the shared picker offers; empty: no picker (no box to drive). */
    fun pickerKinds(heat: Heat?, nowMs: Long): List<HistoryPeriod> = when {
        use(heat) -> HistoryPeriod.entries
        plan(heat, nowMs) -> listOf(HistoryPeriod.DAY)
        else -> emptyList()
    }

    /** Whether a history of the period shown is needed: Förbrukning always, Plan for a past day. */
    fun needsHistory(heat: Heat?, choice: PeriodChoice, nowMs: Long): Boolean =
        use(heat) || (plan(heat, nowMs) && choice.period == HistoryPeriod.DAY && choice.date != null)
}

/**
 * The reads the heat page's boxes and its details sheet share: the history of a period, asked when a box opens, when
 * the period changes, and again every minute while the running period is shown ([request] with `force`); an answer
 * held is kept on screen while a newer one is on its way. [choice] is the period the page's picker shows.
 */
internal class HeatHistoryStore(private val session: HaSession, private val changed: () -> Unit) {
    var choice: PeriodChoice = PeriodChoice(HistoryPeriod.DAY)

    private val histories = HashMap<String, Load<HeatHistory>>()
    private val inFlight = HashSet<String>()

    private fun key(choice: PeriodChoice) = "${choice.period.key}:${choice.date ?: "now"}"

    fun history(choice: PeriodChoice = this.choice): Load<HeatHistory>? = histories[key(choice)]

    /** Read [choice] (`force`: again though an answer is held, as the running period is every minute; a past one is read once). */
    fun request(choice: PeriodChoice = this.choice, force: Boolean = false) {
        val k = key(choice)
        if (k in inFlight || (!force && histories[k] is Load.Ready<*>)) return
        inFlight += k
        if (histories[k] == null) histories[k] = Load.Loading
        val kind = HistoryKind.entries.first { it.key == choice.period.key }
        session.heatHistory(kind, choice.date) { outcome ->
            inFlight -= k
            when (outcome) {
                is SiteRead.Read -> histories[k] = Load.Ready(outcome.value)
                // An answer held stays; with none, the period says it could not be read.
                else -> if (histories[k] !is Load.Ready<*>) histories[k] = Load.Failed(outcome == SiteRead.Unsupported || (outcome is SiteRead.Failed && outcome.code == "unknown_command"))
            }
            changed()
        }
    }
}
