package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.HouseNow

/**
 * Which of the house page's tabs have something to show. A tab with nothing to show is left out, never drawn empty,
 * and a row of fewer than two tabs is not drawn at all (the page's own rule; the Home Assistant card shows every tab).
 * Decided from the answers' own blocks: Now always; Energy flow where the day's energy flow exists (the site has a
 * grid meter in the Energy dashboard); Price and battery where there is a battery.
 */
internal object HouseTabs {
    /**
     * [built] are the tabs this app has so far, in the card's order; [house] is what the page last read (`null` before
     * the first answer: Now alone) and [live] the live flows when read.
     */
    fun offered(built: List<HouseTab>, house: House?, live: HouseNow?): List<HouseTab> {
        if (house == null) return built.filter { it == HouseTab.NOW }
        return built.filter { tab ->
            when (tab) {
                HouseTab.NOW -> true
                HouseTab.FLOW -> house.energyFlow != null
                HouseTab.PRICE -> house.battery != null || live?.has?.battery == true
                // The energy chart and the earnings have their data in the history; offered once their tabs are built.
                HouseTab.GRAPH, HouseTab.EARN -> true
            }
        }
    }

    /** The row is drawn only with two tabs or more. */
    fun showRow(tabs: List<HouseTab>): Boolean = tabs.size >= 2
}
