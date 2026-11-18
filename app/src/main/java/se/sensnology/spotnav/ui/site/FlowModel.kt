package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.HouseHistory
import se.sensnology.spotnav.ha.house.HouseLoads

/** What the Energy flow tab shows for the shown period. */
internal sealed interface FlowContent {
    /** The period's history is on its way. */
    data object Loading : FlowContent

    /** The history could not be read; [unsupported]: the integration is older than the history. */
    data class Failed(val unsupported: Boolean) : FlowContent

    /** The site has no meter to build the energy from. */
    data object NoMeters : FlowContent

    /** Nothing flowed between two blocks yet: the sentence [waiting] (the day's first minutes) or none measured, and the [tiles] that have energy. */
    data class Tiles(val waiting: Boolean, val tiles: List<FlowModel.Tile>) : FlowContent

    /** The diagram; [past] says the battery's level is the one at the period's end, not now. */
    data class Diagram(val flow: House.EnergyFlow, val past: Boolean) : FlowContent
}

/** The Energy flow tab (`frontend/src/house-view.ts` `flowPanel`, `house-period-view.ts`), as pure decisions. */
internal object FlowModel {
    /** Below this (kWh) a tile's figure would read "0,0 kWh": it is left out. */
    const val MIN_TILE_KWH = 0.05

    /** [key] names the tile, [series] its colour, [word] the card's key of its name. */
    data class Tile(val key: String, val series: String, val word: String, val kwh: Double)

    private fun energy(flow: House.EnergyFlow, source: Boolean, role: String): Double? = flow.kwh(source, role)

    /** The tiles of a period's energy flow; a part the site does not have, or with no energy, is left out: never a tile of 0 kWh. */
    fun tiles(flow: House.EnergyFlow): List<Tile> = buildList {
        fun add(key: String, series: String, word: String, kwh: Double?) {
            if (kwh != null && kwh >= MIN_TILE_KWH) add(Tile(key, series, word, kwh))
        }
        add("sun", "sun", "house.tile.sun", energy(flow, true, "solar"))
        add("house", "house", "house.tile.house", energy(flow, false, "house"))
        add("discharged", "battery", "house.tile.discharged", energy(flow, true, "battery"))
        add("charged", "battery", "house.tile.charged", energy(flow, false, "battery"))
        add("bought", "grid", "house.tile.bought", energy(flow, true, "grid"))
        add("sold", "sell", "house.tile.sold", energy(flow, false, "grid"))
        add("heat", "heat", "house.tile.heat", energy(flow, false, "heat"))
        add("car", "car", "house.tile.car", energy(flow, false, "car"))
    }

    /**
     * What to show for [choice]: today's day is the house's own answer ([house], up to its last read), any other
     * period the history read for it ([history]; `null` while on its way, [failed] when it could not be read).
     */
    fun content(choice: PeriodChoice, house: House?, history: HouseHistory?, failed: FlowFailure?): FlowContent {
        val today = choice.period == HistoryPeriod.DAY && choice.date == null
        val flow: House.EnergyFlow?
        val running: Boolean
        if (today) {
            if (house == null) return FlowContent.Loading
            flow = house.energyFlow
            running = true
        } else {
            if (failed != null) return FlowContent.Failed(failed.unsupported)
            if (history == null) return FlowContent.Loading
            flow = history.energyFlow
            running = history.running
        }
        if (flow == null) return FlowContent.NoMeters
        if (!SankeyLayout.drawable(flow)) {
            return FlowContent.Tiles(waiting = running && choice.period == HistoryPeriod.DAY, tiles = tiles(flow))
        }
        return FlowContent.Diagram(flow, past = !running || choice.period != HistoryPeriod.DAY)
    }

    /** A history that was refused: its stable code, or none. Unsupported where the integration lacks the action or this version. */
    class FlowFailure(val code: String?, val unsupportedVersion: Boolean = false) {
        val unsupported: Boolean get() = unsupportedVersion || code == "unknown_command" || code == "spotnav_unsupported_api_version"
    }

    /** The pill's words: "Bil · 35 %". [part] is the block's energy, [total] the sources'. */
    fun pillWords(text: SiteText, node: House.FlowNode, total: Double): String =
        "${role(text, node.role)} · ${share(text, node.kwh, total)}"

    fun share(text: SiteText, part: Double, total: Double): String = if (total > 0) text.percent(part / total * 100) else "–"

    /** A role the card does not know (a newer backend's) is named as it comes. */
    fun role(text: SiteText, role: String): String =
        if (role in ROLES) text.words.say("house.flow.$role") else role

    private val ROLES = setOf("solar", "battery", "grid", "house", "car", "heat")

