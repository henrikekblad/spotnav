package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.house.House

/** The period boxes the house page stacks under the Now box and its picker, in the card's order; [tabKey] names the box's heading. */
internal enum class HouseBox(val tabKey: String, val chart: String) {
    GRAPH("graph", "house.graph"),
    PRICE("price", "house.price")
}

/**
 * Which boxes the house page has under its Now box: the Energy flow, then the period boxes above. A box with nothing
 * to show is left out, never drawn empty (owner rule, plan section 8), decided from the answer's own fields: before
 * the house has answered there is none; the Energy flow is there where the day's energy flow exists; Pris och batteri
 * with a battery or with prices. (A box whose period turns out to hold nothing hides itself too, see
 * [HouseHistoryPanel.render].) The one shared period picker is drawn only while some box needs it.
 */
internal object HouseBoxes {
    /** The Energy flow box. */
    fun flow(house: House?): Boolean = house?.energyFlow != null

    fun shown(house: House?): List<HouseBox> = HouseBox.entries.filter { house != null && has(house, it) }

    /** The period boxes there are: the picker drives these. */
    fun periodBoxes(house: House?): Int = shown(house).size + if (flow(house)) 1 else 0

    fun picker(house: House?): Boolean = periodBoxes(house) > 0

    private fun has(house: House, box: HouseBox): Boolean = when (box) {
        HouseBox.PRICE -> house.battery != null || house.hours.any { it.buy != null || it.sell != null }
        HouseBox.GRAPH -> true
    }
}
