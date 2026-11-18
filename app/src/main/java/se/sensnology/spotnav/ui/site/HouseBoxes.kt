package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.house.House

/** The period boxes the house page stacks under the Now box, in the card's order; [tabKey] names the box's heading. */
internal enum class HouseBox(val tabKey: String, val chart: String) {
    GRAPH("graph", "house.graph"),
    PRICE("price", "house.price")
}

/**
 * Which period boxes the house page has. A box with nothing to show is left out, never drawn empty (owner rule, plan
 * section 7), decided from the answer's own fields: before the house has answered there is none; Pris och batteri is
 * there with a battery or with prices, and with neither it is gone. (A box whose period turns out to hold nothing
 * hides itself too, see [HouseHistoryPanel.render].)
 */
internal object HouseBoxes {
    fun shown(house: House?): List<HouseBox> = HouseBox.entries.filter { house != null && has(house, it) }

    private fun has(house: House, box: HouseBox): Boolean = when (box) {
        HouseBox.PRICE -> house.battery != null || house.hours.any { it.buy != null || it.sell != null }
        HouseBox.GRAPH -> true
    }
}