    /** The big figure of a block. No decimal from 100 kWh up, so a month's or a year's figures fit a narrow block. */
    fun kwh(text: SiteText, value: Double): String = "${text.fixed(value, if (value >= 100) 0 else 1)} kWh"

    /** Whether a block opens its pie: the house, and the heat where it holds more than one source. */
    fun opens(flow: House.EnergyFlow, node: House.FlowNode): String? = when {
        node.source -> null
        node.role == "house" -> "house"
        node.role == "heat" && flow.heatSources > 1 -> "heat"
        else -> null
    }
}

/** The pie behind House or Heat in the flow (`frontend/src/house-loads.ts`), as pure decisions. */
internal object LoadsModel {
    /** A meter under this share of the whole is put into Other metered. */
    const val SMALL_SHARE = 0.015

    /** The colours a pie has for meters; any further meter goes into Other metered. */
    const val SLOTS = 8

    /** [key]: the meter's statistic id, `other-metered` or `unknown`. [colour]: index into the pie's palette, or -1 other / -2 unknown. */
    data class Slice(val key: String, val name: String, val kwh: Double, val share: Double, val colour: Int)

    class Pie(val whole: Double, val slices: List<Slice>, val merged: List<HouseLoads.Load>)

    const val OTHER_COLOUR = -1
    const val UNKNOWN_COLOUR = -2

    /**
     * The meters at or over [SMALL_SHARE] of the whole (at most [SLOTS], largest first), then Other metered with the
     * rest and Unknown (the house less every meter); an empty slice is left out. The heat's pie shows every source with
     * energy however small, up to [SLOTS]. The whole is the house, or the meters where they add up to more.
     */
    fun pie(words: se.sensnology.spotnav.ui.common.CardWords, answer: HouseLoads): Pie {
        val whole = maxOf(answer.houseKwh ?: 0.0, answer.metersKwh)
        val loads = answer.loads.sortedByDescending { it.kwh }
        val shown = ArrayList<HouseLoads.Load>()
        val merged = ArrayList<HouseLoads.Load>()
        for (load in loads) {
            val big = if (answer.kind == "heat") load.kwh > 0 else load.kwh / whole >= SMALL_SHARE
            if (whole > 0 && big && shown.size < SLOTS) shown.add(load) else merged.add(load)
        }
        // A meter's colour by its statistic id among those shown, not by its size.
        val byId = shown.map { it.statisticId }.sorted()
        val slices = ArrayList<Slice>()
        shown.mapTo(slices) { Slice(it.statisticId, it.name, it.kwh, if (whole > 0) it.kwh / whole else 0.0, byId.indexOf(it.statisticId)) }
        val metered = merged.sumOf { it.kwh }
        val unknown = answer.otherKwh ?: 0.0
        if (metered > 0) {
            slices.add(Slice("other-metered", words.say("house.loads.otherMetered"), metered, if (whole > 0) metered / whole else 0.0, OTHER_COLOUR))
        }
        if (unknown > 0 || slices.isEmpty()) {
            slices.add(Slice("unknown", words.say("house.loads.unknown"), unknown, if (whole > 0) unknown / whole else 0.0, UNKNOWN_COLOUR))
        }
        return Pie(whole, slices, merged)
    }

    fun kwh(text: SiteText, value: Double): String =
        "${text.fixed(value, if (value >= 100) 0 else if (value > 0 && value < 1) 2 else 1)} kWh"

    fun share(text: SiteText, share: Double): String = if (share > 0 && share < 0.005) "<1 %" else text.percent(share * 100)

    /** The notes under the pie, in the card's order, as (line, sentence). */
    fun notes(text: SiteText, answer: HouseLoads, pie: Pie, carApart: Boolean, heatApart: Boolean): List<String> {
        val words = text.words
        val lines = ArrayList<String>()
        for (item in answer.implausible) {
            lines.add(words.say(if (item.leftOut) "house.loads.implausibleOut" else "house.loads.implausible", "name" to item.name, "hours" to text.fixed(item.hours.toDouble(), 0)))
        }
        if (pie.merged.isNotEmpty()) {
            lines.add(words.say("house.loads.otherHolds", "list" to pie.merged.joinToString(", ") { "${it.name} ${kwh(text, it.kwh)}" }))
        }
        if (answer.overKwh > 0) lines.add(words.say("house.loads.over", "kwh" to kwh(text, answer.overKwh)))
        val heat = answer.kind == "heat"
        if (answer.loads.isEmpty() && !heat) lines.add(words.say("house.loads.none"))
        val apart = when {
            heat -> null
            carApart && heatApart -> "house.loads.carHeat"
            carApart -> "house.loads.car"
            heatApart -> "house.loads.heat"
            else -> null
        }
        if (apart != null) lines.add(words.say(apart))
        return lines
    }
}
